package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.SubagentModelGrant
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real dispatcher up to the model-config boundary, without network or tool effects. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SubagentModelDispatchTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var first: OpenAiCompatibleProviderSetting
    private lateinit var second: OpenAiCompatibleProviderSetting
    private val configBoundary = "ETA_TEST_CONFIG_BOUNDARY"

    @Before
    fun setUp() = runBlocking {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        SettingsDataStore.setSelection(null, null)
        SettingsDataStore.setOfficialModelCatalogRevision(0)
        SettingsDataStore.setSubagentSettings(SubagentSettings())
        ProviderRepository.init(context)
        first = seed("dispatch-a", "first-entry", "HOOK_A")
        second = seed("dispatch-b", "second-entry", "HOOK_B")
    }

    @After
    fun tearDown() {
        runBlocking { SettingsDataStore.setSubagentSettings(SubagentSettings()) }
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun dispatchUsesTheSelectedProvidersHookAfterSettingsAndDatabaseReopen() = runBlocking {
        configure("dispatch-b::second-entry")
        EtaDatabase.closeForTests()
        ProviderRepository.init(context)
        var selectedConfig: AgentModelClient.ModelConfig? = null
        val result = dispatch("shared-api-model") { id, effort ->
            selectedConfig = RuntimeConfigRepository.subagentRuntimeConfig(primary(), id, effort)
            error(configBoundary)
        }
        assertEquals("SUBAGENT_CONFIG_ERROR", result.getString("code"))
        assertTrue(result.toString().contains(configBoundary))
        assertEquals("dispatch-b", selectedConfig?.providerId)
        assertEquals("second-entry", selectedConfig?.modelEntryId)
        assertEquals(second.models.single().requestHook, selectedConfig?.requestHook)
    }

    @Test
    fun dispatchFailsWhenTheAuthorizedModelIsDisabledBeforeConfigReload() = runBlocking {
        val target = second.models.single()
        configure("dispatch-b::second-entry")
        var selectedId: String? = null
        var selectedConfig: AgentModelClient.ModelConfig? = null
        val result = dispatch(target.modelId) { id, effort ->
            selectedId = id
            ProviderRepository.replaceModels(second.id, listOf(target.copy(isEnabled = false)))
            selectedConfig = RuntimeConfigRepository.subagentRuntimeConfig(
                primary(), id, effort, requireAvailableOverride = true,
            )
            error(configBoundary)
        }
        assertEquals(target.id, selectedId)
        assertEquals("SUBAGENT_CONFIG_ERROR", result.getString("code"))
        assertTrue(result.toString().contains("已选子模型 ${target.id} 或其服务商已停用或移除"))
        assertNull(selectedConfig)
    }

    @Test
    fun ambiguousLegacyGrantAndUnknownRequestsNeverFallBackToPrimary() = runBlocking {
        var selectedId: String? = null
        configure("shared-api-model")
        val stale = dispatch("shared-api-model") { id, _ -> selectedId = id; error(configBoundary) }
        assertEquals("MODEL_NOT_AUTHORIZED", stale.getString("code"))
        assertNull(selectedId)
        configure("dispatch-b::second-entry")
        val unknown = dispatch("missing-api-model") { id, _ -> selectedId = id; error(configBoundary) }
        assertEquals("MODEL_NOT_AUTHORIZED", unknown.getString("code"))
        assertNull(selectedId)
    }

    @Test
    fun duplicateAuthorizedNamesRequireAndHonorTheExactSelectorFromTheSchema() = runBlocking {
        configure("dispatch-a::first-entry", "dispatch-b::second-entry")
        val options = SubagentModelSelection.resolve(ProviderRepository.allProviders(), SettingsDataStore.subagentSettings())
        val secondOption = options.single { it.uuid == "second-entry" }.option()
        val schema = AgentSubagentToolCatalog.schema(authorizedModels = options.map { it.option() })
        assertTrue(schema.toString().contains(secondOption.modelId))
        var selectedId: String? = null
        val bare = dispatch("shared-api-model") { id, _ -> selectedId = id; error(configBoundary) }
        assertEquals("MODEL_NOT_AUTHORIZED", bare.getString("code"))
        assertNull(selectedId)
        val exact = dispatch(secondOption.modelId) { id, _ -> selectedId = id; error(configBoundary) }
        assertEquals("SUBAGENT_CONFIG_ERROR", exact.getString("code"))
        assertEquals("second-entry", selectedId)
    }

    private suspend fun configure(vararg ids: String) {
        SettingsDataStore.setSubagentSettings(SubagentSettings(
            allowModelSelection = true,
            allowedModelIds = ids.toList(),
            allowedGrants = ids.map { SubagentModelGrant(it) },
        ))
    }

    private fun primary() = RuntimeConfigRepository.buildRuntimeConfig(first, first.models.single())

    private fun dispatch(
        requested: String,
        override: suspend (String?, String?) -> AgentModelClient.ModelConfig,
    ): JSONObject {
        val conversation = "dispatch-test-${UUID.randomUUID()}"
        val controller = AgentRunController()
        val executor = SubagentExecutor(
            conversationKey = conversation,
            parentController = controller,
            configProvider = { error("Unexpected primary-model fallback") },
            configForOverride = override,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { error("Unexpected tool execution") },
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            mcpTools = JSONArray(),
            executionContext = null,
            onProgress = { error("Config validation must finish before publishing progress") },
        )
        val result = executor.execute(1, AgentModelClient.ToolCall(
            "selection-call", "subagent", JSONObject()
                .put("prompt", "Fixture work")
                .put("description", "Select fixture model")
                .put("subagent_type", "explore")
                .put("model", requested).toString(),
        ))
        assertTrue(SubagentRunRegistry.liveIds(conversation).isEmpty())
        controller.cancel()
        return JSONObject(result.content)
    }

    private suspend fun seed(providerId: String, localId: String, prompt: String): OpenAiCompatibleProviderSetting {
        val provider = OpenAiCompatibleProviderSetting(
            id = providerId, name = providerId, baseUrl = "https://example.invalid/v1", apiKey = "fixture-key",
            models = listOf(Model(localId, "shared-api-model", localId,
                requestHook = ModelRequestHook(true, ModelRequestHookChannel.USER, prompt))),
        )
        ProviderRepository.addProvider(provider)
        return provider
    }
}
