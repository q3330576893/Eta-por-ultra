package io.github.mangi.eta.agent.planning

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal enum class TaskPlanStatus(val wire: String) {
    PENDING("pending"), IN_PROGRESS("in_progress"), COMPLETED("completed"),
    BLOCKED("blocked"), FAILED("failed"), CANCELLED("cancelled");
    companion object {
        fun parse(value: String): TaskPlanStatus = entries.firstOrNull { it.wire == value }
            ?: throw TaskWorkspaceException("PLAN_INVALID_STATUS", "步骤状态必须为 pending、in_progress、completed、blocked、failed 或 cancelled")
    }
}

internal data class TaskPlanStep(
    val id: String,
    val title: String,
    val status: TaskPlanStatus,
    val completionNote: String = "",
    val evidenceToolCallIds: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("status", status.wire)
        .put("completion_note", completionNote).put("evidence_tool_call_ids", JSONArray(evidenceToolCallIds))
}

internal data class AgentTaskPlan(
    val runId: String,
    val title: String,
    val revision: Long,
    val steps: List<TaskPlanStep>,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("run_id", runId).put("title", title).put("revision", revision)
        .put("steps", JSONArray(steps.map(TaskPlanStep::toJson))).put("updated_at", updatedAt)
}

internal data class TaskPlanEvidence(val toolCallId: String, val tool: String, val observedAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("tool_call_id", toolCallId).put("tool", tool).put("observed_at", observedAt)
}

/** Historical data is embedded once, never opened as a mutable plan or a source of current receipts. */
internal data class TaskPlanHistory(
    val originalRunId: String,
    val continuationRevision: Long,
    val capturedAt: Long,
    val reason: String,
    val plan: AgentTaskPlan?,
    val receiptRefs: List<TaskPlanEvidence>,
) {
    fun sourceJson(): JSONObject = JSONObject().put("original_run_id", originalRunId)
        .put("continuation_revision", continuationRevision).put("captured_at", capturedAt).put("reason", reason)
        .put("read_only", true).put("receipts_executable", false).put("completion_semantics", "historical_model_report_only")

    fun toJson(): JSONObject = sourceJson().put("plan", plan?.toJson() ?: JSONObject.NULL)
        .put("receipt_refs", JSONArray(receiptRefs.map(TaskPlanEvidence::toJson)))
}

internal data class TaskPlanSnapshot(
    val plan: AgentTaskPlan?,
    val evidence: List<TaskPlanEvidence>,
    val previousPlan: TaskPlanHistory? = null,
) {
    val revision: Long get() = plan?.revision ?: 0L
    fun toJson(): JSONObject = JSONObject().put("ok", true).put("kind", KIND).put("schema_version", 1)
        .put("plan", plan?.toJson() ?: JSONObject.NULL).put("revision", revision)
        .put("recent_evidence", JSONArray(evidence.takeLast(8).map(TaskPlanEvidence::toJson)))
        .put("completion_semantics", "reported_complete_with_successful_tool_receipts; verify the actual user objective before reporting success")
        .also { json -> previousPlan?.let { history ->
            json.put("previous_plan", history.plan?.toJson() ?: JSONObject.NULL)
                .put("previous_plan_source", history.sourceJson())
                .put("previous_receipt_refs", JSONArray(history.receiptRefs.map(TaskPlanEvidence::toJson)))
        } }
    companion object { const val KIND = "eta.task_plan" }
}

