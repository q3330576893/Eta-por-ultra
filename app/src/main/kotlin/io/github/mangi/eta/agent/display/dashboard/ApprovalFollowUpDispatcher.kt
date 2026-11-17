package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.content.Intent
import io.github.mangi.eta.agent.runtime.AgentApprovalFollowUpRuntime
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.deliverApproval
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Durable notification/Activity delivery. Regular Chat callers continue to submit choose's text themselves. */
internal object ApprovalFollowUpDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val pending get() = PendingPromptStore.followUps

    internal interface Boundary {
        fun deliver(context: Context, answer: ApprovalResolution): AgentApprovalFollowUpRuntime.Outcome
        fun openOwner(context: Context, answer: ApprovalResolution)
    }

    private object RuntimeBoundary : Boundary {
        override fun deliver(context: Context, answer: ApprovalResolution): AgentApprovalFollowUpRuntime.Outcome =
            AgentRuntimeClient(context.applicationContext, AndroidAgentLogger).deliverApproval(context, answer)

        override fun openOwner(context: Context, answer: ApprovalResolution) {
            ApprovalFollowUpDispatcher.openOwner(context, answer)
        }
    }

    /** The resolving transaction already retained this answer. Dispatch never recreates an ACKed row. */
    fun dispatch(context: Context, resolution: ApprovalResolution, openConversation: Boolean = true) {
        val app = context.applicationContext
        PendingPromptStore.init(app)
        scope.launch { dispatchPending(app, resolution, openConversation) }
    }

    /** Awaitable delivery entry; the final UI check runs inside the entry action's store lock. */
    internal suspend fun dispatchPending(
        context: Context,
        resolution: ApprovalResolution,
        openConversation: Boolean = true,
        boundary: Boundary = RuntimeBoundary,
        ownerDispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) {
        val pending = PendingPromptStore.pendingExactUnclaimed(resolution) ?: return
        val outcome = deliver(context, pending, boundary)
        if (outcome != AgentApprovalFollowUpRuntime.Outcome.ACCEPTED && openConversation) {
            withContext(ownerDispatcher) {
                PendingPromptStore.ifPendingExactUnclaimed(pending) { boundary.openOwner(context, it) }
            }
        }
    }

    /** ACK only an accepted exact run; failures release the claim for a later UI or dispatcher retry. */
    internal fun deliver(
        context: Context,
        resolution: ApprovalResolution,
        boundary: Boundary = RuntimeBoundary,
    ): AgentApprovalFollowUpRuntime.Outcome {
        val answer = PendingPromptStore.claimExact(resolution) ?: return AgentApprovalFollowUpRuntime.Outcome.UNAVAILABLE
        return try {
            val result = boundary.deliver(context, answer)
            if (result == AgentApprovalFollowUpRuntime.Outcome.ACCEPTED) PendingPromptStore.acknowledge(answer.token)
            result
        } finally {
            PendingPromptStore.release(answer.token)
        }
    }

    /** Call only for the original conversation after its submit callback is ready. */
    fun submitPending(conversationKey: String, onSubmit: (ApprovalResolution) -> Boolean): Int =
        PendingPromptStore.submitPending(conversationKey, onSubmit)

    fun openOwner(context: Context, answer: ApprovalResolution) {
        if (answer.conversationKey.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                    .putExtra(MainActivity.EXTRA_CONVERSATION_ID, answer.conversationKey)
                    .putExtra(MainActivity.EXTRA_CONVERSATION_KEY, answer.conversationKey)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { AndroidAgentLogger.warn("Approval owner entry pending: ${it.javaClass.simpleName}") }
    }
}
