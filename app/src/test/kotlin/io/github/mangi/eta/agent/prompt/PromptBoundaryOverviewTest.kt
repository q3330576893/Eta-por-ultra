package io.github.mangi.eta.agent.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提示词与工具边界」概览的文案契约。
 *
 * 这里断言的是用户实际读到的整句文本，而不是"包含某个词"——概览的全部价值就在措辞上：
 * 用户要靠它判断这段设置会让模型和请求发生什么。文案改动必须是有意识的，不能让断言自动跟随。
 *
 * 阈值 6000 与 [PromptConflictAnalyzer] 的 LENGTH_BUDGET_CHARS 同源，此处按公开行为硬编码。
 */
class PromptBoundaryOverviewTest {

    private fun overview(
        customization: PromptCustomization,
        policy: AgentToolPolicy = AgentToolPolicy(),
    ): PromptBoundaryOverview = PromptBoundaryOverview.of(customization, policy)

    private fun promptLine(value: PromptBoundaryOverview): String = value.lines[0]

    private fun modeLine(value: PromptBoundaryOverview): String = value.lines[1]

    private fun toolLine(value: PromptBoundaryOverview): String = value.lines[2]

    private fun scopeLine(value: PromptBoundaryOverview): String = value.lines[3]

    private fun active(base: String = "你是测试助手"): PromptCustomization =
        PromptCustomization(enabled = true, baseSystemPrompt = base)

    // --- 1. 未启用 -------------------------------------------------------------

    @Test
    fun disabledPromptExplainsNothingIsInjected() {
        val value = overview(PromptCustomization(enabled = false, baseSystemPrompt = "你是猫娘"))
        assertEquals("提示词：未启用，当前不注入任何自定义内容。", promptLine(value))
        assertEquals("即使有内容，未启用也不注入", 4, value.promptChars)
        assertEquals(4, value.estimatedTokens)
    }

    @Test
    fun disabledPromptWithNoContentStillReportsDisabledRatherThanEmpty() {
        val value = overview(PromptCustomization(enabled = false))
        assertEquals("提示词：未启用，当前不注入任何自定义内容。", promptLine(value))
        assertEquals(0, value.promptChars)
        assertEquals(0, value.estimatedTokens)
    }

    // --- 2. 已启用但内容全空 ---------------------------------------------------

    @Test
    fun enabledButEmptyContentIsReportedAsEquivalentToOff() {
        val value = overview(PromptCustomization(enabled = true))
        assertEquals("提示词：已启用但没有内容，等同于关闭。", promptLine(value))
        assertEquals(0, value.promptChars)
        assertEquals(0, value.estimatedTokens)
    }

    @Test
    fun whitespaceOnlyContentIsReportedAsInjected() {
        // 记录当前行为：promptChars 用 String.length 累加，纯空白算"有内容"，
        // 而 PromptCustomization.active 用 isNotBlank 判定，二者不一致（见报告的源码问题 1）。
        val customization = PromptCustomization(enabled = true, baseSystemPrompt = "   ")
        assertFalse("前提：纯空白不算 active", customization.active)
        val value = overview(customization)
        assertEquals(3, value.promptChars)
        assertEquals(3, value.estimatedTokens)
        assertEquals("提示词：约 3 tokens，每轮请求都会带上这一段。", promptLine(value))
    }

    // --- 3/4. 正常内容与篇幅告警 ----------------------------------------------

    @Test
    fun normalContentReportsTokenEstimateWithoutLengthWarning() {
        val value = overview(active("回答时先给结论，再给依据。"))
        assertEquals("提示词：约 13 tokens，每轮请求都会带上这一段。", promptLine(value))
        assertFalse("13 字不该提示篇幅偏大", promptLine(value).contains("篇幅偏大"))
    }

    @Test
    fun lengthWarningAppearsOnlyAboveSixThousandChars() {
        val atLimit = overview(active("字".repeat(6_000)))
        assertEquals("提示词：约 6000 tokens，每轮请求都会带上这一段。", promptLine(atLimit))
        assertFalse("恰好 6000 字不告警", promptLine(atLimit).contains("篇幅偏大"))

        val overLimit = overview(active("字".repeat(6_001)))
        assertEquals(
            "提示词：约 6001 tokens，每轮请求都会带上这一段。篇幅偏大，会明显压缩可用于对话的上下文。",
            promptLine(overLimit),
        )
        assertTrue(promptLine(overLimit).contains("会明显压缩可用于对话的上下文"))
    }

    @Test
    fun lengthBudgetCountsEveryPromptFieldNotOnlyBase() {
        // 3000 字的 system + 3001 字的追加指令 = 6001，同样应告警。
        val value = overview(
            PromptCustomization(
                enabled = true,
                baseSystemPrompt = "字".repeat(3_000),
                appendInstructions = "字".repeat(3_001),
            ),
        )
        assertEquals(6_001, value.promptChars)
        assertTrue("跨字段累计也要告警", promptLine(value).contains("篇幅偏大"))
    }

