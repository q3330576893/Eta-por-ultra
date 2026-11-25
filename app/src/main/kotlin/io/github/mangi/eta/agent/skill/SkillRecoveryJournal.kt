package io.github.mangi.eta.agent.skill

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SkillRecoveryRecord(
    val id: String,
    val originalTargetExisted: Boolean,
    val backupCompleted: Boolean = false,
    val newTargetCommitted: Boolean = false,
    val registrySnapshot: SkillRegistryRecoverySnapshot = SkillRegistryRecoverySnapshot(
        skillId = id,
        entryExisted = false,
    ),
)

internal data class SkillRegistryRecoverySnapshot(
    val skillId: String,
    val entryExisted: Boolean,
    val enabled: Boolean = false,
    val source: String = "",
    val installState: String = "",
)

internal data class RecoveredSkillOperation(
    val operationDirectory: File,
    val records: List<SkillRecoveryRecord>,
)

internal class PendingSkillRecoveryJournal private constructor(
    private val operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    private var records = records

    fun markBackupCompleted(skillId: String) {
        update(skillId) { it.copy(backupCompleted = true) }
    }

    fun markNewTargetCommitted(skillId: String) {
        update(skillId) { it.copy(newTargetCommitted = true) }
    }

    fun clear() {
        Files.deleteIfExists(File(operationDirectory, JOURNAL_FILE_NAME).toPath())
    }

    private fun update(skillId: String, transform: (SkillRecoveryRecord) -> SkillRecoveryRecord) {
        var found = false
        records = records.map { record ->
            if (record.id == skillId) {
                found = true
                transform(record)
            } else {
                record
            }
        }
        check(found) { "恢复日志中不存在 Skill：$skillId" }
        writeJournalAtomically(operationDirectory, records)
    }

    companion object {
        fun begin(
            skillsRoot: File,
            operationDirectory: File,
            records: List<SkillRecoveryRecord>,
        ): PendingSkillRecoveryJournal {
            validateOperationDirectory(skillsRoot, operationDirectory)
            require(records.isNotEmpty()) { "恢复日志至少需要一个 Skill" }
            validateRecords(records)
            writeJournalAtomically(operationDirectory, records)
            return PendingSkillRecoveryJournal(operationDirectory, records)
        }
    }
}

internal class SkillRecoveryRequiredException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 必须在持有 [SkillMutationLock] 的跨进程锁时调用。 */
internal fun recoverPendingSkillOperations(
    skillsRoot: File,
    directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
): List<RecoveredSkillOperation> =
    recoverPendingSkillOperationsDetailed(skillsRoot, directoryMover).recovered

/**
 * 恢复结果。
 *
 * [quarantinedSkillIds] 是“日志可解析但互相冲突”的 Skill：同一 id 出现在两个待恢复事务里，
 * 无法判定该回滚到哪一份，文件树可能停在任一状态。这些 id 必须从索引与加载路径中排除
 * （fail-closed），但事务目录已被隔离，因此其余健康 Skill 与安装/删除功能不受影响。
 */
internal data class SkillRecoveryOutcome(
    val recovered: List<RecoveredSkillOperation>,
    val quarantinedSkillIds: Set<String>,
)

/**
 * 两阶段恢复：先把所有日志解析并做一致性校验，再统一改文件树。
 *
 * 旧实现边解析边改盘：一旦后面的事务解析失败或 id 冲突，前面的事务已经把 backup 消耗掉了，
 * 日志却仍然存在——下一次启动就会因为“备份缺失”永久失败。先解析后执行让这个状态不可能出现。
 *
 * 日志无法解析时仍然 fail-closed：损坏的日志无法归因到具体 Skill，此时任何“继续索引”都可能
 * 把半提交内容当成已安装技能注入模型。它的隔离粒度是单个 skills 根目录——其它根目录、
 * 以及 Agent 运行本身（[SkillIndexService.listInstalledSkills] 返回空集合）都不受影响。
 */
