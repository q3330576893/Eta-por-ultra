package io.github.mangi.eta.ui.components

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentInsightNotice
import io.github.mangi.eta.agent.runtime.AgentInsightNoticeKind
import io.github.mangi.eta.agent.runtime.AgentInsightSource
import io.github.mangi.eta.agent.runtime.AgentInsightStage
import io.github.mangi.eta.agent.runtime.AgentInsightToolStatus
import io.github.mangi.eta.agent.runtime.AgentTaskPlanInsight
import io.github.mangi.eta.agent.runtime.AgentTaskPlanStepStatus
import io.github.mangi.eta.ui.model.AgentInsightUsageUi
import io.github.mangi.eta.ui.model.AgentRunInsightsUi
import io.github.mangi.eta.ui.model.AgentToolInsightUi
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compact observability inspector, separate from the conversation's AgentWorkProcess timeline.
 * The caller projects elapsed time via state.toUi(nowMillis); this card owns no clock or fake metrics.
 */
@Composable
internal fun AgentRunInsightsCard(
    insights: AgentRunInsightsUi,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    detailsMaxHeight: Dp = 380.dp,
) {
    var expanded by rememberSaveable(insights.runId) { mutableStateOf(initiallyExpanded) }
    val stage = stageLabel(insights.stage)
    val tone = when (insights.stage) {
        AgentInsightStage.FAILED -> Color(0xFFCE5264)
        AgentInsightStage.CANCELLED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> if (insights.running) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(8.dp))
            Text(
                text = insights.currentAction?.let { "$stage · $it" } ?: stage,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = durationLabel(insights.elapsedMillis),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp),
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(if (expanded) R.string.agent_insights_collapse else R.string.agent_insights_expand),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 5.dp).size(18.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = detailsMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InsightText(stringResource(R.string.agent_insights_elapsed, durationLabel(insights.elapsedMillis)))
                insights.round?.let { InsightText(stringResource(R.string.agent_insights_round, it)) }
                insights.firstResponseMillis?.let {
                    InsightText(stringResource(R.string.agent_observation_task_headers, durationLabel(it)))
                }
                insights.firstIncrementMillis?.let {
                    InsightText(stringResource(R.string.agent_observation_task_delta, durationLabel(it)))
                }
                RequestObservations(insights)
                UsageObservations(insights.usage)
                insights.plan?.let { PlanObservation(it) }
                insights.error?.takeIf(String::isNotBlank)?.let {
                    InsightDetail(stringResource(R.string.agent_insights_error_detail), it)
                }
                if (insights.notices.isNotEmpty()) {
                    InsightHeading(stringResource(R.string.agent_insights_notices_heading))
                    insights.notices.forEach { notice -> key(notice.key) { NoticeObservation(notice) } }
                }
                if (insights.tools.isNotEmpty()) {
                    InsightHeading(stringResource(R.string.agent_insights_tools_heading))
                    InsightText(stringResource(R.string.agent_insights_tools_count, insights.retainedToolCount))
                    InsightText(stringResource(R.string.agent_insights_summary_notice))
                    insights.tools.forEach { tool -> key(tool.key) { ToolObservation(insights.runId, tool) } }
                }
                if (insights.droppedToolCount + insights.droppedRequestCount + insights.droppedNoticeCount > 0) {
                    InsightText(stringResource(R.string.agent_insights_history_limit, insights.droppedToolCount,
                        insights.droppedRequestCount, insights.droppedNoticeCount))
                }
            }
        }
    }
}

@Composable
private fun RequestObservations(insights: AgentRunInsightsUi) {
    var expanded by rememberSaveable(insights.runId, "requests") { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.agent_observation_requests, insights.requests.size),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(if (expanded) R.string.agent_insights_collapse else R.string.agent_insights_expand),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
        )
    }
    if (expanded) {
        InsightText(stringResource(R.string.agent_observation_retries, insights.retryCount))
        insights.requests.forEach { request ->
            key(request.round, request.attempt) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.background.copy(alpha = 0.65f)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    InsightHeading(stringResource(R.string.agent_observation_attempt, request.round, request.attempt))
                    InsightText(stringResource(R.string.agent_observation_duration, observedValue(request.durationMillis?.let { durationLabel(it) })))
                    InsightText(stringResource(R.string.agent_observation_headers, observedValue(request.responseMillis?.let { durationLabel(it) })))
                    InsightText(stringResource(R.string.agent_observation_delta, observedValue(request.firstIncrementMillis?.let { durationLabel(it) })))
                    InsightText(stringResource(R.string.agent_observation_http, observedValue(request.httpCode?.toString())))
                    request.retryReason?.takeIf(String::isNotBlank)?.let {
                        InsightText(stringResource(R.string.agent_observation_retry_code, it))
                    }
                }
            }
        }
    }
}

@Composable
private fun observedValue(value: String?): String = value?.let {
    stringResource(R.string.agent_observation_observed, it)
} ?: stringResource(R.string.agent_observation_unknown)