/** One persisted plan per immutable run ID, never an agent-provided filesystem path. */
internal class AgentTaskPlanStore(root: File, private val runId: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val files = TaskWorkspaceFiles(root)
    private val filename: String
    init {
        require(runId.isNotBlank() && runId.length <= 512 && '\u0000' !in runId) { "runId 无效" }
        filename = "plan-" + sha256(runId) + ".json"
    }

    fun snapshot(): TaskPlanSnapshot = files.locked { readLocked() }

    /** Runtime-only import. A fresh run receives remaining work, with every step pending and no old receipts. */
    fun importContinuation(record: ContinuationRecord): TaskPlanSnapshot = files.locked {
        TaskContinuationStore.validateRecord(record)
        if (record.claimedRunId != runId || record.status == TaskContinuationStatus.READY || record.originalRunId == runId) {
            throw TaskWorkspaceException("CONTINUATION_CLAIM_MISMATCH", "恢复记录与当前运行的认领信息不匹配")
        }
        val current = readLocked()
        current.previousPlan?.let { history ->
            if (history.originalRunId == record.originalRunId) return@locked current
            throw TaskWorkspaceException("PLAN_CONTINUATION_CONFLICT", "本次运行已关联另一份历史计划")
        }
        if (current.revision != 0L || current.evidence.isNotEmpty()) {
            throw TaskWorkspaceException("PLAN_CONTINUATION_CONFLICT", "仅空白新运行可导入历史计划")
        }
        val history = TaskPlanHistory(record.originalRunId, record.revision, record.createdAt, record.reason,
            record.planSnapshot, record.recentReceiptRefs)
        val remaining = record.planSnapshot?.steps.orEmpty().filter { it.id in record.pendingStepIds }
            .map { TaskPlanStep(it.id, it.title, TaskPlanStatus.PENDING) }
        val next = TaskPlanSnapshot(
            remaining.takeIf { it.isNotEmpty() }?.let { AgentTaskPlan(runId, record.planSnapshot!!.title, 1L, it, clock()) },
            emptyList(), history,
        )
        writeLocked(next)
        next
    }

    fun recordSuccessfulTool(toolCallId: String, tool: String) = files.locked {
        if (toolCallId.isBlank() || toolCallId.length > 200 || '\u0000' in toolCallId || !TOOL.matches(tool)) return@locked
        if (tool in INTERNAL_TOOLS || tool.startsWith("app_playbooks_")) return@locked
        val current = readLocked()
        if (current.evidence.any { it.toolCallId == toolCallId }) return@locked
        val evidence = (current.evidence + TaskPlanEvidence(toolCallId, tool, clock())).takeLast(MAX_EVIDENCE)
        writeLocked(current.copy(evidence = evidence))
    }

    fun update(expectedRevision: Long, title: String, steps: List<TaskPlanStep>): TaskPlanSnapshot = files.locked {
        validate(title, steps)
        val current = readLocked()
        if (expectedRevision != current.revision) throw TaskWorkspaceException("PLAN_REVISION_CONFLICT", "计划已变化，请 get_plan 后使用返回 revision 更新")
        if (current.revision >= Long.MAX_VALUE - 1) throw TaskWorkspaceException("PLAN_REVISION_LIMIT", "计划版本已达到上限")
        val receipts = current.evidence.associateBy { it.toolCallId }
        val previous = current.plan?.steps.orEmpty().associateBy { it.id }
        steps.filter { it.status == TaskPlanStatus.COMPLETED }.forEach { step ->
            val unchangedCompletion = previous[step.id]?.let {
                it.status == TaskPlanStatus.COMPLETED && it.title == step.title &&
                    it.completionNote == step.completionNote && it.evidenceToolCallIds == step.evidenceToolCallIds
            } == true
            if (!unchangedCompletion && (step.completionNote.isBlank() || step.evidenceToolCallIds.isEmpty() ||
                    step.evidenceToolCallIds.any { it !in receipts })) {
                throw TaskWorkspaceException("PLAN_EVIDENCE_REQUIRED", "完成步骤需要 completion_note 与本次运行已成功执行的 evidence_tool_call_ids；先 get_plan 查看实际回执")
            }
        }
        val next = TaskPlanSnapshot(AgentTaskPlan(runId, title.trim(), current.revision + 1,
            steps.map { it.copy(title = it.title.trim(), completionNote = it.completionNote.trim()) }, clock()), current.evidence, current.previousPlan)
        writeLocked(next)
        next
    }

    private fun readLocked(): TaskPlanSnapshot {
        val raw = files.read(filename, MAX_BYTES) ?: return TaskPlanSnapshot(null, emptyList())
        try {
            val json = JSONObject(raw)
            if (json.getString("run_id") != runId) throw TaskWorkspaceException("PLAN_SCOPE_MISMATCH", "任务计划所属运行不匹配")
            val evidence = parseEvidence(json.getJSONArray("evidence"), MAX_EVIDENCE)
            val plan = json.optJSONObject("plan")?.let { parsePlan(it, runId) }
            val previous = json.optJSONObject("previous_plan")?.let { value ->
                val originalRunId = value.getString("original_run_id")
                val continuationRevision = value.getLong("continuation_revision")
                val capturedAt = value.getLong("captured_at")
                val reason = value.getString("reason")
                if (!validRunId(originalRunId) || originalRunId == runId || continuationRevision < 1 ||
                    capturedAt < 0 || reason.isBlank() || reason.length > 240 || '\u0000' in reason ||
                    value.opt("read_only") != true || value.opt("receipts_executable") != false) {
                    throw TaskWorkspaceException("STORE_CORRUPT", "历史计划来源异常")
                }
                TaskPlanHistory(originalRunId, continuationRevision, capturedAt, reason,
                    value.optJSONObject("plan")?.let { parsePlan(it, originalRunId) },
                    parseEvidence(value.getJSONArray("receipt_refs"), TaskContinuationStore.MAX_RECEIPT_REFS))
            }
            return TaskPlanSnapshot(plan, evidence, previous)
        } catch (error: TaskWorkspaceException) { throw error }
        catch (_: Exception) { throw TaskWorkspaceException("STORE_CORRUPT", "任务计划文件解析失败，已保留原文件") }
    }

    private fun writeLocked(value: TaskPlanSnapshot) {
        val json = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("plan", value.plan?.toJson() ?: JSONObject.NULL)
            .put("evidence", JSONArray(value.evidence.map(TaskPlanEvidence::toJson)))
            .also { json -> value.previousPlan?.let { json.put("previous_plan", it.toJson()) } }
        files.write(filename, json.toString(), MAX_BYTES)
    }

    companion object {
        val INTERNAL_TOOLS = setOf("get_plan", "update_plan")
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        private val TOOL = Regex("[A-Za-z][A-Za-z0-9_.-]{0,199}")
        private const val MAX_EVIDENCE = 128
        // Includes one bounded historical snapshot plus this run's independently bounded receipts.
        private const val MAX_BYTES = 256 * 1024

        internal fun validRunId(value: String): Boolean = value.isNotBlank() && value.length <= 512 && '\u0000' !in value

        internal fun validate(title: String, steps: List<TaskPlanStep>) {
            requireText(title, 120, "计划标题")
            if (steps.size !in 1..20) throw TaskWorkspaceException("PLAN_INVALID_STEPS", "计划需要 1–20 个步骤")
            if (steps.map { it.id }.distinct().size != steps.size || steps.any { !ID.matches(it.id) }) {
                throw TaskWorkspaceException("PLAN_INVALID_STEP_ID", "步骤 id 需要唯一的 1–64 位英文、数字、下划线或连字符")
            }
            if (steps.count { it.status == TaskPlanStatus.IN_PROGRESS } > 1) throw TaskWorkspaceException("PLAN_MULTIPLE_ACTIVE", "同一计划最多一个 in_progress 步骤")
            steps.forEach { step ->
                requireText(step.title, 160, "步骤标题")
                if (step.completionNote.length > 240 || '\u0000' in step.completionNote) throw TaskWorkspaceException("PLAN_INVALID_NOTE", "完成说明最多 240 字符")
                if (step.evidenceToolCallIds.size > 8 || step.evidenceToolCallIds.distinct().size != step.evidenceToolCallIds.size ||
                    step.evidenceToolCallIds.any { it.isBlank() || it.length > 200 || '\u0000' in it }) {
                    throw TaskWorkspaceException("PLAN_INVALID_EVIDENCE", "每个步骤最多引用 8 个唯一工具回执")
                }
                if (step.status != TaskPlanStatus.COMPLETED && (step.completionNote.isNotBlank() || step.evidenceToolCallIds.isNotEmpty())) {
                    throw TaskWorkspaceException("PLAN_INVALID_EVIDENCE", "只有 completed 步骤携带完成说明与回执")
                }
            }
        }

        internal fun parsePlan(value: JSONObject, expectedRunId: String): AgentTaskPlan {
            val steps = parseSteps(value.getJSONArray("steps"))
            validate(value.getString("title"), steps)
            val revision = value.getLong("revision")
            val updatedAt = value.getLong("updated_at")
            if (!validRunId(expectedRunId) || revision !in 1 until Long.MAX_VALUE || updatedAt < 0 || value.getString("run_id") != expectedRunId) {
                throw TaskWorkspaceException("STORE_CORRUPT", "计划版本或所属运行异常")
            }
            return AgentTaskPlan(expectedRunId, value.getString("title"), revision, steps, updatedAt)
        }

        internal fun parseEvidence(rows: JSONArray, maximum: Int): List<TaskPlanEvidence> {
            if (rows.length() > maximum) throw TaskWorkspaceException("STORE_CORRUPT", "计划回执数量异常")
            val evidence = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                TaskPlanEvidence(row.getString("tool_call_id"), row.getString("tool"), row.getLong("observed_at"))
            }
            if (evidence.map { it.toolCallId }.distinct().size != evidence.size || evidence.any {
                    it.toolCallId.isBlank() || it.toolCallId.length > 200 || '\u0000' in it.toolCallId ||
                        !TOOL.matches(it.tool) || it.tool in INTERNAL_TOOLS || it.tool.startsWith("app_playbooks_") || it.observedAt < 0
                }) throw TaskWorkspaceException("STORE_CORRUPT", "计划回执内容异常")
            return evidence
        }

        fun parseSteps(rows: JSONArray): List<TaskPlanStep> {
            if (rows.length() !in 1..20) throw TaskWorkspaceException("PLAN_INVALID_STEPS", "计划需要 1–20 个步骤")
            return (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                if (row.keys().asSequence().any { it !in setOf("id", "title", "status", "completion_note", "evidence_tool_call_ids") }) {
                    throw TaskWorkspaceException("PLAN_INVALID_STEP", "步骤含未声明字段")
                }
                val evidence = row.optJSONArray("evidence_tool_call_ids")?.let { ids ->
                    if (ids.length() > 8) throw TaskWorkspaceException("PLAN_INVALID_EVIDENCE", "每个步骤最多引用 8 个回执")
                    (0 until ids.length()).map { ids.get(it) as? String ?: throw TaskWorkspaceException("PLAN_INVALID_EVIDENCE", "回执 id 必须为文本") }
                }.orEmpty()
                TaskPlanStep(row.get("id") as? String ?: "", row.get("title") as? String ?: "",
                    TaskPlanStatus.parse(row.get("status") as? String ?: ""),
                    row.opt("completion_note")?.let { it as? String ?: throw TaskWorkspaceException("PLAN_INVALID_NOTE", "说明必须为文本") }.orEmpty(), evidence)
            }
        }
        private fun requireText(value: String, max: Int, label: String) {
            if (value.isBlank() || value.length > max || '\u0000' in value) throw TaskWorkspaceException("PLAN_INVALID_TEXT", "$label 需要 1–$max 字符")
        }
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