internal fun recoverPendingSkillOperationsDetailed(
    skillsRoot: File,
    directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
): SkillRecoveryOutcome {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (!Files.exists(workRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        return SkillRecoveryOutcome(emptyList(), emptySet())
    }
    if (!workRoot.isDirectory || Files.isSymbolicLink(workRoot.toPath())) {
        throw SkillRecoveryRequiredException("Skill 恢复目录不安全")
    }
    val operations = workRoot.listFiles()
        ?.filter { it.name.startsWith(OPERATION_PREFIX) }
        ?.sortedBy { it.name }
        ?: throw SkillRecoveryRequiredException("无法读取 Skill 恢复目录")

    val parsed = operations.mapNotNull { operation ->
        val journalFile = File(operation, JOURNAL_FILE_NAME)
        if (!Files.exists(journalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
        PendingOperation(
            operation = operation,
            journalFile = journalFile,
            records = parseOperationJournal(skillsRoot, operation, journalFile),
        )
    }

    val duplicatedIds = parsed
        .flatMap { it.records }
        .groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    val conflicting = parsed.filter { pending -> pending.records.any { it.id in duplicatedIds } }
    val executable = parsed - conflicting.toSet()

    val quarantinedIds = linkedSetOf<String>()
    conflicting.forEach { pending ->
        quarantinedIds += quarantineRecoveryOperation(skillsRoot, pending.operation)
    }

    val recovered = executable.map { pending ->
        applyOperation(skillsRoot, pending.operation, pending.records, directoryMover)
    }
    return SkillRecoveryOutcome(recovered, quarantinedIds)
}

/**
 * 索引/加载时必须排除的 Skill id：来自被隔离的事务目录。
 *
 * 归因只使用文件系统痕迹（`backup/<id>`、`recovery/<id>`）以及能解析出的日志记录，不猜测。
 */
internal fun quarantinedSkillIds(skillsRoot: File): Set<String> {
    val quarantineRoot = File(skillInstallerWorkRoot(skillsRoot), QUARANTINE_DIRECTORY_NAME)
    if (!Files.isDirectory(quarantineRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptySet()
    if (Files.isSymbolicLink(quarantineRoot.toPath())) return emptySet()
    val ids = linkedSetOf<String>()
    quarantineRoot.listFiles().orEmpty().forEach { quarantined ->
        listOf(BACKUP_DIRECTORY_NAME, RECOVERY_DIRECTORY_NAME).forEach { kind ->
            val root = File(quarantined, kind)
            if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
            root.listFiles().orEmpty().forEach { child ->
                if (SKILL_ID_REGEX.matches(child.name)) ids += child.name
            }
        }
        // 日志可读但互相冲突时，把记录里的 id 一并排除。
        runCatching { parseJournal(File(quarantined, JOURNAL_FILE_NAME)) }.getOrNull()
            ?.forEach { record -> ids += record.id }
    }
    return ids
}

private data class PendingOperation(
    val operation: File,
    val journalFile: File,
    val records: List<SkillRecoveryRecord>,
)

private fun parseOperationJournal(
    skillsRoot: File,
    operation: File,
    journalFile: File,
): List<SkillRecoveryRecord> {
    validateOperationDirectory(skillsRoot, operation)
    if (Files.isSymbolicLink(journalFile.toPath()) || !journalFile.isFile) {
        recoveryFailure("Skill 恢复日志不是普通文件")
    }
    if (journalFile.length() !in 1..MAX_JOURNAL_BYTES) {
        recoveryFailure("Skill 恢复日志大小无效")
    }
    return parseJournal(journalFile)
}

/**
 * 把无法安全归因的事务整体移出 `operation-*` 命名空间。
 *
 * 隔离而不是删除：备份里可能仍是最新可用的旧内容，日志是唯一线索；同时它不再参与恢复，
 * 系统可以继续工作。移动失败时保持 fail-closed（继续抛异常），绝不静默丢弃。
 */
private fun quarantineRecoveryOperation(skillsRoot: File, operation: File): Set<String> {
    val ids = linkedSetOf<String>()
    listOf(BACKUP_DIRECTORY_NAME, RECOVERY_DIRECTORY_NAME).forEach { kind ->
        val root = File(operation, kind)
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
        root.listFiles().orEmpty().forEach { child ->
            if (SKILL_ID_REGEX.matches(child.name)) ids += child.name
        }
    }
    runCatching { parseJournal(File(operation, JOURNAL_FILE_NAME)) }.getOrNull()
        ?.forEach { record -> ids += record.id }

    val workRoot = skillInstallerWorkRoot(skillsRoot)
    val quarantineRoot = File(workRoot, QUARANTINE_DIRECTORY_NAME)
    if (!quarantineRoot.isDirectory && !quarantineRoot.mkdir()) {
        recoveryFailure("无法创建 Skill 恢复隔离目录")
    }
    if (Files.isSymbolicLink(quarantineRoot.toPath())) {
        recoveryFailure("Skill 恢复隔离目录不安全")
    }
    val target = File(quarantineRoot, operation.name)
    if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        deleteSkillPathWithoutFollowingLinks(quarantineRoot, target)
    }
    try {
        Files.move(operation.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(operation.toPath(), target.toPath())
    }
    return ids
}

private fun applyOperation(
    skillsRoot: File,
    operation: File,
    records: List<SkillRecoveryRecord>,
    directoryMover: SkillDirectoryMover,
): RecoveredSkillOperation {
    try {
        validateOperationDirectory(skillsRoot, operation)
        val backupRoot = File(operation, BACKUP_DIRECTORY_NAME)
        records.forEach { record ->
            val target = File(skillsRoot, record.id)
            val backup = File(backupRoot, record.id)
            val backupExists = Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (record.originalTargetExisted) {
                when {
                    backupExists -> restoreBackup(
                        skillsRoot,
                        operation,
                        backupRoot,
                        record.id,
                        directoryMover,
                    )
                    record.backupCompleted -> recoveryFailure("旧 Skill 备份缺失：${record.id}")
                    !isSafeExistingTarget(skillsRoot, target) ->
                        recoveryFailure("旧 Skill 目标与恢复日志不一致：${record.id}")
                }
            } else {
                if (backupExists) recoveryFailure("全新 Skill 不应存在旧备份：${record.id}")
                if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
                    recoveryFailure("无法移除未完成安装的 Skill：${record.id}")
                }
            }
        }
        return RecoveredSkillOperation(operationDirectory = operation, records = records)
    } catch (error: SkillRecoveryRequiredException) {
        throw error
    } catch (error: Exception) {
        throw SkillRecoveryRequiredException("Skill 自动恢复失败", error)
    }
}

internal fun completeRecoveredSkillOperations(
    skillsRoot: File,
    recovered: List<RecoveredSkillOperation>,
) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    recovered.forEach { recovery ->
        val operation = recovery.operationDirectory
        // 幂等：成功恢复的事务目录已经删除，重复调用（调用方 catch/finally 双路径）必须是无操作，
        // 否则会把“已完成”重新报成 SkillRecoveryRequiredException，让整个技能系统看起来坏掉。
        if (!Files.exists(operation.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
        validateOperationDirectory(skillsRoot, operation)
        Files.deleteIfExists(File(operation, JOURNAL_FILE_NAME).toPath())
        // journal 删除即表示文件与 registry 已共同恢复完成；残留的无 journal 暂存目录
        // 不再影响索引，清理失败也不能把已经完成的恢复重新标记为待处理。
        deleteSkillPathWithoutFollowingLinks(workRoot, operation)
    }
}

internal fun createSkillRecoveryOperationDirectory(skillsRoot: File): File {
    val workRoot = prepareSkillInstallerWorkRoot(skillsRoot)
    repeat(8) {
        val operation = File(workRoot, "$OPERATION_PREFIX${UUID.randomUUID()}")
        if (operation.mkdir()) return operation
    }
    throw IOException("无法创建 Skill 安装事务")
}

private fun restoreBackup(
    skillsRoot: File,
    operation: File,
    backupRoot: File,
    skillId: String,
    directoryMover: SkillDirectoryMover,
) {
    if (
        Files.isSymbolicLink(backupRoot.toPath()) || !backupRoot.isDirectory ||
        !isStrictChild(operation, backupRoot)
    ) {
        recoveryFailure("Skill 备份根目录不安全")
    }
    val backup = File(backupRoot, skillId)
    if (
        Files.isSymbolicLink(backup.toPath()) || !backup.isDirectory ||
        !isStrictChild(backupRoot, backup)
    ) {
        recoveryFailure("旧 Skill 备份不安全：$skillId")
    }
    val recoveryRoot = File(operation, RECOVERY_DIRECTORY_NAME)
    if (!recoveryRoot.mkdirs() && !recoveryRoot.isDirectory) {
        recoveryFailure("无法创建 Skill 恢复暂存目录")
    }
    if (Files.isSymbolicLink(recoveryRoot.toPath()) || !isStrictChild(operation, recoveryRoot)) {
        recoveryFailure("Skill 恢复暂存目录不安全")
    }
    val staging = File(recoveryRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(operation, staging)) {
        recoveryFailure("无法清理 Skill 恢复暂存目录")
    }
    copyDirectoryWithoutFollowingLinks(backup, staging)

    val target = File(skillsRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
        recoveryFailure("无法清理未完成提交的 Skill：$skillId")
    }
    directoryMover.move(staging, target)
}

private fun copyDirectoryWithoutFollowingLinks(source: File, target: File) {
    if (Files.isSymbolicLink(source.toPath()) || !source.isDirectory) {
        recoveryFailure("Skill 备份目录不安全")
    }
    if (!target.mkdir()) recoveryFailure("无法创建 Skill 恢复副本")
    val children = source.listFiles() ?: recoveryFailure("无法读取 Skill 备份目录")
    children.forEach { child ->
        if (Files.isSymbolicLink(child.toPath())) {
            recoveryFailure("Skill 备份包含符号链接")
        }
        val destination = File(target, child.name)
        when {
            child.isDirectory -> copyDirectoryWithoutFollowingLinks(child, destination)
            child.isFile -> Files.copy(child.toPath(), destination.toPath())
            else -> recoveryFailure("Skill 备份包含不支持的文件类型")
        }
    }
}

private fun parseJournal(journalFile: File): List<SkillRecoveryRecord> {
    val json = runCatching { JSONObject(journalFile.readText(Charsets.UTF_8)) }
        .getOrElse { recoveryFailure("Skill 恢复日志格式无效") }
    if (json.optInt("version", -1) != JOURNAL_VERSION) {
        recoveryFailure("Skill 恢复日志版本不受支持")
    }
    val entries = json.optJSONArray("skills") ?: recoveryFailure("Skill 恢复日志缺少 skills")
    if (entries.length() !in 1..MAX_JOURNAL_SKILLS) {
        recoveryFailure("Skill 恢复日志条目数无效")
    }
    val records = (0 until entries.length()).map { index ->
        val entry = entries.optJSONObject(index) ?: recoveryFailure("Skill 恢复日志条目无效")
        val id = entry.optString("id")
        if (!entry.has("originalTargetExisted") || entry.opt("originalTargetExisted") !is Boolean) {
            recoveryFailure("Skill 恢复日志缺少原目标状态")
        }
        if (!entry.has("backupCompleted") || entry.opt("backupCompleted") !is Boolean) {
            recoveryFailure("Skill 恢复日志缺少备份状态")
        }
        if (!entry.has("newTargetCommitted") || entry.opt("newTargetCommitted") !is Boolean) {
            recoveryFailure("Skill 恢复日志缺少提交状态")
        }
        SkillRecoveryRecord(
            id = id,
            originalTargetExisted = entry.getBoolean("originalTargetExisted"),
            backupCompleted = entry.getBoolean("backupCompleted"),
            newTargetCommitted = entry.getBoolean("newTargetCommitted"),
            registrySnapshot = parseRegistrySnapshot(entry, id),
        )
    }
    validateRecords(records)
    return records
}

private fun writeJournalAtomically(
    operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    val json = JSONObject()
        .put("version", JOURNAL_VERSION)
        .put(
            "skills",
            JSONArray().apply {
                records.forEach { record ->
                    put(
                        JSONObject()
                            .put("id", record.id)
                            .put("originalTargetExisted", record.originalTargetExisted)
                            .put("backupCompleted", record.backupCompleted)
                            .put("newTargetCommitted", record.newTargetCommitted)
                            .put(
                                "registry",
                                JSONObject()
                                    .put("entryExisted", record.registrySnapshot.entryExisted)
                                    .put("enabled", record.registrySnapshot.enabled)
                                    .put("source", record.registrySnapshot.source)
                                    .put("installState", record.registrySnapshot.installState)
                            )
                    )
                }
            }
        )
    val journal = File(operationDirectory, JOURNAL_FILE_NAME)
    val temporary = File(operationDirectory, ".$JOURNAL_FILE_NAME-${UUID.randomUUID()}")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}

private fun validateOperationDirectory(skillsRoot: File, operationDirectory: File) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (
        Files.isSymbolicLink(operationDirectory.toPath()) || !operationDirectory.isDirectory ||
        operationDirectory.parentFile?.canonicalFile != workRoot.canonicalFile ||
        !OPERATION_NAME_REGEX.matches(operationDirectory.name)
    ) {
        recoveryFailure("Skill 操作目录不安全")
    }
}

private fun validateRecords(records: List<SkillRecoveryRecord>) {
    if (records.map { it.id }.distinct().size != records.size) {
        recoveryFailure("Skill 恢复日志包含重复 id")
    }
    records.forEach { record ->
        if (record.id.length !in 1..64 || !SKILL_ID_REGEX.matches(record.id)) {
            recoveryFailure("Skill 恢复日志包含非法 id")
        }
        if (!record.originalTargetExisted && record.backupCompleted) {
            recoveryFailure("全新 Skill 的恢复日志包含非法备份状态")
        }
        if (record.originalTargetExisted && record.newTargetCommitted && !record.backupCompleted) {
            recoveryFailure("替换 Skill 的恢复日志状态不一致")
        }
        val registry = record.registrySnapshot
        if (registry.skillId != record.id) recoveryFailure("Skill 恢复日志 registry id 不一致")
        if (registry.entryExisted) {
            if (
                registry.source !in VALID_REGISTRY_SOURCES ||
                registry.installState !in VALID_INSTALL_STATES ||
                (registry.source == USER_SKILL_SOURCE &&
                    registry.installState != INSTALL_STATE_INSTALLED_VALUE)
            ) {
                recoveryFailure("Skill 恢复日志包含非法 registry 状态")
            }
        } else if (
            registry.enabled || registry.source.isNotEmpty() || registry.installState.isNotEmpty()
        ) {
            recoveryFailure("不存在的 registry 快照包含额外状态")
        }
    }
}

private fun parseRegistrySnapshot(
    entry: JSONObject,
    skillId: String,
): SkillRegistryRecoverySnapshot {
    val registry = entry.optJSONObject("registry")
        ?: recoveryFailure("Skill 恢复日志缺少 registry 快照")
    val booleanKeys = listOf("entryExisted", "enabled")
    if (booleanKeys.any { key -> !registry.has(key) || registry.opt(key) !is Boolean }) {
        recoveryFailure("Skill 恢复日志 registry 快照无效")
    }
    if (!registry.has("source") || registry.opt("source") !is String) {
        recoveryFailure("Skill 恢复日志 registry source 无效")
    }
    if (!registry.has("installState") || registry.opt("installState") !is String) {
        recoveryFailure("Skill 恢复日志 registry installState 无效")
    }
    return SkillRegistryRecoverySnapshot(
        skillId = skillId,
        entryExisted = registry.getBoolean("entryExisted"),
        enabled = registry.getBoolean("enabled"),
        source = registry.getString("source"),
        installState = registry.getString("installState"),
    )
}

private fun isSafeExistingTarget(skillsRoot: File, target: File): Boolean =
    !Files.isSymbolicLink(target.toPath()) && target.isDirectory && isStrictChild(skillsRoot, target)

private fun isStrictChild(root: File, target: File): Boolean {
    val rootPath = root.canonicalFile.toPath()
    val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
    return targetPath.startsWith(rootPath) && targetPath != rootPath
}

internal fun skillInstallerWorkRoot(skillsRoot: File): File = File(
    requireNotNull(skillsRoot.canonicalFile.parentFile) { "Skills 目录必须有父目录" },
    ".eta-skill-installer",
)

private fun recoveryFailure(message: String): Nothing =
    throw SkillRecoveryRequiredException(message)

private const val JOURNAL_VERSION = 1
internal const val JOURNAL_FILE_NAME = "pending-install.json"
private const val BACKUP_DIRECTORY_NAME = "backup"
private const val RECOVERY_DIRECTORY_NAME = "recovery"
private const val QUARANTINE_DIRECTORY_NAME = "quarantine"
private const val OPERATION_PREFIX = "operation-"
private const val MAX_JOURNAL_BYTES = 256L * 1024L
private const val MAX_JOURNAL_SKILLS = 2_048
private const val INSTALL_STATE_INSTALLED_VALUE = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN_VALUE = "removed_builtin"
private val VALID_REGISTRY_SOURCES = setOf(USER_SKILL_SOURCE, BUILTIN_SKILL_SOURCE)
private val VALID_INSTALL_STATES = setOf(
    INSTALL_STATE_INSTALLED_VALUE,
    INSTALL_STATE_REMOVED_BUILTIN_VALUE,
)
private val OPERATION_NAME_REGEX = Regex("^operation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SKILL_ID_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
