package io.github.mangi.eta.agent.skill

import java.io.File
import java.nio.file.Files
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublicGitHubSkillSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `inspection pins ref to commit tree and filters candidates by prefix`() {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(
                    request,
                    commitJson(),
                )
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("SKILL.md"))
                                .put(treeEntry("skills/alpha/SKILL.md"))
                                .put(treeEntry("skills/beta/SKILL.md"))
                                .put(treeEntry("other/ignored/SKILL.md")),
                        ),
                )
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val inspection = source.inspect(
            GitHubSkillRepository("example", "skills", ref = "main", path = "skills"),
        )

        assertEquals(listOf("skills/alpha", "skills/beta"), inspection.candidates.map { it.path })
        assertEquals(COMMIT_SHA, inspection.commitSha)
        assertTrue(requests[1].url.queryParameter("recursive") == "1")
        assertTrue(requests[1].url.encodedPath.endsWith("/git/trees/$TREE_SHA"))
        requests.forEach { request -> assertNull(request.header("Authorization")) }
        source.close()
    }

    @Test
    fun `download uses fixed commit codeload host and deletes temporary archive`() {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (request.url.host) {
                "api.github.com" -> jsonResponse(request, commitJson())
                "codeload.github.com" -> response(
                    request = request,
                    body = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
                        .toResponseBody("application/zip".toMediaType()),
                )
                else -> error("unexpected host ${request.url.host}")
            }
        }
        val cacheDirectory = File(temporaryFolder.root, "skill-github").also { it.mkdirs() }
        val staleArchive = File(cacheDirectory, "eta-skill-github-stale.zip").also {
            it.writeBytes(byteArrayOf(1))
            it.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1_000)
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)
        lateinit var archiveFile: File

        source.downloadArchive(GitHubSkillRepository("example", "skills", ref = "main"))
            .use { archive ->
                archiveFile = archive.file
                assertTrue(archiveFile.isFile)
                assertEquals(COMMIT_SHA, archive.commitSha)
            }

        assertFalse(archiveFile.exists())
        assertFalse(staleArchive.exists())
        val download = requests.single { it.url.host == "codeload.github.com" }
        assertEquals("/$OWNER/$REPOSITORY/zip/$COMMIT_SHA", download.url.encodedPath)
        source.close()
    }

    @Test
    fun `redirects are rejected instead of following an untrusted host`() {
        val client = fakeClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "https://example.com/archive.zip")
                .body(ByteArray(0).toResponseBody())
                .build()
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.inspect(GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"))
        }

        assertEquals("GITHUB_REDIRECT_REJECTED", failure.code)
        source.close()
    }

    @Test
    fun `download rejects a different commit for an already pinned sha`() {
        val source = PublicGitHubSkillSource(
            temporaryFolder.root,
            fakeClient { request -> jsonResponse(request, commitJson()) },
        )
        val pinned = "3".repeat(40)

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.downloadArchive(
                GitHubSkillRepository(OWNER, REPOSITORY, ref = pinned),
            )
        }

        assertEquals("GITHUB_COMMIT_MISMATCH", failure.code)
        source.close()
    }

    @Test
    fun `download cache rejects directory symlink`() {
        val external = temporaryFolder.newFolder("external-cache")
        val cacheDirectory = File(temporaryFolder.root, "skill-github")
        try {
            Files.createSymbolicLink(cacheDirectory.toPath(), external.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val source = PublicGitHubSkillSource(
            temporaryFolder.root,
            fakeClient { request -> jsonResponse(request, commitJson()) },
        )

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.downloadArchive(GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"))
        }

        assertEquals("CACHE_UNAVAILABLE", failure.code)
        assertTrue(external.listFiles().orEmpty().isEmpty())
        source.close()
    }

    @Test
    fun `case variant skill files collapse into a single candidate per directory`() {
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson())
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("skills/alpha/skill.md"))
                                .put(treeEntry("skills/alpha/SKILL.md"))
                                .put(treeEntry("skills/beta/Skill.MD"))
                                .put(treeEntry("skills/gamma/SKILL.md.bak"))
                                .put(treeEntry("skills/gamma/README.md")),
                        ),
                )
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val inspection = source.inspect(GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"))

        assertEquals(listOf("skills/alpha", "skills/beta"), inspection.candidates.map { it.path })
        assertEquals(listOf("alpha", "beta"), inspection.candidates.map { it.name })
        source.close()
    }

    @Test
    fun `nested multi skill repository returns every skill root sorted`() {
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson())
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("plugins/zeta/SKILL.md"))
                                .put(treeEntry("plugins/alpha/nested/deep/SKILL.md"))
                                .put(treeEntry("plugins/alpha/SKILL.md"))
                                .put(treeEntry("SKILL.md")),
                        ),
                )
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val inspection = source.inspect(GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"))

        assertEquals(
            listOf(".", "plugins/alpha", "plugins/alpha/nested/deep", "plugins/zeta"),
            inspection.candidates.map { it.path },
        )
        // 仓库根用仓库名作为候选名，其余用目录名。
        assertEquals(REPOSITORY, inspection.candidates.first().name)
        assertEquals("deep", inspection.candidates[2].name)
        source.close()
    }

    @Test
    fun `rate limit failure explains the anonymous quota and the wait`() {
        val client = fakeClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .header("Retry-After", "120")
                .body(ByteArray(0).toResponseBody())
                .build()
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.inspect(GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"))
        }

        assertEquals("GITHUB_RATE_LIMITED", failure.code)
        assertTrue(failure.message.orEmpty(), "每小时 60 次" in failure.message.orEmpty())
        assertTrue(failure.message.orEmpty(), "120 秒" in failure.message.orEmpty())
        source.close()
    }

    @Test
    fun `missing repository and missing ref produce different actionable messages`() {
        // 仓库级 404：先请求默认分支元数据，失败点落在 repos/{owner}/{repo}。
        val repositoryNotFound = fakeClient { request ->
            val repositoryMetadata = request.url.encodedPath == "/repos/$OWNER/$REPOSITORY"
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (repositoryMetadata) 404 else 200)
                .message(if (repositoryMetadata) "Not Found" else "OK")
                .body(
                    if (repositoryMetadata) {
                        ByteArray(0).toResponseBody()
                    } else {
                        JSONObject().put("default_branch", "main").toString()
                            .toResponseBody("application/json".toMediaType())
                    },
                )
                .build()
        }
        val repositoryFailure = assertThrows(GitHubSkillSourceException::class.java) {
            PublicGitHubSkillSource(temporaryFolder.root, repositoryNotFound)
                .inspect(GitHubSkillRepository(OWNER, REPOSITORY))
        }
        assertEquals("GITHUB_NOT_FOUND", repositoryFailure.code)
        assertTrue(
            repositoryFailure.message.orEmpty(),
            "owner/repository" in repositoryFailure.message.orEmpty(),
        )

        // ref 级 404：仓库元数据成功，commits/{ref} 失败。
        val refOnlyNotFound = fakeClient { request ->
            val missing = request.url.encodedPath.contains("/commits/")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (missing) 404 else 200)
                .message(if (missing) "Not Found" else "OK")
                .body(
                    if (missing) {
                        ByteArray(0).toResponseBody()
                    } else {
                        commitJson().toString().toResponseBody("application/json".toMediaType())
                    },
                )
                .build()
        }
        val refFailure = assertThrows(GitHubSkillSourceException::class.java) {
            PublicGitHubSkillSource(temporaryFolder.root, refOnlyNotFound)
                .inspect(GitHubSkillRepository(OWNER, REPOSITORY, ref = "release/2026"))
        }
        assertEquals("GITHUB_NOT_FOUND", refFailure.code)
        assertTrue(refFailure.message.orEmpty(), "ref" in refFailure.message.orEmpty())
        assertTrue(
            refFailure.message.orEmpty(),
            "默认分支" in refFailure.message.orEmpty(),
        )
    }

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    private fun commitJson(): JSONObject = JSONObject()
        .put("sha", COMMIT_SHA)
        .put(
            "commit",
            JSONObject().put("tree", JSONObject().put("sha", TREE_SHA)),
        )

    private fun treeEntry(path: String): JSONObject = JSONObject()
        .put("type", "blob")
        .put("path", path)

    private fun jsonResponse(request: Request, body: JSONObject): Response = response(
        request,
        body.toString().toResponseBody("application/json".toMediaType()),
    )

    private fun response(request: Request, body: okhttp3.ResponseBody): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

    private companion object {
        const val OWNER = "example"
        const val REPOSITORY = "skills"
        const val COMMIT_SHA = "1111111111111111111111111111111111111111"
        const val TREE_SHA = "2222222222222222222222222222222222222222"
    }
}