    // --- 5/6. 生效方式 ---------------------------------------------------------

    @Test
    fun replaceModeKeepsCapabilityContract() {
        val value = overview(
            PromptCustomization(
                enabled = true,
                mode = PromptCustomizationMode.REPLACE,
                baseSystemPrompt = "你是另一个人格",
            ),
        )
        assertEquals(
            "生效方式：替换内置身份段；工具、设备、屏幕观察等能力契约仍会注入，否则工具调用会失效。",
            modeLine(value),
        )
        assertTrue("必须点明能力契约仍注入", modeLine(value).contains("能力契约仍会注入"))
    }

    @Test
    fun appendModeKeepsBuiltInIdentity() {
        val value = overview(
            PromptCustomization(
                enabled = true,
                mode = PromptCustomizationMode.APPEND,
                baseSystemPrompt = "补充说明",
            ),
        )
        assertEquals(
            "生效方式：追加在内置身份段之后；内置身份与能力契约都保留。",
            modeLine(value),
        )
    }

    @Test
    fun replaceModeLineDoesNotDependOnEnabledState() {
        // 记录当前行为：第二行只看 mode，不看 enabled，所以会和第一行的"未启用"并存。
        // 见报告的源码问题 2。
        val value = overview(
            PromptCustomization(enabled = false, mode = PromptCustomizationMode.REPLACE, baseSystemPrompt = "身份"),
        )
        assertEquals("提示词：未启用，当前不注入任何自定义内容。", promptLine(value))
        assertEquals(
            "生效方式：替换内置身份段；工具、设备、屏幕观察等能力契约仍会注入，否则工具调用会失效。",
            modeLine(value),
        )
    }

    // --- 7/8/9. 工具边界 ------------------------------------------------------

    @Test
    fun allowAllToolsReportsFullAccess() {
        val value = overview(active(), AgentToolPolicy(mode = ToolPolicyMode.ALLOW_ALL))
        assertEquals("工具边界：全部开放，模型能看到所有已启用的工具。", toolLine(value))
        assertEquals("ALLOW_ALL 用 0 表示不受限", 0, value.allowedToolCount)
    }

