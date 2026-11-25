package io.github.mangi.eta.agent.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files

data class SkillResourceLimits(
    val maxTextBytes: Long = 512L * 1024L,
    val maxResources: Int = 512,
    val maxPathDepth: Int = 16,
    /**
     * 遍历时最多检查的目录项数（含目录本身）。
     *
     * [maxResources] 只约束“已计入的文件数”，一棵只有空目录的宽树可以无限展开；这里给出
     * 与层级限制配对的宽度上限，避免 `references/` 被构造成扫盘放大器。
     */
    val maxScannedEntries: Int = 4_096,
)

/** 在已安装 Skill 根目录内列出和读取有界 UTF-8 文本资源。 */
class SkillResourceReader internal constructor(
    skillsRoot: File,
    private val limits: SkillResourceLimits = SkillResourceLimits(),
) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile

    fun listResources(
        entry: SkillIndexEntry,
        relativeDirectory: String? = null,
    ): SkillResourceListResult = SkillMutationLock.withLock(canonicalSkillsRoot) {
        listResourcesAfterRecovery(entry, relativeDirectory)
    }

    private fun listResourcesAfterRecovery(
        entry: SkillIndexEntry,
        relativeDirectory: String?,
    ): SkillResourceListResult {
        val skillRoot = validateSkillRoot(entry)
            ?: return failure(
                SkillResourceErrorCode.INVALID_SKILL_ROOT,
                "Skill 根目录不在应用私有 Skills 目录内",
            )
        val start = if (relativeDirectory.isNullOrBlank() || relativeDirectory == ".") {
            skillRoot
        } else {
            val segments = validateResourcePath(relativeDirectory)
                ?: return failure(
                    SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                    "资源目录必须是 Skill 内的安全相对路径",
                )
            if (hasSymbolicLinkComponent(skillRoot, segments)) {
                return failure(
                    SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                    "Skill 资源路径包含不允许的符号链接",
                )
            }
            resolveInside(skillRoot, segments)
                ?: return failure(
                    SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                    "资源目录超出 Skill 根目录",
                )
        }
        if (!start.isDirectory || Files.isSymbolicLink(start.toPath())) {
            return failure(SkillResourceErrorCode.RESOURCE_NOT_FOUND, "资源目录不存在")
        }

        val resources = mutableListOf<SkillResourceInfo>()
        val pending = ArrayDeque<File>()
        pending.add(start)
        var scanned = 0
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = directory.listFiles()?.sortedBy { it.name }
                ?: return failure(SkillResourceErrorCode.IO_ERROR, "无法读取 Skill 资源目录")
            for (child in children) {
                scanned += 1
                if (scanned > limits.maxScannedEntries) {
                    return failure(
                        SkillResourceErrorCode.TOO_MANY_RESOURCES,
                        "Skill 资源目录项超过 ${limits.maxScannedEntries} 个",
                    )
                }
                if (Files.isSymbolicLink(child.toPath())) {
                    return failure(
                        SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                        "Skill 资源中包含不允许的符号链接",
                    )
                }
                val relative = child.relativeTo(skillRoot).invariantSeparatorsPath
                val depth = relative.split('/').size
                if (depth > limits.maxPathDepth) {
                    return failure(
                        SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                        "Skill 资源路径层级超过 ${limits.maxPathDepth} 层",
                    )
                }
                when {
                    child.isDirectory -> pending.add(child)
                    child.isFile -> {
                        resources += SkillResourceInfo(relativePath = relative, sizeBytes = child.length())
                        if (resources.size > limits.maxResources) {
                            return failure(
                                SkillResourceErrorCode.TOO_MANY_RESOURCES,
                                "Skill 资源数超过 ${limits.maxResources} 个",
                            )
                        }
                    }
                }
            }
        }
        return SkillResourceListResult.Success(resources.sortedBy { it.relativePath })
    }

    fun readText(
        entry: SkillIndexEntry,
        relativePath: String,
    ): SkillResourceReadResult = SkillMutationLock.withLock(canonicalSkillsRoot) {
        readTextAfterRecovery(entry, relativePath)
    }

    private fun readTextAfterRecovery(
        entry: SkillIndexEntry,
        relativePath: String,
    ): SkillResourceReadResult {
        val skillRoot = validateSkillRoot(entry)
            ?: return readFailure(
                SkillResourceErrorCode.INVALID_SKILL_ROOT,
                "Skill 根目录不在应用私有 Skills 目录内",
            )
        val segments = validateResourcePath(relativePath)
            ?: return readFailure(
                SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                "资源路径必须是 Skill 内的安全相对路径",
            )
        val target = resolveInside(skillRoot, segments)
            ?: return readFailure(
                SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                "资源路径超出 Skill 根目录",
            )
        if (!target.isFile || Files.isSymbolicLink(target.toPath())) {
            return readFailure(SkillResourceErrorCode.RESOURCE_NOT_FOUND, "Skill 资源不存在")
        }
        if (hasSymbolicLinkComponent(skillRoot, segments)) {
            return readFailure(
                SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                "Skill 资源路径包含不允许的符号链接",
            )
        }
        if (target.length() > limits.maxTextBytes) {
            return readFailure(
                SkillResourceErrorCode.RESOURCE_TOO_LARGE,
                "Skill 文本资源超过 ${limits.maxTextBytes} 字节限制",
            )
        }
        // 打开时带 NOFOLLOW_LINKS：上面的 isFile/isSymbolicLink 检查与实际读取之间仍有窗口，
        // 只有让 open 本身拒绝符号链接，才能保证读到的就是校验过的那个普通文件。
        val text = when (val result = readStrictUtf8NoFollow(target, limits.maxTextBytes)) {
            is StrictUtf8Result.Ok -> result.text
            StrictUtf8Result.TooLarge -> return readFailure(
                SkillResourceErrorCode.RESOURCE_TOO_LARGE,
                "Skill 文本资源超过 ${limits.maxTextBytes} 字节限制",
            )
            StrictUtf8Result.NotText -> return readFailure(
                SkillResourceErrorCode.BINARY_RESOURCE,
                "该资源不是可安全读取的 UTF-8 文本",
            )
            StrictUtf8Result.Unreadable -> return readFailure(
                SkillResourceErrorCode.IO_ERROR,
                "读取 Skill 资源失败",
            )
        }
        return SkillResourceReadResult.Success(
            relativePath = segments.joinToString("/"),
            text = text,
        )
    }

    private fun validateSkillRoot(entry: SkillIndexEntry): File? {
        if (!entry.installed) return null
        val root = runCatching { File(entry.rootPath).canonicalFile }.getOrNull() ?: return null
        val skillsPath = canonicalSkillsRoot.toPath()
        val rootPath = root.toPath()
        if (!rootPath.startsWith(skillsPath) || rootPath == skillsPath || !root.isDirectory) return null
        return root
    }

    private fun validateResourcePath(raw: String): List<String>? {
        if (
            raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') || raw.contains('\\') ||
            raw.contains('\u0000') || raw.any { it.isISOControl() } ||
            // Windows 盘符前缀（`C:/x`、`C:x`）：java.io.File 会把它当成绝对路径直接逃出根目录。
            // 与安装器使用同一条规则，保证“装得进去的路径”和“读得出来的路径”一致。
            WINDOWS_DRIVE_PREFIX.containsMatchIn(raw)
        ) {
            return null
        }
        val segments = raw.removeSuffix("/").split('/')
        if (
            segments.isEmpty() || segments.size > limits.maxPathDepth ||
            segments.any { it.isBlank() || it == "." || it == ".." }
        ) {
            return null
        }
        return segments
    }

    private fun resolveInside(root: File, segments: List<String>): File? {
        val target = segments.fold(root) { current, segment -> File(current, segment) }
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf {
            it.toPath().startsWith(root.toPath()) && it.toPath() != root.toPath()
        }
    }

    private fun hasSymbolicLinkComponent(root: File, segments: List<String>): Boolean {
        var current = root
        return segments.any { segment ->
            current = File(current, segment)
            Files.isSymbolicLink(current.toPath())
        }
    }

    private fun failure(code: SkillResourceErrorCode, message: String) =
        SkillResourceListResult.Failure(SkillResourceError(code, message))

    private fun readFailure(code: SkillResourceErrorCode, message: String) =
        SkillResourceReadResult.Failure(SkillResourceError(code, message))

    private companion object {
        /** 与安装器使用同一规则：`C:` / `C:/x` 一律视为绝对路径而不是相对层级。 */
        val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}

