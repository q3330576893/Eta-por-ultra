package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRuntimeRequestConfigResolver
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimeConfigRequestHookTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking {
            SettingsDataStore.setSelection(providerId = null, modelId = null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun selectionConversationLookupAndDatabaseReopenUseTheSelectedModelsOwnHook() = runBlocking {
        val provider = provider()
        ProviderRepository.addProvider(provider)
        val first = provider.models[0]
        val second = provider.models[1]
        RuntimeConfigRepository.setSelectedModelId(first.id)
        assertEquals(first.requestHook, RuntimeConfigRepository.currentRuntimeConfig()?.requestHook)
        assertEquals(second.requestHook, RuntimeConfigRepository.runtimeConfigFor(second.id)?.requestHook)

        EtaDatabase.closeForTests()
        ProviderRepository.init(context)
        assertEquals(second.requestHook, RuntimeConfigRepository.runtimeConfigFor(second.id)?.requestHook)
        val updatedHook = second.requestHook.copy(prompt = "Updated model supplement")
        ProviderRepository.replaceModels(provider.id, listOf(first, second.copy(requestHook = updatedHook)))
        RuntimeConfigRepository.setSelectedModelId(second.id)
        assertEquals(updatedHook, RuntimeConfigRepository.currentRuntimeConfig()?.requestHook)
        assertEquals(updatedHook, RuntimeConfigRepository.runtimeConfigFor("removed-model")?.requestHook)
        assertEquals(first.requestHook, RuntimeConfigRepository.runtimeConfigFor(first.id)?.requestHook)
    }

    @Test
    fun explicitSubagentUsesItsOwnHookWhileInheritedAndUnavailableModelsKeepTheParentSnapshot() = runBlocking {
        val provider = provider()
        ProviderRepository.addProvider(provider)
        val primary = RuntimeConfigRepository.buildRuntimeConfig(provider, provider.models[0]).copy(
            requestHook = ModelRequestHook(true, ModelRequestHookChannel.SYSTEM, "This run's parent snapshot"),
            terminalTools = true, browserTools = false, subagentTools = true,
        )
        val inherited = RuntimeConfigRepository.subagentRuntimeConfig(primary)
        assertEquals(primary.requestHook, inherited.requestHook)
        assertFalse(inherited.subagentTools)

        val explicit = RuntimeConfigRepository.subagentRuntimeConfig(primary, overrideModelId = provider.models[1].id)
        assertEquals(provider.models[1].modelId, explicit.model)
        assertEquals(provider.models[1].requestHook, explicit.requestHook)
        assertTrue(explicit.terminalTools)
        assertFalse(explicit.browserTools)
        assertFalse(explicit.subagentTools)

        ProviderRepository.replaceModels(provider.id, listOf(provider.models[0], provider.models[1].copy(isEnabled = false)))
        assertEquals(primary.requestHook,
            RuntimeConfigRepository.subagentRuntimeConfig(primary, overrideModelId = provider.models[1].id).requestHook)
        assertEquals(primary.requestHook,
            RuntimeConfigRepository.subagentRuntimeConfig(primary, overrideModelId = "missing-model").requestHook)
    }

    @Test
    fun oauthRuntimeLookupRebuildsTheHookFromTheBoundLocalModel() = runBlocking {
        val hook = ModelRequestHook(true, ModelRequestHookChannel.USER, "Account model supplement")
        val provider = provider().copy(
            id = "hook-account", authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "hook-account-model", modelId = "fixture-account-model",
                displayName = "Account model", requestHook = hook)),
        )
        ProviderRepository.addProvider(provider)
        assertEquals(hook, RuntimeConfigRepository.oauthRuntimeConfigFor(
            provider.id, provider.authMode, provider.models.single().modelId,
        )?.requestHook)
    }

    @Test
    fun oauthReResolutionBindsLocalEntryWhenTwoEntriesUseTheSameApiModelName() = runBlocking {
        val source = provider()
        val first = source.models[0].copy(modelId = "shared-api-model")
        val second = source.models[1].copy(modelId = "shared-api-model")
        val provider = source.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT, models = listOf(first, second))
        ProviderRepository.addProvider(provider)
        val original = AgentRuntimeWire.RunRequest(
            runId = "same-api-hook", prompt = "Question", images = emptyList(),
            config = RuntimeConfigRepository.buildRuntimeConfig(provider, second),
        )
        val incoming = AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toLegacyBundle(original))
        val resolved = requireNotNull(RuntimeConfigRepository.oauthRuntimeConfigFor(
            incoming.config.providerId, incoming.config.authMode, incoming.config.model, incoming.config.modelEntryId,
        ))
        assertEquals(second.id, resolved.modelEntryId)
        assertEquals(second.requestHook, resolved.requestHook)
        assertEquals(second.requestHook,
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(incoming, resolved).config.requestHook)
        assertEquals(first.requestHook, RuntimeConfigRepository.oauthRuntimeConfigFor(
            provider.id, provider.authMode, first.modelId, first.id,
        )?.requestHook)
        assertNull(RuntimeConfigRepository.oauthRuntimeConfigFor(provider.id, provider.authMode, first.modelId, "missing-entry"))
        assertNull(RuntimeConfigRepository.oauthRuntimeConfigFor(provider.id, provider.authMode, "wrong-api-model", second.id))
        ProviderRepository.replaceModels(provider.id, listOf(first, second.copy(isEnabled = false)))
        assertNull(RuntimeConfigRepository.oauthRuntimeConfigFor(provider.id, provider.authMode, second.modelId, second.id))
        assertEquals(first.requestHook, RuntimeConfigRepository.oauthRuntimeConfigFor(
            provider.id, provider.authMode, first.modelId,
        )?.requestHook)
    }

    private fun provider(): OpenAiCompatibleProviderSetting = OpenAiCompatibleProviderSetting(
        id = "hook-runtime-provider", name = "Hook runtime", baseUrl = "https://example.invalid/v1",
        apiKey = "fixture-key", models = listOf(
            Model(id = "hook-primary", modelId = "fixture-primary", displayName = "Primary",
                requestHook = ModelRequestHook(true, ModelRequestHookChannel.SYSTEM, "Primary model supplement")),
            Model(id = "hook-secondary", modelId = "fixture-secondary", displayName = "Secondary",
                requestHook = ModelRequestHook(true, ModelRequestHookChannel.BOTH, "Secondary model supplement")),
        ),
    )
}
