package io.github.mangi.eta.agent.skill

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.SkillRegistryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val BUILTIN_SKILL_MANIFEST_ASSET = "builtin_skills/manifest.json"
internal const val BUILTIN_SKILL_SOURCE = "builtin"
internal const val USER_SKILL_SOURCE = "user"
private const val BUILTIN_SOURCE = BUILTIN_SKILL_SOURCE
private const val USER_SOURCE = USER_SKILL_SOURCE
private const val INSTALL_STATE_INSTALLED = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN = "removed_builtin"

internal fun isSafeBuiltinSkillInstallation(targetDir: File): Boolean {
    if (Files.isSymbolicLink(targetDir.toPath())) return false
    return SkillFileNames.find(targetDir) != null
}

/** 内置技能 manifest 条目。 */
private data class BuiltinSkillAsset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val assetPath: String = "",
    val hasScripts: Boolean = false,
    val hasReferences: Boolean = false,
    val hasAssets: Boolean = false,
    val hasEvals: Boolean = false,
)

/** 注册表中的技能状态记录。 */
private data class SkillRegistryEntry(
    val enabled: Boolean = true,
    val source: String = USER_SOURCE,
    val installState: String = INSTALL_STATE_INSTALLED,
)

// =====================================================================================
// SkillRegistryStore — 持久化技能安装元数据；技能正文仍保留在文件树中。
// =====================================================================================

private class SkillRegistryStore(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun read(): LinkedHashMap<String, SkillRegistryEntry> {
        return runCatching { readStrict() }.getOrElse { linkedMapOf() }
    }

    fun readStrict(): LinkedHashMap<String, SkillRegistryEntry> = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(appContext)
            .skillDao()
            .registryEntries()
            .associateTo(linkedMapOf()) { entity ->
                entity.skillId to SkillRegistryEntry(
                    enabled = entity.enabled,
                    source = entity.source.ifBlank { USER_SOURCE },
                    installState = entity.installState.ifBlank { INSTALL_STATE_INSTALLED },
                )
            }
    }

    fun write(entries: Map<String, SkillRegistryEntry>) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .skillDao()
                .replaceRegistry(
                    entries.toSortedMap().map { (skillId, value) ->
                        SkillRegistryEntity(
                            skillId = skillId,
                            enabled = value.enabled,
                            source = value.source,
                            installState = value.installState,
                        )
                    }
                )
        }
    }

    fun set(skillId: String, entry: SkillRegistryEntry) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .skillDao()
                .upsertRegistryEntry(
                    SkillRegistryEntity(
                        skillId = skillId,
                        enabled = entry.enabled,
                        source = entry.source,
                        installState = entry.installState,
                    )
                )
        }
    }

    fun remove(skillId: String) {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .skillDao()
                .deleteRegistryEntry(skillId)
        }
    }
}

// =====================================================================================
// SkillSourceMetadataStore — GitHub 来源记录（安装来源 + 检查更新的唯一依据）
// =====================================================================================

/**
 * 来源元数据文件名，位于 Skill 根目录。
 *
 * 它属于 Eta 的私有记录，不是 Skill 内容的一部分：不进 frontmatter、不进正文、不在
 * `references/` 里，并被 [SkillResourceReader] 显式排除，模型永远看不到它。
 */
internal const val SKILL_SOURCE_METADATA_FILE_NAME = ".eta-source.json"

/** 元数据 schema 版本；将来字段变更时靠它区分，未知版本一律当作「无来源」。 */
internal const val SKILL_SOURCE_METADATA_SCHEMA_VERSION = 1

/** 元数据硬上限：来源记录只有几十字节，超过这个体积一定是坏文件。 */
private const val SKILL_SOURCE_METADATA_MAX_BYTES = 4L * 1024L
private const val SKILL_SOURCE_REF_MAX_LENGTH = 200
private const val SKILL_SOURCE_PATH_MAX_LENGTH = 1_000
private const val SKILL_SOURCE_COMMIT_MAX_LENGTH = 64

private val SKILL_SOURCE_SLUG_PATTERN =
    Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}")
private val SKILL_SOURCE_REF_PATTERN = Regex("[A-Za-z0-9._/-]+")
private val SKILL_SOURCE_COMMIT_PATTERN = Regex("[a-fA-F0-9]{40,64}")
private val SKILL_SOURCE_DRIVE_PREFIX = Regex("^[A-Za-z]:")

/**
 * 一个 Skill 的 GitHub 安装来源。
 *
 * [commitSha] 的口径由 [commitScope] 决定：`ref` 表示整个 ref 的最新 commit，
 * `path` 表示最后一次改动该 path 的 commit。安装链路目前只产出 ref 口径。
 * 三者齐备才能做更新检查：只有 slug 无法定位 path，只有 slug+path 无法判断是否有变化。
 *
 * [commitScope] 说明 [commitSha] 的口径：`path` 表示「最后一次改动该路径的 commit」，
 * `ref` 是安装时拿不到路径级 commit 的降级口径（按整个 ref 的最新 commit 比较）。
 * 检查更新必须用同一口径比较，否则「仓库别处有提交」会被误报成本 Skill 有更新。
 */
@Immutable
data class SkillSourceMetadata(
    val repository: String,
    val ref: String,
    val path: String,
    val commitSha: String,
    val installedAtMillis: Long = 0L,
    val commitScope: String = SKILL_COMMIT_SCOPE_REF,
)

internal const val SKILL_COMMIT_SCOPE_PATH = "path"
internal const val SKILL_COMMIT_SCOPE_REF = "ref"

/** 检查更新时实际要查询的路径：ref 口径等价于仓库根。 */
internal val SkillSourceMetadata.lookupPath: String
    get() = if (commitScope == SKILL_COMMIT_SCOPE_REF) "." else path

