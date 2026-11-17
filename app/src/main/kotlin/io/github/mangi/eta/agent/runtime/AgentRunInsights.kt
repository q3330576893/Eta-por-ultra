package io.github.mangi.eta.agent.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * A bounded, in-memory observation of one run. Times are caller-provided monotonic milliseconds.
 * A result event without a start has an unknown duration; ending a run never proves a tool succeeded.
 */
internal data class AgentRunInsightState(
    val runId: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val stage: AgentInsightStage = AgentInsightStage.STARTING,
    val round: Int? = null,
    val messageCount: Int? = null,
    val declaredToolCount: Int? = null,
    val firstResponseAtMillis: Long? = null,
    val firstIncrementAtMillis: Long? = null,
    val tools: List<AgentToolInsight> = emptyList(),
    val requests: List<AgentRequestInsight> = emptyList(),
    val notices: List<AgentInsightNotice> = emptyList(),
    val usage: AgentInsightUsage = AgentInsightUsage(),
    val plan: AgentTaskPlanInsight? = null,
    val droppedToolCount: Int = 0,
    val droppedRequestCount: Int = 0,
    val droppedNoticeCount: Int = 0,
    val error: String? = null,
) {
    val isRunning: Boolean get() = finishedAtMillis == null
    val firstResponseLatencyMillis: Long? get() = firstResponseAtMillis?.elapsedSince(startedAtMillis)
    val firstIncrementLatencyMillis: Long? get() = firstIncrementAtMillis?.elapsedSince(startedAtMillis)
    fun elapsedMillis(nowMillis: Long): Long =
        (finishedAtMillis ?: maxOf(nowMillis, updatedAtMillis)).elapsedSince(startedAtMillis)
}

internal enum class AgentInsightStage {
    STARTING, WAITING_MODEL, RESPONDING, THINKING, EXECUTING_TOOL, EXECUTING_AGENT,
    RETRY_WAIT, COMPACTING, COMPLETED, FAILED, CANCELLED,
}

internal enum class AgentInsightSource { TOOL, MCP, SKILL, AGENT, UNKNOWN }
internal enum class AgentInsightToolStatus { RUNNING, SUCCESS, FAILED, UNKNOWN }
internal enum class AgentInsightOutcome { COMPLETED, FAILED, CANCELLED }

internal data class AgentToolInsight(
    val key: String,
    val round: Int,
    val toolCallId: String,
    val name: String,
    val source: AgentInsightSource,
    val hosted: Boolean,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val status: AgentInsightToolStatus = AgentInsightToolStatus.RUNNING,
    /** Only the summaries actually supplied by AgentEvent are retained, never invented raw payloads. */
    val argumentsSummary: String? = null,
    val commandPreview: String? = null,
    val resultSummary: String? = null,
    val argumentsTruncated: Boolean = false,
    val commandTruncated: Boolean = false,
    val resultTruncated: Boolean = false,
    val imageCount: Int? = null,
    val imageBytes: Int? = null,
    val resultMissingAtRunEnd: Boolean = false,
    val subagent: AgentSubagentInsight? = null,
) {
    fun elapsedMillis(nowMillis: Long): Long? {
        val start = startedAtMillis ?: return null
        val end = finishedAtMillis ?: if (status == AgentInsightToolStatus.RUNNING) nowMillis else return null
        return end.elapsedSince(start)
    }
}

internal data class AgentSubagentInsight(
    val id: String,
    val type: String,
    val status: String? = null,
    val currentAction: String? = null,
    val observedToolSteps: Int? = null,
    val error: String? = null,
    val outputPreview: String? = null,
)

internal data class AgentRequestInsight(
    val round: Int,
    val attempt: Int = 1,
    val startedAtMillis: Long? = null,
    val responseAtMillis: Long? = null,
    val firstIncrementAtMillis: Long? = null,
    val httpCode: Int? = null,
    val retryReason: String? = null,
    val endedAtMillis: Long? = null,
) {
    val durationMillis: Long? get() = startedAtMillis?.let { start -> endedAtMillis?.elapsedSince(start) }
    val responseLatencyMillis: Long? get() = startedAtMillis?.let { start -> responseAtMillis?.elapsedSince(start) }
    val firstIncrementLatencyMillis: Long? get() = startedAtMillis?.let { start -> firstIncrementAtMillis?.elapsedSince(start) }

    fun isActive(): Boolean = endedAtMillis == null

}

