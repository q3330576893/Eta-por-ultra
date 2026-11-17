package io.github.mangi.eta.agent.planning

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal enum class TaskContinuationStatus(val wire: String) {
    READY("ready"), CLAIMED("claimed"), ACK("ack");

    companion object {
        fun parse(value: String): TaskContinuationStatus = entries.firstOrNull { it.wire == value }
            ?: throw TaskWorkspaceException("STORE_CORRUPT", "恢复记录状态异常")
    }
}

/** No transcript, tool arguments, screenshots, coordinates or executable receipts are retained here. */
internal data class ContinuationRecord(
    val originalRunId: String,
    val ownerConversationKey: String,
    val createdAt: Long,
    val planRevision: Long,
    val pendingStepIds: List<String>,
    val recentReceiptRefs: List<TaskPlanEvidence>,
    val reason: String,
    val status: TaskContinuationStatus = TaskContinuationStatus.READY,
    val revision: Long = 1L,
    val planSnapshot: AgentTaskPlan? = null,
    val claimedRunId: String? = null,
    val claimedAt: Long? = null,
    val acknowledgedAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("original_run_id", originalRunId)
        .put("owner_conversation_key", ownerConversationKey).put("created_at", createdAt)
        .put("plan_revision", planRevision).put("pending_step_ids", JSONArray(pendingStepIds))
        .put("recent_receipt_refs", JSONArray(recentReceiptRefs.map(TaskPlanEvidence::toJson)))
        .put("reason", reason).put("status", status.wire).put("revision", revision)
        .put("plan_snapshot", planSnapshot?.toJson() ?: JSONObject.NULL)
        .put("claimed_run_id", claimedRunId ?: JSONObject.NULL)
        .put("claimed_at", claimedAt ?: JSONObject.NULL).put("acknowledged_at", acknowledgedAt ?: JSONObject.NULL)
        .put("read_only_history", true).put("receipts_executable", false)
}

/**
 * Bounded app-private journal. All reads and transitions share the workspace's OS file lock and
 * atomic fsync/rename writer, including across Runtime/UI processes. Original runs are immutable
 * identities; an acknowledged tombstone prevents a retained checkpoint from becoming ready again.
 */
