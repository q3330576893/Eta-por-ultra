package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentModelSelectionTest {
    @Test
    fun stableSelectionIdResolvesTheSelectedProviderAndModelHook() {
        val providers = duplicateProviders()

        val resolved = providers.enabledModelBySelectionId(
            subagentModelSelectionId("provider-b", "model-b"),
        )

        assertEquals("provider-b", resolved?.provider?.id)
        assertEquals("model-b", resolved?.model?.id)
        assertEquals("HOOK_B", resolved?.model?.requestHook?.prompt)
    }

    @Test
    fun ambiguousLegacyApiModelIdDoesNotGuessAProvider() {
        assertNull(duplicateProviders().resolveSubagentModelGrant("shared-model"))
    }

    @Test
    fun uniqueLegacyApiModelIdStillResolvesForBackwardCompatibility() {
        val source = duplicateProviders().first() as OpenAiCompatibleProviderSetting
        val provider = source.copy(
            models = listOf(source.models.first()),
        )

        val resolved = listOf(provider).resolveSubagentModelGrant("shared-model")

        assertEquals("model-a", resolved?.model?.id)
        assertEquals("HOOK_A", resolved?.model?.requestHook?.prompt)
    }

    private fun duplicateProviders(): List<ProviderSetting> = listOf(
        OpenAiCompatibleProviderSetting(
            id = "provider-a",
            name = "Provider A",
            baseUrl = "https://a.example/v1",
            models = listOf(
                Model(
                    id = "model-a",
                    modelId = "shared-model",
                    displayName = "Shared A",
                    requestHook = ModelRequestHook(true, ModelRequestHookChannel.SYSTEM, "HOOK_A"),
                ),
            ),
        ),
        OpenAiCompatibleProviderSetting(
            id = "provider-b",
            name = "Provider B",
            baseUrl = "https://b.example/v1",
            models = listOf(
                Model(
                    id = "model-b",
                    modelId = "shared-model",
                    displayName = "Shared B",
                    requestHook = ModelRequestHook(true, ModelRequestHookChannel.USER, "HOOK_B"),
                ),
            ),
        ),
    )
}