/** 仓库内相对路径判定：`.` 或逐段非空、非 `.`/`..`、无反斜杠、无控制字符、无盘符。 */
internal fun isSafeSkillSourcePath(path: String): Boolean {
    val value = path.trim()
    if (value == ".") return true
    if (
        value.isEmpty() || value.length > SKILL_SOURCE_PATH_MAX_LENGTH ||
        value.startsWith('/') || value.startsWith('\\') || '\\' in value ||
        value.endsWith('/') || '\u0000' in value || value.any { it.isISOControl() } ||
        SKILL_SOURCE_DRIVE_PREFIX.containsMatchIn(value)
    ) {
        return false
    }
    return value.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/** 元数据解析：任何字段不合法都返回 null（视为「无来源」），绝不抛给调用方。 */
internal fun parseSkillSourceMetadata(raw: String?): SkillSourceMetadata? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (json.optInt("schemaVersion", 0) != SKILL_SOURCE_METADATA_SCHEMA_VERSION) return null
    val repository = json.optString("repository").trim()
    val ref = json.optString("ref").trim()
    val path = json.optString("path").trim().ifEmpty { "." }
    val commitSha = json.optString("commitSha").trim()
    if (!SKILL_SOURCE_SLUG_PATTERN.matches(repository)) return null
    if (
        ref.isEmpty() || ref.length > SKILL_SOURCE_REF_MAX_LENGTH ||
        !SKILL_SOURCE_REF_PATTERN.matches(ref) || ref.startsWith('/') || ref.endsWith('/') ||
        "//" in ref || ref.split('/').any { it == "." || it == ".." }
    ) {
        return null
    }
    if (!isSafeSkillSourcePath(path)) return null
    if (
        commitSha.length !in 40..SKILL_SOURCE_COMMIT_MAX_LENGTH ||
        !SKILL_SOURCE_COMMIT_PATTERN.matches(commitSha)
    ) {
        return null
    }
    return SkillSourceMetadata(
        repository = repository,
        ref = ref,
        path = path,
        commitSha = commitSha.lowercase(Locale.ROOT),
        installedAtMillis = json.optLong("installedAt", 0L).coerceAtLeast(0L),
        commitScope = when (json.optString("commitScope").trim()) {
            SKILL_COMMIT_SCOPE_REF -> SKILL_COMMIT_SCOPE_REF
            else -> SKILL_COMMIT_SCOPE_PATH
        },
    )
}

internal fun serializeSkillSourceMetadata(metadata: SkillSourceMetadata): String = JSONObject()
    .put("schemaVersion", SKILL_SOURCE_METADATA_SCHEMA_VERSION)
    .put("repository", metadata.repository)
    .put("ref", metadata.ref)
    .put("path", metadata.path)
    .put("commitSha", metadata.commitSha.lowercase(Locale.ROOT))
    .put("commitScope", metadata.commitScope)
    .put("installedAt", metadata.installedAtMillis)
    .toString()

/**
 * `.eta-source.json` 读写。
 *
 * 容错要求是硬性的：文件缺失、损坏、超大、是符号链接、字段不合法——一律返回 null，
 * 让 Skill 继续可用，只是不参与更新检查。写失败也返回 false 而不是抛异常，因为
 * 「来源记录丢了」远轻于「Skill 装不上」。
 */
internal object SkillSourceMetadataStore {
    fun fileFor(skillDir: File): File = File(skillDir, SKILL_SOURCE_METADATA_FILE_NAME)

    fun read(skillDir: File): SkillSourceMetadata? {
        if (!skillDir.isDirectory || Files.isSymbolicLink(skillDir.toPath())) return null
        val file = fileFor(skillDir)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return null
        val length = runCatching { file.length() }.getOrElse { return null }
        if (length <= 0L || length > SKILL_SOURCE_METADATA_MAX_BYTES) return null
        val text = (readStrictUtf8NoFollow(file, SKILL_SOURCE_METADATA_MAX_BYTES) as? StrictUtf8Result.Ok)
            ?.text
            ?: return null
        return parseSkillSourceMetadata(text)
    }

