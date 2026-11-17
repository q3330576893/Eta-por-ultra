package io.github.mangi.eta.agent.planning

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskContinuationStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun ownerMismatchCannotReadOrClaimAnotherConversationRecord() {
        val root = temporary.newFolder()
        val store = TaskContinuationStore(root)
        store.record("original", "conversation-a", emptySnapshot(), createdAt = 10L)

        assertNull(store.forConversation("conversation-b"))
        fails("CONTINUATION_OWNER_MISMATCH") {
            store.get("original", "conversation-b")
        }
        fails("CONTINUATION_OWNER_MISMATCH") {
            store.claim("original", "conversation-b", expectedRevision = 1L, newRunId = "new")
        }
        assertEquals(TaskContinuationStatus.READY, store.forConversation("conversation-a")?.status)
    }

    @Test
    fun claimAndAckAreCasAndReplaySafeButAnotherNewRunCannotReuseClaim() {
        val root = temporary.newFolder()
        val store = TaskContinuationStore(root)
        store.record("original", "conversation", emptySnapshot(), createdAt = 10L)

        val claimed = store.claim("original", "conversation", 1L, "new-run")
        assertEquals(TaskContinuationStatus.CLAIMED, claimed.status)
        assertEquals(2L, claimed.revision)
        assertEquals(claimed, store.claim("original", "conversation", 2L, "new-run"))
        fails("CONTINUATION_REVISION_CONFLICT") {
            store.claim("original", "conversation", 1L, "other-run")
        }
        fails("CONTINUATION_CLAIM_MISMATCH") {
            store.claim("original", "conversation", 2L, "other-run")
        }

        val ack = store.acknowledgeAfterStart("original", "conversation", 2L, "new-run")
        assertEquals(TaskContinuationStatus.ACK, ack.status)
        assertEquals(3L, ack.revision)
        assertEquals(ack, store.acknowledgeAfterStart("original", "conversation", 3L, "new-run"))
        assertNull(store.forConversation("conversation"))
        fails("CONTINUATION_ACKNOWLEDGED") {
            store.claim("original", "conversation", 3L, "new-run")
        }
    }

    @Test
    fun corruptJournalIsRejectedAndLeftUntouched() {
        val root = temporary.newFolder()
        val store = TaskContinuationStore(root)
        store.record("original", "conversation", emptySnapshot())
        val file = root.resolve("agent_task_workspace").resolve(TaskContinuationStore.FILENAME)
        val corrupt = "{\"schema_version\":1,\"kind\":\"eta.task_continuations\",\"records\":["
        Files.write(file.toPath(), corrupt.toByteArray(StandardCharsets.UTF_8))

        fails("STORE_CORRUPT") { store.forConversation("conversation") }
        assertEquals(corrupt, Files.readString(file.toPath()))
    }

    @Test
    fun recordCountIsBoundedAndReadyRecordsCannotBeEvicted() {
        val root = temporary.newFolder()
        val store = TaskContinuationStore(root)
        repeat(TaskContinuationStore.MAX_RECORDS) { index ->
            store.record("original-$index", "conversation", emptySnapshot(), createdAt = index.toLong())
        }
        fails("CONTINUATION_LIMIT") {
            store.record("original-overflow", "conversation", emptySnapshot(), createdAt = 99L)
        }
        assertEquals(TaskContinuationStore.MAX_RECORDS,
            (0 until TaskContinuationStore.MAX_RECORDS).count { index ->
                store.get("original-$index", "conversation") != null
            })
    }

    @Test
    fun acknowledgedTombstoneIsEvictedBeforeReadyRecordAndDuplicateOriginalRunStaysAck() {
        val root = temporary.newFolder()
        val store = TaskContinuationStore(root)
        store.record("old", "conversation", emptySnapshot(), createdAt = 1L)
        val claimed = store.claim("old", "conversation", 1L, "new")
        store.acknowledgeAfterStart("old", "conversation", claimed.revision, "new")
        val duplicate = store.record("old", "conversation", emptySnapshot(), createdAt = 101L)
        assertEquals(TaskContinuationStatus.ACK, duplicate.status)
        repeat(TaskContinuationStore.MAX_RECORDS - 1) { index ->
            store.record("ready-$index", "conversation", emptySnapshot(), createdAt = (index + 2).toLong())
        }
        val replacement = store.record("replacement", "conversation", emptySnapshot(), createdAt = 100L)
        assertEquals("replacement", replacement.originalRunId)
        assertNull(store.get("old", "conversation"))
        assertEquals(TaskContinuationStatus.READY, store.get("ready-0", "conversation")?.status)
    }

    @Test
    fun continuationImportsOnlyPendingStepsAndNeverReusesOldReceipts() {
        val root = temporary.newFolder()
        val original = AgentTaskPlanStore(root, "original")
        original.recordSuccessfulTool("old-receipt", "observe_screen")
        original.update(0L, "任务", listOf(
            TaskPlanStep("done", "已完成", TaskPlanStatus.COMPLETED, "原运行观察到结果", listOf("old-receipt")),
            TaskPlanStep("next", "继续观察", TaskPlanStatus.IN_PROGRESS),
            TaskPlanStep("blocked", "等待条件", TaskPlanStatus.BLOCKED),
        ))
        original.recordSuccessfulTool("old-receipt", "observe_screen")
        val record = TaskContinuationStore(root).record("original", "conversation", original.snapshot(), createdAt = 20L)

        val claimed = TaskContinuationRuntime.claimForStart(root, "conversation", "new-run")!!
        assertEquals(record.revision + 1L, claimed.revision)
        val imported = AgentTaskPlanStore(root, "new-run").snapshot()
        assertEquals("new-run", imported.plan?.runId)
        assertEquals(listOf("next", "blocked"), imported.plan?.steps?.map { it.id })
        assertTrue(imported.plan?.steps.orEmpty().all { it.status == TaskPlanStatus.PENDING })
        assertTrue(imported.evidence.isEmpty())
        assertEquals("original", imported.previousPlan?.originalRunId)
        assertEquals(TaskPlanStatus.COMPLETED, imported.previousPlan?.plan?.steps?.first()?.status)

        val attemptedCompletion = imported.plan!!.steps.map { step ->
            if (step.id == "next") step.copy(
                status = TaskPlanStatus.COMPLETED,
                completionNote = "复用旧结果",
                evidenceToolCallIds = listOf("old-receipt"),
            ) else step
        }
        fails("PLAN_EVIDENCE_REQUIRED") {
            AgentTaskPlanStore(root, "new-run").update(imported.revision, "任务", attemptedCompletion)
        }
        TaskContinuationRuntime.acknowledgeAfterStart(root, "conversation", "new-run")
        assertNull(TaskContinuationStore(root).forConversation("conversation"))
    }

    @Test
    fun planStatusesRemainProtocolOneCompatible() {
        val root = temporary.newFolder()
        val store = AgentTaskPlanStore(root, "run")
        val statuses = listOf(TaskPlanStatus.PENDING, TaskPlanStatus.IN_PROGRESS,
            TaskPlanStatus.BLOCKED, TaskPlanStatus.FAILED, TaskPlanStatus.CANCELLED)
        val steps = statuses.mapIndexed { index, status -> TaskPlanStep("step-$index", "步骤 $index", status) }
        val snapshot = store.update(0L, "状态兼容", steps)
        assertEquals(statuses, snapshot.plan?.steps?.map { it.status })
        assertEquals(statuses, AgentTaskPlanStore.parseSteps(snapshot.plan!!.toJson().getJSONArray("steps")).map { it.status })
    }

    @Test
    fun twoRestartsReadTheSameHintAndCheckpointOwnerMustMatch() {
        val root = temporary.newFolder()
        val checkpoint = AgentRunCheckpointStore.Checkpoint(
            runId = "original", ownerInstanceId = "old-process",
            handoff = AgentRuntimeWire.EntryHandoff("handoff", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            events = emptyList(), createdAt = 10L, updatedAt = 20L,
        )
        fails("CONTINUATION_OWNER_MISMATCH") {
            TaskContinuationRuntime.recordFromCheckpoint(root, checkpoint, "another")
        }
        val recorded = TaskContinuationRuntime.recordFromCheckpoint(root, checkpoint, "conversation")!!
        repeat(2) {
            val restored = TaskContinuationStore(root).forConversation("conversation")!!
            assertEquals(recorded, restored)
            assertTrue(TaskContinuationRuntime.promptForRecord(restored).contains("重新 observe_screen"))
            assertEquals(TaskContinuationStatus.READY, restored.status)
        }
        assertEquals(10L, recorded.createdAt)
        assertNull(TaskContinuationRuntime.recordFromCheckpoint(root,
            checkpoint.copy(operation = AgentRuntimeWire.OP_COMPACT), "conversation"))
    }

    @Test
    fun concurrentClaimsWithOneRevisionHaveOnlyOneWinner() {
        val root = temporary.newFolder()
        TaskContinuationStore(root).record("original", "conversation", emptySnapshot())
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (0..1).map { index -> executor.submit<String> {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                try {
                    TaskContinuationStore(root).claim("original", "conversation", 1L, "new-$index")
                    "claimed"
                } catch (error: TaskWorkspaceException) { error.code }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf("CONTINUATION_REVISION_CONFLICT", "claimed"),
                results.map { it.get(5, TimeUnit.SECONDS) }.sorted())
            assertEquals(2L, TaskContinuationStore(root).forConversation("conversation")?.revision)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun invalidFieldsAndOversizedJournalPreserveExistingRecord() {
        val root = temporary.newFolder()
        val store = TaskContinuationStore(root)
        val record = store.record("original", "conversation", emptySnapshot())
        fails("CONTINUATION_INVALID") { store.record("other", "conversation", emptySnapshot(), reason = "x".repeat(241)) }
        fails("CONTINUATION_INVALID") { TaskContinuationStore.validateRecord(record.copy(pendingStepIds = listOf("invented"))) }
        assertEquals(record, store.forConversation("conversation"))
        val file = root.resolve("agent_task_workspace").resolve(TaskContinuationStore.FILENAME)
        Files.write(file.toPath(), ByteArray(TaskContinuationStore.MAX_BYTES + 1) { 32 })
        fails("STORE_CORRUPT") { store.forConversation("conversation") }
        assertEquals((TaskContinuationStore.MAX_BYTES + 1).toLong(), file.length())
    }

    private fun emptySnapshot() = TaskPlanSnapshot(null, emptyList())

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $code")
        } catch (error: TaskWorkspaceException) {
            assertEquals(code, error.code)
        }
    }
}
