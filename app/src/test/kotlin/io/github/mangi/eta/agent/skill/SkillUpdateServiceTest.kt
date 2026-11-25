package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新判定、限流与「无来源时跳过」三条核心语义。 */
class SkillUpdateServiceTest {

    private val installedSha = "1111111111111111111111111111111111111111"
    private val newerSha = "2222222222222222222222222222222222222222"

    @Test
    fun sameCommitIsNotReportedAsUpdate() {
        var now = 1_000L
        val service = SkillUpdateService(
            sourceReader = { skillId -> sourceFor(skillId) },
            lookup = { _, _, _ -> installedSha },
            clock = { now },
        )

        val statuses = service.checkForUpdates(listOf("alpha"))

        val status = statuses.getValue("alpha")
        assertFalse(status.hasUpdate)
        assertEquals(installedSha, status.latestCommitSha)
        assertNull(status.error)
    }

    @Test
    fun differentCommitIsReportedAsUpdate() {
        val service = SkillUpdateService(
            sourceReader = { skillId -> sourceFor(skillId) },
            lookup = { _, _, _ -> newerSha },
        )

        val status = service.checkForUpdates(listOf("alpha")).getValue("alpha")

        assertTrue(status.hasUpdate)
        assertEquals(newerSha, status.latestCommitSha)
        assertEquals(installedSha.take(8), status.shortInstalledSha)
        assertEquals(newerSha.take(8), status.shortLatestSha)
    }

    @Test
    fun skillWithoutSourceRecordIsSkippedInsteadOfFailing() {
        val queried = mutableListOf<String>()
        val service = SkillUpdateService(
            sourceReader = { skillId -> if (skillId == "alpha") sourceFor(skillId) else null },
            lookup = { _, _, _ -> queried += "hit"; newerSha },
        )

        val statuses = service.checkForUpdates(listOf("alpha", "manual-only"))

        assertEquals(setOf("alpha"), statuses.keys)
        assertEquals(1, queried.size)
    }

    @Test
    fun repeatedCheckInsideThrottleWindowDoesNotHitNetworkAgain() {
        var now = 5_000L
        var calls = 0
        val service = SkillUpdateService(
            sourceReader = { skillId -> sourceFor(skillId) },
            lookup = { _, _, _ -> calls += 1; newerSha },
            clock = { now },
            throttleMillis = 60_000L,
        )

        service.checkForUpdates(listOf("alpha"))
        assertEquals(1, calls)

        // 窗口内重复点击：不再发请求，但仍返回上一次的真实结论。
        now += 1_000L
        val cached = service.checkForUpdates(listOf("alpha")).getValue("alpha")
        assertEquals(1, calls)
        assertTrue(cached.hasUpdate)

        // 窗口外允许重新检查。
        now += 60_000L
        service.checkForUpdates(listOf("alpha"))
        assertEquals(2, calls)
    }

    @Test
    fun forceBypassesThrottleForExplicitRecheck() {
        var calls = 0
        val service = SkillUpdateService(
            sourceReader = { skillId -> sourceFor(skillId) },
            lookup = { _, _, _ -> calls += 1; newerSha },
            clock = { 1_000L },
        )

        service.checkForUpdates(listOf("alpha"))
        service.checkForUpdates(listOf("alpha"), force = true)

        assertEquals(2, calls)
    }

    @Test
    fun lookupFailureCarriesActionableReasonInsteadOfSilentSuccess() {
        val service = SkillUpdateService(
            sourceReader = { skillId -> sourceFor(skillId) },
            lookup = { _, _, _ ->
                throw GitHubSkillSourceException(
                    "GITHUB_RATE_LIMITED",
                    "GitHub 匿名 API 请求已达上限（每小时 60 次）；请稍后重试",
                )
            },
        )

        val status = service.checkForUpdates(listOf("alpha")).getValue("alpha")

        assertFalse("查不到不能显示成已是最新", status.hasUpdate)
        assertEquals("GitHub 匿名 API 请求已达上限（每小时 60 次）；请稍后重试", status.error)
    }

