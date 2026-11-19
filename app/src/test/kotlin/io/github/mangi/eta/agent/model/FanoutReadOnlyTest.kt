package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.prompt.SessionSamplingOverride
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [33])
class FanoutReadOnlyTest {
    @Test fun comparisonMakesOneToolFreeProviderCallAndKeepsSnapshot() {
        val controller = AgentRunController()
        var calls = 0
        val sampling = SessionSamplingOverride(temperature = 0.4)
        val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
            model = "fixture", systemPrompt = "system", terminalTools = true, subagentTools = true,
            hostedWebSearchEnabled = true, extraBodyJson = "{\"tools\":[]}", sessionSamplingOverride = sampling)
        val target = FanoutModelTarget("p:m", "m", "Model", model)
        val snapshot = JSONArray().put(JSONObject().put("role", "system").put("content", "shared system"))
            .put(JSONObject().put("role", "user").put("content", "shared question")).toString()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, false, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                calls++
                assertEquals(ProviderRequestPurpose.COMPARISON, request.purpose)
                assertEquals(0, request.effectiveTools.length())
                assertFalse(request.effectiveConfig.hostedWebSearchEnabled)
                assertFalse(request.effectiveConfig.terminalTools)
                assertFalse(request.effectiveConfig.subagentTools)
                assertTrue(request.effectiveConfig.extraBodyJson.isEmpty())
                assertEquals(sampling, request.effectiveConfig.sessionSamplingOverride)
                assertEquals(snapshot, request.messages.toString())
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "answer"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "answer"))
            }
        }
        val streamed = mutableListOf<String>()
        val result = AgentMultiModelFanout().runReadOnly(target,
            FanoutRequest("shared question", listOf(target), messagesJson = snapshot), controller,
            providerClient = provider, onText = { streamed += it })
        assertEquals(1, calls)
        assertTrue(result.succeeded)
        assertEquals("answer", result.content)
        assertEquals(listOf("answer"), streamed)
        assertTrue(controller.isCancelled)
    }

    @Test fun cancelledBranchKeepsPartialTextAndClosesController() {
        val controller = AgentRunController()
        val target = FanoutModelTarget("p:m", "m", "Model", AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""))
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, false, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "partial"))
                runController.cancel()
                runController.throwIfCancelled()
                error("cancel checkpoint")
            }
        }
        val result = AgentMultiModelFanout().runReadOnly(target, FanoutRequest("question", listOf(target)),
            controller, providerClient = provider)
        assertEquals(FanoutBranchStatus.Failed, result.status)
        assertEquals("partial", result.content)
        assertEquals("Cancelled", result.error)
        assertTrue(controller.isCancelled)
    }
}