    fun write(skillDir: File, metadata: SkillSourceMetadata): Boolean {
        if (!skillDir.isDirectory || Files.isSymbolicLink(skillDir.toPath())) return false
        if (!SKILL_SOURCE_SLUG_PATTERN.matches(metadata.repository)) return false
        if (!isSafeSkillSourcePath(metadata.path)) return false
        if (!SKILL_SOURCE_COMMIT_PATTERN.matches(metadata.commitSha)) return false
        val target = fileFor(skillDir)
        if (Files.isSymbolicLink(target.toPath())) return false
        val payload = serializeSkillSourceMetadata(metadata).toByteArray(Charsets.UTF_8)
        if (payload.size > SKILL_SOURCE_METADATA_MAX_BYTES) return false
        // 先写临时文件再原子替换：并发读到半个 JSON 会被当成「无来源」，等于随机丢失更新提示。
        val temp = File(skillDir, "$SKILL_SOURCE_METADATA_FILE_NAME.${UUID.randomUUID()}.tmp")
        return runCatching {
            temp.outputStream().use { output ->
                output.write(payload)
                output.flush()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrElse {
            runCatching { temp.delete() }
            false
        }
    }

    fun delete(skillDir: File): Boolean {
        val file = fileFor(skillDir)
        if (Files.isSymbolicLink(file.toPath())) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }
}

// =====================================================================================
// SkillUpdateService — 检查已装 Skill 在来源仓库中的更新
// =====================================================================================

/** 同一 Skill 的两次检查最短间隔：匿名 GitHub API 每小时只有 60 次额度。 */
internal const val SKILL_UPDATE_CHECK_THROTTLE_MILLIS = 60_000L

/** 单次检查最多查询的 Skill 数，避免一次点击烧掉整小时额度。 */
internal const val MAX_SKILL_UPDATE_CHECKS = 20

/** 查询「该仓库该 path 在 ref 上的最新 commit SHA」；实现方失败时直接抛异常。 */
internal fun interface SkillSourceCommitLookup {
    fun latestCommitSha(repository: String, ref: String, path: String): String
}

/**
 * 基于 GitHub REST 的路径级 commit 查询。
 *
 * 只发一个请求：`GET /repos/{owner}/{repo}/commits?path=…&sha=…&per_page=1`。
 * 与安装链路保持同一套安全约束——不带 Token、不跟随重定向、响应体硬上限；
 * 失败沿用 [GitHubSkillSourceException] 的错误码，让上层能翻成可执行的中文提示。
 * `path == "."` 时表示仓库根，等价于整个 ref 的最新 commit。
 */
internal class GitHubSkillCommitLookup(
    baseClient: OkHttpClient,
) : SkillSourceCommitLookup {
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun latestCommitSha(repository: String, ref: String, path: String): String {
        val slug = repository.trim()
        if (!SKILL_SOURCE_SLUG_PATTERN.matches(slug)) {
            throw IllegalArgumentException("来源仓库记录无效，请重新从 GitHub 安装该 Skill")
        }
        val normalizedRef = GitHubSkillRepositoryParser.normalizeRef(ref)
        val normalizedPath = GitHubSkillRepositoryParser.normalizeRelativePath(path)
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(GITHUB_API_HOST)
            .addPathSegment("repos")
            .addPathSegment(slug.substringBefore('/'))
            .addPathSegment(slug.substringAfter('/'))
            .addPathSegment("commits")
            .addQueryParameter("sha", normalizedRef)
            .addQueryParameter("per_page", "1")
            .apply { if (normalizedPath != ".") addQueryParameter("path", normalizedPath) }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", GITHUB_USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                throw GitHubSkillSourceException(
                    "GITHUB_REDIRECT_REJECTED",
                    "GitHub 请求发生重定向，已拒绝访问其他主机",
                )
            }
            if (!response.isSuccessful) {
                throw when {
                    response.code == 403 || response.code == 429 -> GitHubSkillSourceException(
                        "GITHUB_RATE_LIMITED",
                        "GitHub 匿名 API 请求已达上限（每小时 60 次）；请稍后重试",
                    )
                    response.code == 404 -> GitHubSkillSourceException(
                        "GITHUB_NOT_FOUND",
                        "来源仓库或路径已不存在（HTTP 404）；该 Skill 的仓库可能被删除或改名",
                    )
                    else -> GitHubSkillSourceException(
                        "GITHUB_REQUEST_FAILED",
                        "GitHub 请求失败（HTTP ${response.code}）；请稍后重试",
                    )
                }
            }
            val length = response.body.contentLength()
            if (length > MAX_UPDATE_CHECK_RESPONSE_BYTES) {
                throw GitHubSkillSourceException("GITHUB_RESPONSE_TOO_LARGE", "GitHub 响应超过安全上限")
            }
            response.body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_UPDATE_CHECK_RESPONSE_BYTES) {
                        throw GitHubSkillSourceException(
                            "GITHUB_RESPONSE_TOO_LARGE",
                            "GitHub 响应超过安全上限",
                        )
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        }
        val commits = runCatching { JSONArray(body) }.getOrElse {
            throw GitHubSkillSourceException("INVALID_GITHUB_RESPONSE", "GitHub 返回了无效 JSON", it)
        }
        return (0 until commits.length())
            .asSequence()
            .mapNotNull { commits.optJSONObject(it) }
            .map { it.optString("sha") }
            .firstOrNull { SKILL_SOURCE_COMMIT_PATTERN.matches(it) }
            ?.lowercase(Locale.ROOT)
            ?: throw GitHubSkillSourceException(
                "INVALID_GITHUB_RESPONSE",
                "GitHub 未返回该路径的有效 commit；请确认仓库与路径仍然存在",
            )
    }

    private companion object {
        const val GITHUB_API_HOST = "api.github.com"
        const val GITHUB_USER_AGENT = "Eta-Skill-Updater"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val MAX_UPDATE_CHECK_RESPONSE_BYTES = 1L * 1024 * 1024
    }
}

/** 单个 Skill 的更新检查结果。[error] 非空表示这次没查成，原因必须原样展示给用户。 */
@Immutable
data class SkillUpdateStatus(
    val skillId: String,
    val source: SkillSourceMetadata,
    val latestCommitSha: String? = null,
    val hasUpdate: Boolean = false,
    val error: String? = null,
    val checkedAtMillis: Long = 0L,
) {
    val shortInstalledSha: String get() = source.commitSha.take(8)
    val shortLatestSha: String get() = latestCommitSha?.take(8).orEmpty()
}

/**
 * 更新检查编排。
 *
 * 只处理「有来源记录」的 Skill：手工装的老 Skill 没有 slug/commit，无法判断新旧，
 * 直接跳过而不是报错。限流按 skillId 记忆上次检查时间与结果，窗口内重复调用不会
 * 再打网络，返回的仍是上一次的真实结论。
 */
