package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.breeno.BreenoTapProviderClient
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class ModelRequestHookProjectionTest {
    @Test
    fun disabledHookPreservesExistingRequestAndCostsNothing() {
        val source = JSONArray().put(message("user", "question"))
        val hook = ModelRequestHook(prompt = "saved but disabled")
        val request = request(source, hook)
        assertSame(source, request.effectiveMessages)
        assertEquals(0, ModelRequestHookProjection.estimatedTokens(hook))
        assertEquals("question", source.getJSONObject(0).getString("content"))
    }

    @Test
    fun systemHookIsAfterLeadingInstructionsWithoutMutatingCanonicalHistory() {
        val source = JSONArray().put(message("system", "base"))
            .put(message("developer", "developer"))
            .put(message("user", "question"))
        val before = source.toString()
        val request = request(source, hook(ModelRequestHookChannel.SYSTEM))
        val projected = request.effectiveMessages
        assertNotSame(source, projected)
        assertEquals(4, projected.length())
        assertEquals("system", projected.getJSONObject(2).getString("role"))
        assertTrue(projected.getJSONObject(2).getString("content").contains(MARKER))
        assertEquals("question", projected.getJSONObject(3).getString("content"))
        assertEquals(before, source.toString())
        assertSame(projected, request.effectiveMessages)
    }

    @Test
    fun userHookPreservesImagePartsAndSkipsObservationsAndSummaries() {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "question"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,AA==")))
        val user = JSONObject().put("role", "user").put("content", content).put("_eta_message_id", "original")
        val observation = message("user", "tool screenshot").put("_eta_observation", true)
        val summary = message("user", "summary").put("_eta_context_summary", true)
        val source = JSONArray().put(user).put(observation).put(summary)
        val before = source.toString()
        val projected = request(source, hook(ModelRequestHookChannel.USER)).effectiveMessages
        val parts = projected.getJSONObject(0).getJSONArray("content")
        assertEquals(3, parts.length())
        assertTrue(parts.getJSONObject(0).getString("text").contains(MARKER))
        assertEquals(content.getJSONObject(0).toString(), parts.getJSONObject(1).toString())
        assertEquals(content.getJSONObject(1).toString(), parts.getJSONObject(2).toString())
        assertEquals("original", projected.getJSONObject(0).getString("_eta_message_id"))
        assertEquals(observation.toString(), projected.getJSONObject(1).toString())
        assertEquals(summary.toString(), projected.getJSONObject(2).toString())
        assertEquals(before, source.toString())
    }

    @Test
    fun userAndBothHooksSkipTheRoleplayDepthZeroUserMessage() {
        val source = JSONArray().put(message("system", "base"))
            .put(message("user", "question").put("_eta_message_id", "real-user"))
        val sourceBefore = source.toString()
        val roleplay = roleplayDepthZero().projectMessages(source)
        val roleplayBefore = roleplay.toString()
        val depth = roleplay.getJSONObject(roleplay.length() - 1)
        assertTrue(depth.getBoolean(RoleplayRunContext.DEPTH_USER_MARKER))

        for (channel in listOf(ModelRequestHookChannel.USER, ModelRequestHookChannel.BOTH)) {
            val projected = request(roleplay, hook(channel)).effectiveMessages
            val target = (0 until projected.length()).map(projected::getJSONObject)
                .single { it.optString("_eta_message_id") == "real-user" }
            val text = target.getString("content")
            assertTrue(text.contains(MARKER))
            assertTrue(text.endsWith("\n\nquestion"))
            assertEquals(depth.toString(), projected.getJSONObject(projected.length() - 1).toString())
            assertEquals(if (channel == ModelRequestHookChannel.BOTH) 2 else 1,
                Regex(MARKER).findAll(projected.toString()).count())
            assertEquals(roleplayBefore, roleplay.toString())
            assertEquals(sourceBefore, source.toString())
        }
    }

    @Test
    fun breenoUsesTheHookBearingRealUserInsteadOfSyntheticUserText() {
        val source = JSONArray().put(message("user", "question"))
        val roleplay = roleplayDepthZero().projectMessages(source)
            .put(message("user", "observation").put("_eta_observation", true))
            .put(message("user", "summary").put("_eta_context_summary", true))
        val base = request(roleplay, hook(ModelRequestHookChannel.USER))
        val projected = base.copy(config = base.config.copy(authMode = "breeno_tap")).effectiveMessages

        assertTrue(BreenoTapProviderClient.lastUserText(projected).contains(MARKER))
        assertTrue(BreenoTapProviderClient.lastUserText(projected).endsWith("\n\nquestion"))
        assertEquals("question", BreenoTapProviderClient.lastUserText(roleplay))
    }

    @Test
    fun userQuotingHookTextDoesNotSuppressConfiguredInjection() {
        val source = JSONArray().put(message("user", "$MARKER is quoted in my input"))
        val projected = request(source, hook(ModelRequestHookChannel.USER)).effectiveMessages
        val text = projected.getJSONObject(0).getString("content")
        assertEquals(2, Regex(MARKER).findAll(text).count())
        assertTrue(text.endsWith("$MARKER is quoted in my input"))
    }

    @Test
    fun successiveToolRoundsKeepOneHookAndNativeToolHistoryUnchanged() {
        val source = JSONArray().put(message("system", "base")).put(message("user", "question"))
        val policy = hook(ModelRequestHookChannel.BOTH)
        val first = request(source, policy).effectiveMessages
        val signedAssistant = message("assistant", "")
            .put("_eta_native_signature", "signed-data")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1").put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))))
        val tool = message("tool", "result").put("tool_call_id", "call-1")
        source.put(signedAssistant).put(tool)
        val second = request(source, policy).effectiveMessages
        assertEquals(2, Regex(MARKER).findAll(first.toString()).count())
        assertEquals(2, Regex(MARKER).findAll(second.toString()).count())
        assertEquals(first.getJSONObject(1).toString(), second.getJSONObject(1).toString())
        assertEquals(first.getJSONObject(2).toString(), second.getJSONObject(2).toString())
        assertEquals(signedAssistant.toString(), second.getJSONObject(3).toString())
        assertEquals(tool.toString(), second.getJSONObject(4).toString())
        assertFalse(source.toString().contains(MARKER))
    }

    @Test
    fun compactionTranscriptionAndRewriteDoNotInheritChatHook() {
        val source = JSONArray().put(message("system", "special purpose"))
            .put(message("user", "input"))
        for (purpose in listOf(ProviderRequestPurpose.COMPACTION, ProviderRequestPurpose.TRANSCRIPT_REFINE,
            ProviderRequestPurpose.REPLY_REWRITE)) {
            val request = request(source, hook(ModelRequestHookChannel.BOTH), purpose)
            assertFalse(request.effectiveConfig.requestHook.active)
            assertSame(source, request.effectiveMessages)
            assertFalse(request.effectiveMessages.toString().contains(MARKER))
        }
        assertTrue(request(source, hook(ModelRequestHookChannel.USER), ProviderRequestPurpose.COMPARISON)
            .effectiveMessages.toString().contains(MARKER))
    }

    @Test
    fun missingRealUserNeverRewritesAnObservation() {
        val source = JSONArray().put(message("user", "observation").put("_eta_observation", true))
        val projected = request(source, hook(ModelRequestHookChannel.USER)).effectiveMessages
        assertEquals(source.toString(), projected.toString())
    }

    @Test
    fun invalidEnabledConfigurationFailsBeforeProjectionAndPreservesInput() {
        val source = JSONArray().put(message("user", "question"))
        val before = source.toString()
        for (prompt in listOf("  ", "x".repeat(ModelRequestHook.MAX_PROMPT_CHARS + 1))) {
            assertThrows(IllegalArgumentException::class.java) {
                request(source, ModelRequestHook(enabled = true, prompt = prompt)).effectiveMessages
            }
        }
        assertEquals(before, source.toString())
    }

    @Test
    fun bothChannelReservesBothCopiesAndRevisionChangesWithContentAndChannel() {
        val system = hook(ModelRequestHookChannel.SYSTEM)
        val user = hook(ModelRequestHookChannel.USER)
        val both = hook(ModelRequestHookChannel.BOTH)
        assertTrue(ModelRequestHookProjection.estimatedTokens(system) > 0)
        assertEquals(2 * ModelRequestHookProjection.estimatedTokens(system), ModelRequestHookProjection.estimatedTokens(both))
        assertFalse(system.revision == user.revision)
        assertFalse(system.revision == system.copy(prompt = "changed").revision)
        assertEquals(system.revision, system.copy(enabled = false).revision)
    }

    @Test
    fun knownWindowAndBorrowedVendorChannelAreValidatedBeforeSending() {
        val source = JSONArray().put(message("user", "question"))
        val base = request(source, hook(ModelRequestHookChannel.BOTH))
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(config = base.config.copy(contextWindow = 1)).effectiveMessages
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(config = base.config.copy(authMode = "breeno_tap")).effectiveMessages
        }
        val user = hook(ModelRequestHookChannel.USER)
        assertTrue(base.copy(config = base.config.copy(authMode = "breeno_tap", requestHook = user))
            .effectiveMessages.toString().contains(MARKER))
    }

    @Test
    fun transportRetryReusesProjectionAndUsageIncludesInjectedPrompt() {
        val source = JSONArray().put(message("system", "base")).put(message("user", "question"))
        val request = request(source, hook(ModelRequestHookChannel.BOTH))
        val payloads = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                payloads += request.effectiveMessages.toString()
                if (payloads.size == 1) throw SocketTimeoutException("fixture")
                onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, outputTokens = 1)))
                return ProviderResponse(message("assistant", "done").put("finish_reason", "stop"))
            }
        }
        AgentModelRetry(waitBeforeRetry = { _, _ -> }).complete(1, request, provider, AgentRunController(),
            onEvent = { events += it }, onProviderEvent = { _, _ -> }, discardAttemptReasoning = {})
        assertEquals(2, payloads.size)
        assertEquals(payloads[0], payloads[1])
        assertEquals(2, Regex(MARKER).findAll(payloads[0]).count())
        val usage = events.filterIsInstance<AgentEvent.UsageReceived>().single().usage
        val expected = AgentContextComposition.estimate(request.effectiveMessages, request.effectiveTools)
        assertEquals(expected.systemPromptTokens, usage.systemPromptTokens)
        assertEquals(expected.conversationTokens, usage.conversationTokens)
        assertFalse(source.toString().contains(MARKER))
    }

    private fun request(messages: JSONArray, hook: ModelRequestHook,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT) = ProviderRequest(
        AgentModelClient.ModelConfig(baseUrl = "http://localhost", apiKey = "", model = "fixture", systemPrompt = "",
            requestHook = hook), messages, JSONArray(), purpose = purpose,
    )

    private fun roleplayDepthZero() = RoleplayRunContext(
        characterId = "fixture",
        card = CharacterCardCodec.decodeJson("""{
            "name":"Fixture","extensions":{"depth_prompt":{"prompt":"roleplay context","depth":0,"role":"user"}}
        }"""),
        userName = "User",
        userDescription = "",
        contextWindow = 128_000,
    )

    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun hook(channel: ModelRequestHookChannel) = ModelRequestHook(enabled = true, channel = channel, prompt = MARKER)

    companion object {
        private const val MARKER = "ETA_HOOK_TEST_MARKER"
    }
}
