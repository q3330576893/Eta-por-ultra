package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.runtime.AgentInsightNotice
import io.github.mangi.eta.agent.runtime.AgentInsightSource
import io.github.mangi.eta.agent.runtime.AgentInsightStage
import io.github.mangi.eta.agent.runtime.AgentInsightToolStatus
import io.github.mangi.eta.agent.runtime.AgentRunInsightState
import io.github.mangi.eta.agent.runtime.AgentSubagentInsight
import io.github.mangi.eta.agent.runtime.AgentTaskPlanInsight

/** A display projection of actual Runtime observations; absent fields remain absent. */
@Immutable
internal data class AgentRunInsightsUi(
    val runId: String,
    val running: Boolean,
    val stage: AgentInsightStage,
    val currentAction: String?,
    val elapsedMillis: Long,
    val firstResponseMillis: Long?,
    val firstIncrementMillis: Long?,
    val round: Int?,
    val retainedToolCount: Int,
    val droppedToolCount: Int,
    val droppedNoticeCount: Int,
    val droppedRequestCount: Int,
    val tools: List<AgentToolInsightUi>,
    val requests: List<AgentRequestInsightUi>,
    val retryCount: Int,
    val notices: List<AgentInsightNotice>,
    val usage: AgentInsightUsageUi,
    val plan: AgentTaskPlanInsight?,
    val error: String?,
)

@Immutable
internal data class AgentToolInsightUi(
    val key: String,
    val name: String,
    val source: AgentInsightSource,
    val hosted: Boolean,
    val round: Int,
    val status: AgentInsightToolStatus,
    val elapsedMillis: Long?,
    val startOffsetMillis: Long?,
    val endOffsetMillis: Long?,
    val argumentsSummary: String?,
    val commandPreview: String?,
    val resultSummary: String?,
    val argumentsTruncated: Boolean,
    val commandTruncated: Boolean,
    val resultTruncated: Boolean,
    val imageCount: Int?,
    val resultMissingAtRunEnd: Boolean,
    val subagent: AgentSubagentInsight?,
)

@Immutable
internal data class AgentRequestInsightUi(
    val round: Int,
    val attempt: Int,
    val durationMillis: Long?,
    val responseMillis: Long?,
    val firstIncrementMillis: Long?,
    val httpCode: Int?,
    val retryReason: String?,
)

@Immutable
internal data class AgentInsightUsageUi(
    /** These are run totals only when explicitly observed in a requestCount-bearing event. */
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
    val requestCount: Int? = null,
    val modelDurationMillis: Long? = null,
    val firstTokenTotalMillis: Long? = null,
    val toolDurationMillis: Long? = null,
    val contextTokens: Int? = null,
    /** AgentLoop preserves reasoning tokens for the most recent request, not a run total. */
    val latestReasoningTokens: Int? = null,
    val latestRequestInputTokens: Int? = null,
    val latestRequestOutputTokens: Int? = null,
) {
    val hasObservations: Boolean get() = listOf(inputTokens, outputTokens, cachedTokens, requestCount,
        contextTokens, latestReasoningTokens, latestRequestInputTokens, latestRequestOutputTokens,
        modelDurationMillis, firstTokenTotalMillis, toolDurationMillis).any { it != null }
}

internal fun AgentRunInsightState.toUi(nowMillis: Long): AgentRunInsightsUi {
    val now = finishedAtMillis ?: maxOf(updatedAtMillis, nowMillis)
    val activeTool = tools.lastOrNull {
        it.status == AgentInsightToolStatus.RUNNING && it.round >= (round ?: 0)
    }
    val action = activeTool?.subagent?.currentAction?.takeIf(String::isNotBlank)
        ?: activeTool?.name?.takeIf(String::isNotBlank)
    val total = usage.cumulative
    return AgentRunInsightsUi(
        runId = runId, running = isRunning, stage = stage, currentAction = action,
        elapsedMillis = elapsedMillis(now), firstResponseMillis = firstResponseLatencyMillis,
        firstIncrementMillis = firstIncrementLatencyMillis, round = round,
        retainedToolCount = tools.size, droppedToolCount = droppedToolCount,
        droppedNoticeCount = droppedNoticeCount, droppedRequestCount = droppedRequestCount,
        tools = tools.map { tool -> AgentToolInsightUi(
            key = tool.key, name = tool.name, source = tool.source, hosted = tool.hosted, round = tool.round,
            status = tool.status, elapsedMillis = tool.elapsedMillis(now),
            startOffsetMillis = tool.startedAtMillis?.let { (it - startedAtMillis).coerceAtLeast(0) },
            endOffsetMillis = tool.finishedAtMillis?.let { (it - startedAtMillis).coerceAtLeast(0) },
            argumentsSummary = tool.argumentsSummary, commandPreview = tool.commandPreview,
            resultSummary = tool.resultSummary, argumentsTruncated = tool.argumentsTruncated,
            commandTruncated = tool.commandTruncated, resultTruncated = tool.resultTruncated,
            imageCount = tool.imageCount, resultMissingAtRunEnd = tool.resultMissingAtRunEnd,
            subagent = tool.subagent,
        ) },
        requests = requests.map { request -> AgentRequestInsightUi(
            round = request.round, attempt = request.attempt,
            durationMillis = request.durationMillis,
            responseMillis = request.responseLatencyMillis,
            firstIncrementMillis = request.firstIncrementLatencyMillis,
            httpCode = request.httpCode, retryReason = request.retryReason,
        ) },
        retryCount = notices.count { it.kind == io.github.mangi.eta.agent.runtime.AgentInsightNoticeKind.RETRY },
        notices = notices,
        usage = AgentInsightUsageUi(
            inputTokens = total?.inputTokens, outputTokens = total?.outputTokens,
            cachedTokens = total?.cachedTokens, requestCount = total?.requestCount,
            modelDurationMillis = total?.requestDurationMs, firstTokenTotalMillis = total?.firstTokenMs,
            toolDurationMillis = total?.toolDurationMs, contextTokens = usage.latestContextTokens,
            latestReasoningTokens = usage.latestReasoningTokens,
            latestRequestInputTokens = usage.latestRequest?.inputTokens,
            latestRequestOutputTokens = usage.latestRequest?.outputTokens,
        ),
        plan = plan,
        error = error,
    )
}