internal enum class AgentInsightNoticeKind { RETRY, COMPACTION, HISTORY_TRIMMED }
internal data class AgentInsightNotice(
    val key: String,
    val kind: AgentInsightNoticeKind,
    val phase: String,
    val observedAtMillis: Long,
    val round: Int? = null,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
    val delayMillis: Int? = null,
    val reason: String? = null,
    val tokensBefore: Int? = null,
    val tokensAfter: Int? = null,
    val omittedMessages: Int? = null,
    val omittedUserRounds: Int? = null,
)

/**
 * AgentLoop emits request snapshots during streaming, then a run-total snapshot (requestCount set).
 * Repeated totals, including publishToolDuration, replace/merge observations instead of being added.
 * Reasoning/context in that event are still the latest request's values, not run totals.
 */
internal data class AgentInsightUsage(
    val cumulative: AgentTokenUsage? = null,
    val cumulativeRound: Int? = null,
    val latestRequest: AgentTokenUsage? = null,
    val latestRequestRound: Int? = null,
    val latestContextTokens: Int? = null,
    val latestReasoningTokens: Int? = null,
)

internal object AgentRunInsights {
    const val MAX_TOOL_RECORDS = 60
    const val MAX_REQUEST_RECORDS = 60
    const val MAX_NOTICE_RECORDS = 60
    const val MAX_PREVIEW_CHARS = 4_096
    private const val MAX_SUBAGENT_SNAPSHOT_CHARS = 65_536

    fun start(runId: String, nowMillis: Long): AgentRunInsightState {
        require(runId.isNotBlank()) { "A run identity is required" }
        require(nowMillis >= 0) { "Monotonic time must be non-negative" }
        return AgentRunInsightState(runId, nowMillis, nowMillis)
    }

    /** Supply the enclosing runId from the Runtime callback; late/foreign/terminal events are ignored. */
    fun apply(
        state: AgentRunInsightState,
        runId: String,
        event: AgentEvent,
        nowMillis: Long,
    ): AgentRunInsightState {
        if (runId != state.runId || !state.isRunning) return state
        val at = maxOf(state.updatedAtMillis, nowMillis)
        val updated = when (event) {
            is AgentEvent.RunStarted -> state.copy(declaredToolCount = event.toolCount)
            is AgentEvent.RoundStarted -> if (event.round < (state.round ?: 0)) state else state.copy(
                round = event.round, messageCount = event.messageCount, stage = AgentInsightStage.WAITING_MODEL,
            )
            is AgentEvent.ProviderRequestStarted -> requestStarted(state, event.round, at)
            is AgentEvent.ProviderResponseStarted -> responseStarted(state, event, at)
            is AgentEvent.AssistantBlockStart -> state.withAssistantStage(event.round, event.kind)
            is AgentEvent.AssistantBlockDelta -> if (event.delta.isEmpty()) state else {
                val request = state.activeRequest(event.round)
                    ?: AgentRequestInsight(event.round, state.nextAttempt(event.round))
                state.withAssistantStage(event.round, event.kind).copy(
                    firstIncrementAtMillis = state.firstIncrementAtMillis ?: at,
                ).upsertRequest(request.copy(firstIncrementAtMillis = request.firstIncrementAtMillis ?: at))
            }
            is AgentEvent.AssistantReceived -> requestFinished(state, event.round, at)
            is AgentEvent.ToolStarted -> startTool(state, event.round, event.toolCallId, event.name, false, at,
                event.argsPreview, event.command)
            is AgentEvent.ToolFinished -> {
                val plan = AgentTaskPlanInsightParser.fromEvent(runId, event)
                    ?.takeIf { it.revision >= (state.plan?.revision ?: -1L) }
                val withPlan = if (plan != null) state.copy(plan = plan) else state
                finishTool(withPlan, event.round, event.toolCallId, event.name, false, at,
                    event.success, event.resultSummary, event.command, event.imageCount, event.imageBytes)
            }
            is AgentEvent.HostedToolStarted -> startTool(state, event.round, event.toolCallId, event.name, true, at)
            is AgentEvent.HostedToolFinished -> finishTool(state, event.round, event.toolCallId, event.name, true, at,
                event.success)
            is AgentEvent.SubagentProgress -> subagentProgress(state, event)
            is AgentEvent.UsageReceived -> usageReceived(state, event)
            is AgentEvent.ModelRetryScheduled -> retryScheduled(state, event, at)
            is AgentEvent.ContextCompaction -> compaction(state, event, at)
            is AgentEvent.HistoryTrimmed -> state.upsertNotice(AgentInsightNotice(
                key = "history:$at", kind = AgentInsightNoticeKind.HISTORY_TRIMMED, phase = "observed",
                observedAtMillis = at, omittedMessages = event.omittedMessages,
                omittedUserRounds = event.omittedUserRounds,
            ))
            is AgentEvent.RunFinished -> complete(state.copy(round = maxOf(state.round ?: 0, event.round)),
                runId, AgentInsightOutcome.COMPLETED, at)
            is AgentEvent.RunFailed -> complete(state, runId, AgentInsightOutcome.FAILED, at, event.reason)
            is AgentEvent.AssistantBlockEnd, is AgentEvent.SearchSuggestions,
            is AgentEvent.UserSupplementReceived, is AgentEvent.ToolImagesAttached -> state
        }
        return if (updated == state) state else updated.copy(updatedAtMillis = at)
    }

