package io.github.mangi.eta.agent.display.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

/** Notification actions carry the token and owner; the receiver never guesses the currently viewed chat. */
internal class AgentConfirmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (token.isBlank()) return
        val owner = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        AgentConfirmGuard.init(context)
        when (intent.action) {
            ACTION_APPROVE -> AgentConfirmGuard.resolve(context, token, PromptOptionId.APPROVE, owner)
                ?.let { ApprovalFollowUpDispatcher.dispatch(context, it) }
            ACTION_REJECT -> AgentConfirmGuard.resolve(context, token, PromptOptionId.REJECT, owner)
                ?.let { ApprovalFollowUpDispatcher.dispatch(context, it) }
            ACTION_MANUAL -> AgentConfirmGuard.resolve(context, token, PromptOptionId.MANUAL, owner)
                ?.let {
                    // The dispatcher sends the manual marker to this exact run; the runtime seam pauses only it.
                    // Keep other sessions and paused flags untouched. The notification is the handoff surface.
                    ApprovalFollowUpDispatcher.dispatch(context, it, openConversation = false)
                    AgentConfirmGuard.showManualCompletionNotification(context, it)
                }
            ACTION_MANUAL_COMPLETE -> AgentConfirmGuard.completeManual(context, token, owner)
                ?.let { ApprovalFollowUpDispatcher.dispatch(context, it) }
            ACTION_OPEN_OWNER -> AgentConfirmGuard.approval(token)?.let {
                ApprovalFollowUpDispatcher.openOwner(
                    context,
                    ApprovalResolution(token, it.conversationKey, it.runId, "", ApprovalResolution.Kind.ANSWER),
                )
            }
            else -> AndroidAgentLogger.debug { "Unknown approval notification action=${intent.action}" }
        }
    }

    companion object {
        const val ACTION_APPROVE = "io.github.mangi.eta.action.CONFIRM_APPROVE"
        const val ACTION_REJECT = "io.github.mangi.eta.action.CONFIRM_REJECT"
        const val ACTION_MANUAL = "io.github.mangi.eta.action.CONFIRM_MANUAL"
        const val ACTION_MANUAL_COMPLETE = "io.github.mangi.eta.action.CONFIRM_MANUAL_COMPLETE"
        const val ACTION_OPEN_OWNER = "io.github.mangi.eta.action.CONFIRM_OPEN_OWNER"
        const val EXTRA_TOKEN = "confirm_action_token"
        const val EXTRA_CONVERSATION = "confirm_action_conversation"
    }
}