internal class SkillUpdateService(
    private val sourceReader: (String) -> SkillSourceMetadata?,
    private val lookup: SkillSourceCommitLookup,
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttleMillis: Long = SKILL_UPDATE_CHECK_THROTTLE_MILLIS,
    private val maxChecks: Int = MAX_SKILL_UPDATE_CHECKS,
) {
    private val lastCheckedAt = ConcurrentHashMap<String, Long>()
    private val lastStatus = ConcurrentHashMap<String, SkillUpdateStatus>()

    constructor(
        indexService: SkillIndexService,
        lookup: SkillSourceCommitLookup,
        clock: () -> Long = System::currentTimeMillis,
        throttleMillis: Long = SKILL_UPDATE_CHECK_THROTTLE_MILLIS,
    ) : this(
        sourceReader = indexService::readSkillSource,
        lookup = lookup,
        clock = clock,
        throttleMillis = throttleMillis,
    )

    fun checkForUpdates(
        skillIds: Collection<String>,
        force: Boolean = false,
    ): Map<String, SkillUpdateStatus> {
        val now = clock()
        val result = LinkedHashMap<String, SkillUpdateStatus>()
        var networkChecks = 0
        skillIds.asSequence().distinct().forEach { skillId ->
            val source = runCatching { sourceReader(skillId) }.getOrNull() ?: return@forEach
            val previous = lastCheckedAt[skillId]
            if (!force && previous != null && now - previous < throttleMillis) {
                lastStatus[skillId]?.let { result[skillId] = it }
                return@forEach
            }
            if (networkChecks >= maxChecks) return@forEach
            networkChecks += 1
            val status = checkOne(skillId, source, now)
            lastCheckedAt[skillId] = now
            lastStatus[skillId] = status
            result[skillId] = status
        }
        return result
    }

    /** 记录一次外部已知结果（例如刚安装/刚更新完），顺带把限流窗口重置到当前时间。 */
    fun rememberInstalled(skillId: String, source: SkillSourceMetadata, now: Long = clock()) {
        lastCheckedAt[skillId] = now
        lastStatus[skillId] = SkillUpdateStatus(
            skillId = skillId,
            source = source,
            latestCommitSha = source.commitSha,
            hasUpdate = false,
            checkedAtMillis = now,
        )
    }

    fun forget(skillId: String) {
        lastCheckedAt.remove(skillId)
        lastStatus.remove(skillId)
    }    private fun checkOne(skillId: String, source: SkillSourceMetadata, now: Long): SkillUpdateStatus {
        val latest = try {
            lookup.latestCommitSha(source.repository, source.ref, source.lookupPath)
        } catch (error: Exception) {
            return SkillUpdateStatus(
                skillId = skillId,
                source = source,
                error = describeUpdateCheckFailure(error),
                checkedAtMillis = now,
            )
        }
        if (!SKILL_SOURCE_COMMIT_PATTERN.matches(latest)) {
            return SkillUpdateStatus(
                skillId = skillId,
                source = source,
                error = "GitHub 返回的 commit 格式无效，请稍后重试",
                checkedAtMillis = now,
            )
        }
        return SkillUpdateStatus(
            skillId = skillId,
            source = source,
            latestCommitSha = latest.lowercase(Locale.ROOT),
            hasUpdate = !latest.equals(source.commitSha, ignoreCase = true),
            checkedAtMillis = now,
        )
    }
}

/** 把后端异常翻成用户能照着做的中文原因；绝不吞掉成「检查失败」四个字。 */
internal fun describeUpdateCheckFailure(error: Throwable): String = when (error) {
    is GitHubSkillSourceException -> error.message ?: "检查更新失败（${error.code}）"
    is IllegalArgumentException -> error.message ?: "来源记录无效，请重新从 GitHub 安装该 Skill"
    else -> "检查更新失败：${error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName}"
}

// =====================================================================================
// BuiltinSkillAssetStore — 从 assets 读取并安装内置技能
// =====================================================================================

private class BuiltinSkillAssetStore(
    private val context: Context,
    private val skillsRoot: File,
) {
    private val builtins: List<BuiltinSkillAsset> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            context.assets.open(BUILTIN_SKILL_MANIFEST_ASSET).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val arr = json.optJSONArray("skills") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    BuiltinSkillAsset(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        description = obj.optString("description"),
                        assetPath = obj.optString("assetPath"),
                        hasScripts = obj.optBoolean("hasScripts"),
                        hasReferences = obj.optBoolean("hasReferences"),
                        hasAssets = obj.optBoolean("hasAssets"),
                        hasEvals = obj.optBoolean("hasEvals"),
                    )
                }.filter { it.id.isNotBlank() && it.assetPath.isNotBlank() }
            }
        }.getOrElse { emptyList() }
    }

    fun listBuiltins(): List<BuiltinSkillAsset> = builtins

    fun findBuiltin(skillId: String): BuiltinSkillAsset? =
        listBuiltins().firstOrNull { it.id == skillId }

    fun seedMissingBuiltins(registryStore: SkillRegistryStore) {
        // 读取失败必须中止播种：以空快照继续会把 registry 整体改写为“只有内置项且全部启用”，
        // 等于丢掉所有用户 Skill 的来源标记与 enabled=false 选择。
        val registry = registryStore.readStrict()
        var changed = false
        listBuiltins().forEach { builtin ->
            val entry = registry[builtin.id]
            if (entry?.installState == INSTALL_STATE_REMOVED_BUILTIN) return@forEach
            if (entry?.source == USER_SOURCE) return@forEach
            val targetDir = File(skillsRoot, builtin.id)
            if (!isSafeBuiltinSkillInstallation(targetDir)) {
                installBuiltinInternal(builtin)
            }
            if (entry?.source == BUILTIN_SOURCE && entry.installState == INSTALL_STATE_INSTALLED) {
                return@forEach
            }
            registry[builtin.id] = SkillRegistryEntry(
                // 重新落盘内置 Skill 文件不代表用户想重新启用它：保留既有开关状态。
                enabled = entry?.enabled ?: true,
                source = BUILTIN_SOURCE,
                installState = INSTALL_STATE_INSTALLED,
            )
            changed = true
        }
        if (changed) registryStore.write(registry)
    }

    fun installBuiltin(skillId: String, registryStore: SkillRegistryStore) {
        val builtin = findBuiltin(skillId)
            ?: throw IllegalArgumentException("未找到内置 skill：$skillId")
        installBuiltinInternal(builtin)
        registryStore.set(
            skillId,
            SkillRegistryEntry(enabled = true, source = BUILTIN_SOURCE, installState = INSTALL_STATE_INSTALLED),
        )
    }

    private fun installBuiltinInternal(builtin: BuiltinSkillAsset) {
        val targetDir = File(skillsRoot, builtin.id)
        if (Files.exists(targetDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, targetDir)) {
                error("无法安全清理内置 Skill 目录：${builtin.id}")
            }
        }
        copyAssetRecursively(context.assets, builtin.assetPath, targetDir)
    }

    private fun copyAssetRecursively(assetManager: AssetManager, assetPath: String, target: File) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        if (!target.exists()) target.mkdirs()
        children.forEach { child ->
            copyAssetRecursively(assetManager, "$assetPath/$child", File(target, child))
        }
    }
}

// =====================================================================================
// SkillIndexService — 扫描、索引、管理技能
// =====================================================================================