    fun complete(
        state: AgentRunInsightState,
        runId: String,
        outcome: AgentInsightOutcome,
        nowMillis: Long,
        detail: String? = null,
    ): AgentRunInsightState {
        if (runId != state.runId || !state.isRunning) return state
        val at = maxOf(state.updatedAtMillis, nowMillis)
        return state.copy(
            finishedAtMillis = at,
            updatedAtMillis = at,
            stage = when (outcome) {
                AgentInsightOutcome.COMPLETED -> AgentInsightStage.COMPLETED
                AgentInsightOutcome.FAILED -> AgentInsightStage.FAILED
                AgentInsightOutcome.CANCELLED -> AgentInsightStage.CANCELLED
            },
            error = detail?.take(MAX_PREVIEW_CHARS),
            tools = state.tools.map { tool ->
                if (tool.status == AgentInsightToolStatus.RUNNING) tool.copy(
                    status = AgentInsightToolStatus.UNKNOWN,
                    resultMissingAtRunEnd = true,
                ) else tool
            },
        )
    }

    /** mcp_<server>_<tool>_<digest> is the exact McpRunSnapshot.modelToolName contract. */
    fun sourceFor(name: String, hosted: Boolean = false): AgentInsightSource = when {
        name.isBlank() -> AgentInsightSource.UNKNOWN
        name in setOf("subagent", "subagent_profile") -> AgentInsightSource.AGENT
        name in SKILL_TOOLS -> AgentInsightSource.SKILL
        MCP_LOCAL_NAME.matches(name) || hosted && name in setOf("mcp_call", "MCP 工具") -> AgentInsightSource.MCP
        else -> AgentInsightSource.TOOL // Only a generic tool dispatch is established by this event.
    }

    private fun requestStarted(state: AgentRunInsightState, round: Int, at: Long): AgentRunInsightState {
        // Every provider request start is an observed attempt. Repeated starts in one round
        // are kept separately so a retry cannot overwrite the previous attempt.
        val previous = state.activeRequest(round)
        val closed = if (previous == null) state else state.upsertRequest(previous.copy(endedAtMillis = at))
        val request = AgentRequestInsight(
            round = round,
            attempt = closed.nextAttempt(round),
            startedAtMillis = at,
        )
        val updated = closed.upsertRequest(request)
        return if (round < (state.round ?: 0)) updated else updated.copy(round = round, stage = AgentInsightStage.WAITING_MODEL)
    }

