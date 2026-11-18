package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.SessionSamplingOverride
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSamplingInjectionRequestTest {
    @Test
    fun chatCompletions_injectsTemperatureAndTopP() {
        val config = baseConfig().copy(
            sessionSamplingOverride = SessionSamplingOverride(temperature = 0.25, topP = 0.75),
        )
        val request = OpenAiChatCompletionsProvider.buildRequestJson(config, JSONArray(), JSONArray())
        assertEquals(0.25, request.getDouble("temperature"), 0.0)
        assertEquals(0.75, request.getDouble("top_p"), 0.0)
    }

    @Test
    fun chatCompletions_maxOutputTokensMapsToMaxCompletionTokens() {
        val config = baseConfig().copy(
            sessionSamplingOverride = SessionSamplingOverride(maxOutputTokens = 1024),
        )
        val request = OpenAiChatCompletionsProvider.buildRequestJson(config, JSONArray(), JSONArray())
        assertEquals(1024, request.getInt("max_completion_tokens"))
        assertFalse(request.has("max_output_tokens"))
    }

    @Test
    fun responses_codexChannelIgnoresSamplingWithoutThrowing() {
        val config = baseConfig().copy(
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
            sessionSamplingOverride = SessionSamplingOverride(temperature = 0.5, topP = 0.9, maxOutputTokens = 100),
        )
        // Codex 拒绝 temperature 等字段；能力表应提前跳过，不触发 CodexResponsesRequest 的 require。
        val request = OpenAiResponsesProvider.buildRequestJson(config, JSONArray(), JSONArray())
        assertFalse(request.has("temperature"))
        assertFalse(request.has("top_p"))
        assertFalse(request.has("max_output_tokens"))
    }

    @Test
    fun responses_apiKeyChannelInjectsSampling() {
        val config = baseConfig().copy(
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
            sessionSamplingOverride = SessionSamplingOverride(temperature = 0.4, topP = 0.6),
        )
        val request = OpenAiResponsesProvider.buildRequestJson(config, JSONArray(), JSONArray())
        assertEquals(0.4, request.getDouble("temperature"), 0.0)
        assertEquals(0.6, request.getDouble("top_p"), 0.0)
    }

    @Test
    fun gemini_injectsSamplingIntoGenerationConfig() {
        val config = baseConfig().copy(
            sessionSamplingOverride = SessionSamplingOverride(temperature = 0.2, topP = 0.95, maxOutputTokens = 512),
        )
        val request = GeminiInteractionsRequest.build(config, JSONArray(), JSONArray())
        val generation = request.getJSONObject("generation_config")
        assertEquals(0.2, generation.getDouble("temperature"), 0.0)
        assertEquals(0.95, generation.getDouble("top_p"), 0.0)
        assertEquals(512, generation.getInt("max_output_tokens"))
        // 采样字段不能漏到顶层（protectedFields 之外的键会污染请求）。
        assertFalse(request.has("temperature"))
    }

    private fun baseConfig() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "k",
        model = "fixture",
        systemPrompt = "",
        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
    )
}
