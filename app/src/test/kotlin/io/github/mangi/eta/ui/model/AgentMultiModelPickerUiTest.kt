package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMultiModelPickerUiTest {
    @Test
    fun compareToggle_addsUntilMaxThenRejects() {
        var selected = AgentModelPickerProjector.toggleCompareModel(emptyList(), "a")
        selected = AgentModelPickerProjector.toggleCompareModel(selected, "b")
        selected = AgentModelPickerProjector.toggleCompareModel(selected, "c")
        assertEquals(listOf("a", "b", "c"), selected)
        // 超过 3 个被拒绝
        assertEquals(listOf("a", "b", "c"), AgentModelPickerProjector.toggleCompareModel(selected, "d"))
    }

    @Test
    fun compareToggle_removesExistingModel() {
        val selected = AgentModelPickerProjector.toggleCompareModel(listOf("a", "b"), "a")
        assertEquals(listOf("b"), selected)
    }

    @Test
    fun pickerState_compareEnabledNeedsAtLeastTwo() {
        assertFalse(AgentModelPickerUiState(compareModelIds = listOf("a")).compareEnabled)
        assertTrue(AgentModelPickerUiState(compareModelIds = listOf("a", "b")).compareEnabled)
    }

    @Test
    fun fanoutGraphUi_activeAndTokenSummary() {
        val root = BranchNodeUi(id = "q", title = "提问", createdAt = 0L)
        val graph = FanoutGraphUi(
            root = root,
            columns = listOf(
                FanoutBranchColumnUi("1", "m1", "模型一", FanoutBranchStatusUi.Succeeded, tokenText = "120"),
                FanoutBranchColumnUi("2", "m2", "模型二", FanoutBranchStatusUi.Failed, tokenText = "80"),
            ),
        )
        assertTrue(graph.active)
        assertEquals("120 + 80", graph.estimatedTotalTokensText)
        assertFalse(FanoutGraphUi(root, emptyList()).active)
    }
}