class SkillIndexService(
    private val context: Context,
    private val skillsRoot: File,
) {
    private val indexLock = Any()
    private val registryStore = SkillRegistryStore(context.applicationContext)
    private val builtinStore = BuiltinSkillAssetStore(context.applicationContext, skillsRoot)

    @Volatile
    private var builtinsSeeded = false

    @Volatile
    private var cachedManagementEntries: IndexCache? = null

    /**
     * 索引缓存只有在“skills 根目录一级条目 + registry 内容 + 隔离集合”都没变化时才可复用。
     *
     * 只按进程内的 invalidate 标记复用会让第二个 [SkillIndexService]（另一个进程、
     * 或 UI 与 Agent 各自持有的实例）永远读不到别人的安装/禁用结果——即“装了但模型看不到”
     * 与“已禁用却仍在索引里”。这里只读一次根目录名字和一张小表，不做递归遍历，
     * 也不重新解析任何 SKILL.md。
     */
    private data class IndexSignature(
        val rootChildrenFingerprint: Int,
        val registryFingerprint: Int,
        val quarantineFingerprint: Int,
    )

    private data class IndexCache(
        val signature: IndexSignature,
        val entries: List<SkillIndexEntry>,
    )

    /**
     * 所有可读写索引的入口都先在同一把跨进程锁内完成待处理文件事务与 Room 快照恢复。
     * 仅能读取文件、不具备 registry 恢复能力的 Loader 会由锁实现保持 fail-closed。
     */
    internal fun <T> withMutationLock(block: () -> T): T = SkillMutationLock.withLock(
        skillsRoot = skillsRoot,
        recoveryHandler = ::restoreRecoveredRegistry,
        block = block,
    )

    fun seedBuiltinSkillsIfNeeded() {
        withMutationLock {
            if (builtinsSeeded) return@withMutationLock
            synchronized(indexLock) {
                seedBuiltinSkillsLocked()
            }
        }
    }

    fun listSkillsForManagement(forceRefresh: Boolean = false): List<SkillIndexEntry> =
        withMutationLock {
            synchronized(indexLock) {
                if (forceRefresh) {
                    builtinsSeeded = false
                    cachedManagementEntries = null
                }
                seedBuiltinSkillsLocked()
                // registry 读失败必须暴露，而不是当成“没有 registry”：后者会把所有 Skill
                // 默认成 enabled=true，等于绕过用户的禁用选择。
                val registry = registryStore.readStrict()
                val quarantined = quarantinedSkillIds(skillsRoot)
                val signature = IndexSignature(
                    rootChildrenFingerprint = rootChildrenFingerprint(),
                    registryFingerprint = registryFingerprint(registry),
                    quarantineFingerprint = quarantined.hashCode(),
                )
                cachedManagementEntries
                    ?.takeIf { it.signature == signature }
                    ?.entries
                    ?: buildManagementEntries(registry, quarantined).also {
                        cachedManagementEntries = IndexCache(signature, it)
                    }
            }
        }

    /**
     * 注入到模型上下文的技能集合。
     *
     * 任何索引/恢复失败都返回空集合：宁可本轮没有技能，也不能把未验证的正文注入提示词，
     * 更不能让一次技能恢复失败导致整次 Agent 运行抛异常。管理/UI 路径仍会看到真实错误。
     */
    fun listInstalledSkills(): List<SkillIndexEntry> = try {
        listSkillsForManagement().filter { it.installed && it.enabled }
    } catch (_: Exception) {
        emptyList()
    }

    fun findInstalledSkill(identifier: String): SkillIndexEntry? {
        val normalized = SkillParser.normalizeSkillLookup(identifier)
        if (normalized.isBlank()) return null
        val entries = try {
            listSkillsForManagement().filter { it.installed && it.enabled }
        } catch (_: Exception) {
            return null
        }
        return entries.firstOrNull { SkillParser.normalizeSkillLookup(it.id) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.name) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.skillFilePath) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.rootPath) == normalized }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean): SkillIndexEntry {
        return withMutationLock {
            synchronized(indexLock) {
                val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
                    ?: throw IllegalArgumentException("未找到已安装 skill：$skillId")
                registryStore.set(
                    entry.id,
                    SkillRegistryEntry(enabled = enabled, source = entry.source, installState = INSTALL_STATE_INSTALLED),
                )
                invalidateIndexLocked()
                entry.copy(enabled = enabled)
            }
        }
    }

    fun deleteSkill(skillId: String): Boolean {
        return withMutationLock {
            synchronized(indexLock) {
                val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
                    ?: return@synchronized false
                val targetDir = managedSkillDirectory(entry) ?: return@synchronized false
                val registrySnapshot = captureRegistryRecoverySnapshots(listOf(entry.id)).single()
                val operation = runCatching {
                    createSkillRecoveryOperationDirectory(skillsRoot)
                }.getOrElse { return@synchronized false }
                val workRoot = skillInstallerWorkRoot(skillsRoot)
                val backupRoot = File(operation, "backup")
                if (!backupRoot.mkdir()) {
                    deleteSkillPathWithoutFollowingLinks(workRoot, operation)
                    return@synchronized false
                }
                val backupDir = File(backupRoot, entry.id)
                var registryMutationStarted = false
                try {
                    val journal = PendingSkillRecoveryJournal.begin(
                        skillsRoot = skillsRoot,
                        operationDirectory = operation,
                        records = listOf(
                            SkillRecoveryRecord(
                                id = entry.id,
                                originalTargetExisted = true,
                                registrySnapshot = registrySnapshot,
                            )
                        ),
                    )
                    moveSkillDirectoryAtomically(targetDir, backupDir)
                    journal.markBackupCompleted(entry.id)
                    registryMutationStarted = true
                    if (entry.source == BUILTIN_SOURCE) {
                        registryStore.set(
                            entry.id,
                            SkillRegistryEntry(
                                enabled = false,
                                source = BUILTIN_SOURCE,
                                installState = INSTALL_STATE_REMOVED_BUILTIN,
                            ),
                        )
                    } else {
                        registryStore.remove(entry.id)
                    }
                    invalidateIndexLocked()
                    journal.clear()
                    true
                } catch (error: Exception) {
                    val journalExists = Files.exists(
                        File(operation, JOURNAL_FILE_NAME).toPath(),
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    val rollbackComplete = !journalExists || runCatching {
                        val recovered = recoverPendingSkillOperations(skillsRoot)
                        restoreRecoveredRegistry(recovered)
                        completeRecoveredSkillOperations(skillsRoot, recovered)
                    }.isSuccess
                    if (!rollbackComplete) {
                        throw SkillRecoveryRequiredException(
                            "Skill 删除失败，且文件或 registry 尚未完整恢复",
                            error,
                        )
                    }
                    invalidateIndexLocked()
                    if (registryMutationStarted) throw error
                    false
                } finally {
                    if (!Files.exists(
                            File(operation, JOURNAL_FILE_NAME).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        )
                    ) {
                        // 成功删除时备份内可能含历史 symlink，必须只 unlink，不能递归跟随。
                        deleteSkillPathWithoutFollowingLinks(workRoot, operation)
                    }
                }
            }
        }
    }

    fun installBuiltinSkill(skillId: String): SkillIndexEntry {
        return withMutationLock {
            synchronized(indexLock) {
                builtinStore.installBuiltin(skillId, registryStore)
                invalidateIndexLocked()
                findInstalledSkill(skillId)
                    ?: throw IllegalStateException("安装内置 skill 后索引失败：$skillId")
            }
        }
    }

    /** 文件提交成功后，以单次 Room 事务登记用户 Skill，并同步清除索引缓存。 */
    internal fun registerInstalledUserSkills(skillIds: List<String>) {
        withMutationLock {
            synchronized(indexLock) {
                require(skillIds.none { builtinStore.findBuiltin(it) != null }) {
                    "不能把内置 Skill 登记为用户 Skill"
                }
                val registry = registryStore.readStrict()
                skillIds.distinct().forEach { skillId ->
                    registry[skillId] = SkillRegistryEntry(
                        enabled = true,
                        source = USER_SOURCE,
                        installState = INSTALL_STATE_INSTALLED,
                    )
                }
                registryStore.write(registry)
                invalidateIndexLocked()
            }
        }
    }

    /** 在正式目录发生任何移动前，持久化事务涉及 id 的完整旧 registry 状态。 */
    internal fun captureRegistryRecoverySnapshots(
        skillIds: List<String>,
    ): List<SkillRegistryRecoverySnapshot> = synchronized(indexLock) {
        val registry = registryStore.readStrict()
        skillIds.distinct().map { skillId ->
            val entry = registry[skillId]
            if (entry == null) {
                SkillRegistryRecoverySnapshot(skillId = skillId, entryExisted = false)
            } else {
                SkillRegistryRecoverySnapshot(
                    skillId = skillId,
                    entryExisted = true,
                    enabled = entry.enabled,
                    source = entry.source,
                    installState = entry.installState,
                )
            }
        }
    }

    /** 文件恢复完成后，以单次 Room 事务还原全部旧快照；失败时调用方保留 journal。 */
    internal fun restoreRecoveredRegistry(recovered: List<RecoveredSkillOperation>) {
        if (recovered.isEmpty()) return
        synchronized(indexLock) {
            val registry = registryStore.readStrict()
            recovered.flatMap { it.records }.forEach { record ->
                val snapshot = record.registrySnapshot
                if (snapshot.entryExisted) {
                    registry[snapshot.skillId] = SkillRegistryEntry(
                        enabled = snapshot.enabled,
                        source = snapshot.source,
                        installState = snapshot.installState,
                    )
                } else {
                    registry.remove(snapshot.skillId)
                }
            }
            registryStore.write(registry)
            invalidateIndexLocked()
        }
    }

    internal fun isBuiltinSkillId(skillId: String): Boolean =
        synchronized(indexLock) { builtinStore.findBuiltin(skillId) != null }

    /**
     * 读取某个已安装 Skill 的 GitHub 来源记录。
     *
     * 只认「已安装 + 有可管理目录」的条目；内置 Skill、占位条目、目录已越界的一律返回 null，
     * 让调用方按「无来源」处理。读取失败（损坏、缺字段、无权限）同样返回 null。
     *
     * 刻意不在 [indexLock] 内调用 [listSkillsForManagement]：后者自己会先取跨进程
     * mutation 锁再取 indexLock，若这里反过来持有 indexLock 等 mutation 锁就会死锁。
     */
    fun readSkillSource(skillId: String): SkillSourceMetadata? {
        val entry = runCatching { listSkillsForManagement() }.getOrNull()
            ?.firstOrNull { it.id == skillId && it.installed && it.source == USER_SOURCE }
            ?: return null
        val directory = managedSkillDirectory(entry) ?: return null
        return runCatching { SkillSourceMetadataStore.read(directory) }.getOrNull()
    }

    /**
     * 写入某个已安装 Skill 的来源记录。
     *
     * 只对用户 Skill 生效：内置 Skill 由 assets 提供，不存在「来源仓库更新」。
     * 写失败只返回 false：来源记录是「更新检查」的附加信息，绝不能因此让安装流程失败，
     * 更不能让已经可用的 Skill 变成不可用。
     */
    fun recordSkillSource(skillId: String, metadata: SkillSourceMetadata): Boolean {
        val entry = runCatching { listSkillsForManagement() }.getOrNull()
            ?.firstOrNull { it.id == skillId && it.installed && it.source == USER_SOURCE }
            ?: return false
        val directory = managedSkillDirectory(entry) ?: return false
        return runCatching { SkillSourceMetadataStore.write(directory, metadata) }.getOrDefault(false)
    }

    /**
     * 一次性读出所有已安装用户 Skill 的来源记录。
     *
     * 列表页每刷新一次都要知道「哪些 Skill 能检查更新」，逐个调用 [readSkillSource] 会把
     * 索引重算 N 次；这里复用同一份缓存快照，只做 N 次小文件读取。
     */
    fun readInstalledSkillSources(): Map<String, SkillSourceMetadata> {
        val entries = runCatching { listSkillsForManagement() }.getOrElse { return emptyMap() }
        return entries.asSequence()
            .filter { it.installed && it.source == USER_SOURCE }
            .mapNotNull { entry ->
                val directory = managedSkillDirectory(entry) ?: return@mapNotNull null
                val metadata = runCatching { SkillSourceMetadataStore.read(directory) }.getOrNull()
                    ?: return@mapNotNull null
                entry.id to metadata
            }
            .toMap()
    }

    private fun seedBuiltinSkillsLocked() {
        if (builtinsSeeded) return
        if (!skillsRoot.exists() && !skillsRoot.mkdirs()) {
            error("无法创建 Skills 目录：${skillsRoot.absolutePath}")
        }
        builtinStore.seedMissingBuiltins(registryStore)
        builtinsSeeded = true
        invalidateIndexLocked()
    }

    /** registry 内容指纹；只读取小表，不触碰 skills 文件树。 */
    private fun registryFingerprint(registry: Map<String, SkillRegistryEntry>): Int {
        var result = registry.size
        registry.forEach { (skillId, entry) ->
            result = 31 * result + skillId.hashCode()
            result = 31 * result + entry.hashCode()
        }
        return result
    }

    /**
     * skills 根目录一级条目名指纹。
     *
     * 只取名字：安装/删除会改变名字集合，跨实例立即可见；而 Skill 内部文件的原地修改不会
     * 改变名字集合，需要调用方显式 `forceRefresh = true`（UI 与安装器都这么做）。
     * 若把 mtime 也算进来，任何一次资源写入都会让 100+ 技能的索引全量重建。
     */
    private fun rootChildrenFingerprint(): Int {
        val children = runCatching { skillsRoot.list() }.getOrNull() ?: return -1
        return children.sorted().hashCode()
    }

    private fun buildManagementEntries(
        registry: Map<String, SkillRegistryEntry>,
        quarantined: Set<String>,
    ): List<SkillIndexEntry> {
        val builtinAssets = builtinStore.listBuiltins().associateBy { it.id }
        val installed = scanInstalledEntries(registry, builtinAssets, quarantined)
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        val removedBuiltins = builtinAssets.values
            .asSequence()
            .filter { it.id !in installedIds && registry[it.id]?.installState == INSTALL_STATE_REMOVED_BUILTIN }
            .map { buildBuiltinPlaceholder(it, registry[it.id]) }
            .toList()
        return (installed + removedBuiltins).sortedWith(
            compareByDescending<SkillIndexEntry> { it.installed }
                .thenBy { sourceRank(it.source) }
                .thenBy { it.name.lowercase() },
        )
    }

    private fun invalidateIndexLocked() {
        cachedManagementEntries = null
    }

    private fun scanInstalledEntries(
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
        quarantined: Set<String>,
    ): List<SkillIndexEntry> {
        if (!skillsRoot.exists()) return emptyList()
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val skillFiles = skillsRoot.walkTopDown()
            .onEnter { dir ->
                dir.name != ".git" &&
                    !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(canonicalRoot) }.getOrDefault(false)
            }
            .filter {
                it.isFile && SkillFileNames.isSkillFileName(it.name) &&
                    !Files.isSymbolicLink(it.toPath())
            }
            .toList()
        // 一个目录只产生一个 Skill：大小写变体在大小写敏感的文件系统上可以共存，
        // 若逐个建条目，会得到两个 id 相同、rootPath 不同的条目并在后续去重时随机留下一个。
        return skillFiles
            .groupBy { it.parentFile?.canonicalPath.orEmpty() }
            .mapNotNull { (_, files) ->
                val chosen = SkillFileNames.preferredVariant(files.map { it.name }) ?: return@mapNotNull null
                val directory = files.first().parentFile ?: return@mapNotNull null
                buildInstalledEntry(
                    skillDir = directory,
                    skillFile = File(directory, chosen),
                    registry = registry,
                    builtinAssets = builtinAssets,
                )
            }
            .distinctBy { it.rootPath }
            .filter { entry -> entry.id !in quarantined }
            .sortedBy { it.id }
    }

    private fun buildInstalledEntry(
        skillDir: File,
        skillFile: File,
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
    ): SkillIndexEntry? {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val canonicalDir = skillDir.canonicalFile
        val canonicalPath = canonicalDir.toPath()
        if (!canonicalPath.startsWith(canonicalRoot) || canonicalPath == canonicalRoot) return null
        if (Files.isSymbolicLink(skillFile.toPath())) return null
        val parsed = SkillParser.parseSkillFile(skillFile) ?: return null
        val frontmatter = parsed.frontmatter
        val id = SkillParser.sanitizeSkillId(canonicalDir.name, frontmatter["name"])
        val metadata = frontmatter["metadata"]?.let { SkillParser.parseIndentedBlock(it) } ?: emptyMap()
        val registryState = registry[id]
        val builtinAsset = builtinAssets[id]
        return SkillIndexEntry(
            id = id,
            name = frontmatter["name"]?.ifBlank { id } ?: id,
            description = frontmatter["description"]?.trim().orEmpty(),
            compatibility = frontmatter["compatibility"]?.trim(),
            metadata = metadata,
            rootPath = canonicalDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = File(canonicalDir, "scripts").isDirectory,
            hasReferences = File(canonicalDir, "references").isDirectory,
            hasAssets = File(canonicalDir, "assets").isDirectory,
            hasEvals = File(canonicalDir, "evals").isDirectory,
            enabled = registryState?.enabled ?: true,
            source = registryState?.source?.ifBlank { null }
                ?: if (builtinAsset != null) BUILTIN_SOURCE else USER_SOURCE,
            installed = true,
        )
    }

    private fun buildBuiltinPlaceholder(
        builtin: BuiltinSkillAsset,
        registryState: SkillRegistryEntry?,
    ): SkillIndexEntry {
        val targetDir = File(skillsRoot, builtin.id)
        // 占位条目的路径只用于展示与安装；优先回显目录中真实存在的变体文件名。
        val skillFile = SkillFileNames.find(targetDir) ?: File(targetDir, SkillFileNames.CANONICAL)
        return SkillIndexEntry(
            id = builtin.id,
            name = builtin.name.ifBlank { builtin.id },
            description = builtin.description,
            rootPath = targetDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = builtin.hasScripts,
            hasReferences = builtin.hasReferences,
            hasAssets = builtin.hasAssets,
            hasEvals = builtin.hasEvals,
            enabled = registryState?.enabled ?: false,
            source = BUILTIN_SOURCE,
            installed = false,
        )
    }

    private fun sourceRank(source: String): Int = when (source) {
        BUILTIN_SOURCE -> 0
        else -> 2
    }

    private fun managedSkillDirectory(entry: SkillIndexEntry): File? {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val requested = File(entry.rootPath)
        if (Files.isSymbolicLink(requested.toPath())) return null
        val canonical = runCatching { requested.canonicalFile }.getOrNull() ?: return null
        val candidatePath = canonical.toPath()
        return canonical.takeIf {
            candidatePath.startsWith(canonicalRoot) && candidatePath != canonicalRoot && it.isDirectory
        }
    }
}

