package io.github.mangi.eta.agent.planning

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit continuation entry points for Runtime and the conversation coordinator.
 * Banner rendering calls only forConversation/continuationPrompt. On a user continuation action,
 * prepareRequest claims one record and imports read-only history into that new run. Acknowledge
 * only on RunStarted; a failed dispatch may release its claim. Checkpoints are never deleted here.
 */
internal object TaskContinuationRuntime {
    fun recordFromCheckpoint(
        context: Context,
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        conversationKey: String,
        reason: String = "runtime_interrupted",
    ): ContinuationRecord? = recordFromCheckpoint(context.applicationContext.filesDir, checkpoint, conversationKey, reason)

    internal fun recordFromCheckpoint(
        root: File,
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        conversationKey: String,
        reason: String = "runtime_interrupted",
    ): ContinuationRecord? {
        TaskContinuationStore.requireOwner(conversationKey)
        if (checkpoint.operation != AgentRuntimeWire.OP_CHAT || checkpoint.handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return null
        if (AgentUiHandoffPayload.from(checkpoint.handoff.payload).conversationId != conversationKey) {
            throw TaskWorkspaceException("CONTINUATION_OWNER_MISMATCH", "checkpoint 所属会话与恢复目标不匹配")
        }
        return TaskContinuationStore(root).record(checkpoint.runId, conversationKey,
            AgentTaskPlanStore(root, checkpoint.runId).snapshot(), reason, checkpoint.createdAt)
    }

    fun forConversation(context: Context, key: String): ContinuationRecord? =
        TaskContinuationStore(context.applicationContext.filesDir).forConversation(key)

    /** Pure read across any number of restarts; callers must not acknowledge on banner display. */
    fun continuationPrompt(context: Context, key: String): String? =
        forConversation(context, key)?.let(::promptForRecord)

    fun claimForStart(context: Context, key: String, newRunId: String, expectedRevision: Long? = null): ContinuationRecord? =
        claimForStart(context.applicationContext.filesDir, key, newRunId, expectedRevision)

    internal fun claimForStart(root: File, key: String, newRunId: String, expectedRevision: Long? = null): ContinuationRecord? {
        val store = TaskContinuationStore(root)
        // Resolve an existing claim by its target run before selecting a newer hint.
        val current = store.claimedForRun(key, newRunId) ?: store.forConversation(key) ?: return null
        val claimed = store.claim(current.originalRunId, key, expectedRevision ?: current.revision, newRunId)
        // This is a private, Runtime-only operation. Historical receipts never enter evidence.
        AgentTaskPlanStore(root, newRunId).importContinuation(claimed)
        return claimed
    }

    /** Explicit request helper: ordinary new requests keep an independent empty plan. */
    fun prepareRequest(
        context: Context,
        key: String,
        request: AgentRuntimeWire.RunRequest,
        expectedRevision: Long? = null,
    ): AgentRuntimeWire.RunRequest? {
        if (request.operation != AgentRuntimeWire.OP_CHAT || request.effectiveModelSessionId != key ||
            request.handoff?.let { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE ||
                AgentUiHandoffPayload.from(it.payload).conversationId != key } == true) {
            throw TaskWorkspaceException("CONTINUATION_OWNER_MISMATCH", "恢复请求所属会话不匹配")
        }
        val record = claimForStart(context, key, request.runId, expectedRevision) ?: return null
        val instruction = promptForRecord(record)
        return request.copy(prompt = request.prompt.takeIf(String::isNotBlank)?.let { "$it\n\n$instruction" } ?: instruction)
    }

    fun acknowledgeAfterStart(context: Context, key: String, newRunId: String): ContinuationRecord? =
        acknowledgeAfterStart(context.applicationContext.filesDir, key, newRunId)

    internal fun acknowledgeAfterStart(root: File, key: String, newRunId: String): ContinuationRecord? {
        val store = TaskContinuationStore(root)
        val current = store.claimedForRun(key, newRunId) ?: return null
        val history = AgentTaskPlanStore(root, newRunId).snapshot().previousPlan
        if (history?.originalRunId != current.originalRunId) {
            throw TaskWorkspaceException("CONTINUATION_IMPORT_REQUIRED", "新运行尚未关联这份恢复记录")
        }
        return store.acknowledgeAfterStart(current.originalRunId, key, current.revision, newRunId)
    }

    fun releaseAfterStartFailure(context: Context, key: String, newRunId: String): ContinuationRecord? {
        val store = TaskContinuationStore(context.applicationContext.filesDir)
        val current = store.claimedForRun(key, newRunId) ?: return null
        return store.releaseAfterStartFailure(current.originalRunId, key, current.revision, newRunId)
    }

    internal fun promptForRecord(record: ContinuationRecord): String {
        TaskContinuationStore.validateRecord(record)
        // Only ids and bounded provenance enter this hint. Plan titles/notes stay external data in get_plan.
        val source = JSONObject().put("original_run_id", record.originalRunId).put("plan_revision", record.planRevision)
            .put("pending_step_ids", JSONArray(record.pendingStepIds)).put("record_revision", record.revision)
        return "此前运行中断，恢复来源：$source。先调用 get_plan 核对当前运行的 plan 和只读 previous_plan；" +
            "原 run 的 completed 仅是历史模型报告，历史 receipt refs 仅供追溯，不能作为本次运行成功回执或用户目标已达成的证明。" +
            "剩余步骤已在新运行初始化为 pending，依据当前真实状态继续；涉及 GUI 时先重新 observe_screen，" +
            "只使用新观察的 observation_id、节点和截图坐标，旧 UI 坐标、节点、动作及工具参数均不重放。" +
            "完成前以新的工具结果核验用户目标，再更新本次计划并如实报告结果。"
    }
}
