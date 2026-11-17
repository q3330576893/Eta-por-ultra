package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.ui.model.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskPlanInsightTest {
    @Test
    fun explicitPlanToolResultIsProjectedBeforePreviewIsTruncated() {
        val note = "actual note ".repeat(500)
        val json = payload(2, """[{"id":"a","title":"Read current screen","status":"in_progress","completion_note":"$note"}]""")
        val state = AgentRunInsights.apply(AgentRunInsights.start("run", 0), "run", result(json), 10)
        assertEquals(2L, state.plan?.revision)
        assertEquals(1, state.plan?.steps?.size)
        assertEquals(AgentTaskPlanStepStatus.IN_PROGRESS, state.plan?.steps?.single()?.status)
        assertEquals(1_024, state.plan?.steps?.single()?.completionNote?.length)
        assertEquals(4_096, state.tools.single().resultSummary?.length)
        assertTrue(state.tools.single().resultTruncated)
        assertEquals(state.plan, state.toUi(30).plan)
    }

    @Test
    fun ordinaryResultOrUnsuccessfulToolNeverCreatesAPlan() {
        val raw = payload(1)
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(raw).copy(name = "read_file")))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(raw).copy(success = false)))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(raw).copy(success = null)))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(raw.replace("eta.task_plan", "other.data"))))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(raw.replace("\"ok\":true", "\"ok\":false"))))
    }

    @Test
    fun foreignRunMalformedPayloadAndUnsupportedSchemaRemainUnknown() {
        assertNull(AgentTaskPlanInsightParser.fromEvent("another-run", result(payload(1))))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result("{malformed")))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(payload(1).replace("\"schema_version\":1", "\"schema_version\":2"))))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result("x".repeat(20_481))))
    }

    @Test
    fun priorRevisionCannotReplaceNewerPlan() {
        var state = AgentRunInsights.start("run", 0)
        state = AgentRunInsights.apply(state, "run", result(payload(5)).copy(toolCallId = "new"), 10)
        state = AgentRunInsights.apply(state, "run", result(payload(3)).copy(toolCallId = "old"), 20)
        assertEquals(5L, state.plan?.revision)
    }

    @Test
    fun completedStepIsOnlyAProducerReportAndMayOmitReceiptIdsInTraceProjection() {
        val raw = payload(1, """[{"id":"a","title":"Read screen","status":"completed","completion_note":"Tool returned the requested screen"}]""")
        val plan = AgentTaskPlanInsightParser.fromEvent("run", result(raw))
        assertEquals(AgentTaskPlanStepStatus.COMPLETED, plan?.steps?.single()?.status)
        assertTrue(plan?.steps?.single()?.evidenceToolCallIds.orEmpty().isEmpty())
    }

    @Test
    fun optionalReceiptReferencesAreRetainedBoundedAndDeduplicated() {
        val raw = payload(1, """[{"id":"a","title":"Read screen","status":"completed","evidence_tool_call_ids":["receipt-1","receipt-1","receipt-2"]}]""")
        val plan = AgentTaskPlanInsightParser.fromEvent("run", result(raw))
        assertEquals(listOf("receipt-1", "receipt-2"), plan?.steps?.single()?.evidenceToolCallIds)
    }

    @Test
    fun tooManyStepsDuplicateIdsAndUnknownStatusAreRejected() {
        val step = """{"id":"a","title":"Step","status":"pending"}"""
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(payload(1, "[" + List(21) { step }.joinToString() + "]"))))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(payload(1, "[$step,$step]"))))
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(payload(1, step.replace("pending", "assumed_done").let { "[$it]" }))))
    }

    @Test
    fun newRunHasNoPreviousPlanAndCancelledRunIgnoresLatePlan() {
        var state = AgentRunInsights.start("run", 0)
        state = AgentRunInsights.apply(state, "run", result(payload(1)), 10)
        state = AgentRunInsights.complete(state, "run", AgentInsightOutcome.CANCELLED, 20)
        state = AgentRunInsights.apply(state, "run", result(payload(9)).copy(toolCallId = "late"), 30)
        assertEquals(1L, state.plan?.revision)
        assertNull(AgentRunInsights.start("new-run", 100).plan)
    }

    @Test
    fun blockedFailedAndCancelledRemainTruthfulProtocolOneStates() {
        val statuses = listOf("blocked", "failed", "cancelled")
        val steps = statuses.mapIndexed { index, status ->
            """{"id":"s$index","title":"Step $index","status":"$status"}"""
        }.joinToString(prefix = "[", postfix = "]")
        val plan = AgentTaskPlanInsightParser.fromEvent("run", result(payload(1, steps)))
        assertEquals(listOf(AgentTaskPlanStepStatus.BLOCKED, AgentTaskPlanStepStatus.FAILED,
            AgentTaskPlanStepStatus.CANCELLED), plan?.steps?.map { it.status })
    }

    @Test
    fun previousPlanKeepsItsOriginalRunAndReadOnlySourceOutsideCurrentSteps() {
        val history = """, "previous_plan":{"run_id":"old-run","revision":7,"title":"Original","steps":[{"id":"done","title":"Historical report","status":"completed"}]},"previous_plan_source":{"original_run_id":"old-run","continuation_revision":2,"captured_at":10,"reason":"runtime_interrupted","read_only":true,"receipts_executable":false},"previous_receipt_refs":[{"tool_call_id":"old-receipt"}]}"""
        val raw = payload(1).dropLast(1) + history
        val plan = AgentTaskPlanInsightParser.fromEvent("run", result(raw))!!
        assertEquals("run", plan.runId)
        assertEquals(AgentTaskPlanStepStatus.PENDING, plan.steps.single().status)
        assertEquals("old-run", plan.previousPlan?.originalRunId)
        assertEquals("old-run", plan.previousPlan?.plan?.runId)
        assertEquals(AgentTaskPlanStepStatus.COMPLETED, plan.previousPlan?.plan?.steps?.single()?.status)
        assertEquals(listOf("old-receipt"), plan.previousPlan?.receiptRefs)
        assertNull(AgentTaskPlanInsightParser.fromEvent("run", result(raw.replace("\"read_only\":true", "\"read_only\":false"))))
    }

    private fun result(raw: String) = AgentEvent.ToolFinished(1, "plan", "update_plan", raw, 0, 0, true)
    private fun payload(revision: Int, steps: String = """[{"id":"a","title":"Read screen","status":"pending"}]""") =
        """{"ok":true,"kind":"eta.task_plan","schema_version":1,"plan":{"run_id":"run","revision":$revision,"title":"Current task","steps":$steps,"updated_at":123}}"""
}