    @Test
    fun emptyAllowListReportsNoToolsUsable() {
        val value = overview(
            active(),
            AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST, allowedTools = emptySet()),
        )
        assertEquals("工具边界：白名单为空，请求里不会带任何工具，模型无法调用工具。", toolLine(value))
        assertEquals(0, value.allowedToolCount)
    }

    @Test
    fun allowAllAndEmptyAllowListBothCountZeroButReadDifferently() {
        val allowAll = overview(active(), AgentToolPolicy(mode = ToolPolicyMode.ALLOW_ALL))
        val emptyList = overview(active(), AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST))
        assertEquals("allowedToolCount 都是 0", allowAll.allowedToolCount, emptyList.allowedToolCount)
        assertNotEquals("文案必须区分两种 0", toolLine(allowAll), toolLine(emptyList))
    }

    @Test
    fun allowListReportsExactToolCount() {
        val value = overview(
            active(),
            AgentToolPolicy(
                mode = ToolPolicyMode.ALLOW_LIST,
                allowedTools = setOf("read_file", "write_file", "shell"),
            ),
        )
        assertEquals("工具边界：只开放 3 个工具；模型看不到其余工具，提示词里提到它们会造成空转。", toolLine(value))
        assertEquals(3, value.allowedToolCount)
    }

    @Test
    fun allowListCountsDistinctEntriesOnly() {
        val policy = AgentToolPolicy(
            mode = ToolPolicyMode.ALLOW_LIST,
            allowedTools = setOf("read_file", "read_file", "shell"),
        )
        val value = overview(active(), policy)
        assertEquals(2, value.allowedToolCount)
        assertEquals("工具边界：只开放 2 个工具；模型看不到其余工具，提示词里提到它们会造成空转。", toolLine(value))
    }

    @Test
    fun allowAllIgnoresLeftoverWhitelistEntries() {
        // withMode 只改 mode 不清空 allowedTools，残留白名单不应让说明变成"只开放 N 个"。
        val policy = AgentToolPolicy(
            mode = ToolPolicyMode.ALLOW_ALL,
            allowedTools = setOf("read_file", "write_file"),
        )
        assertTrue("ALLOW_ALL 下未列入白名单的工具也允许", policy.allows("shell"))
        val value = overview(active(), policy)
        assertEquals("工具边界：全部开放，模型能看到所有已启用的工具。", toolLine(value))
        assertEquals(0, value.allowedToolCount)
    }

    // --- 10. 每行都非空 -------------------------------------------------------

    @Test
    fun everyScenarioProducesFourNonBlankLines() {
        val scenarios = listOf(
            Triple("未启用", PromptCustomization(), AgentToolPolicy()),
            Triple("启用但空", PromptCustomization(enabled = true), AgentToolPolicy()),
            Triple("正常追加", active(), AgentToolPolicy()),
            Triple(
                "替换模式",
                PromptCustomization(
                    enabled = true,
                    mode = PromptCustomizationMode.REPLACE,
                    baseSystemPrompt = "身份",
                ),
                AgentToolPolicy(),
            ),
            Triple("超长", active("字".repeat(6_001)), AgentToolPolicy()),
            Triple("全部开放", active(), AgentToolPolicy(mode = ToolPolicyMode.ALLOW_ALL)),
            Triple(
                "空白名单",
                active(),
                AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST, allowedTools = emptySet()),
            ),
            Triple(
                "有白名单",
                active(),
                AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST, allowedTools = setOf("a", "b")),
            ),
        )
        scenarios.forEach { (name, customization, policy) ->
            val value = PromptBoundaryOverview.of(customization, policy)
            assertEquals("$name：固定四行", 4, value.lines.size)
            value.lines.forEachIndexed { index, line ->
                assertTrue("$name 第 ${index + 1} 行不应为空白：[$line]", line.isNotBlank())
            }
        }
    }

    @Test
    fun scopeLineStatesGlobalScopeAndRetiredModelEntry() {
        val value = overview(active())
        assertEquals(
            "作用范围：全局。所有模型共用这一份设置，模型编辑页不再有单独的提示词入口。",
            scopeLine(value),
        )
        // 每种组合都一样：作用范围不随提示词或工具设置变化。
        val other = overview(PromptCustomization(), AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST))
        assertEquals(scopeLine(value), scopeLine(other))
    }

    // --- 11. token 与字符数 1:1 ----------------------------------------------

    @Test
    fun estimatedTokensTrackCharacterCountOneToOne() {
        listOf(0, 1, 12, 599, 6_000, 6_001, 12_345).forEach { chars ->
            val value = overview(PromptCustomization(enabled = true, baseSystemPrompt = "字".repeat(chars)))
            assertEquals("$chars 字应等于 $chars tokens", chars, value.estimatedTokens)
            assertEquals(chars, value.promptChars)
        }
    }

    @Test
    fun promptCharsSumsEveryTextField() {
        val value = overview(
            PromptCustomization(
                enabled = true,
                baseSystemPrompt = "a".repeat(10),
                personaName = "b".repeat(5),
                personaStyle = "c".repeat(3),
                appendInstructions = "d".repeat(7),
            ),
        )
        assertEquals(25, value.promptChars)
        assertEquals(25, value.estimatedTokens)
        assertEquals("提示词：约 25 tokens，每轮请求都会带上这一段。", promptLine(value))
    }

    @Test
    fun estimatedTokensCountCodePointsNotUtf8Bytes() {
        // 中文按"字"估，不按 UTF-8 字节：4 个汉字是 4 tokens，不是 12。
        val value = overview(active("提示词边界"))
        assertEquals(5, value.promptChars)
        assertEquals(5, value.estimatedTokens)
        assertEquals("提示词：约 5 tokens，每轮请求都会带上这一段。", promptLine(value))
    }

    // --- 12. allowedToolCount -------------------------------------------------

    @Test
    fun allowedToolCountIsZeroForAllowAllAndActualSizeForAllowList() {
        fun count(policy: AgentToolPolicy): Int = overview(active(), policy).allowedToolCount

        assertEquals("ALLOW_ALL 无白名单", 0, count(AgentToolPolicy(mode = ToolPolicyMode.ALLOW_ALL)))
        assertEquals(
            "ALLOW_ALL 有残留白名单",
            0,
            count(AgentToolPolicy(mode = ToolPolicyMode.ALLOW_ALL, allowedTools = setOf("x"))),
        )
        assertEquals("ALLOW_LIST 空", 0, count(AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST)))
        assertEquals(
            "ALLOW_LIST 一项",
            1,
            count(AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST, allowedTools = setOf("x"))),
        )
        assertEquals(
            "ALLOW_LIST 五项",
            5,
            count(
                AgentToolPolicy(
                    mode = ToolPolicyMode.ALLOW_LIST,
                    allowedTools = setOf("a", "b", "c", "d", "e"),
                ),
            ),
        )
    }

    // --- 组合：提示词与工具互不影响 ------------------------------------------

    @Test
    fun promptAndToolSectionsAreIndependent() {
        val longDisabled = overview(
            PromptCustomization(enabled = false, baseSystemPrompt = "字".repeat(7_000)),
            AgentToolPolicy(mode = ToolPolicyMode.ALLOW_LIST, allowedTools = setOf("shell", "read_file")),
        )
        assertEquals("提示词：未启用，当前不注入任何自定义内容。", promptLine(longDisabled))
        assertEquals("工具边界：只开放 2 个工具；模型看不到其余工具，提示词里提到它们会造成空转。", toolLine(longDisabled))
        assertEquals(2, longDisabled.allowedToolCount)
        assertEquals(7_000, longDisabled.promptChars)
        assertFalse("未启用就不该出现篇幅告警", longDisabled.lines.any { it.contains("篇幅偏大") })
    }
}
