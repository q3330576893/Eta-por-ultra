package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import okhttp3.Headers
import org.junit.Assert.*
import org.junit.Test

class XingchiCompatibilityTest {
    private val source = OpenAiCompatibleProviderSetting(id = "xingchi-fixture", name = "星驰", baseUrl = "https://api.xingchiapi.com",
        apiKey = "fixture-key", systemPrompt = "保留提示词", customHeaders = listOf(CustomHeader("X-Fixture", "keep")))

    @Test fun templateIsExplicitAndScopedToExactHttpsApiKeyHost() {
        val draft = ProviderConfigDraft.from(source)
        assertTrue(XingchiCompatibility.available(source, draft))
        listOf("https://api.xingchiapi.com.example/v1", "https://xingchiapi.com/v1", "http://api.xingchiapi.com/v1",
            "https://api.xingchiapi.com:8443/v1", "https://someone@api.xingchiapi.com/v1").forEach { url ->
            assertFalse(url, XingchiCompatibility.available(source, draft.copy(baseUrl = url)))
            assertThrows(IllegalArgumentException::class.java) { XingchiCompatibility.apply(draft.copy(baseUrl = url)) }
        }
        assertFalse(XingchiCompatibility.available(source, draft.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT)))
        assertFalse(XingchiCompatibility.available(AnthropicProviderSetting(id = "anthropic", name = "other", baseUrl = "https://api.anthropic.com"), draft))
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, draft.endpointMode)
        assertTrue(draft.headers.none { it.header.name == "User-Agent" })
    }

    @Test fun applyingFillsProtocolAndHeadersWithoutChangingCredentialsOrOtherConfiguration() {
        val draft = ProviderConfigDraft.from(source)
        val result = XingchiCompatibility.apply(draft)
        assertEquals("https://api.xingchiapi.com/v1", result.baseUrl)
        assertEquals(OpenAiEndpointMode.RESPONSES, result.endpointMode)
        assertEquals(draft.apiKey, result.apiKey)
        assertEquals(draft.name, result.name)
        assertEquals(draft.systemPrompt, result.systemPrompt)
        assertEquals(draft.isEnabled, result.isEnabled)
        assertEquals(draft.authMode, result.authMode)
        assertEquals("keep", result.headers.first().header.value)
        assertEquals(XingchiCompatibility.USER_AGENT, result.headers.last().header.value)
        assertTrue(result.headers.last().header.value.contains("EtaUltra"))
        assertEquals(result, XingchiCompatibility.apply(result))
        val restored = result.toProvider(source)
        assertEquals(result, ProviderConfigDraft.from(restored).copy(headers = result.headers))
    }

    @Test fun customClientSelectionAndOtherHeadersRemainEditableAndPreserved() {
        val draft = ProviderConfigDraft.from(source).copy(baseUrl = "https://api.xingchiapi.com/custom/v1",
            headers = listOf(ProviderHeaderDraft(header = CustomHeader(" user-agent ", "user-selected-client/1")),
                ProviderHeaderDraft(header = CustomHeader("X-Custom", "preserve"))))
        val result = XingchiCompatibility.apply(draft)
        assertEquals(draft.baseUrl, result.baseUrl)
        assertEquals(draft.headers, result.headers)
    }

    @Test fun blankClientHeaderIsReplacedOnceAndRuntimeOverridesEtaDefaultWithoutDuplicatingIt() {
        val draft = ProviderConfigDraft.from(source).copy(headers = listOf(ProviderHeaderDraft(header = CustomHeader("user-agent", ""))))
        val result = XingchiCompatibility.apply(draft)
        val builder = Headers.Builder().set("Authorization", "Bearer fixture-key")
        ProviderRequestHeaders.mergeInto(builder, result.baseUrl, result.headers.map { it.header })
        val headers = builder.build()
        assertEquals(listOf(XingchiCompatibility.USER_AGENT), headers.values("User-Agent"))
        assertEquals("Bearer fixture-key", headers["Authorization"])
        assertEquals(1, result.headers.size)
    }
}