    private fun responseStarted(state: AgentRunInsightState, event: AgentEvent.ProviderResponseStarted, at: Long): AgentRunInsightState {
        val existing = state.activeRequest(event.round)
            ?: AgentRequestInsight(event.round, attempt = state.nextAttempt(event.round))
        val updated = state.copy(firstResponseAtMillis = state.firstResponseAtMillis ?: at)
            .upsertRequest(existing.copy(
                responseAtMillis = existing.responseAtMillis ?: at,
                httpCode = existing.httpCode ?: event.httpCode,
            ))
        return if (event.round < (state.round ?: 0)) updated else updated.copy(round = event.round, stage = AgentInsightStage.RESPONDING)
    }

    private fun requestFinished(state: AgentRunInsightState, round: Int, at: Long): AgentRunInsightState {
        val request = state.activeRequest(round) ?: return state
        return state.upsertRequest(request.copy(endedAtMillis = request.endedAtMillis ?: at))
    }

    private fun AgentRunInsightState.withAssistantStage(round: Int, kind: AgentEvent.AssistantBlockKind): AgentRunInsightState {
        if (round < (this.round ?: 0)) return this
        val next = if (kind == AgentEvent.AssistantBlockKind.THINKING) AgentInsightStage.THINKING else AgentInsightStage.RESPONDING
        return copy(round = round, stage = next)
    }

    private fun startTool(
        state: AgentRunInsightState, round: Int, id: String, name: String, hosted: Boolean, at: Long,
        args: String? = null, command: String? = null,
    ): AgentRunInsightState {
        val key = toolKey(round, id, hosted)
        val previous = state.tools.lastOrNull { it.key == key }
        if (previous?.finishedAtMillis != null) return state
        val tool = (previous ?: AgentToolInsight(key, round, id.take(256), name.take(256), sourceFor(name, hosted), hosted)).copy(
            startedAtMillis = previous?.startedAtMillis ?: at,
            argumentsSummary = args?.take(MAX_PREVIEW_CHARS) ?: previous?.argumentsSummary,
            argumentsTruncated = (args?.length ?: 0) > MAX_PREVIEW_CHARS || previous?.argumentsTruncated == true,
            commandPreview = command?.take(MAX_PREVIEW_CHARS) ?: previous?.commandPreview,
            commandTruncated = (command?.length ?: 0) > MAX_PREVIEW_CHARS || previous?.commandTruncated == true,
        )
        val updated = state.upsertTool(tool)
        return if (round < (state.round ?: 0)) updated
        else updated.copy(round = round).withExecutingStage()
    }

    private fun finishTool(
        state: AgentRunInsightState, round: Int, id: String, name: String, hosted: Boolean, at: Long,
        success: Boolean?, result: String? = null, command: String? = null, images: Int? = null, imageBytes: Int? = null,
    ): AgentRunInsightState {
        val key = toolKey(round, id, hosted)
        val previous = state.tools.lastOrNull { it.key == key }
        if (previous?.finishedAtMillis != null) return state
        val tool = (previous ?: AgentToolInsight(key, round, id.take(256), name.take(256), sourceFor(name, hosted), hosted)).copy(
            finishedAtMillis = at,
            status = when (success) { true -> AgentInsightToolStatus.SUCCESS; false -> AgentInsightToolStatus.FAILED; null -> AgentInsightToolStatus.UNKNOWN },
            resultSummary = result?.take(MAX_PREVIEW_CHARS),
            resultTruncated = (result?.length ?: 0) > MAX_PREVIEW_CHARS,
            commandPreview = command?.take(MAX_PREVIEW_CHARS) ?: previous?.commandPreview,
            commandTruncated = (command?.length ?: 0) > MAX_PREVIEW_CHARS || previous?.commandTruncated == true,
            imageCount = images,
            imageBytes = imageBytes,
        )
        val updated = state.upsertTool(tool)
        if (round < (state.round ?: 0)) return updated
        val withRound = updated.copy(round = maxOf(round, state.round ?: 0))
        return if (withRound.tools.any { it.status == AgentInsightToolStatus.RUNNING }) withRound.withExecutingStage()
        else withRound.copy(stage = AgentInsightStage.WAITING_MODEL)
    }

