package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.SubagentModelGrant
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.subagentModelSelectionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentModelSelectionContractTest {
    @Test
    fun selectedEntryKeepsItsHookAcrossProviderReordering() {
        val (first, second) = providers()
        val settings = allowed("provider-b::model-b")
        for (ordered in listOf(listOf(first, second), listOf(second, first))) {
            val models = SubagentModelSelection.resolve(ordered, settings)
            val selected = requireNotNull(SubagentModelSelection.select(models, "shared-model"))
            assertEquals("model-b", selected.uuid)
            assertEquals(second.models.single().requestHook, selected.resolved.model.requestHook)
            assertEquals("shared-model", models.single().option().modelId)
        }
    }

    @Test
    fun duplicateApiNamesHaveDistinctSelectorsAndRejectTheBareName() {
        val all = providers()
        val models = SubagentModelSelection.resolve(all, allowed("model-a", "model-b"))
        assertEquals(listOf("provider-a::model-a", "provider-b::model-b"), models.map { it.selector })
        assertNull(SubagentModelSelection.select(models, "shared-model"))
        for (model in models) {
            val option = model.option()
            assertEquals("shared-model", option.apiId)
            assertEquals(model.uuid, SubagentModelSelection.select(models, option.modelId)?.uuid)
            assertEquals(model.uuid, SubagentModelSelection.select(models, model.uuid)?.uuid)
            assertTrue(option.describe().contains(model.resolved.provider.name))
        }
    }

    @Test
    fun emptyAllowListOffersAllEnabledModelsButStaleGrantsDoNot() {
        val all = providers()
        assertEquals(2, SubagentModelSelection.resolve(all, allowed()).size)
        assertTrue(SubagentModelSelection.resolve(all, allowed("deleted-entry")).isEmpty())
        assertTrue(SubagentModelSelection.resolve(all, allowed("shared-model")).isEmpty())
        assertTrue(SubagentModelSelection.resolve(all, SubagentSettings()).isEmpty())
        assertTrue(SubagentModelSelection.resolve(all, allowed().copy(allowedModelIds = listOf(" "))).isEmpty())
    }

    @Test
    fun disabledProviderOrModelDoesNotTransferItsGrantToTheOtherProvider() {
        val (first, second) = providers()
        val settings = allowed("provider-b::model-b")
        assertTrue(SubagentModelSelection.resolve(listOf(first, second.copy(isEnabled = false)), settings).isEmpty())
        assertTrue(SubagentModelSelection.resolve(listOf(first,
            second.copy(models = second.models.map { it.copy(isEnabled = false) })), settings).isEmpty())
    }

    @Test
    fun prefixesAndCaseArePreservedForExactEntrySelectors() {
        val (first, second) = providers()
        val all = listOf(first.copy(models = first.models.map { it.copy(modelId = "a:shared") }),
            second.copy(models = second.models.map { it.copy(modelId = "b:shared") }))
        val models = SubagentModelSelection.resolve(all, allowed())
        assertEquals("model-a", SubagentModelSelection.select(models, "a:shared")?.uuid)
        assertEquals("model-b", SubagentModelSelection.select(models, "b:shared")?.uuid)
        assertNull(SubagentModelSelection.select(models, "shared"))
        assertNull(SubagentModelSelection.select(models, "wrong-provider::model-b"))
    }

    @Test
    fun legacyUniqueApiNameAndLocalIdRemainCompatible() {
        val all = listOf(providers().last())
        for (grant in listOf("model-b", "shared-model", subagentModelSelectionId("provider-b", "model-b"))) {
            val model = SubagentModelSelection.resolve(all, allowed(grant)).single()
            assertEquals("model-b", model.uuid)
            assertFalse(model.resolved.model.requestHook.enabled)
            assertEquals("HOOK_B_DRAFT", model.resolved.model.requestHook.prompt)
        }
    }

    private fun allowed(vararg ids: String) = SubagentSettings(
        allowModelSelection = true,
        allowedModelIds = ids.toList(),
        allowedGrants = ids.map { SubagentModelGrant(it) },
    )

    private fun providers() = listOf(
        OpenAiCompatibleProviderSetting(
            id = "provider-a", name = "Provider A", baseUrl = "https://a.example.invalid/v1",
            models = listOf(Model("model-a", "shared-model", "Shared A",
                requestHook = ModelRequestHook(true, ModelRequestHookChannel.SYSTEM, "HOOK_A"))),
        ),
        OpenAiCompatibleProviderSetting(
            id = "provider-b", name = "Provider B", baseUrl = "https://b.example.invalid/v1",
            models = listOf(Model("model-b", "shared-model", "Shared B",
                requestHook = ModelRequestHook(false, ModelRequestHookChannel.USER, "HOOK_B_DRAFT"))),
        ),
    )
}
