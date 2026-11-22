package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestHookTest {
    @Test
    fun defaultHookIsDisabledAndInactive() {
        val hook = ModelRequestHook()
        assertTrue(!hook.enabled)
        assertTrue(!hook.active)
        assertEquals(ModelRequestHookChannel.SYSTEM, hook.channel)
        assertEquals(ModelRequestHook.REVISION_LENGTH, hook.revision.length)
    }

    @Test
    fun revisionUsesTrimmedPromptAndChannelButNotEnabled() {
        val disabled = ModelRequestHook(
            enabled = false,
            channel = ModelRequestHookChannel.USER,
            prompt = "  研究任务  ",
        )
        val enabled = disabled.copy(enabled = true)
        assertEquals(disabled.revision, enabled.revision)
        assertTrue(enabled.active)
        assertNotEquals(
            disabled.revision,
            disabled.copy(channel = ModelRequestHookChannel.BOTH).revision,
        )
        assertEquals(
            disabled.revision,
            ModelRequestHook(prompt = "研究任务", channel = ModelRequestHookChannel.USER).revision,
        )
    }

    @Test
    fun researchTemplateFitsThePersistedPromptBudget() {
        assertTrue(ModelRequestHook.RESEARCH_PROMPT.isNotBlank())
        assertTrue(ModelRequestHook.RESEARCH_PROMPT.length <= ModelRequestHook.MAX_PROMPT_CHARS)
    }
}
