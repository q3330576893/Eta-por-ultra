package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.data.db.ProviderWithModels
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.GeminiProviderSetting
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

    @Test fun finalPresetsMatchTheFourLiveValidatedEtaAttributedValues() {
        assertEquals(listOf(
            "OpenCode" to "opencode/1.19.6 (EtaUltra compatibility)",
            "WorkBuddy" to "WorkBuddy/1.0.0 (EtaUltra compatibility)",
            "Codex" to "codex_cli_rs/0.114.0 (EtaUltra compatibility)",
            "DeepSeek Harness" to "deepseek-harness/1.0.0 (EtaUltra compatibility)",
        ), XingchiCompatibility.ClientPreset.entries.map { it.displayName to it.userAgent })
    }

    @Test fun everyExplicitTransitionReplacesAllCaseAndWhitespaceVariantsAndIsIdempotent() {
        val keep = ProviderHeaderDraft(header = CustomHeader("X-Keep", "untouched"))
        for (previous in XingchiCompatibility.ClientPreset.entries) {
            for (next in XingchiCompatibility.ClientPreset.entries) {
                val draft = ProviderConfigDraft.from(source).copy(headers = listOf(
                    ProviderHeaderDraft(header = CustomHeader(" user-agent ", previous.userAgent)),
                    keep,
                    ProviderHeaderDraft(header = CustomHeader("USER-AGENT", "custom/2")),
                    ProviderHeaderDraft(header = CustomHeader("User-Agent", " ")),
                ))
                val result = XingchiCompatibility.select(draft, next)
                assertEquals(next, XingchiCompatibility.selectedPreset(result))
                assertEquals(listOf(keep), result.headers.filterNot { it.header.name == "User-Agent" })
                assertEquals(listOf(next.userAgent), result.headers.filter { it.header.name == "User-Agent" }.map { it.header.value })
                assertEquals(result, XingchiCompatibility.select(result, next))
                assertEquals(draft.copy(headers = result.headers), result)
                val builder = Headers.Builder().set("Authorization", "Bearer fixture-key")
                ProviderRequestHeaders.mergeInto(builder, result.baseUrl, result.headers.map { it.header })
                assertEquals(listOf(next.userAgent), builder.build().values("User-Agent"))
                assertEquals("Bearer fixture-key", builder.build()["Authorization"])
            }
        }
    }

    @Test fun recognitionUsesPersistedExactValuesIncludingLegacyOpenCodeAndCustomRemainsCustom() {
        for (preset in XingchiCompatibility.ClientPreset.entries) {
            val draft = ProviderConfigDraft.from(source).copy(headers = listOf(
                ProviderHeaderDraft(header = CustomHeader(" uSeR-aGeNt ", preset.userAgent)),
            ))
            assertEquals(preset, XingchiCompatibility.selectedPreset(draft))
        }
        for (value in listOf("my-client/9", "WorkBuddy (EtaUltra compatibility)",
            "codex_cli_rs (EtaUltra compatibility)", "deepseek-harness (EtaUltra compatibility)", "", " ")) {
            val draft = ProviderConfigDraft.from(source).copy(headers = listOf(
                ProviderHeaderDraft(header = CustomHeader("User-Agent", value)),
            ))
            assertNull(XingchiCompatibility.selectedPreset(draft))
            if (value.isNotBlank()) assertEquals(draft.headers, XingchiCompatibility.apply(draft).headers)
        }
        val duplicate = ProviderConfigDraft.from(source).copy(headers = listOf(
            ProviderHeaderDraft(header = CustomHeader("User-Agent", XingchiCompatibility.USER_AGENT)),
            ProviderHeaderDraft(header = CustomHeader("user-agent", "my-client/9")),
        ))
        assertNull(XingchiCompatibility.selectedPreset(duplicate))
        assertEquals(duplicate.headers, XingchiCompatibility.apply(duplicate).headers)
        assertNull(XingchiCompatibility.selectedPreset(ProviderConfigDraft.from(source)))
    }

    @Test fun choosingIdentityAloneNeverChangesUrlProtocolCredentialsOrOtherDraftFields() {
        val draft = ProviderConfigDraft.from(source).copy(baseUrl = "https://api.xingchiapi.com/custom/v1",
            hostedWebSearchEnabled = true, isEnabled = false)
        for (preset in XingchiCompatibility.ClientPreset.entries) {
            val selected = XingchiCompatibility.select(draft, preset)
            assertEquals(draft, selected.copy(headers = draft.headers))
            val saved = selected.toProvider(source) as OpenAiCompatibleProviderSetting
            assertEquals(source.copy(baseUrl = draft.baseUrl, isEnabled = false, hostedWebSearchEnabled = true,
                customHeaders = selected.headers.map { it.header }), saved)
        }
    }

    @Test fun allPresetsAndCustomValuesRoundTripThroughRealEntityHeaderJsonPerConnection() {
        val other = source.copy(id = "connection-b", customHeaders = listOf(CustomHeader("User-Agent", "private-client/7")))
        val otherEntity = other.toEntity()
        for (preset in XingchiCompatibility.ClientPreset.entries) {
            val selected = XingchiCompatibility.select(XingchiCompatibility.apply(ProviderConfigDraft.from(source)), preset)
            val saved = selected.toProvider(source)
            val entity = saved.toEntity()
            val restored = ProviderWithModels(entity, saved.toModelEntities()).toDomain()
            assertEquals(saved, restored)
            assertEquals(preset, XingchiCompatibility.selectedPreset(ProviderConfigDraft.from(restored)))
            assertEquals(preset.userAgent, restored.customHeaders.single { it.name == "User-Agent" }.value)
            assertEquals("fixture-key", restored.apiKey)
            assertEquals(OpenAiEndpointMode.RESPONSES, (restored as OpenAiCompatibleProviderSetting).endpointMode)
            assertEquals("connection-b", otherEntity.id)
            val restoredOther = ProviderWithModels(otherEntity, emptyList()).toDomain()
            assertEquals(other, restoredOther)
            assertNull(XingchiCompatibility.selectedPreset(ProviderConfigDraft.from(restoredOther)))
        }
    }

    @Test fun bothActionsRejectQueryFragmentLookalikeHostsPortsAndNonApiKeyAuthentication() {
        val draft = ProviderConfigDraft.from(source)
        val blocked = listOf(
            "https://api.xingchiapi.com.example/v1", "https://prefix.api.xingchiapi.com/v1",
            "https://xingchiapi.com/v1", "http://api.xingchiapi.com/v1",
            "https://api.xingchiapi.com:8443/v1", "https://user:pass@api.xingchiapi.com/v1",
            "https://api.xingchiapi.com/v1?x=1", "https://api.xingchiapi.com/v1#fragment",
        ).map { draft.copy(baseUrl = it) } + draft.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT)
        blocked.forEach { invalid ->
            assertFalse(XingchiCompatibility.available(source, invalid))
            assertThrows(IllegalArgumentException::class.java) { XingchiCompatibility.apply(invalid) }
            XingchiCompatibility.ClientPreset.entries.forEach { preset ->
                assertThrows(IllegalArgumentException::class.java) { XingchiCompatibility.select(invalid, preset) }
            }
        }
        assertFalse(XingchiCompatibility.available(GeminiProviderSetting(id = "gemini", name = "other"), draft))
    }

    @Test fun customProviderAndExplicitDefaultPortRemainSupportedWithoutRewritingExistingPaths() {
        val custom = CustomProviderSetting(id = "custom", name = "星驰", baseUrl = "https://API.XINGCHIAPI.COM:443/other/v1")
        val draft = ProviderConfigDraft.from(custom)
        assertTrue(XingchiCompatibility.available(custom, draft))
        val selected = XingchiCompatibility.select(draft, XingchiCompatibility.ClientPreset.CODEX)
        assertEquals(draft.baseUrl, selected.baseUrl)
        val saved = selected.toProvider(custom)
        val restored = ProviderWithModels(saved.toEntity(), saved.toModelEntities()).toDomain()
        assertEquals(saved, restored)
        assertEquals(XingchiCompatibility.ClientPreset.CODEX, XingchiCompatibility.selectedPreset(ProviderConfigDraft.from(restored)))
        assertEquals(draft.baseUrl, XingchiCompatibility.apply(selected).baseUrl)
    }

    @Test fun completingMultipleBlankHeadersAddsOnlyOneLegacyDefaultAndStaysIdempotent() {
        val draft = ProviderConfigDraft.from(source).copy(headers = listOf(
            ProviderHeaderDraft(header = CustomHeader("User-Agent", "")),
            ProviderHeaderDraft(header = CustomHeader(" user-AGENT ", "  ")),
        ))
        val completed = XingchiCompatibility.apply(draft)
        assertEquals(1, completed.headers.size)
        assertEquals(XingchiCompatibility.ClientPreset.OPENCODE, XingchiCompatibility.selectedPreset(completed))
        assertEquals(completed, XingchiCompatibility.apply(completed))
    }
}
