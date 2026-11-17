package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.planning.AgentTaskPlanStore
import io.github.mangi.eta.agent.planning.TaskContinuationRuntime
import io.github.mangi.eta.agent.planning.TaskContinuationStatus
import io.github.mangi.eta.agent.planning.TaskContinuationStore
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentChatContinuationStartTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun twoRestartsKeepBannerAndCheckpointUntilReplacementActuallyStarts() {
        val root = temporary.newFolder()
        val context = context(root)
        val checkpoint = checkpoint()
        val checkpoints = mutableSetOf("original", "unrelated")
        val recorded = TaskContinuationRuntime.recordFromCheckpoint(context, checkpoint, "conversation")!!
        repeat(2) {
            val restarted = context(root)
            assertEquals(recorded, TaskContinuationRuntime.forConversation(restarted, "conversation"))
            assertNotNull(TaskContinuationRuntime.continuationPrompt(restarted, "conversation"))
            assertTrue("original" in checkpoints)
            assertEquals(TaskContinuationStatus.READY, TaskContinuationStore(root).get("original", "conversation")?.status)
        }
        val start = AgentChatContinuationStart(context, "conversation", "new-run") { _, oldRun ->
            assertEquals(TaskContinuationStatus.ACK, TaskContinuationStore(root).get(oldRun, "conversation")?.status)
            checkpoints.remove(oldRun)
        }
        assertNotNull(start.prepare(request("new-run")))
        assertTrue("original" in checkpoints)
        assertNotNull(TaskContinuationRuntime.forConversation(context, "conversation"))
        assertEquals("original", AgentTaskPlanStore(root, "new-run").snapshot().previousPlan?.originalRunId)
        assertTrue(start.acknowledgeStarted())
        assertEquals(setOf("unrelated"), checkpoints)
        assertNull(TaskContinuationRuntime.forConversation(context, "conversation"))
        assertTrue(start.acknowledgeStarted())
    }

    @Test
    fun failedDispatchReleasesOnlyItsClaimAndAllowsExplicitRetry() {
        val root = temporary.newFolder()
        val context = context(root)
        TaskContinuationRuntime.recordFromCheckpoint(context, checkpoint(), "conversation")
        val removed = mutableListOf<String>()
        val failed = AgentChatContinuationStart(context, "conversation", "failed-run") { _, id -> removed += id }
        assertNotNull(failed.prepare(request("failed-run")))
        assertEquals(TaskContinuationStatus.CLAIMED, TaskContinuationRuntime.forConversation(context, "conversation")?.status)
        failed.releaseStartFailure()
        assertEquals(TaskContinuationStatus.READY, TaskContinuationRuntime.forConversation(context, "conversation")?.status)
        assertTrue(removed.isEmpty())
        assertFalse(AgentChatContinuationStart(context, "conversation", "ordinary-run").acknowledgeStarted())
        assertNotNull(AgentChatContinuationStart(context, "conversation", "retry-run") { _, id -> removed += id }
            .prepare(request("retry-run")))
        assertTrue(removed.isEmpty())
    }

    @Test
    fun ordinaryRequestLeavesContinuationAndNewRunPlanIndependent() {
        val root = temporary.newFolder()
        val context = context(root)
        val original = TaskContinuationRuntime.recordFromCheckpoint(context, checkpoint(), "conversation")
        val ordinary = request("ordinary-run")
        assertEquals("继续当前新问题", ordinary.prompt)
        assertEquals(original, TaskContinuationRuntime.forConversation(context, "conversation"))
        assertNull(AgentTaskPlanStore(root, ordinary.runId).snapshot().previousPlan)
        assertNull(TaskContinuationRuntime.forConversation(context, "another-conversation"))
    }

    private fun context(root: File): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    private fun checkpoint() = AgentRunCheckpointStore.Checkpoint(
        runId = "original", ownerInstanceId = "old-process",
        handoff = AgentRuntimeWire.EntryHandoff("original", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
        events = emptyList(), createdAt = 10L, updatedAt = 20L,
    )

    private fun request(runId: String) = AgentRuntimeWire.RunRequest(
        runId = runId, prompt = "继续当前新问题",
        config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
        images = emptyList(), modelSessionId = "conversation",
        handoff = AgentRuntimeWire.EntryHandoff(runId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
    )
}
