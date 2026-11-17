package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.ui.model.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunInsightsTest {
    private val run = "run-one"

    @Test
    fun firstHttpResponseAndIncrementHaveDistinctObservedLatencies() {
        var state = AgentRunInsights.start(run, 1_000)
        state = apply(state, AgentEvent.RunStarted(0, 0, 8, false), 1_005)
        state = apply(state, AgentEvent.RoundStarted(1, 4), 1_010)
        state = apply(state, AgentEvent.ProviderRequestStarted(1), 1_100)
        state = apply(state, AgentEvent.ProviderResponseStarted(1, 200), 1_250)
        state = apply(state, delta(1, ""), 1_300)
        assertNull(state.firstIncrementLatencyMillis)
        state = apply(state, delta(1, "hello"), 1_480)
        assertEquals(250L, state.firstResponseLatencyMillis)
        assertEquals(480L, state.firstIncrementLatencyMillis)
        assertEquals(150L, state.requests.single().responseLatencyMillis)
        assertEquals(380L, state.requests.single().firstIncrementLatencyMillis)
        assertEquals(8, state.declaredToolCount)
        assertEquals(4, state.messageCount)
    }

    @Test
    fun sameRoundAttemptsProjectActualHeadersDeltaStatusAndUnknowns() {
        var state = AgentRunInsights.start(run, 1_000)
        state = apply(state, AgentEvent.ProviderRequestStarted(1), 1_100)
        state = apply(state, AgentEvent.ProviderResponseStarted(1, 503), 1_250)
        state = apply(state, AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "HTTP_503"), 1_400)
        state = apply(state, AgentEvent.ProviderRequestStarted(1), 3_400)
        state = apply(state, AgentEvent.ProviderResponseStarted(1, 200), 3_500)
        state = apply(state, delta(1, ""), 3_600)
        state = apply(state, delta(1, "actual delta"), 3_850)
        state = apply(state, AgentEvent.AssistantReceived(1, 12, "", emptyList()), 4_000)
        state = apply(state, AgentEvent.UsageReceived(1, AgentTokenUsage(requestCount = 99)), 4_010)
        val ui = state.toUi(9_000)
        assertEquals(2, ui.requests.size)
        assertEquals(1, ui.retryCount)
        assertEquals(250L, ui.firstResponseMillis)
        assertEquals(2_850L, ui.firstIncrementMillis)
        val failed = ui.requests[0]
        assertEquals(1, failed.attempt)
        assertEquals(300L, failed.durationMillis)
        assertEquals(150L, failed.responseMillis)
        assertNull(failed.firstIncrementMillis)
        assertEquals(503, failed.httpCode)
        assertEquals("HTTP_503", failed.retryReason)
        val completed = ui.requests[1]
        assertEquals(2, completed.attempt)
        assertEquals(600L, completed.durationMillis)
        assertEquals(100L, completed.responseMillis)
        assertEquals(450L, completed.firstIncrementMillis)
        assertEquals(200, completed.httpCode)
        assertNull(completed.retryReason)
        assertNull(ui.usage.inputTokens)
        assertNull(ui.usage.outputTokens)
    }

    @Test
    fun requestProjectionKeepsMissingStartAndResponseUnknown() {
        var state = AgentRunInsights.start(run, 100)
        state = apply(state, delta(1, "actual delta"), 200)
        val request = state.toUi(400).requests.single()
        assertNull(request.durationMillis)
        assertNull(request.responseMillis)
        assertNull(request.firstIncrementMillis)
        assertNull(request.httpCode)
    }

    @Test
    fun repeatedResponseAndDeltaDoNotReplaceFirstObservation() {
        var state = AgentRunInsights.start(run, 0)
        state = apply(state, AgentEvent.ProviderRequestStarted(1), 100)
        state = apply(state, AgentEvent.ProviderResponseStarted(1, 200), 200)
        state = apply(state, AgentEvent.ProviderResponseStarted(1, 200), 240)
        state = apply(state, delta(1, "a"), 300)
        state = apply(state, delta(1, "b"), 450)
        assertEquals(200L, state.firstResponseAtMillis)
        assertEquals(300L, state.firstIncrementAtMillis)
        assertEquals(200L, state.requests.single().responseAtMillis)
    }

    @Test
    fun roundAndToolCallIdentityKeepLocalAndHostedCallsSeparate() {
        var state = AgentRunInsights.start(run, 0)
        state = apply(state, started(1, "same", "observe_screen"), 10)
        state = apply(state, AgentEvent.HostedToolStarted(1, "same", "web_search"), 20)
        state = apply(state, finished(1, "same", "observe_screen", true), 90)
        state = apply(state, AgentEvent.HostedToolFinished(1, "same", "web_search", true), 150)
        state = apply(state, started(2, "same", "observe_screen"), 200)
        assertEquals(3, state.tools.size)
        assertEquals(80L, state.tools[0].elapsedMillis(999))
        assertEquals(130L, state.tools[1].elapsedMillis(999))
        assertEquals(AgentInsightToolStatus.RUNNING, state.tools[2].status)
        assertNull(state.tools[1].argumentsSummary)
        assertNull(state.tools[1].resultSummary)
        assertTrue(state.tools[1].hosted)
    }

    @Test
    fun hostedFailureRetainsFalseAndMissingStartHasUnknownDuration() {
        var state = AgentRunInsights.start(run, 100)
        state = apply(state, AgentEvent.HostedToolFinished(1, "missing-start", "web_search", false), 900)
        val tool = state.tools.single()
        assertNull(tool.startedAtMillis)
        assertNull(tool.elapsedMillis(1_000))
        assertEquals(AgentInsightToolStatus.FAILED, tool.status)
        assertEquals(900L, tool.finishedAtMillis)
    }

    @Test
    fun missingSuccessFieldRemainsUnknownEvenIfSummaryLooksSuccessful() {
        val state = apply(AgentRunInsights.start(run, 0),
            finished(1, "legacy", "read_file", null).copy(resultSummary = "完成"), 100)
        assertEquals(AgentInsightToolStatus.UNKNOWN, state.tools.single().status)
        assertEquals("完成", state.tools.single().resultSummary)
    }

    @Test
    fun completionDoesNotFabricateToolSuccessOrEndTime() {
        var state = apply(AgentRunInsights.start(run, 100), started(1, "pending", "fetch_url"), 150)
        state = apply(state, AgentEvent.RunFinished(1, 8), 800)
        assertEquals(AgentInsightStage.COMPLETED, state.stage)
        assertEquals(AgentInsightToolStatus.UNKNOWN, state.tools.single().status)
        assertTrue(state.tools.single().resultMissingAtRunEnd)
        assertNull(state.tools.single().finishedAtMillis)
        assertNull(state.tools.single().elapsedMillis(5_000))
        assertEquals(700L, state.toUi(5_000).elapsedMillis)
    }

    @Test
    fun cancelledFailedAndLateRunsNeverPolluteReplacementRun() {
        var old = apply(AgentRunInsights.start(run, 0), started(1, "old", "read_file"), 10)
        old = AgentRunInsights.complete(old, run, AgentInsightOutcome.CANCELLED, 100, "user stopped")
        assertEquals(AgentInsightStage.CANCELLED, old.stage)
        assertFalse(old.isRunning)
        assertSame(old, apply(old, AgentEvent.RunFailed("late"), 200))
        var current = AgentRunInsights.start("replacement", 1_000)
        assertSame(current, AgentRunInsights.apply(current, run, finished(1, "old", "read_file", true), 1_050))
        assertSame(current, AgentRunInsights.complete(current, run, AgentInsightOutcome.FAILED, 1_100, "old error"))
        current = AgentRunInsights.apply(current, "replacement", AgentEvent.RunFailed("actual failure"), 1_200)
        assertEquals(AgentInsightStage.FAILED, current.stage)
        assertEquals("actual failure", current.error)
        assertTrue(current.tools.isEmpty())
    }

    @Test
    fun duplicateStartAndFinishPreserveOriginalTimesAndPayload() {
        var state = apply(AgentRunInsights.start(run, 0), started(1, "call", "read_file"), 100)
        state = apply(state, started(1, "call", "read_file"), 150)
        state = apply(state, finished(1, "call", "read_file", true), 350)
        val terminalToolState = state
        assertEquals(100L, state.tools.single().startedAtMillis)
        assertSame(terminalToolState, apply(state, finished(1, "call", "read_file", false), 900))
        assertSame(terminalToolState, apply(state, started(1, "call", "read_file"), 1_000))
    }

    @Test
    fun backwardsCallerTimeCannotProduceNegativeOrReverseDurations() {
        var state = apply(AgentRunInsights.start(run, 1_000), started(1, "call", "read_file"), 1_100)
        state = apply(state, finished(1, "call", "read_file", true), 900)
        assertEquals(1_100L, state.updatedAtMillis)
        assertEquals(0L, state.tools.single().elapsedMillis(0))
        assertEquals(100L, state.elapsedMillis(0))
    }

    @Test
    fun boundedRetentionAndPreviewLimitsKeepLatestFacts() {
        var state = AgentRunInsights.start(run, 0)
        repeat(75) { index ->
            val text = "x".repeat(AgentRunInsights.MAX_PREVIEW_CHARS + 20)
            state = apply(state, started(index, "call-$index", "read_file").copy(argsPreview = text, command = text), index * 10L)
            state = apply(state, finished(index, "call-$index", "read_file", true).copy(resultSummary = text), index * 10L + 1)
            state = apply(state, AgentEvent.ProviderRequestStarted(index), index * 10L + 2)
            state = apply(state, AgentEvent.ModelRetryScheduled(index, 1, 3, 1_000, "TIMEOUT"), index * 10L + 3)
        }
        assertEquals(60, state.tools.size)
        assertEquals(60, state.requests.size)
        assertEquals(60, state.notices.size)
        assertEquals(15, state.droppedToolCount)
        assertEquals(15, state.droppedRequestCount)
        assertEquals(15, state.droppedNoticeCount)
        assertEquals("call-74", state.tools.last().toolCallId)
        state.tools.forEach {
            assertEquals(4_096, it.argumentsSummary?.length)
            assertEquals(4_096, it.resultSummary?.length)
            assertTrue(it.argumentsTruncated && it.commandTruncated && it.resultTruncated)
        }
    }

    @Test
    fun runTotalsReplaceStreamingAndRepeatedToolDurationEventsInsteadOfBeingAdded() {
        var state = AgentRunInsights.start(run, 0)
        state = apply(state, AgentEvent.UsageReceived(1, AgentTokenUsage(contextTokens = 150, inputTokens = 100, outputTokens = 3)), 100)
        assertNull(state.toUi(100).usage.inputTokens)
        assertEquals(100, state.toUi(100).usage.latestRequestInputTokens)
        val roundOne = AgentTokenUsage(contextTokens = 150, inputTokens = 100, outputTokens = 20,
            cachedTokens = 40, requestDurationMs = 800, firstTokenMs = 200, requestCount = 1)
        state = apply(state, AgentEvent.UsageReceived(1, roundOne), 800)
        state = apply(state, AgentEvent.UsageReceived(1, roundOne.copy(toolDurationMs = 300)), 1_100)
        state = apply(state, AgentEvent.UsageReceived(1, roundOne.copy(toolDurationMs = 300)), 1_101)
        state = apply(state, AgentEvent.UsageReceived(2, AgentTokenUsage(contextTokens = 230, inputTokens = 200, outputTokens = 4)), 1_200)
        assertEquals(100, state.toUi(1_200).usage.inputTokens)
        assertEquals(200, state.toUi(1_200).usage.latestRequestInputTokens)
        val total = AgentTokenUsage(contextTokens = 230, inputTokens = 300, outputTokens = 50,
            cachedTokens = 60, requestDurationMs = 1_400, firstTokenMs = 350, requestCount = 2,
            toolDurationMs = 300, reasoningTokens = 7)
        state = apply(state, AgentEvent.UsageReceived(2, total), 1_600)
        assertEquals(300, state.toUi(1_600).usage.inputTokens)
        assertEquals(50, state.toUi(1_600).usage.outputTokens)
        assertEquals(2, state.toUi(1_600).usage.requestCount)
        assertEquals(300L, state.toUi(1_600).usage.toolDurationMillis)
        assertEquals(230, state.toUi(1_600).usage.contextTokens)
        assertEquals(7, state.toUi(1_600).usage.latestReasoningTokens)
        state = apply(state, AgentEvent.UsageReceived(1, roundOne), 1_700)
        assertEquals(300, state.toUi(1_700).usage.inputTokens)
        assertEquals(230, state.toUi(1_700).usage.contextTokens)
    }

    @Test
    fun absentReasoningTokensInNextRequestDoNotReusePreviousRequestReasoning() {
        var state = AgentRunInsights.start(run, 0)
        state = apply(state, AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 10, reasoningTokens = 8)), 10)
        state = apply(state, AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 20)), 20)
        assertNull(state.toUi(30).usage.latestReasoningTokens)
    }

    @Test
    fun retryAndCompactionAreObservedStagesRatherThanInventedProgress() {
        var state = apply(AgentRunInsights.start(run, 0), AgentEvent.ProviderRequestStarted(1), 20)
        val retry = AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "MODEL_TIMEOUT")
        state = apply(state, retry, 300)
        assertEquals(AgentInsightStage.RETRY_WAIT, state.stage)
        assertEquals(2_000, state.notices.single().delayMillis)
        assertEquals("MODEL_TIMEOUT", state.requests.single().retryReason)
        state = apply(state, AgentEvent.RoundStarted(2, 4), 2_300)
        assertEquals(AgentInsightStage.WAITING_MODEL, state.stage)
        state = apply(state, AgentEvent.ContextCompaction("compact", "started", tokensBefore = 30_000), 2_400)
        assertEquals(AgentInsightStage.COMPACTING, state.stage)
        state = apply(state, AgentEvent.ContextCompaction("compact", "completed", tokensAfter = 9_000), 2_600)
        val compact = state.notices.last()
        assertEquals(30_000, compact.tokensBefore)
        assertEquals(9_000, compact.tokensAfter)
        assertEquals(AgentInsightStage.WAITING_MODEL, state.stage)
        assertNull(state.toUi(3_000).usage.contextTokens)
    }

    @Test
    fun subagentSnapshotRetainsOnlyObservedFieldsAndParsesToolCount() {
        var state = apply(AgentRunInsights.start(run, 0), started(1, "agent-call", "subagent"), 10)
        val snapshot = """{"status":"running","currentAction":"read file","steps":[{"kind":"thinking"},{"kind":"tool"},{"kind":"tool"}],"error":null,"output":""}"""
        state = apply(state, AgentEvent.SubagentProgress(1, "agent-call", "child-id", "explore", snapshot), 30)
        val tool = state.tools.single()
        assertEquals(AgentInsightSource.AGENT, tool.source)
        assertEquals(AgentInsightStage.EXECUTING_AGENT, state.stage)
        assertEquals(2, tool.subagent?.observedToolSteps)
        assertEquals("read file", state.toUi(50).currentAction)
        assertNull(tool.subagent?.error)
        assertNull(tool.subagent?.outputPreview)
    }

    @Test
    fun earlierRoundToolCannotReplaceCurrentRoundAction() {
        var state = apply(AgentRunInsights.start(run, 0), AgentEvent.RoundStarted(2, 5), 100)
        state = apply(state, delta(2, "current response"), 110)
        state = apply(state, started(1, "late-old-call", "old_tool"), 120)
        assertEquals(AgentInsightStage.RESPONDING, state.stage)
        assertEquals(2, state.round)
        assertNull(state.toUi(130).currentAction)
        state = apply(state, finished(1, "late-old-call", "old_tool", true), 140)
        assertEquals(AgentInsightStage.RESPONDING, state.stage)
        assertEquals(2, state.round)
    }

    @Test
    fun toolIdsWithIdenticalTruncatedPreviewStillHaveSeparateIdentity() {
        val prefix = "same".repeat(100)
        var state = apply(AgentRunInsights.start(run, 0), started(1, prefix + "a", "read_file"), 10)
        state = apply(state, started(1, prefix + "b", "read_file"), 20)
        state = apply(state, finished(1, prefix + "a", "read_file", true), 30)
        assertEquals(2, state.tools.size)
        assertEquals(AgentInsightToolStatus.SUCCESS, state.tools[0].status)
        assertEquals(AgentInsightToolStatus.RUNNING, state.tools[1].status)
    }

    @Test
    fun sourceClassificationRequiresKnownToolContracts() {
        assertEquals(AgentInsightSource.MCP, AgentRunInsights.sourceFor("mcp_ab12_search_1234abcd"))
        assertEquals(AgentInsightSource.MCP, AgentRunInsights.sourceFor("mcp_call", hosted = true))
        assertEquals(AgentInsightSource.SKILL, AgentRunInsights.sourceFor("skills_read"))
        assertEquals(AgentInsightSource.AGENT, AgentRunInsights.sourceFor("subagent"))
        assertEquals(AgentInsightSource.UNKNOWN, AgentRunInsights.sourceFor(""))
        assertEquals(AgentInsightSource.TOOL, AgentRunInsights.sourceFor("my_mcp_magic"))
        assertEquals(AgentInsightSource.TOOL, AgentRunInsights.sourceFor("skills_custom_name"))
    }

    private fun apply(state: AgentRunInsightState, event: AgentEvent, at: Long) = AgentRunInsights.apply(state, run, event, at)
    private fun started(round: Int, id: String, name: String) = AgentEvent.ToolStarted(round, id, name, "actual args preview")
    private fun finished(round: Int, id: String, name: String, success: Boolean?) =
        AgentEvent.ToolFinished(round, id, name, "actual result preview", 0, 0, success)
    private fun delta(round: Int, text: String) = AgentEvent.AssistantBlockDelta(round, AgentEvent.AssistantBlockKind.TEXT, 0, text.length, text)
}
