package io.github.mangi.eta.agent.model

import org.junit.Assert.assertTrue
import org.junit.Test

/** 摘要提示必须显式保留事实时间，防止「曾用 Android」与「现用 iPhone」被压成无时间陈述。 */
class AgentContextSummarizerPromptTest {
    @Test
    fun summaryPromptKeepsFactDatesAndDistinguishesPastFromPresent() {
        val prompt = summarySystemPrompt(roleplay = false)

        assertTrue(prompt.contains("时间"))
        assertTrue(prompt.contains("曾用 Android"))
        assertTrue(prompt.contains("现用 iPhone"))
        assertTrue(prompt.contains("日期"))
        assertTrue(prompt.contains("以较新者为准"))
    }

    @Test
    fun roleplayPromptKeepsTimeRuleAlongsideStoryRules() {
        val prompt = summarySystemPrompt(roleplay = true)

        assertTrue(prompt.contains("剧情"))
        assertTrue(prompt.contains("不能把剧情动作写成实际工具执行结果"))
        assertTrue(prompt.contains("曾用 Android"))
    }
}