/** 严格读取的失败原因；[NotText] 与“读不到”必须区分，否则会把权限问题报成二进制内容。 */
internal sealed interface StrictUtf8Result {
    data class Ok(val text: String) : StrictUtf8Result
    data object TooLarge : StrictUtf8Result
    data object NotText : StrictUtf8Result
    data object Unreadable : StrictUtf8Result
}

/**
 * 严格 UTF-8 解码；超限、NUL 或除换行/回车/制表符外的控制字符均视为非文本。
 *
 * 保持原有可空返回语义供安装器复用。
 */
internal fun readStrictUtf8(file: File, maxBytes: Long): String? =
    (readStrictUtf8Detailed(file, maxBytes, followLinks = true) as? StrictUtf8Result.Ok)?.text

/**
 * 与 [readStrictUtf8] 相同，但 [followLinks] 为 false 时用 NOFOLLOW_LINKS 打开文件。
 *
 * 技能资源在“校验通过”和“真正读取”之间存在符号链接替换窗口；只有让 open 自己拒绝链接，
 * 才能保证读到的正是被校验的那个普通文件。
 */
internal fun readStrictUtf8NoFollow(file: File, maxBytes: Long): StrictUtf8Result =
    readStrictUtf8Detailed(file, maxBytes, followLinks = false)

private fun readStrictUtf8Detailed(
    file: File,
    maxBytes: Long,
    followLinks: Boolean,
): StrictUtf8Result {
    val length = runCatching { file.length() }.getOrElse { return StrictUtf8Result.Unreadable }
    if (length > maxBytes) return StrictUtf8Result.TooLarge
    val bytes = try {
        val options = if (followLinks) {
            emptyArray()
        } else {
            arrayOf(java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }
        var oversize = false
        val collected = Files.newInputStream(file.toPath(), *options).use { input ->
            val initialCapacity = minOf(length, maxBytes, Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(32)
            val output = ByteArrayOutputStream(initialCapacity)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    oversize = true
                    break
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (oversize) return StrictUtf8Result.TooLarge
        collected
    } catch (_: Exception) {
        // 含 NOFOLLOW_LINKS 拒绝符号链接的情况。
        return StrictUtf8Result.Unreadable
    }
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        ?: return StrictUtf8Result.NotText
    if (text.any { character ->
            character == '\u0000' ||
                (character.isISOControl() && character != '\n' && character != '\r' && character != '\t')
        }
    ) {
        return StrictUtf8Result.NotText
    }
    return StrictUtf8Result.Ok(text)
}
