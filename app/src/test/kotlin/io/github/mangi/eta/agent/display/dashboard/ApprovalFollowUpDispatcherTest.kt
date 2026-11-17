package io.github.mangi.eta.agent.display.dashboard

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentApprovalFollowUpRuntime.Outcome
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.MainActivity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ApprovalFollowUpDispatcherTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val answer = ApprovalResolution("token-a", "owner-a", "run-a", "continue")

    @Before
    fun setUp() {
        // Robolectric replaces Application/preferences between methods; release the prior fixture's handles.
        PendingPromptStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        AgentConfirmGuard::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        PendingPromptStore.init(context)
        clearOutbox()
    }

    @After
    fun tearDown() {
        clearOutbox()
    }

    private fun clearOutbox() {
        PendingPromptStore.followUps.value.forEach { PendingPromptStore.release(it.token) }
        PendingPromptStore.release(answer.token)
        assertTrue(PendingPromptStore.commit(emptyList(), emptyList(), null))
        PendingPromptStore.followUps.value.toList().forEach { assertTrue(PendingPromptStore.acknowledge(it.token)) }
    }

    private fun commit() {
        assertTrue(PendingPromptStore.commit(emptyList(), emptyList(), answer))
    }

    private suspend fun dispatch(
        boundary: RecordingBoundary,
        resolution: ApprovalResolution = answer,
        ownerDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        openConversation: Boolean = true,
    ) = ApprovalFollowUpDispatcher.dispatchPending(context, resolution, openConversation, boundary, ownerDispatcher)

    @Test
    fun uiCommitThenAcknowledgeBeforeDispatchDoesNotRecreateOrOpen() = runBlocking {
        commit()
        assertEquals(0, PendingPromptStore.submitPending("other-owner") { true })
        assertEquals(1, PendingPromptStore.submitPending(answer.conversationKey) { it == answer })
        val boundary = RecordingBoundary()
        dispatch(boundary)
        assertTrue(boundary.sent.isEmpty())
        assertTrue(boundary.opened.isEmpty())
        assertTrue(PendingPromptStore.followUps.value.isEmpty())
        val stored = context.getSharedPreferences("eta_pending_prompt", Context.MODE_PRIVATE)
            .getString("follow_ups", null)
        assertEquals(0, JSONArray(stored).length())
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun uiClaimBeforeDispatchPreventsBothDeliveryAndOwnerEntry() = runBlocking {
        commit()
        assertEquals(answer, PendingPromptStore.claimExact(answer))
        val boundary = RecordingBoundary()
        dispatch(boundary)
        assertTrue(boundary.sent.isEmpty())
        assertTrue(boundary.opened.isEmpty())
        assertEquals(listOf(answer), PendingPromptStore.followUps.value)
        assertNull(PendingPromptStore.pendingExactUnclaimed(answer))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun inactiveRunRetainsAnswerAndOpensOriginalOwner() = runBlocking {
        commit()
        val boundary = RecordingBoundary(Outcome.INACTIVE, openActivity = true)
        dispatch(boundary)
        assertEquals(listOf(answer), boundary.sent)
        assertEquals(listOf(answer), boundary.opened)
        assertEquals(answer, PendingPromptStore.pendingExactUnclaimed(answer))
        val opened = shadowOf(context).nextStartedActivity
        assertNotNull(opened)
        assertEquals(MainActivity.ACTION_OPEN_CONVERSATION_ID, opened.action)
        assertEquals(answer.conversationKey, opened.getStringExtra(MainActivity.EXTRA_CONVERSATION_ID))
        assertEquals(answer.conversationKey, opened.getStringExtra(MainActivity.EXTRA_CONVERSATION_KEY))
    }

    @Test
    fun wrongRunOwnerOrTokenDoesNotSendOrOpen() = runBlocking {
        commit()
        val boundary = RecordingBoundary()
        dispatch(boundary, answer.copy(runId = "run-b"))
        dispatch(boundary, answer.copy(conversationKey = "owner-b"))
        dispatch(boundary, answer.copy(token = "token-b"))
        assertTrue(boundary.sent.isEmpty())
        assertTrue(boundary.opened.isEmpty())
        assertEquals(answer, PendingPromptStore.pendingExactUnclaimed(answer))
    }

    @Test
    fun failedDeliveryReleasesClaimAndRetryAcknowledgesOnlyAccepted() = runBlocking {
        commit()
        val boundary = RecordingBoundary(Outcome.UNAVAILABLE)
        dispatch(boundary, openConversation = false)
        assertEquals(answer, PendingPromptStore.pendingExactUnclaimed(answer))
        boundary.outcome = Outcome.ACCEPTED
        dispatch(boundary)
        dispatch(boundary)
        assertEquals(listOf(answer, answer), boundary.sent)
        assertTrue(boundary.opened.isEmpty())
        assertTrue(PendingPromptStore.followUps.value.isEmpty())
    }

    @Test
    fun throwingDeliveryStillReleasesExactClaimForRetry() {
        commit()
        val boundary = RecordingBoundary().apply { failure = IllegalStateException("delivery failed") }
        try {
            ApprovalFollowUpDispatcher.deliver(context, answer, boundary)
            throw AssertionError("Expected delivery failure")
        } catch (_: IllegalStateException) {
            assertEquals(answer, PendingPromptStore.pendingExactUnclaimed(answer))
        }
        boundary.failure = null
        boundary.outcome = Outcome.ACCEPTED
        assertEquals(Outcome.ACCEPTED, ApprovalFollowUpDispatcher.deliver(context, answer, boundary))
        assertTrue(PendingPromptStore.followUps.value.isEmpty())
    }

    @Test
    fun uiAcknowledgeDuringOwnerHandoffPreventsStaleEntry() = runBlocking {
        commit()
        val boundary = RecordingBoundary(Outcome.INACTIVE)
        dispatch(boundary, ownerDispatcher = beforeOwner {
            assertEquals(1, PendingPromptStore.submitPending(answer.conversationKey) { true })
        })
        assertEquals(listOf(answer), boundary.sent)
        assertTrue(boundary.opened.isEmpty())
        assertTrue(PendingPromptStore.followUps.value.isEmpty())
    }

    @Test
    fun uiClaimDuringOwnerHandoffPreventsEntryWithoutStealingClaim() = runBlocking {
        commit()
        val boundary = RecordingBoundary(Outcome.INACTIVE)
        dispatch(boundary, ownerDispatcher = beforeOwner {
            assertEquals(answer, PendingPromptStore.claimExact(answer))
        })
        assertEquals(listOf(answer), boundary.sent)
        assertTrue(boundary.opened.isEmpty())
        assertNull(PendingPromptStore.claimExact(answer))
        PendingPromptStore.release(answer.token)
        assertEquals(answer, PendingPromptStore.pendingExactUnclaimed(answer))
    }

    @Test
    fun exactStoreClaimAndEntryRejectMismatchesAndReleasedClaimCanRetry() {
        commit()
        assertNull(PendingPromptStore.claimExact(answer.copy(runId = "run-b")))
        assertFalse(PendingPromptStore.ifPendingExactUnclaimed(answer.copy(conversationKey = "owner-b")) {
            throw AssertionError("Unexpected owner entry")
        })
        assertEquals(answer, PendingPromptStore.claimExact(answer))
        assertFalse(PendingPromptStore.ifPendingExactUnclaimed(answer) {
            throw AssertionError("Claimed answer opened")
        })
        PendingPromptStore.release(answer.token)
        assertTrue(PendingPromptStore.ifPendingExactUnclaimed(answer) { assertEquals(answer, it) })
        assertEquals(answer, PendingPromptStore.claimExact(answer))
    }

    @Test
    fun manualNotificationBodyOpensOriginalTokenAndOwnerAndKeepsCompletionAction() {
        val pending = PendingApproval(
            token = answer.token,
            risk = ConfirmRisk.IRREVERSIBLE,
            title = "manual operation",
            fields = emptyList(),
            quiet = false,
            options = listOf(PromptOption(PromptOptionId.MANUAL, "manual")),
            conversationKey = answer.conversationKey,
            runId = answer.runId,
        )
        assertTrue(PendingPromptStore.commit(emptyList(), listOf(pending), null))
        AgentConfirmGuard.init(context)
        AgentConfirmGuard.showManualCompletionNotification(
            context, requireNotNull(ApprovalAnswers.option(pending, PromptOptionId.MANUAL)),
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.single()
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(AgentConfirmActivity::class.java.name, open.component?.className)
        assertEquals(answer.token, open.getStringExtra(AgentConfirmActivity.EXTRA_TOKEN))
        assertEquals(answer.conversationKey, open.getStringExtra(AgentConfirmActivity.EXTRA_CONVERSATION))
        val complete = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(AgentConfirmActionReceiver.ACTION_MANUAL_COMPLETE, complete.action)
        assertEquals(answer.token, complete.getStringExtra(AgentConfirmActionReceiver.EXTRA_TOKEN))
        assertEquals(answer.conversationKey, complete.getStringExtra(AgentConfirmActionReceiver.EXTRA_CONVERSATION))
    }

    private fun beforeOwner(action: () -> Unit) = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            action()
            block.run()
        }
    }

    private class RecordingBoundary(
        var outcome: Outcome = Outcome.UNAVAILABLE,
        private val openActivity: Boolean = false,
    ) : ApprovalFollowUpDispatcher.Boundary {
        val sent = mutableListOf<ApprovalResolution>()
        val opened = mutableListOf<ApprovalResolution>()
        var failure: RuntimeException? = null

        override fun deliver(context: Context, answer: ApprovalResolution): Outcome {
            sent += answer
            failure?.let { throw it }
            return outcome
        }

        override fun openOwner(context: Context, answer: ApprovalResolution) {
            opened += answer
            if (openActivity) ApprovalFollowUpDispatcher.openOwner(context, answer)
        }
    }
}
