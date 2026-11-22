package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ModelRequestHookPersistenceTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun savedHookSurvivesDatabaseReopenAndRemoteCatalogRefresh() = runBlocking {
        val provider = seed(listOf(ModelRequestHook()))
        for (channel in ModelRequestHookChannel.entries) {
            val draftHook = ModelRequestHook(true, channel, "  ETA_PERSIST_$channel\n研究说明  ")
            val expected = draftHook.copy(prompt = draftHook.prompt.trim())
            val current = ModelRepository.modelsByProvider(provider.id).single()
            val saved = ModelRepository.saveModel(provider.id, current.copy(requestHook = draftHook))
            assertEquals(expected, saved.requestHook)

            EtaDatabase.closeForTests()
            ProviderRepository.init(context)
            val reopened = ModelRepository.modelsByProvider(provider.id).single()
            assertEquals(expected, reopened.requestHook)
            val raw = EtaDatabase.get(context).providerDao().models(provider.id).single().requestHookJson
            assertEquals(expected, Json.decodeFromString<ModelRequestHook>(raw))

            val result = ModelRepository.syncRemoteModels(provider.id, listOf(reopened.copy(
                id = "remote-id-must-not-replace-local-id",
                displayName = "Refreshed remote label",
                contextWindow = 65_536,
                requestHook = ModelRequestHook(true, ModelRequestHookChannel.SYSTEM, "ETA_REMOTE_HOOK_MUST_NOT_REPLACE_LOCAL"),
            )), adoptedModelIds = setOf(reopened.modelId))
            assertTrue(result.applied)
            EtaDatabase.closeForTests()
            val refreshed = ModelRepository.modelsByProvider(provider.id).single()
            assertEquals(saved.id, refreshed.id)
            assertEquals("Refreshed remote label", refreshed.displayName)
            assertEquals(expected, refreshed.requestHook)
            assertEquals(saved.id, EtaDatabase.get(context).conversationDao().conversationEntities().single().modelId)
        }
    }

    @Test
    fun invalidHookSaveLeavesThePriorDatabaseValueUnchanged() = runBlocking {
        val expected = ModelRequestHook(true, ModelRequestHookChannel.USER, "ETA_PREVIOUS_VALID_HOOK")
        val provider = seed(listOf(expected))
        val stored = ModelRepository.modelsByProvider(provider.id).single()
        val before = EtaDatabase.get(context).providerDao().models(provider.id).single().requestHookJson
        val invalid = listOf(
            expected.copy(prompt = " \n\t "),
            expected.copy(prompt = "x".repeat(ModelRequestHook.MAX_PROMPT_CHARS + 1)),
        )
        for (hook in invalid) {
            val failure = runCatching { ModelRepository.saveModel(provider.id, stored.copy(requestHook = hook)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            EtaDatabase.closeForTests()
            assertEquals(expected, ModelRepository.modelsByProvider(provider.id).single().requestHook)
            assertEquals(before, EtaDatabase.get(context).providerDao().models(provider.id).single().requestHookJson)
        }
    }

    @Test
    fun backupApiRoundTripRestoresEnabledAndDisabledHooksAndConversationBinding() = runBlocking {
        val hooks = listOf(
            ModelRequestHook(true, ModelRequestHookChannel.BOTH, "ETA_BACKUP_ENABLED\n研究说明"),
            ModelRequestHook(false, ModelRequestHookChannel.USER, "ETA_BACKUP_DISABLED_DRAFT"),
        )
        val provider = seed(hooks)
        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(context, output)
        assertEquals(2, exported.modelCount)
        val json = JSONObject(output.toString(Charsets.UTF_8.name()))
        val exportedModels = json.getJSONArray("providers").getJSONObject(0).getJSONArray("models")
        assertEquals(hooks, (0 until exportedModels.length()).map {
            Json.decodeFromString<ModelRequestHook>(exportedModels.getJSONObject(it).getString("requestHookJson"))
        })

        ProviderRepository.replaceModels(provider.id, provider.models.map { it.copy(requestHook = ModelRequestHook()) })
        EtaDatabase.get(context).conversationDao().replaceAll(emptyList(), emptyList(), emptyList(), null)
        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()), forgetCredentials = {})
        assertEquals(2, imported.modelCount)
        assertEquals(1, imported.conversationCount)
        EtaDatabase.closeForTests()

        val restored = requireNotNull(ProviderRepository.providerById(provider.id))
        assertEquals(provider.models.map { it.id }, restored.models.map { it.id })
        assertEquals(hooks, restored.models.map { it.requestHook })
        assertTrue(restored.models.first().requestHook.enabled)
        assertFalse(restored.models.last().requestHook.enabled)
        assertConversationBinding(restored.models.first().id)
    }

    @Test
    fun legacyBackupWithoutHookFieldRestoresDisabledDefaultAndPreservesOtherData() = runBlocking {
        val provider = seed(listOf(ModelRequestHook(true, ModelRequestHookChannel.BOTH, "ETA_PRE_IMPORT_HOOK")))
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val legacy = JSONObject(output.toString(Charsets.UTF_8.name()))
        val providers = legacy.getJSONArray("providers")
        for (index in 0 until providers.length()) {
            val models = providers.getJSONObject(index).getJSONArray("models")
            for (model in 0 until models.length()) {
                assertTrue(models.getJSONObject(model).has("requestHookJson"))
                models.getJSONObject(model).remove("requestHookJson")
            }
        }
        assertFalse(legacy.toString().contains("requestHookJson"))
        assertTrue(requireNotNull(ProviderRepository.providerById(provider.id)).models.single().requestHook.enabled)

        val imported = EtaBackupRepository.import(context,
            ByteArrayInputStream(legacy.toString().toByteArray(Charsets.UTF_8)), forgetCredentials = {},
        )
        assertEquals(1, imported.modelCount)
        EtaDatabase.closeForTests()

        val restored = requireNotNull(ProviderRepository.providerById(provider.id)).models.single()
        assertEquals(provider.models.single().id, restored.id)
        assertEquals(provider.models.single().modelId, restored.modelId)
        assertEquals(provider.models.single().displayName, restored.displayName)
        assertEquals(ModelRequestHook(), restored.requestHook)
        assertEquals("{}", EtaDatabase.get(context).providerDao().models(provider.id).single().requestHookJson)
        assertConversationBinding(restored.id)
    }

    private suspend fun seed(hooks: List<ModelRequestHook>): OpenAiCompatibleProviderSetting {
        val provider = OpenAiCompatibleProviderSetting(
            id = "hook-persistence-provider",
            name = "Hook persistence fixture",
            baseUrl = "https://example.invalid/v1",
            apiKey = "eta-persistence-fixture-key",
            models = hooks.mapIndexed { index, hook -> Model(
                id = "hook-persistence-model-$index", modelId = "fixture-model-$index",
                displayName = "Fixture model $index", sortOrder = index, requestHook = hook,
            ) },
        )
        ProviderRepository.addProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(ConversationEntity(
                id = CONVERSATION_ID, title = "Retained hook conversation", thinkingEnabled = false,
                modelId = provider.models.first().id, createdAt = 1, updatedAt = 2,
            )),
            messages = listOf(ConversationMessageEntity(
                id = "hook-persistence-message", conversationId = CONVERSATION_ID,
                sortIndex = 0, type = "user", content = "ETA_RETAINED_CONVERSATION_MESSAGE",
            )),
            contextCheckpoints = emptyList(),
            state = ConversationStateEntity(selectedConversationId = CONVERSATION_ID),
        )
        return provider
    }

    private suspend fun assertConversationBinding(modelId: String) {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversation = dao.conversationEntities().single()
        assertEquals(CONVERSATION_ID, conversation.id)
        assertEquals("Retained hook conversation", conversation.title)
        assertEquals(modelId, conversation.modelId)
        assertEquals("ETA_RETAINED_CONVERSATION_MESSAGE", dao.messages().single().content)
        assertEquals(CONVERSATION_ID, dao.state()?.selectedConversationId)
    }

    private companion object {
        const val CONVERSATION_ID = "hook-persistence-conversation"
    }
}
