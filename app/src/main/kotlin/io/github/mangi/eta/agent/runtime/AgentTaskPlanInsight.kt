package io.github.mangi.eta.agent.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Explicit Runtime task-plan protocol. Ordinary tool output never produces a plan. */
internal data class AgentTaskPlanInsight(
    val runId: String,
    val revision: Long,
    val title: String,
    val steps: List<AgentTaskPlanStepInsight>,
    val updatedAt: String?,
    val previousPlan: AgentTaskPlanHistoryInsight? = null,
)

internal data class AgentTaskPlanHistoryInsight(
    val originalRunId: String,
    val continuationRevision: Long,
    val capturedAt: Long?,
    val reason: String?,
    val plan: AgentTaskPlanInsight?,
    val receiptRefs: List<String>,
)

internal data class AgentTaskPlanStepInsight(
    val id: String,
    val title: String,
    val status: AgentTaskPlanStepStatus,
    val completionNote: String?,
    val evidenceToolCallIds: List<String>,
)

internal enum class AgentTaskPlanStepStatus { PENDING, IN_PROGRESS, COMPLETED, BLOCKED, FAILED, CANCELLED }

internal object AgentTaskPlanInsightParser {
    const val MAX_PLAN_RESULT_CHARS = 20_480
    const val MAX_PLAN_STEPS = 20

    fun fromEvent(runId: String, event: AgentEvent.ToolFinished): AgentTaskPlanInsight? {
        if (event.name !in setOf("update_plan", "get_plan") || event.success != true) return null
        if (event.resultSummary.length > MAX_PLAN_RESULT_CHARS) return null
        return runCatching {
            val root = Json.parseToJsonElement(event.resultSummary) as? JsonObject ?: return null
            if (root.boolean("ok") != true || root.text("kind") != "eta.task_plan" || root.int("schema_version") != 1) return null
            val plan = root["plan"] as? JsonObject ?: return null
            val current = parsePlan(plan, runId) ?: return null
            val previous = parseHistory(root, current.runId)
            current.copy(previousPlan = previous)
        }.getOrNull()
    }

    private fun parseHistory(root: JsonObject, currentRunId: String): AgentTaskPlanHistoryInsight? {
        val source = root["previous_plan_source"] ?: return null
        val value = source as? JsonObject ?: error("Invalid history source")
        val originalRunId = value.text("original_run_id")?.takeIf { it.isNotBlank() && it.length <= 512 && it != currentRunId }
            ?: error("Invalid history owner")
        if (value.boolean("read_only") != true || value.boolean("receipts_executable") != false) error("Invalid history authority")
        val revision = value.long("continuation_revision")?.takeIf { it > 0 } ?: error("Invalid history revision")
        val previous = when (val raw = root["previous_plan"]) {
            null, JsonNull -> null
            is JsonObject -> parsePlan(raw, originalRunId) ?: error("Invalid history plan")
            else -> error("Invalid history plan")
        }
        val refs = (root["previous_receipt_refs"] as? JsonArray)?.take(8)?.mapNotNull { raw ->
            (raw as? JsonObject)?.text("tool_call_id")?.takeIf { it.isNotBlank() }?.take(200)
        }?.distinct().orEmpty()
        return AgentTaskPlanHistoryInsight(originalRunId, revision, value.long("captured_at")?.takeIf { it >= 0 },
            value.text("reason")?.take(240), previous, refs)
    }

    private fun parsePlan(plan: JsonObject, expectedRunId: String): AgentTaskPlanInsight? {
        if (plan.text("run_id") != expectedRunId) return null
        val revision = plan.long("revision")?.takeIf { it >= 0 } ?: return null
        val rawSteps = plan["steps"] as? JsonArray ?: return null
        if (rawSteps.size > MAX_PLAN_STEPS) return null
        val steps = rawSteps.map { raw ->
            val item = raw as? JsonObject ?: return null
            val id = item.text("id")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val title = item.text("title")?.takeIf(String::isNotBlank)?.take(512) ?: return null
            val status = when (item.text("status")) {
                "pending" -> AgentTaskPlanStepStatus.PENDING
                "in_progress" -> AgentTaskPlanStepStatus.IN_PROGRESS
                "completed" -> AgentTaskPlanStepStatus.COMPLETED
                "blocked" -> AgentTaskPlanStepStatus.BLOCKED
                "failed" -> AgentTaskPlanStepStatus.FAILED
                "cancelled" -> AgentTaskPlanStepStatus.CANCELLED
                else -> return null
            }
            val evidence = (item["evidence_tool_call_ids"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() }?.take(256)
            }?.distinct()?.take(20).orEmpty()
            // Producer validates current receipts. Historical completions remain model reports.
            AgentTaskPlanStepInsight(id, title, status,
                item.text("completion_note")?.take(1_024)?.takeIf(String::isNotBlank), evidence)
        }
        if (steps.map { it.id }.distinct().size != steps.size) return null
        return AgentTaskPlanInsight(expectedRunId, revision, plan.text("title").orEmpty().take(512), steps,
            plan.text("updated_at")?.take(128))
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
}
