package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.SamplingInjection
import io.github.mangi.eta.agent.prompt.SessionSamplingOverride
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSamplingOverrideTest {
    @Test
    fun json_roundTripPreservesSamplingAndInstruct() {
        val original = SessionSamplingOverride(
            temperature = 0.3,
            topP = 0.9,
            maxOutputTokens = 2048,
            instructTemplate = "结论先行",
            rolePresetName = "严谨分析师",
        )
        val decoded = SessionSamplingOverride.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(0.3, decoded.temperature!!, 0.0)
        assertEquals(0.9, decoded.topP!!, 0.0)
        assertEquals(2048, decoded.maxOutputTokens)
        assertEquals("结论先行", decoded.instructTemplate)
        assertEquals("严谨分析师", decoded.rolePresetName)
    }

    @Test
    fun json_nullFieldsStayNullAndEmptyOverrideDetected() {
        val decoded = SessionSamplingOverride.fromJson(JSONObject("""{
            "temperature": null, "top_p": null, "max_output_tokens": 0,
            "instruct_template": "", "role_preset_name": ""
        }"""))
        assertTrue(decoded.isEmpty)
        assertFalse(decoded.hasSampling)
    }

    @Test
    fun project_codexChannelSkipsAllSamplingFields() {
        val injection = SamplingInjection.project(
            SessionSamplingOverride(temperature = 0.5, topP = 0.8, maxOutputTokens = 100),
            EndpointKind.RESPONSES,
            ProviderTypes.OPENAI_COMPATIBLE,
            ProviderAuthModes.OPENAI_CHATGPT,
        )
        assertTrue(injection.injectable.isEmpty())
        assertEquals(3, injection.skipped.size)
    }

    @Test
    fun project_openAiKeepsAllSamplingFields() {
        val injection = SamplingInjection.project(
            SessionSamplingOverride(temperature = 0.5, topP = 0.8, maxOutputTokens = 100, instructTemplate = "x"),
            EndpointKind.CHAT_COMPLETIONS,
            ProviderTypes.OPENAI_COMPATIBLE,
            ProviderAuthModes.API_KEY,
        )
        assertEquals(3, injection.injectable.size)
        assertTrue(injection.skipped.isEmpty())
        assertEquals("x", injection.instructTemplate)
    }

    @Test
    fun project_nullOverrideIsNoop() {
        val injection = SamplingInjection.project(null, EndpointKind.RESPONSES, ProviderTypes.OPENAI_COMPATIBLE, ProviderAuthModes.API_KEY)
        assertTrue(injection.isEmpty)
    }
}