    private fun subagentProgress(state: AgentRunInsightState, event: AgentEvent.SubagentProgress): AgentRunInsightState {
        val key = toolKey(event.round, event.toolCallId, false)
        val previous = state.tools.lastOrNull { it.key == key }
        if (previous?.finishedAtMillis != null) return state
        val snapshot = parseSubagent(event)
        val tool = (previous ?: AgentToolInsight(key, event.round, event.toolCallId.take(256), "subagent", AgentInsightSource.AGENT, false))
            .copy(subagent = snapshot)
        val updated = state.upsertTool(tool)
        return if (event.round < (state.round ?: 0)) updated
        else updated.copy(round = event.round).withExecutingStage()
    }

    private fun parseSubagent(event: AgentEvent.SubagentProgress): AgentSubagentInsight {
        val objectValue = if (event.snapshotJson.length > MAX_SUBAGENT_SNAPSHOT_CHARS) null else runCatching {
            Json.parseToJsonElement(event.snapshotJson) as? JsonObject
        }.getOrNull()
        fun text(key: String): String? = (objectValue?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull?.take(MAX_PREVIEW_CHARS)?.takeIf(String::isNotBlank)
        val steps = objectValue?.get("steps") as? JsonArray
        return AgentSubagentInsight(
            id = event.subagentId.take(256), type = event.subagentType.take(128),
            status = text("status"), currentAction = text("currentAction"),
            observedToolSteps = steps?.count { (it as? JsonObject)?.get("kind")?.let { kind ->
                (kind as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "tool"
            } == true },
            error = text("error"), outputPreview = text("output"),
        )
    }

    private fun usageReceived(state: AgentRunInsightState, event: AgentEvent.UsageReceived): AgentRunInsightState {
        val current = state.usage
        val incoming = event.usage
        val atLatestRound = event.round >= (current.latestRequestRound ?: Int.MIN_VALUE)
        val latest = if (atLatestRound) {
            val previous = current.latestRequest.takeIf { current.latestRequestRound == event.round }
            // A cumulative event's input/output values must never be reclassified as request values.
            if (incoming.requestCount == null) (previous ?: AgentTokenUsage()).merge(incoming) else previous
        } else current.latestRequest
        val cumulative = if (incoming.requestCount != null &&
            (incoming.requestCount >= (current.cumulative?.requestCount ?: 0)) &&
            event.round >= (current.cumulativeRound ?: Int.MIN_VALUE)) {
            (current.cumulative ?: AgentTokenUsage()).merge(incoming)
        } else current.cumulative
        val usage = current.copy(
            cumulative = cumulative,
            cumulativeRound = if (cumulative !== current.cumulative) event.round else current.cumulativeRound,
            latestRequest = latest,
            latestRequestRound = if (atLatestRound) event.round else current.latestRequestRound,
            latestContextTokens = if (atLatestRound) incoming.contextTokens ?: current.latestContextTokens else current.latestContextTokens,
            latestReasoningTokens = when {
                event.round > (current.latestRequestRound ?: Int.MIN_VALUE) -> incoming.reasoningTokens
                atLatestRound -> incoming.reasoningTokens ?: current.latestReasoningTokens
                else -> current.latestReasoningTokens
            },
        )
        return state.copy(usage = usage)
    }

    private fun retryScheduled(state: AgentRunInsightState, event: AgentEvent.ModelRetryScheduled, at: Long): AgentRunInsightState {
        val request = state.activeRequest(event.round)
        val updated = if (request != null) {
            state.upsertRequest(request.copy(retryReason = event.reasonCode.take(256), endedAtMillis = at))
        } else state
        val withNotice = updated.upsertNotice(AgentInsightNotice(
            key = "retry:${event.round}:${event.attempt}", kind = AgentInsightNoticeKind.RETRY,
            phase = "scheduled", observedAtMillis = at, round = event.round, attempt = event.attempt,
            maxAttempts = event.maxAttempts, delayMillis = event.delayMs, reason = event.reasonCode.take(256),
        ))
        return if (event.round < (state.round ?: 0)) withNotice
        else withNotice.copy(stage = AgentInsightStage.RETRY_WAIT, round = event.round)
    }

    private fun compaction(state: AgentRunInsightState, event: AgentEvent.ContextCompaction, at: Long): AgentRunInsightState {
        val key = "compaction:${event.operationId.take(256)}"
        val previous = state.notices.lastOrNull { it.key == key }
        val notice = AgentInsightNotice(
            key = key, kind = AgentInsightNoticeKind.COMPACTION,
            phase = event.phase.take(64), observedAtMillis = at, reason = event.reasonCode.take(256).ifBlank { null },
            tokensBefore = event.tokensBefore ?: previous?.tokensBefore,
            tokensAfter = event.tokensAfter ?: previous?.tokensAfter,
        )
        return state.upsertNotice(notice).copy(
            stage = if (event.phase == AgentEvent.ContextCompaction.PHASE_STARTED) AgentInsightStage.COMPACTING
            else if (state.stage == AgentInsightStage.COMPACTING) AgentInsightStage.WAITING_MODEL else state.stage,
        )
    }

    private fun AgentRunInsightState.withExecutingStage(): AgentRunInsightState {
        val latest = tools.lastOrNull {
            it.status == AgentInsightToolStatus.RUNNING && it.round >= (round ?: 0)
        } ?: return this
        return copy(stage = if (latest.source == AgentInsightSource.AGENT) AgentInsightStage.EXECUTING_AGENT else AgentInsightStage.EXECUTING_TOOL)
    }

    private fun AgentRunInsightState.upsertTool(tool: AgentToolInsight): AgentRunInsightState {
        val index = tools.indexOfLast { it.key == tool.key }
        if (index >= 0) return copy(tools = tools.mapIndexed { i, old -> if (i == index) tool else old })
        val next = tools + tool
        return copy(tools = next.takeLast(MAX_TOOL_RECORDS), droppedToolCount = droppedToolCount + (next.size - MAX_TOOL_RECORDS).coerceAtLeast(0))
    }

    private fun AgentRunInsightState.activeRequest(round: Int): AgentRequestInsight? =
        requests.lastOrNull { it.round == round && it.isActive() }

    private fun AgentRunInsightState.nextAttempt(round: Int): Int =
        (requests.filter { it.round == round }.maxOfOrNull { it.attempt } ?: 0) + 1

    private fun AgentRunInsightState.upsertRequest(request: AgentRequestInsight): AgentRunInsightState {
        val index = requests.indexOfLast { it.round == request.round && it.attempt == request.attempt }
        if (index >= 0) return copy(requests = requests.mapIndexed { i, old -> if (i == index) request else old })
        val next = requests + request
        return copy(requests = next.takeLast(MAX_REQUEST_RECORDS), droppedRequestCount = droppedRequestCount + (next.size - MAX_REQUEST_RECORDS).coerceAtLeast(0))
    }

    private fun AgentRunInsightState.upsertNotice(notice: AgentInsightNotice): AgentRunInsightState {
        val index = notices.indexOfLast { it.key == notice.key }
        if (index >= 0) return copy(notices = notices.mapIndexed { i, old -> if (i == index) notice else old })
        val next = notices + notice
        return copy(notices = next.takeLast(MAX_NOTICE_RECORDS), droppedNoticeCount = droppedNoticeCount + (next.size - MAX_NOTICE_RECORDS).coerceAtLeast(0))
    }

    private fun toolKey(round: Int, id: String, hosted: Boolean): String {
        val identity = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
        return "$round:${if (hosted) "hosted" else "local"}:$identity"
    }
    private val MCP_LOCAL_NAME = Regex("mcp_[A-Za-z0-9]{1,8}_[A-Za-z0-9_-]{1,30}_[0-9a-f]{8}")
    private val SKILL_TOOLS = setOf("skills_list", "skills_read", "skills_read_resource", "skills_manage",
        "skills_list_curated", "skills_inspect_github", "skills_install_from_github")
}

private fun Long.elapsedSince(startedAtMillis: Long): Long = (this - startedAtMillis).coerceAtLeast(0)