@Composable
private fun PlanObservation(plan: AgentTaskPlanInsight) {
    InsightHeading(stringResource(R.string.agent_insights_plan_heading, plan.revision))
    if (plan.title.isNotBlank()) InsightText(plan.title)
    InsightText(stringResource(R.string.agent_insights_plan_notice))
    plan.steps.forEachIndexed { index, step ->
        key(step.id) {
            val status = stringResource(when (step.status) {
                AgentTaskPlanStepStatus.PENDING -> R.string.agent_insights_plan_pending
                AgentTaskPlanStepStatus.IN_PROGRESS -> R.string.agent_insights_plan_running
                AgentTaskPlanStepStatus.COMPLETED -> R.string.agent_insights_plan_completed
                AgentTaskPlanStepStatus.BLOCKED -> R.string.agent_insights_plan_blocked
                AgentTaskPlanStepStatus.FAILED -> R.string.agent_insights_plan_failed
                AgentTaskPlanStepStatus.CANCELLED -> R.string.agent_insights_plan_cancelled
            })
            InsightText("${index + 1}. ${step.title} · $status")
            step.completionNote?.let { InsightText(it) }
            if (step.evidenceToolCallIds.isNotEmpty()) {
                InsightText(stringResource(R.string.agent_insights_plan_evidence, step.evidenceToolCallIds.joinToString()))
            }
        }
    }
}

@Composable
private fun ToolObservation(runId: String, tool: AgentToolInsightUi) {
    var expanded by rememberSaveable(runId, tool.key) { mutableStateOf(false) }
    val status = toolStatusLabel(tool.status)
    val source = sourceLabel(tool.source)
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.background.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tool.name.ifBlank { source },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                InsightText("$source · $status" + if (tool.hosted) " · ${stringResource(R.string.agent_insights_hosted)}" else "")
            }
            Text(
                text = tool.elapsedMillis?.let { durationLabel(it) } ?: stringResource(R.string.agent_insights_duration_unknown),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 5.dp),
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(if (expanded) R.string.agent_insights_collapse else R.string.agent_insights_expand),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
            )
        }
        if (expanded) Column(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            InsightText(stringResource(R.string.agent_insights_round, tool.round))
            tool.startOffsetMillis?.let { InsightText(stringResource(R.string.agent_insights_tool_started, durationLabel(it))) }
            tool.endOffsetMillis?.let { InsightText(stringResource(R.string.agent_insights_tool_finished, durationLabel(it))) }
            tool.argumentsSummary?.takeIf(String::isNotBlank)?.let {
                InsightDetail(stringResource(R.string.agent_insights_args_summary), it, tool.argumentsTruncated)
            }
            tool.commandPreview?.takeIf(String::isNotBlank)?.let {
                InsightDetail(stringResource(R.string.agent_insights_command_preview), it, tool.commandTruncated)
            }
            tool.resultSummary?.takeIf(String::isNotBlank)?.let {
                InsightDetail(stringResource(R.string.agent_insights_result_summary), it, tool.resultTruncated)
            }
            if (tool.resultMissingAtRunEnd) InsightText(stringResource(R.string.agent_insights_missing_tool_result))
            else if (tool.status != AgentInsightToolStatus.RUNNING && tool.resultSummary.isNullOrBlank()) {
                InsightText(stringResource(R.string.agent_insights_result_unavailable))
            }
            tool.subagent?.let { snapshot ->
                InsightText(stringResource(R.string.agent_insights_subagent, snapshot.type, snapshot.id))
                snapshot.currentAction?.let { InsightText(it) }
                snapshot.observedToolSteps?.let { InsightText(stringResource(R.string.agent_insights_subagent_steps, it)) }
                snapshot.error?.let { InsightDetail(stringResource(R.string.agent_insights_error_detail), it) }
                snapshot.outputPreview?.let { InsightDetail(stringResource(R.string.agent_insights_result_summary), it) }
            }
        }
    }
}

@Composable
private fun NoticeObservation(notice: AgentInsightNotice) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        when (notice.kind) {
            AgentInsightNoticeKind.RETRY -> InsightText(stringResource(R.string.agent_insights_retry,
                notice.round ?: 0, notice.attempt ?: 0, notice.maxAttempts ?: 0,
                durationLabel(notice.delayMillis?.toLong() ?: 0)))
            AgentInsightNoticeKind.COMPACTION -> {
                val phase = when (notice.phase) {
                    "started" -> stringResource(R.string.agent_insights_phase_started)
                    "completed" -> stringResource(R.string.agent_insights_phase_completed)
                    else -> notice.phase
                }
                InsightText(stringResource(R.string.agent_insights_compaction_phase, phase))
                notice.tokensBefore?.let { InsightText(stringResource(R.string.agent_insights_tokens_before, it)) }
                notice.tokensAfter?.let { InsightText(stringResource(R.string.agent_insights_tokens_after, it)) }
            }
            AgentInsightNoticeKind.HISTORY_TRIMMED -> InsightText(stringResource(R.string.agent_insights_history_trimmed,
                notice.omittedMessages ?: 0, notice.omittedUserRounds ?: 0))
        }
        notice.reason?.takeIf(String::isNotBlank)?.let { InsightText(it) }
    }
}