// =====================================================================================
// SkillLoader — 加载技能正文和附属资源
// =====================================================================================

class SkillLoader(private val skillsRoot: File) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile
    private val resourceReader = SkillResourceReader(skillsRoot)

    fun load(entry: SkillIndexEntry, triggerReason: String): ResolvedSkillContext? =
        SkillMutationLock.withLock(skillsRoot) {
            loadAfterRecovery(entry, triggerReason)
        }

    private fun loadAfterRecovery(
        entry: SkillIndexEntry,
        triggerReason: String,
    ): ResolvedSkillContext? {
        if (!entry.installed) return null
        val requestedRoot = File(entry.rootPath)
        if (Files.isSymbolicLink(requestedRoot.toPath())) return null
        val skillDir = runCatching { requestedRoot.canonicalFile }.getOrNull() ?: return null
        val rootPath = canonicalSkillsRoot.toPath()
        val skillPath = skillDir.toPath()
        if (!skillPath.startsWith(rootPath) || skillPath == rootPath) return null
        val skillFile = SkillFileNames.find(skillDir) ?: return null
        val parsed = SkillParser.parseSkillFile(skillFile) ?: return null
        val loadedReferences = when (val resources = resourceReader.listResources(entry, "references")) {
            is SkillResourceListResult.Success -> resources.resources.map { it.relativePath }
            is SkillResourceListResult.Failure -> emptyList()
        }
        return ResolvedSkillContext(
            skillId = entry.id,
            frontmatter = parsed.frontmatter,
            metadata = entry.metadata,
            bodyMarkdown = parsed.body,
            loadedReferences = loadedReferences,
            scriptsDir = File(skillDir, "scripts").takeIf { it.isDirectory }?.absolutePath,
            assetsDir = File(skillDir, "assets").takeIf { it.isDirectory }?.absolutePath,
            triggerReason = triggerReason,
            revision = parsed.revision,
        )
    }
}

