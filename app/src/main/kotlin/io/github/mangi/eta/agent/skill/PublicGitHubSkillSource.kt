package io.github.mangi.eta.agent.skill

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class GitHubSkillRepository(
    val owner: String,
    val repository: String,
    val ref: String? = null,
    val path: String? = null,
) {
    val slug: String = "$owner/$repository"
}

internal data class GitHubSkillCandidate(
    val name: String,
    val path: String,
)

internal data class GitHubSkillInspection(
    val repository: String,
    val ref: String,
    val commitSha: String,
    val prefix: String?,
    val candidates: List<GitHubSkillCandidate>,
)

internal class GitHubSkillSourceException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 解析用户提供的 GitHub 仓库或 tree/blob URL，不接受镜像站、凭据与自定义端口。 */
internal object GitHubSkillRepositoryParser {
    fun parse(value: String): GitHubSkillRepository {
        val input = value.trim()
        if (input.isBlank()) invalid("GitHub 仓库不能为空")
        if (input.length > MAX_SOURCE_LENGTH) invalid("GitHub 仓库地址过长")
        return if (input.contains("://")) parseUrl(input) else parseSlug(input)
    }

    fun resolve(
        repository: String,
        explicitRef: String?,
        explicitPath: String?,
    ): GitHubSkillRepository {
        val parsed = parse(repository)
        val normalizedRef = explicitRef?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeRef)
        val normalizedPath = explicitPath?.let(::normalizeRelativePath)
        if (parsed.ref != null && normalizedRef != null && parsed.ref != normalizedRef) {
            invalid("URL 中的 ref 与 ref 参数不一致")
        }
        if (parsed.path != null && normalizedPath != null && parsed.path != normalizedPath) {
            invalid("URL 中的路径与 path 参数不一致")
        }
        return parsed.copy(
            ref = normalizedRef ?: parsed.ref,
            path = normalizedPath ?: parsed.path,
        )
    }

    fun normalizeRelativePath(value: String): String {
        val normalized = value.trim()
        if (normalized == ".") return "."
        if (
            normalized.isBlank() ||
            normalized.length > MAX_PATH_LENGTH ||
            normalized.startsWith('/') ||
            '\u0000' in normalized ||
            '\\' in normalized ||
            normalized.any { it.isISOControl() }
        ) {
            invalid("Skill 路径必须是仓库内的相对路径")
        }
        val segments = normalized.trimEnd('/').split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            invalid("Skill 路径包含无效片段")
        }
        return segments.joinToString("/")
    }

    fun normalizeRef(value: String): String {
        val normalized = value.trim()
        val segments = normalized.split('/')
        if (
            !REF_PATTERN.matches(normalized) ||
            normalized.startsWith('/') ||
            normalized.endsWith('/') ||
            "//" in normalized ||
            "@{" in normalized ||
            segments.any { it == "." || it == ".." || it.endsWith(".lock") }
        ) {
            invalid("GitHub ref 无效")
        }
        return normalized
    }

    private fun parseUrl(input: String): GitHubSkillRepository {
        val uri = runCatching { URI(input) }
            .getOrElse { invalid("GitHub URL 无效") }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            invalid("仅支持 HTTPS GitHub URL")
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host !in ALLOWED_GITHUB_HOSTS || uri.rawUserInfo != null || uri.port != -1) {
            invalid("仅支持 github.com 公共仓库 URL")
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            invalid("GitHub URL 不能包含查询参数或片段")
        }
        if ("//" in uri.path.orEmpty() || '\\' in uri.path.orEmpty()) {
            invalid("GitHub URL 路径无效")
        }
        val segments = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (segments.size < 2) invalid("GitHub URL 缺少 owner/repository")
        val owner = validateOwner(segments[0])
        val repository = validateRepository(segments[1].removeSuffix(".git"))
        if (segments.size == 2) return GitHubSkillRepository(owner, repository)
        if (segments[2] !in setOf("tree", "blob") || segments.size < 4) {
            invalid("仅支持 GitHub 仓库、tree 或 blob URL")
        }
        val ref = normalizeRef(segments[3])
        var path = segments.drop(4).joinToString("/").takeIf { it.isNotBlank() }
            ?.let(::normalizeRelativePath)
        if (segments[2] == "blob") {
            val blobName = path?.substringAfterLast('/').orEmpty()
            if (SkillFileNames.isSkillFileName(blobName)) {
                path = path?.substringBeforeLast('/', missingDelimiterValue = ".")
            }
        }
        return GitHubSkillRepository(owner, repository, ref, path)
    }

    private fun parseSlug(input: String): GitHubSkillRepository {
        val segments = input.removeSuffix(".git").split('/')
        if (segments.size != 2) invalid("仓库应为 owner/repository 或 github.com URL")
        return GitHubSkillRepository(
            owner = validateOwner(segments[0]),
            repository = validateRepository(segments[1]),
        )
    }

    private fun validateOwner(value: String): String {
        if (!OWNER_PATTERN.matches(value)) invalid("GitHub owner 无效")
        return value
    }

    private fun validateRepository(value: String): String {
        if (!REPOSITORY_PATTERN.matches(value) || value == "." || value == "..") {
            invalid("GitHub repository 无效")
        }
        return value
    }

    private fun invalid(message: String): Nothing =
        throw GitHubSkillSourceException("INVALID_GITHUB_SOURCE", message)

    private const val MAX_SOURCE_LENGTH = 2_048
    private const val MAX_PATH_LENGTH = 1_000
    private val ALLOWED_GITHUB_HOSTS = setOf("github.com", "www.github.com")
    private val OWNER_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")
    private val REF_PATTERN = Regex("[A-Za-z0-9._/-]{1,200}")
}