internal class TaskContinuationStore(
    root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val files = TaskWorkspaceFiles(root)

    fun record(
        originalRunId: String,
        ownerConversationKey: String,
        snapshot: TaskPlanSnapshot,
        reason: String = "runtime_interrupted",
        createdAt: Long = clock(),
    ): ContinuationRecord = files.locked {
        requireOwner(ownerConversationKey)
        if (!AgentTaskPlanStore.validRunId(originalRunId)) invalid("原运行标识异常")
        val records = readLocked()
        records.firstOrNull { it.originalRunId == originalRunId }?.let { existing ->
            requireSameOwner(existing, ownerConversationKey)
            return@locked existing
        }
        val record = ContinuationRecord(
            originalRunId = originalRunId,
            ownerConversationKey = ownerConversationKey,
            createdAt = createdAt,
            planRevision = snapshot.revision,
            pendingStepIds = snapshot.plan?.steps.orEmpty().filter { it.status != TaskPlanStatus.COMPLETED }.map { it.id },
            recentReceiptRefs = snapshot.evidence.takeLast(MAX_RECEIPT_REFS),
            reason = reason,
            planSnapshot = snapshot.plan,
        )
        validateRecord(record)
        // Keep active hints until acknowledged. Only old acknowledged tombstones may be evicted.
        val retained = if (records.size < MAX_RECORDS) records else {
            val oldestAck = records.filter { it.status == TaskContinuationStatus.ACK }
                .minWithOrNull(compareBy<ContinuationRecord> { it.acknowledgedAt }.thenBy { it.originalRunId })
                ?: throw TaskWorkspaceException("CONTINUATION_LIMIT", "待恢复记录已达到数量上限")
            records.filter { it.originalRunId != oldestAck.originalRunId }
        }
        writeLocked(retained + record)
        record
    }

    /** Pure lookup: displaying a banner neither claims nor acknowledges its record. */
    fun forConversation(conversationKey: String): ContinuationRecord? = files.locked {
        requireOwner(conversationKey)
        readLocked().filter { it.ownerConversationKey == conversationKey && it.status != TaskContinuationStatus.ACK }
            .maxWithOrNull(compareBy<ContinuationRecord> { it.createdAt }.thenBy { it.originalRunId })
    }

    fun get(originalRunId: String, conversationKey: String): ContinuationRecord? = files.locked {
        requireOwner(conversationKey)
        readLocked().firstOrNull { it.originalRunId == originalRunId }?.also { requireSameOwner(it, conversationKey) }
    }

    fun claim(originalRunId: String, conversationKey: String, expectedRevision: Long, newRunId: String): ContinuationRecord =
        transition(originalRunId, conversationKey, expectedRevision) { current ->
            requireNewRun(current, newRunId)
            when (current.status) {
                TaskContinuationStatus.READY -> current.copy(status = TaskContinuationStatus.CLAIMED,
                    revision = nextRevision(current), claimedRunId = newRunId, claimedAt = clock())
                TaskContinuationStatus.CLAIMED -> {
                    requireClaim(current, newRunId)
                    current
                }
                TaskContinuationStatus.ACK -> throw TaskWorkspaceException("CONTINUATION_ACKNOWLEDGED", "恢复记录已确认启动")
            }
        }

    /** Call only after the replacement run reports RunStarted, never on prompt/banner display. */
    fun acknowledgeAfterStart(originalRunId: String, conversationKey: String, expectedRevision: Long, newRunId: String): ContinuationRecord =
        transition(originalRunId, conversationKey, expectedRevision) { current ->
            requireClaim(current, newRunId)
            when (current.status) {
                TaskContinuationStatus.READY -> throw TaskWorkspaceException("CONTINUATION_CLAIM_REQUIRED", "先认领恢复记录再确认启动")
                TaskContinuationStatus.CLAIMED -> current.copy(status = TaskContinuationStatus.ACK,
                    revision = nextRevision(current), acknowledgedAt = clock())
                TaskContinuationStatus.ACK -> current
            }
        }

    /** A failed dispatch can release only its own unacknowledged claim, using the exact revision. */
    fun releaseAfterStartFailure(originalRunId: String, conversationKey: String, expectedRevision: Long, newRunId: String): ContinuationRecord =
        transition(originalRunId, conversationKey, expectedRevision) { current ->
            requireClaim(current, newRunId)
            if (current.status != TaskContinuationStatus.CLAIMED) {
                throw TaskWorkspaceException("CONTINUATION_CLAIM_REQUIRED", "仅待启动认领可释放")
            }
            current.copy(status = TaskContinuationStatus.READY, revision = nextRevision(current), claimedRunId = null, claimedAt = null)
        }

    /** Used after RunStarted, when the newest conversation hint may already be a different run. */
    fun claimedForRun(conversationKey: String, newRunId: String): ContinuationRecord? = files.locked {
        requireOwner(conversationKey)
        readLocked().singleOrNull { it.ownerConversationKey == conversationKey && it.claimedRunId == newRunId }
    }

    private fun transition(
        originalRunId: String,
        conversationKey: String,
        expectedRevision: Long,
        change: (ContinuationRecord) -> ContinuationRecord,
    ): ContinuationRecord = files.locked {
        requireOwner(conversationKey)
        val records = readLocked()
        val current = records.firstOrNull { it.originalRunId == originalRunId }
            ?: throw TaskWorkspaceException("CONTINUATION_NOT_FOUND", "恢复记录已变化，请重新读取")
        requireSameOwner(current, conversationKey)
        if (expectedRevision != current.revision) {
            throw TaskWorkspaceException("CONTINUATION_REVISION_CONFLICT", "恢复记录已变化，请使用最新 revision")
        }
        val next = change(current)
        validateRecord(next)
        if (next != current) {
            if (next.claimedRunId != null && records.any { it.originalRunId != originalRunId && it.claimedRunId == next.claimedRunId }) {
                throw TaskWorkspaceException("CONTINUATION_CLAIM_CONFLICT", "新运行已认领另一份恢复记录")
            }
            writeLocked(records.map { if (it.originalRunId == originalRunId) next else it })
        }
        next
    }

    private fun readLocked(): List<ContinuationRecord> {
        try {
            val raw = files.read(FILENAME, MAX_BYTES) ?: return emptyList()
            val json = JSONObject(raw)
            if (json.opt("schema_version") != 1 || json.optString("kind") != KIND) corrupt()
            val rows = json.getJSONArray("records")
            if (rows.length() > MAX_RECORDS) corrupt()
            val records = (0 until rows.length()).map { parseRecord(rows.getJSONObject(it)) }
            if (records.map { it.originalRunId }.distinct().size != records.size ||
                records.mapNotNull { it.claimedRunId }.let { it.distinct().size != it.size }) corrupt()
            return records
        } catch (error: TaskWorkspaceException) {
            if (error.code in setOf("STORE_PATH_INVALID", "STORE_UNAVAILABLE", "STORE_TOO_LARGE")) throw error
            corrupt()
        } catch (_: Exception) { corrupt() }
    }

    private fun writeLocked(records: List<ContinuationRecord>) {
        if (records.size > MAX_RECORDS) throw TaskWorkspaceException("CONTINUATION_LIMIT", "恢复记录数量超限")
        records.forEach(::validateRecord)
        files.write(FILENAME, JSONObject().put("schema_version", 1).put("kind", KIND)
            .put("records", JSONArray(records.map(ContinuationRecord::toJson))).toString(), MAX_BYTES)
    }

    private fun requireClaim(record: ContinuationRecord, newRunId: String) {
        requireNewRun(record, newRunId)
        if (record.claimedRunId != newRunId) throw TaskWorkspaceException("CONTINUATION_CLAIM_MISMATCH", "恢复记录已由另一运行认领")
    }

    private fun requireNewRun(record: ContinuationRecord, newRunId: String) {
        if (!AgentTaskPlanStore.validRunId(newRunId) || newRunId == record.originalRunId) invalid("恢复需要新的有效运行标识")
    }

    private fun requireSameOwner(record: ContinuationRecord, conversationKey: String) {
        if (record.ownerConversationKey != conversationKey) throw TaskWorkspaceException("CONTINUATION_OWNER_MISMATCH", "恢复记录所属会话不匹配")
    }

    private fun nextRevision(record: ContinuationRecord): Long {
        if (record.revision >= Long.MAX_VALUE - 1) throw TaskWorkspaceException("CONTINUATION_LIMIT", "恢复记录版本已达到上限")
        return record.revision + 1
    }

    companion object {
        const val MAX_RECORDS = 32
        const val MAX_RECEIPT_REFS = 8
        const val MAX_RECORD_BYTES = 96 * 1024
        const val MAX_BYTES = 4 * 1024 * 1024
        internal const val FILENAME = "continuations.json"
        private const val KIND = "eta.task_continuations"

        internal fun requireOwner(value: String) {
            if (value.isBlank() || value.length > 512 || '\u0000' in value) invalid("会话归属标识异常")
        }

        internal fun validateRecord(record: ContinuationRecord) {
            requireOwner(record.ownerConversationKey)
            if (!AgentTaskPlanStore.validRunId(record.originalRunId) || record.createdAt < 0 ||
                record.revision !in 1 until Long.MAX_VALUE || record.reason.isBlank() || record.reason.length > 240 || '\u0000' in record.reason) invalid("恢复记录字段异常")
            val plan = record.planSnapshot
            if (plan != null) {
                AgentTaskPlanStore.parsePlan(plan.toJson(), record.originalRunId)
                if (record.planRevision != plan.revision) invalid("历史计划版本不匹配")
            } else if (record.planRevision != 0L) invalid("空历史计划版本异常")
            val pending = plan?.steps.orEmpty().filter { it.status != TaskPlanStatus.COMPLETED }.map { it.id }
            if (record.pendingStepIds != pending) invalid("剩余步骤与历史计划不匹配")
            AgentTaskPlanStore.parseEvidence(JSONArray(record.recentReceiptRefs.map(TaskPlanEvidence::toJson)), MAX_RECEIPT_REFS)
            val claimed = record.claimedRunId
            if (claimed != null && (!AgentTaskPlanStore.validRunId(claimed) || claimed == record.originalRunId)) invalid("认领运行标识异常")
            when (record.status) {
                TaskContinuationStatus.READY -> if (claimed != null || record.claimedAt != null || record.acknowledgedAt != null) invalid("待恢复记录携带认领信息")
                TaskContinuationStatus.CLAIMED -> if (claimed == null || record.claimedAt == null || record.claimedAt < 0 || record.acknowledgedAt != null) invalid("认领记录字段异常")
                TaskContinuationStatus.ACK -> if (claimed == null || record.claimedAt == null || record.claimedAt < 0 || record.acknowledgedAt == null || record.acknowledgedAt < 0) invalid("确认记录字段异常")
            }
            if (record.toJson().toString().toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
                throw TaskWorkspaceException("CONTINUATION_LIMIT", "恢复记录超过存储上限")
            }
        }

        private fun parseRecord(row: JSONObject): ContinuationRecord {
            if (row.opt("read_only_history") != true || row.opt("receipts_executable") != false) corrupt()
            val originalRunId = row.getString("original_run_id")
            val pending = row.getJSONArray("pending_step_ids")
            if (pending.length() > 20) corrupt()
            return ContinuationRecord(
                originalRunId, row.getString("owner_conversation_key"), integer(row, "created_at"),
                integer(row, "plan_revision"), (0 until pending.length()).map { pending.get(it) as? String ?: corrupt() },
                AgentTaskPlanStore.parseEvidence(row.getJSONArray("recent_receipt_refs"), MAX_RECEIPT_REFS),
                row.getString("reason"), TaskContinuationStatus.parse(row.getString("status")), integer(row, "revision"),
                row.optJSONObject("plan_snapshot")?.let { AgentTaskPlanStore.parsePlan(it, originalRunId) },
                if (row.isNull("claimed_run_id")) null else (row.get("claimed_run_id") as? String ?: corrupt()),
                optionalInteger(row, "claimed_at"), optionalInteger(row, "acknowledged_at"),
            ).also(::validateRecord)
        }

        private fun integer(row: JSONObject, key: String): Long = when (val value = row.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> corrupt()
        }
        private fun optionalInteger(row: JSONObject, key: String): Long? = if (row.isNull(key)) null else integer(row, key)
        private fun invalid(message: String): Nothing = throw TaskWorkspaceException("CONTINUATION_INVALID", message)
        private fun corrupt(): Nothing = throw TaskWorkspaceException("STORE_CORRUPT", "恢复记录文件异常，已保留原文件")
    }
}
