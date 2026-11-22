package io.github.mangi.eta.agent.model

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Covers validation scope through the public client entry, before the provider-specific encoders. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ModelRequestHookClientScopeTest {
    @Test
    fun manualCompactionIgnoresEnabledBlankHook() = assertManualCompaction(blankHook())

    @Test
    fun manualCompactionIgnoresEnabledOversizedHook() = assertManualCompaction(oversizedHook())

    @Test
    fun replyRewriteIgnoresEnabledBlankHook() = assertReplyRewrite(blankHook())

    @Test
    fun replyRewriteIgnoresEnabledOversizedHook() = assertReplyRewrite(oversizedHook())

    @Test
    fun chatRejectsEnabledBlankHookBeforeProviderInvocation() = assertChatRejected(blankHook())

    @Test
    fun chatRejectsEnabledOversizedHookBeforeProviderInvocation() = assertChatRejected(oversizedHook())

    private fun assertManualCompaction(hook: ModelRequestHook) {
        val config = config(hook)
        val history = history()
        val originalHistory = history.toList()
        val provider = RecordingProvider(ProviderRequestPurpose.COMPACTION, SUMMARY_REPLY)
        var toolCalls = 0

        val result = AgentModelClient.complete(
            config = config,
            prompt = "",
            history = history,
            compactOnly = true,
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                toolCalls++
                error("Compaction fixture must not execute tools")
            },
        )

        assertEquals(1, provider.requests.size)
        assertEquals(0, toolCalls)
        assertEquals("", result.content)
        assertTrue(result.transcript.isEmpty())
        assertNotNull(result.contextSnapshot)
        assertTrue(checkNotNull(result.contextSnapshot).messages.any {
            it.contextSummary && it.content.contains(SUMMARY_REPLY)
        })
        assertEquals(originalHistory, history)
        assertEquals(hook, config.requestHook)
    }

    private fun assertReplyRewrite(hook: ModelRequestHook) {
        val config = config(hook)
        val provider = RecordingProvider(ProviderRequestPurpose.REPLY_REWRITE, REWRITE_REPLY)
        var toolCalls = 0
        var snapshots = 0

        val result = AgentModelClient.complete(
            config = config,
            prompt = ORIGINAL_REPLY,
            history = history(),
            rewriteReply = true,
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                toolCalls++
                error("Reply rewrite fixture must not execute tools")
            },
            onContextSnapshot = { snapshots++ },
        )

        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests.single().messages.toString().contains(ORIGINAL_REPLY))
        assertEquals(REWRITE_REPLY, result.content)
        assertEquals(0, toolCalls)
        assertEquals(0, snapshots)
        assertNull(result.contextSnapshot)
        assertEquals(hook, config.requestHook)
    }

    private fun assertChatRejected(hook: ModelRequestHook) {
        val config = config(hook)
        val provider = RecordingProvider(ProviderRequestPurpose.CHAT, "ETA_HOOK_SCOPE_UNEXPECTED_CHAT_REPLY")
        var toolCalls = 0

        assertThrows(IllegalArgumentException::class.java) {
            AgentModelClient.complete(
                config = config,
                prompt = "ETA_HOOK_SCOPE_NORMAL_CHAT",
                provider = provider,
                toolExecutor = AgentModelClient.ToolExecutor {
                    toolCalls++
                    error("Invalid chat configuration must not execute tools")
                },
            )
        }

        assertEquals("Invalid chat hook reached the provider", 0, provider.requests.size)
        assertEquals(0, toolCalls)
        assertEquals(hook, config.requestHook)
    }

    private fun config(hook: ModelRequestHook) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "eta-hook-scope-fixture-key",
        model = "eta-hook-scope-fixture-model",
        systemPrompt = "ETA_HOOK_SCOPE_BASE_SYSTEM",
        contextWindow = 128_000,
        terminalTools = false,
        browserTools = false,
        deviceDirectTools = false,
        deviceSensitiveReadTools = false,
        deviceSensitiveActionTools = false,
        subagentTools = false,
        autoCompactionEnabled = false,
        requestHook = hook,
    )

    // Complete historical turns are long enough for the short fake summary to reduce the context.
    private fun history() = (1..6).flatMap { turn ->
        listOf(
            AgentModelClient.ConversationMessage("user", "ETA_HOOK_SCOPE_HISTORY_QUESTION_$turn"),
            AgentModelClient.ConversationMessage("assistant", "Historical fact $turn. ".repeat(500)),
        )
    }

    private fun blankHook() = ModelRequestHook(
        enabled = true,
        channel = ModelRequestHookChannel.BOTH,
        prompt = " \n\t ",
    )

    private fun oversizedHook() = ModelRequestHook(
        enabled = true,
        channel = ModelRequestHookChannel.BOTH,
        prompt = OVERSIZED_MARKER + "x".repeat(ModelRequestHook.MAX_PROMPT_CHARS),
    )

    private class RecordingProvider(
        private val expectedPurpose: ProviderRequestPurpose,
        private val reply: String,
    ) : AgentProviderClient {
        val requests = mutableListOf<ProviderRequest>()
        override val id = "request-hook-client-scope-fixture"
        override val capabilities = ProviderCapabilities(
            EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false,
        )

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += request
            assertEquals(expectedPurpose, request.purpose)
            if (expectedPurpose != ProviderRequestPurpose.CHAT) {
                val source = request.messages.toString()
                // Read the same accessors used by real encoders, so a late unscoped validation also fails this test.
                assertEquals(ModelRequestHook(), request.effectiveConfig.requestHook)
                val outgoing = request.effectiveMessages.toString()
                assertEquals(source, outgoing)
                assertEquals(source, request.messages.toString())
                assertFalse(outgoing.contains(OVERSIZED_MARKER))
                assertFalse(outgoing.contains("<model_request_hook"))
                assertEquals(0, request.effectiveTools.length())
            }
            return ProviderResponse(JSONObject()
                .put("role", "assistant")
                .put("content", reply)
                .put("finish_reason", "stop"))
        }
    }

    private companion object {
        const val OVERSIZED_MARKER = "ETA_HOOK_SCOPE_OVERSIZED_PROMPT_"
        const val ORIGINAL_REPLY = "ETA_HOOK_SCOPE_ORIGINAL_REPLY"
        const val SUMMARY_REPLY = "ETA_HOOK_SCOPE_HISTORY_SUMMARIZED"
        const val REWRITE_REPLY = "ETA_HOOK_SCOPE_REPLY_REWRITTEN"
    }
}
