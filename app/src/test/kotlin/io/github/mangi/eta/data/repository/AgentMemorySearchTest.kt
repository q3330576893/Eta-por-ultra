package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner

/**
 * 记忆检索多信号打分 + 事实有效期回归测试。
 * AgentMemoryStore 依赖 android.util.AtomicFile，需要 Robolectric 环境（纯 JVM 会 not mocked）。
 */
@RunWith(EtaRobolectricTestRunner::class)
class AgentMemorySearchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun multiKeywordQueryRanksRowsByMatchedTokenCount() {
        val store = store()
        store.replaceAll(
            listOf(
                "# 核心记忆",
                "- 喜欢 Kotlin",
                "- Kotlin Kotlin Kotlin Eta 开发",
                "## 项目",
                "- Eta Agent",
            ).joinToString("\n"),
        )

        // query 拆成 "kotlin" + "eta"：第 3 行命中两种且 Kotlin 词频最高，应排在仅命中的行之前。
        val result = store.read(query = "kotlin eta", maxChars = 4000)
        assertTrue(result.scoredHits.isNotEmpty())
        val top = result.scoredHits.first()
        assertEquals(3, top.lineNumber)
        assertTrue(top.matchedTokens.containsAll(listOf("kotlin", "eta")))
        assertEquals(setOf(2, 3, 5), result.scoredHits.map { it.lineNumber }.toSet())
    }

    @Test
    fun headingHitGetsExtraWeight() {
        val store = store()
        store.replaceAll(
            listOf(
                "# 核心记忆",
                "## Kotlin 偏好",
                "- 提到 Kotlin 的地方",
            ).joinToString("\n"),
        )

        // 两行都只命中一次 Kotlin，但标题行有额外加权，应排在正文行前面。
        val result = store.read(query = "kotlin", maxChars = 4000)
        assertEquals(2, result.scoredHits.first().lineNumber)
        assertTrue(result.scoredHits.first().isHeading)
    }

    @Test
    fun entityTokenIsPrioritizedOverCommonToken() {
        val store = store()
        store.replaceAll(
            listOf(
                "# 核心记忆",
                "- 常用 ZenProxy 工具",
                "- 常用 folder 文件夹",
            ).joinToString("\n"),
        )

        // ZenProxy 被判为实体（专名，首字母大写），folder 因在通用词表中不算实体；实体命中行得分更高。
        val result = store.read(query = "ZenProxy folder", maxChars = 4000)
        val top = result.scoredHits.first()
        assertEquals(2, top.lineNumber)
        assertTrue(top.matchedTokens.contains("zenproxy"))
    }

    @Test
    fun latestUpdateDateIsParsedAndExposed() {
        val store = store()
        store.replaceAll(
            listOf(
                "# 核心记忆",
                "## 设备",
                "- 2024-05-10 曾用 Android",
                "- 2026-11-18 现用 iPhone",
            ).joinToString("\n"),
        )

        val read = store.read(maxChars = 4000)
        assertEquals("2026-11-18", read.latestUpdateDate)
    }

    @Test
    fun staleHintFlagsOldConflictingFactInSameSection() {
        val store = store()
        store.replaceAll(
            listOf(
                "# 核心记忆",
                "## 设备",
                "- 2024-05-10 手机 Android 方案",
                "- 2026-11-18 手机 iPhone 方案",
            ).joinToString("\n"),
        )

        val read = store.read(query = "手机", maxChars = 4000)
        assertEquals(1, read.staleHints.size)
        val hint = read.staleHints.first()
        assertEquals("设备", hint.section)
        assertEquals(4, hint.newerLine)
        assertEquals(3, hint.olderLine)
        assertEquals("2026-11-18", hint.newerDate)
        assertEquals("2024-05-10", hint.olderDate)
        assertTrue(hint.message.contains("过期"))
    }

    @Test
    fun dateRegexAcceptsMonthOnlyAndFullDate() {
        val store = store()
        store.replaceAll(
            listOf(
                "# 核心记忆",
                "## 项目",
                "- 2026-11 计划",
                "- 2026-11-18 更新",
            ).joinToString("\n"),
        )

        val read = store.read(maxChars = 4000)
        assertEquals("2026-11-18", read.latestUpdateDate)
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