@Composable
private fun UsageObservations(usage: AgentInsightUsageUi) {
    if (!usage.hasObservations) return
    InsightHeading(stringResource(R.string.agent_insights_usage_heading))
    usage.inputTokens?.let { InsightText(stringResource(R.string.agent_insights_input_total, it)) }
    usage.outputTokens?.let { InsightText(stringResource(R.string.agent_insights_output_total, it)) }
    usage.cachedTokens?.let { InsightText(stringResource(R.string.agent_insights_cache_total, it)) }
    usage.contextTokens?.let { InsightText(stringResource(R.string.agent_insights_context_latest, it)) }
    usage.latestReasoningTokens?.let { InsightText(stringResource(R.string.agent_insights_reasoning_latest, it)) }
    if (usage.inputTokens == null) usage.latestRequestInputTokens?.let {
        InsightText(stringResource(R.string.agent_insights_request_input, it))
    }
    if (usage.outputTokens == null) usage.latestRequestOutputTokens?.let {
        InsightText(stringResource(R.string.agent_insights_request_output, it))
    }
    usage.modelDurationMillis?.let { InsightText(stringResource(R.string.agent_insights_model_duration, durationLabel(it))) }
    usage.toolDurationMillis?.let { InsightText(stringResource(R.string.agent_insights_tool_duration, durationLabel(it))) }
    usage.firstTokenTotalMillis?.let { InsightText(stringResource(R.string.agent_insights_total_first_token, durationLabel(it))) }
}

@Composable
private fun InsightDetail(title: String, value: String, truncated: Boolean = false) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable(value) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, value))); copied = true }
                    }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.agent_insights_copy_detail, title),
                    modifier = Modifier.size(15.dp), tint = MiuixTheme.colorScheme.primary)
                Text(text = stringResource(if (copied) R.string.agent_insights_copied else R.string.agent_insights_copy),
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
        SelectionContainer {
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (truncated) InsightText(stringResource(R.string.agent_insights_preview_truncated))
    }
}

@Composable
private fun InsightText(text: String) {
    Text(text = text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun InsightHeading(text: String) {
    Text(text = text, style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium), color = MiuixTheme.colorScheme.onSurface)
}

@Composable
private fun durationLabel(millis: Long): String = when {
    millis < 1_000 -> stringResource(R.string.agent_insights_ms, millis.coerceAtLeast(0))
    millis < 60_000 -> stringResource(R.string.agent_insights_seconds, millis / 1_000, millis / 100 % 10)
    else -> stringResource(R.string.agent_insights_minutes, millis / 60_000, millis / 1_000 % 60)
}

@Composable
private fun stageLabel(stage: AgentInsightStage): String = stringResource(when (stage) {
    AgentInsightStage.STARTING -> R.string.agent_insights_starting
    AgentInsightStage.WAITING_MODEL -> R.string.agent_insights_waiting_model
    AgentInsightStage.RESPONDING -> R.string.agent_insights_responding
    AgentInsightStage.THINKING -> R.string.agent_insights_thinking
    AgentInsightStage.EXECUTING_TOOL -> R.string.agent_insights_tool_running
    AgentInsightStage.EXECUTING_AGENT -> R.string.agent_insights_agent_running
    AgentInsightStage.RETRY_WAIT -> R.string.agent_insights_retry_wait
    AgentInsightStage.COMPACTING -> R.string.agent_insights_compacting
    AgentInsightStage.COMPLETED -> R.string.agent_insights_completed
    AgentInsightStage.FAILED -> R.string.agent_insights_failed
    AgentInsightStage.CANCELLED -> R.string.agent_insights_cancelled
})

@Composable
private fun toolStatusLabel(status: AgentInsightToolStatus): String = stringResource(when (status) {
    AgentInsightToolStatus.RUNNING -> R.string.agent_insights_status_running
    AgentInsightToolStatus.SUCCESS -> R.string.agent_insights_status_success
    AgentInsightToolStatus.FAILED -> R.string.agent_insights_status_failed
    AgentInsightToolStatus.UNKNOWN -> R.string.agent_insights_status_unknown
})

@Composable
private fun sourceLabel(source: AgentInsightSource): String = stringResource(when (source) {
    AgentInsightSource.TOOL -> R.string.agent_insights_source_tool
    AgentInsightSource.MCP -> R.string.agent_insights_source_mcp
    AgentInsightSource.SKILL -> R.string.agent_insights_source_skill
    AgentInsightSource.AGENT -> R.string.agent_insights_source_agent
    AgentInsightSource.UNKNOWN -> R.string.agent_insights_source_unknown
})
