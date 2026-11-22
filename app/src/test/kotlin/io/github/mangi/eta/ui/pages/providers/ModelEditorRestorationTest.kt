package io.github.mangi.eta.ui.pages.providers

import android.app.Application
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ModelEditorRestorationTest {
    @Test
    fun modelEditorRegistryRestoresTabIdentityAndAllUnsavedFields() {
        ModelRequestHookChannel.entries.forEach { channel ->
            val original = model("entry-a", "remote-a", "Model A", 0)
            val draft = ModelEditorDraft.from(original, borrowedVendor = false).copy(
                displayName = "Uncommitted name",
                modelId = "edited-id",
                contextWindowOverrideText = "2147483648", // Keep invalid input for correction, too.
                reasoningOverrideActive = true,
                reasoningEnabled = true,
                selectedReasoningEffortNames = setOf(ReasoningEffort.OFF.name, ReasoningEffort.HIGH.name),
                requestHookEnabled = true,
                requestHookChannel = channel,
                requestHookPrompt = "  未保存\n\"quoted\"\t草稿  ",
            )
            val saved = EditorComposition("provider-a").use { first ->
                first.tab.intValue = 1
                first.editor.draft = draft
                first.registry.performSave()
            }

            EditorComposition("provider-a", saved).use { restored ->
                assertEquals(1, restored.tab.intValue)
                assertEquals(draft, restored.editor.draft)
                assertEquals("entry-a", restored.editor.draft?.sourceModelId)
            }
        }
    }

    @Test
    fun modelEditorCancelSaveAndReopenDoNotCarryDraftBetweenEntries() {
        val first = model("entry-a", "remote-a", "Model A", 0)
        val second = model("entry-b", "remote-b", "Model B", 1)
        val savedClosed = EditorComposition("provider-a").use { composition ->
            composition.editor.draft = ModelEditorDraft.from(first, false).copy(requestHookPrompt = "discard me")
            composition.editor.draft = null // Dialog cancel.
            composition.registry.performSave()
        }
        val savedSecond = EditorComposition("provider-a", savedClosed).use { composition ->
            assertNull(composition.editor.draft)
            composition.editor.draft = ModelEditorDraft.from(second, false).copy(
                requestHookEnabled = true,
                requestHookChannel = ModelRequestHookChannel.USER,
                requestHookPrompt = "save only on B",
                contextWindowOverrideText = "256000",
            )
            composition.registry.performSave()
        }
        val savedAfterCommit = EditorComposition("provider-a", savedSecond).use { composition ->
            val restored = requireNotNull(composition.editor.draft)
            val committed = restored.toModel(second, nativeGemini = false, borrowedVendor = false)
            assertEquals("entry-b", committed.id)
            assertEquals("save only on B", committed.requestHook.prompt)
            assertEquals(ModelRequestHookChannel.USER, committed.requestHook.channel)
            composition.editor.draft = null // Successful repository save closes the dialog.
            composition.registry.performSave()
        }
        EditorComposition("provider-a", savedAfterCommit).use { composition ->
            assertNull(composition.editor.draft)
            composition.editor.draft = ModelEditorDraft.from(first, false)
            assertEquals(first.requestHook.prompt, composition.editor.draft?.requestHookPrompt)
            assertEquals("entry-a", composition.editor.draft?.sourceModelId)
        }
    }

    @Test
    fun newModelIdentityAndProviderScopeSurviveRegistryRecreation() {
        val draft = ModelEditorDraft.from(model("", "", "Custom model", 0), false).copy(
            modelId = "new-id",
            requestHookPrompt = "new draft",
        )
        val saved = EditorComposition("provider-a").use { composition ->
            composition.tab.intValue = 1
            composition.editor.draft = draft
            composition.registry.performSave()
        }
        EditorComposition("provider-a", saved).use { composition ->
            assertEquals(draft, composition.editor.draft)
            assertTrue(requireNotNull(composition.editor.draft).isNew)
        }
        EditorComposition("provider-b", saved).use { composition ->
            assertEquals(0, composition.tab.intValue)
            assertNull(composition.editor.draft)
        }
    }

    /** Actual Compose rememberSaveable + registry lifecycle, without an Android Activity or UI nodes. */
    private class EditorComposition(
        providerId: String,
        restored: Map<String, List<Any?>>? = null,
    ) : AutoCloseable {
        val registry = SaveableStateRegistry(restored) { value ->
            fun saveable(item: Any?): Boolean = when (item) {
                null, is String, is Int, is Boolean -> true
                is List<*> -> item.all(::saveable)
                else -> false
            }
            saveable(value)
        }
        private val recomposer = Recomposer(EmptyCoroutineContext)
        private val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        lateinit var tab: MutableIntState
        lateinit var editor: ModelEditorState

        init {
            composition.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    tab = rememberProviderDetailTab(providerId)
                    editor = rememberModelEditorState(providerId)
                }
            }
        }

        override fun close() {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private fun model(id: String, modelId: String, displayName: String, sortOrder: Int) = Model(
        id = id,
        modelId = modelId,
        displayName = displayName,
        sortOrder = sortOrder,
    )
}
