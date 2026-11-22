package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.prompt.SessionSamplingOverride
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeRequestHookTest {
    @Test
    fun reviewClearsChatHookAndSamplingWithoutMutatingTheOriginatingRequest() {
        val original = modelConfig("Ordinary chat supplement").copy(
            sessionSamplingOverride = SessionSamplingOverride(temperature = 0.7),
            terminalTools = true, browserTools = true, deviceDirectTools = true,
            deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
            hostedWebSearchEnabled = true,
        )
        val review = AgentExperienceReview.configForReview(original)
        assertEquals(ModelRequestHook(), review.requestHook)
        assertNull(review.sessionSamplingOverride)
        assertFalse(review.terminalTools)
        assertFalse(review.browserTools)
        assertFalse(review.deviceDirectTools)
        assertFalse(review.deviceSensitiveReadTools)
        assertFalse(review.deviceSensitiveActionTools)
        assertFalse(review.hostedWebSearchEnabled)
        assertEquals(ReasoningEffort.OFF, review.reasoningEffort)
        assertEquals(original.providerId, review.providerId)
        assertEquals(original.model, review.model)
        assertEquals("Ordinary chat supplement", original.requestHook.prompt)
        assertEquals(0.7, requireNotNull(original.sessionSamplingOverride?.temperature), 0.0)
    }

    @Test
    fun reviewClearsHookWhileRetainingMandatoryModelReasoning() {
        val original = modelConfig("Ordinary chat supplement").copy(
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.HIGH), mandatory = true,
            ),
            thinkingEnabled = true, reasoningEffort = ReasoningEffort.HIGH,
        )
        val review = AgentExperienceReview.configForReview(original)
        assertEquals(ModelRequestHook(), review.requestHook)
        assertEquals(original.effectiveReasoningEffort, review.effectiveReasoningEffort)
    }

    @Test
    fun continuationAndPermissionIntersectionPreserveTheOriginalHookSnapshot() {
        val request = request(modelConfig("Run snapshot supplement"))
        val continued = AgentContinuationBuilder.build(request,
            AgentModelClient.ModelResponse.Text("First result"), "Continue", "next-run", 1,
        )
        assertEquals(request.config.requestHook, continued.config.requestHook)
        assertEquals(request.config.modelEntryId, continued.config.modelEntryId)
        val constrained = AgentRuntimePolicy.constrain(continued.config, AgentRuntimePolicy.Permissions(
            terminalTools = false, browserTools = false, thinking = false,
        ))
        assertEquals(request.config.requestHook, constrained.requestHook)
        assertEquals("Continue", continued.prompt)
    }

    @Test
    fun voiceAndOauthResolutionTakeTheRuntimeModelsHookInsteadOfTheEntryHook() {
        val entry = modelConfig("Entry supplement")
        val local = entry.copy(requestHook = ModelRequestHook(true, ModelRequestHookChannel.SYSTEM, "Current local supplement"))
        val voice = request(entry).copy(handoff = AgentRuntimeWire.EntryHandoff(
            "voice-entry", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "",
        ))
        assertEquals(local.requestHook,
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(voice, local).config.requestHook)
        val oauthEntry = entry.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT)
        val oauthLocal = local.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT)
        assertEquals(oauthLocal.requestHook,
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request(oauthEntry), oauthLocal).config.requestHook)
        assertEquals(entry.requestHook,
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request(entry), local).config.requestHook)
    }

    private fun request(config: AgentModelClient.ModelConfig): AgentRuntimeWire.RunRequest = AgentRuntimeWire.RunRequest(
        runId = "hook-run", prompt = "Question", config = config, images = emptyList(),
    )

    private fun modelConfig(prompt: String): AgentModelClient.ModelConfig = AgentModelClient.ModelConfig(
        providerId = "hook-provider", baseUrl = "https://example.invalid/v1", apiKey = "fixture-key",
        model = "fixture-model", modelEntryId = "fixture-local-entry", systemPrompt = "Provider instructions", reasoningEffort = ReasoningEffort.OFF,
        requestHook = ModelRequestHook(true, ModelRequestHookChannel.USER, prompt),
    )
}