// =====================================================================================
// SkillCompatibilityChecker — 兼容性检查
// =====================================================================================

object SkillCompatibilityChecker {
    fun evaluate(entry: SkillIndexEntry): SkillCompatibilityResult {
        val raw = buildString {
            append(entry.compatibility.orEmpty())
            if (entry.metadata.isNotEmpty()) {
                append(' ')
                append(entry.metadata.values.joinToString(" "))
            }
            append(' ')
            append(entry.description)
        }.lowercase()
        return when {
            raw.contains("apple-") || raw.contains("homekit") || raw.contains("healthkit") ->
                SkillCompatibilityResult(available = false, reason = "不支持 Apple 专属运行时")
            raw.contains("ios") && !raw.contains("android") ->
                SkillCompatibilityResult(available = false, reason = "该 Skill 标注为 iOS 专属")
            else -> SkillCompatibilityResult(available = true)
        }
    }
}

// =====================================================================================
// SkillRuntime — 工厂入口
// =====================================================================================

object SkillRuntime {
    @Volatile
    private var sharedIndexService: SkillIndexService? = null

    @Volatile
    private var sharedUpdateService: SkillUpdateService? = null

    fun skillsRoot(context: Context): File = File(context.filesDir, "skills")

    fun createIndexService(context: Context): SkillIndexService {
        sharedIndexService?.let { return it }
        return synchronized(this) {
            sharedIndexService ?: SkillIndexService(
                context = context.applicationContext,
                skillsRoot = skillsRoot(context),
            ).also { sharedIndexService = it }
        }
    }

    fun createLoader(context: Context): SkillLoader =
        SkillLoader(skillsRoot(context))

    fun createPackageInstaller(context: Context): SkillPackageInstaller =
        SkillPackageInstaller(
            skillsRoot = skillsRoot(context),
            indexService = createIndexService(context),
        )

    fun createResourceReader(context: Context): SkillResourceReader =
        SkillResourceReader(skillsRoot(context))

    /** 更新检查服务：来源记录来自索引服务，commit 查询走公共 GitHub 源。 */
    internal fun createUpdateService(context: Context): SkillUpdateService {
        val appContext = context.applicationContext
        // 必须复用同一个实例：限流状态（上次检查时间 + 上次结论）就存在实例里，
        // 每次新建会让「同一 Skill 短时间内不重复查」彻底失效。
        sharedUpdateService?.let { return it }
        return synchronized(this) {
            sharedUpdateService ?: SkillUpdateService(
                sourceReader = createIndexService(appContext)::readSkillSource,
                lookup = GitHubSkillCommitLookup(
                    io.github.mangi.eta.agent.model.AgentHttpClient.client,
                ),
            ).also { sharedUpdateService = it }
        }
    }
}