    @Test
    fun refScopedSourceIsLookedUpAtRepositoryRoot() {
        val paths = mutableListOf<String>()
        val service = SkillUpdateService(
            sourceReader = { skillId ->
                sourceFor(skillId).copy(commitScope = SKILL_COMMIT_SCOPE_REF, path = "skills/alpha")
            },
            lookup = { _, _, path -> paths += path; newerSha },
        )

        service.checkForUpdates(listOf("alpha"))

        assertEquals(listOf("."), paths)
    }

    @Test
    fun pathScopedSourceIsLookedUpAtRecordedPath() {
        // 只有显式声明 path 口径的来源才按 path 查询；默认值现在是 ref。
        val paths = mutableListOf<String>()
        val service = SkillUpdateService(
            sourceReader = { skillId ->
                // 显式声明 path 口径：只有这种来源才按 path 查询。
                sourceFor(skillId).copy(commitScope = SKILL_COMMIT_SCOPE_PATH)
            },
            lookup = { _, _, path -> paths += path; newerSha },
        )

        service.checkForUpdates(listOf("alpha"))

        assertEquals(listOf("skills/.curated/alpha"), paths)
    }

    @Test
    fun rememberInstalledResetsBadgeAndThrottleWindow() {
        var now = 10_000L
        var calls = 0
        val service = SkillUpdateService(
            sourceReader = { skillId -> sourceFor(skillId) },
            lookup = { _, _, _ -> calls += 1; newerSha },
            clock = { now },
        )
        assertTrue(service.checkForUpdates(listOf("alpha")).getValue("alpha").hasUpdate)

        service.rememberInstalled("alpha", sourceFor("alpha").copy(commitSha = newerSha), now = now)

        // 刚更新完：窗口内的检查不会打网络，也不会再把「有更新」翻出来。
        now += 1_000L
        assertFalse(service.checkForUpdates(listOf("alpha")).getValue("alpha").hasUpdate)
        assertEquals(1, calls)
    }

    @Test
    fun refScopedSourceComparesAgainstRefNotPath() {
        // 真实故障：安装时记录的是 ref 级 commit，检查时却按 path 去查。
        // 仓库里任何无关提交都会让 path 的 commit 落后于 ref，导致永久误报「有更新」。
        val refSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val stalePathSha = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val service = SkillUpdateService(
            sourceReader = { skillId ->
                SkillSourceMetadata(
                    repository = "openai/skills",
                    ref = "main",
                    path = "skills/.curated/$skillId",
                    commitSha = refSha,
                    installedAtMillis = 1_700_000_000_000L,
                    commitScope = SKILL_COMMIT_SCOPE_REF,
                )
            },
            // ref 口径应查仓库根（"."）；若实现按 path 查就会拿到更旧的 sha。
            lookup = { _, _, path -> if (path == "." || path == null) refSha else stalePathSha },
        )

        val status = service.checkForUpdates(listOf("alpha")).getValue("alpha")

        assertFalse("ref 口径的来源不应拿 path 的 sha 比较", status.hasUpdate)
        assertEquals(refSha, status.latestCommitSha)
    }

    @Test
    fun sourceMetadataDefaultsToRefScopeSoCallersCannotRegressSilently() {
        // 安装/更新链路只产出 ref 级 commit；默认值若是 PATH，任何忘记显式传参的调用点
        // 都会重现「仓库无关提交导致永久误报有更新」的缺陷。
        val metadata = SkillSourceMetadata(
            repository = "openai/skills",
            ref = "main",
            path = "skills/.curated/alpha",
            commitSha = installedSha,
            installedAtMillis = 1_700_000_000_000L,
        )
        assertEquals(SKILL_COMMIT_SCOPE_REF, metadata.commitScope)
    }

    private fun sourceFor(skillId: String): SkillSourceMetadata = SkillSourceMetadata(
        repository = "openai/skills",
        ref = "main",
        path = "skills/.curated/$skillId",
        commitSha = installedSha,
        installedAtMillis = 1_700_000_000_000L,
    )
}