/**
 * 公共 GitHub Skill 来源。
 *
 * 候选通过 GitHub API tree 发现；安装归档固定到 commit SHA 后从 codeload 下载。客户端不带
 * Token、不跟随重定向，并对 JSON 与 ZIP 响应分别设置硬上限。
 */
internal class PublicGitHubSkillSource(
    cacheRoot: File,
    baseClient: OkHttpClient,
) : Closeable {
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val cacheDirectory = File(cacheRoot, "skill-github")
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean(false)

    fun listCurated(): GitHubSkillInspection = inspect(
        GitHubSkillRepository(
            owner = CURATED_OWNER,
            repository = CURATED_REPOSITORY,
            ref = CURATED_REF,
            path = CURATED_PATH,
        ),
    )

    fun inspect(repository: GitHubSkillRepository): GitHubSkillInspection {
        ensureOpen()
        val ref = repository.ref ?: resolveDefaultBranch(repository)
        val commit = resolveCommit(repository, ref)
        val tree = getJson(
            apiUrl(
                "repos",
                repository.owner,
                repository.repository,
                "git",
                "trees",
                commit.treeSha,
                query = "recursive" to "1",
            ),
            MAX_TREE_RESPONSE_BYTES,
        )
        if (tree.optBoolean("truncated", false)) {
            throw GitHubSkillSourceException(
                "REPOSITORY_TREE_TOO_LARGE",
                "仓库目录过大，GitHub 未返回完整结果；请提供明确的 Skill 路径",
            )
        }
        val prefix = repository.path?.takeUnless { it == "." }?.trimEnd('/')
        val candidates = buildList {
            val entries = tree.optJSONArray("tree") ?: return@buildList
            val roots = linkedMapOf<String, MutableList<String>>()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if (entry.optString("type") != "blob") continue
                val skillFilePath = entry.optString("path")
                val fileName = skillFilePath.substringAfterLast('/')
                if (!SkillFileNames.isSkillFileName(fileName)) continue
                val root = runCatching {
                    GitHubSkillRepositoryParser.normalizeRelativePath(
                        skillFilePath.substringBeforeLast('/', missingDelimiterValue = "."),
                    )
                }.getOrNull() ?: continue
                if (prefix != null && root != prefix && !root.startsWith("$prefix/")) continue
                roots.getOrPut(root) { mutableListOf() } += fileName
            }
            // 同一目录的大小写变体（SKILL.md / skill.md）属于同一个 Skill，不能算成两个候选。
            roots.forEach { (root, fileNames) ->
                SkillFileNames.preferredVariant(fileNames) ?: return@forEach
                add(
                    GitHubSkillCandidate(
                        name = root.substringAfterLast('/').takeUnless { root == "." }
                            ?: repository.repository,
                        path = root,
                    ),
                )
                if (size > MAX_CANDIDATES) {
                    throw GitHubSkillSourceException(
                        "TOO_MANY_SKILL_CANDIDATES",
                        "候选 Skill 超过 $MAX_CANDIDATES 个，请提供更具体的仓库路径（path）",
                    )
                }
            }
        }.sortedBy { it.path }
        return GitHubSkillInspection(
            repository = repository.slug,
            ref = ref,
            commitSha = commit.sha,
            prefix = prefix,
            candidates = candidates,
        )
    }

    fun downloadArchive(repository: GitHubSkillRepository): DownloadedGitHubArchive {
        ensureOpen()
        val ref = repository.ref ?: resolveDefaultBranch(repository)
        val resolvedCommitSha = resolveCommit(repository, ref).sha
        if (
            COMMIT_SHA_PATTERN.matches(ref) &&
            !resolvedCommitSha.equals(ref, ignoreCase = true)
        ) {
            throw GitHubSkillSourceException(
                "GITHUB_COMMIT_MISMATCH",
                "GitHub 返回的 commit 与已检查版本不一致",
            )
        }
        val commitSha = ref.takeIf(COMMIT_SHA_PATTERN::matches) ?: resolvedCommitSha
        ensureSafeCacheDirectory()
        cleanupStaleArchives()
        val target = File.createTempFile("eta-skill-github-", ".zip", cacheDirectory)
        return try {
            val url = codeloadUrl(repository, commitSha)
            downloadTo(url, target, MAX_ARCHIVE_BYTES)
            DownloadedGitHubArchive(
                file = target,
                repository = repository.slug,
                ref = ref,
                commitSha = commitSha,
            )
        } catch (throwable: Throwable) {
            target.delete()
            throw throwable
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
    }

    private fun resolveDefaultBranch(repository: GitHubSkillRepository): String {
        val metadata = getJson(
            apiUrl("repos", repository.owner, repository.repository),
            MAX_METADATA_RESPONSE_BYTES,
        )
        return metadata.optString("default_branch").trim()
            .takeIf { it.isNotBlank() }
            ?.let(GitHubSkillRepositoryParser::normalizeRef)
            ?: throw GitHubSkillSourceException(
                "INVALID_GITHUB_RESPONSE",
                "GitHub 未返回默认分支",
            )
    }

    private fun resolveCommit(repository: GitHubSkillRepository, ref: String): CommitPointer {
        val commit = getJson(
            apiUrl("repos", repository.owner, repository.repository, "commits", ref),
            MAX_METADATA_RESPONSE_BYTES,
        )
        val sha = commit.optString("sha").takeIf { COMMIT_SHA_PATTERN.matches(it) }
        val treeSha = commit.optJSONObject("commit")
            ?.optJSONObject("tree")
            ?.optString("sha")
            ?.takeIf { COMMIT_SHA_PATTERN.matches(it) }
        if (sha == null || treeSha == null) {
            throw GitHubSkillSourceException(
                "INVALID_GITHUB_RESPONSE",
                "GitHub 未返回有效 commit/tree SHA",
            )
        }
        return CommitPointer(sha = sha, treeSha = treeSha)
    }

    private fun getJson(url: HttpUrl, maxBytes: Long): JSONObject {
        val bytes = execute(url) { response ->
            val contentLength = response.body.contentLength()
            if (contentLength > maxBytes) responseTooLarge()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val output = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) responseTooLarge()
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throwable ->
                throw GitHubSkillSourceException(
                    "INVALID_GITHUB_RESPONSE",
                    "GitHub 返回了无效 JSON",
                    throwable,
                )
            }
    }

    private fun downloadTo(url: HttpUrl, target: File, maxBytes: Long) {
        execute(url, accept = "application/zip") { response ->
            val contentLength = response.body.contentLength()
            if (contentLength > maxBytes) archiveTooLarge()
            response.body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) archiveTooLarge()
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun <T> execute(
        url: HttpUrl,
        accept: String = "application/vnd.github+json",
        block: (okhttp3.Response) -> T,
    ): T {
        ensureOpen()
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .build()
        val call = client.newCall(request)
        activeCalls += call
        if (closed.get()) call.cancel()
        return try {
            call.execute().use { response ->
                if (response.isRedirect) {
                    throw GitHubSkillSourceException(
                        "GITHUB_REDIRECT_REJECTED",
                        "GitHub 下载发生重定向，已拒绝访问其他主机",
                    )
                }
                if (!response.isSuccessful) {
                    val rateLimited = response.code == 403 || response.code == 429
                    val code = when {
                        rateLimited -> "GITHUB_RATE_LIMITED"
                        response.code == 404 -> "GITHUB_NOT_FOUND"
                        else -> "GITHUB_REQUEST_FAILED"
                    }
                    val message = when (code) {
                        "GITHUB_RATE_LIMITED" -> rateLimitMessage(response)
                        "GITHUB_NOT_FOUND" -> notFoundMessage(url)
                        else -> "GitHub 请求失败（HTTP ${response.code}）；请稍后重试，" +
                            "或换用仓库根地址重新检查"
                    }
                    throw GitHubSkillSourceException(code, message)
                }
                block(response)
            }
        } catch (failure: GitHubSkillSourceException) {
            throw failure
        } catch (failure: IOException) {
            throw GitHubSkillSourceException(
                code = if (closed.get()) "GITHUB_REQUEST_CANCELLED" else "GITHUB_NETWORK_ERROR",
                message = if (closed.get()) "GitHub 请求已取消" else "无法访问 GitHub 公共仓库",
                cause = failure,
            )
        } finally {
            activeCalls -= call
        }
    }

    /**
     * 限流提示给出可执行动作：匿名 API 每小时 60 次，撞满后只能等待或换仓库根地址缩小请求量。
     * `Retry-After` / `X-RateLimit-Reset` 只在可信格式下才回显，避免把响应头原样透给用户。
     */
    private fun rateLimitMessage(response: okhttp3.Response): String {
        val retryAfterSeconds = response.header("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it in 1..MAX_RATE_LIMIT_WAIT_SECONDS }
        val resetEpochSeconds = response.header("X-RateLimit-Reset")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
        val waitSeconds = retryAfterSeconds
            ?: resetEpochSeconds?.let { reset ->
                (reset - System.currentTimeMillis() / 1_000).coerceAtLeast(0)
            }?.takeIf { it in 1..MAX_RATE_LIMIT_WAIT_SECONDS }
        val waitHint = waitSeconds?.let { "请在约 $it 秒后重试" }
            ?: "请稍后重试"
        return "GitHub 匿名 API 请求已达上限（每小时 60 次）；$waitHint，" +
            "或用更具体的仓库地址（owner/repo/tree/ref/path）减少请求"
    }

    /** 404 提示按 URL 类别区分：仓库、ref、路径缺失的处理方式完全不同。 */
    private fun notFoundMessage(url: HttpUrl): String {
        val segments = url.pathSegments
        if (url.host != API_HOST) {
            return "未找到该 GitHub 仓库归档；请确认仓库为公开仓库且 commit 仍然存在"
        }
        // 路径形如 repos/{owner}/{repo}[/commits/{ref} | /git/trees/{sha}]
        return when {
            segments.getOrNull(3) == "commits" ->
                "未找到该 ref（分支、标签或 commit）；请确认 ref 拼写，或省略 ref 使用默认分支"
            segments.getOrNull(3) == "git" ->
                "未找到该 commit 对应的目录树；请重新检查仓库，ref 可能已被强制推送覆盖"
            segments.size == 3 ->
                "未找到公开 GitHub 仓库；请确认 owner/repository 拼写，且仓库为 public"
            else -> "未找到公开 GitHub 仓库、ref 或路径；请确认三者都存在且仓库为 public"
        }
    }

    private fun apiUrl(
        vararg segments: String,
        query: Pair<String, String>? = null,
    ): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(API_HOST)
        .apply { segments.forEach(::addPathSegment) }
        .apply { query?.let { addQueryParameter(it.first, it.second) } }
        .build()

    private fun codeloadUrl(repository: GitHubSkillRepository, commitSha: String): HttpUrl =
        HttpUrl.Builder()
            .scheme("https")
            .host(CODELOAD_HOST)
            .addPathSegment(repository.owner)
            .addPathSegment(repository.repository)
            .addPathSegment("zip")
            .addPathSegment(commitSha)
            .build()

    private fun ensureOpen() {
        if (closed.get()) {
            throw GitHubSkillSourceException("GITHUB_REQUEST_CANCELLED", "GitHub 请求已取消")
        }
    }

    private fun ensureSafeCacheDirectory() {
        val path = cacheDirectory.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw GitHubSkillSourceException("CACHE_UNAVAILABLE", "Skill 下载缓存目录不安全")
            }
            return
        }
        if (!cacheDirectory.mkdirs() || Files.isSymbolicLink(path)) {
            throw GitHubSkillSourceException("CACHE_UNAVAILABLE", "无法创建 Skill 下载缓存")
        }
    }

    private fun cleanupStaleArchives(nowMillis: Long = System.currentTimeMillis()) {
        cacheDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith("eta-skill-github-") &&
                    file.name.endsWith(".zip") &&
                    nowMillis - file.lastModified() > STALE_ARCHIVE_AGE_MILLIS
            }
            .forEach { it.delete() }
    }

    private fun responseTooLarge(): Nothing =
        throw GitHubSkillSourceException("GITHUB_RESPONSE_TOO_LARGE", "GitHub 响应超过安全上限")

    private fun archiveTooLarge(): Nothing =
        throw GitHubSkillSourceException(
            "GITHUB_ARCHIVE_TOO_LARGE",
            "GitHub 仓库归档超过 ${MAX_ARCHIVE_BYTES / 1024 / 1024} MiB 上限",
        )

    internal data class DownloadedGitHubArchive(
        val file: File,
        val repository: String,
        val ref: String,
        val commitSha: String,
    ) : Closeable {
        override fun close() {
            file.delete()
        }
    }

    private data class CommitPointer(
        val sha: String,
        val treeSha: String,
    )

    private companion object {
        const val CURATED_OWNER = "openai"
        const val CURATED_REPOSITORY = "skills"
        const val CURATED_REF = "main"
        const val CURATED_PATH = "skills/.curated"
        const val API_HOST = "api.github.com"
        const val CODELOAD_HOST = "codeload.github.com"
        const val USER_AGENT = "Eta-Skill-Installer"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val MAX_CANDIDATES = 200
        const val MAX_METADATA_RESPONSE_BYTES = 1L * 1024 * 1024
        const val MAX_TREE_RESPONSE_BYTES = 8L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
        const val STALE_ARCHIVE_AGE_MILLIS = 24L * 60 * 60 * 1_000
        const val MAX_RATE_LIMIT_WAIT_SECONDS = 24L * 60 * 60
        val COMMIT_SHA_PATTERN = Regex("[a-fA-F0-9]{40,64}")
    }
}
