package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageActionAvailabilityTest {
    @Test
    fun copyStaysAvailableWhileAssistantResponseStreams() {
        val actions = userMessageActionAvailability(isStreaming = true, isEditing = false)
        assertTrue(actions.canCopy)
        assertFalse(actions.canMutate)
    }

    @Test
    fun copyStaysAvailableWhileMessageIsBeingEdited() {
        val actions = userMessageActionAvailability(isStreaming = false, isEditing = true)
        assertTrue(actions.canCopy)
        assertFalse(actions.canMutate)
    }

    @Test
    fun allActionsAreAvailableWhenConversationIsIdle() {
        val actions = userMessageActionAvailability(isStreaming = false, isEditing = false)
        assertTrue(actions.canCopy)
        assertTrue(actions.canMutate)
    }
}
