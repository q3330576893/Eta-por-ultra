package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.display.dashboard.ApprovalResolution
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The approval branch reuses MSG_STEER, but never falls through to latest-run steering/continuation. */
internal object AgentApprovalFollowUpRuntime {
    private const val MARKER = "approval_resolution_v1"
    private const val TOKEN = "approval_token"
    private const val KIND = "approval_kind"
    private const val STATUS = "approval_status"
    private const val PAUSE = "pause"
    private val router = ApprovalRunRouter()

    enum class Outcome { ACCEPTED, INACTIVE, OWNER_MISMATCH, UNAVAILABLE }

    /**
     * Service MSG_STEER integration, before its regular steering branch:
     * if (AgentApprovalFollowUpRuntime.handleMessage(msg, ::sessionFor, ::recordSupplementEvent)) {
     *     RunProgressNotifier.refresh()
     *     refreshOverlayPhase()
     *     return
     * }
     */
    fun handleMessage(
        message: Message,
        sessionFor: (String) -> AgentRuntimeSession?,
        eventFactory: (String) -> AgentEvent.UserSupplementReceived,
    ): Boolean {
        val data = message.data
        if (data?.getBoolean(MARKER) != true) return false
        val runId = AgentRuntimeWire.runIdFromBundle(data)
        val owner = data.getString(AgentRuntimeWire.KEY_CONVERSATION_ID).orEmpty()
        val token = data.getString(TOKEN).orEmpty()
        val text = data.getString(AgentRuntimeWire.KEY_PROMPT).orEmpty()
        val kind = data.getString(KIND).orEmpty()
        val session = runId.takeIf(String::isNotBlank)?.let(sessionFor)
        val target = session?.let {
            ApprovalRunTarget(
                runId = it.runId,
                conversationKey = it.conversationKey,
                active = !it.isTerminal,
                accept = { answer ->
                    val event = it.steer(answer) { eventFactory(answer) }
                    event != null
                },
                pause = {
                    it.controller.pause()
                    RunControl.setPaused(it.runId, true, "approval.manual")
                },
                resume = {
                    it.controller.resume()
                    RunControl.setPaused(it.runId, false, "approval.resolve")
                },
            )
        }
        val outcome = if (kind == PAUSE) {
            router.pause(runId, owner, target)
        } else {
            router.deliver(token, runId, owner, text, kind == ApprovalResolution.Kind.MANUAL.name, target)
        }
        runCatching {
            message.replyTo?.send(Message.obtain(null, AgentRuntimeWire.MSG_STEER).apply {
                this.data = AgentRuntimeWire.ackBundle(runId).apply {
                    putBoolean(MARKER, true)
                    putString(TOKEN, token)
                    putString(STATUS, outcome.name)
                }
            })
        }
        return true
    }

    fun deliver(context: Context, resolution: ApprovalResolution): Outcome =
        send(context, resolution.token, resolution.conversationKey, resolution.runId, resolution.followUp, resolution.kind.name)

    fun pause(context: Context, token: String, conversationKey: String, runId: String?): Outcome =
        send(context, token, conversationKey, runId, "", PAUSE)

    private fun send(
        context: Context,
        token: String,
        conversationKey: String,
        runId: String?,
        text: String,
        kind: String,
    ): Outcome {
        // An unknown run belongs in the original conversation's durable outbox, never an aggregate target.
        if (runId.isNullOrBlank()) return Outcome.INACTIVE
        val received = CountDownLatch(1)
        val result = AtomicReference(Outcome.UNAVAILABLE)
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                val data = message.data ?: return
                if (data.getBoolean(MARKER) && data.getString(TOKEN) == token &&
                    AgentRuntimeWire.runIdFromBundle(data) == runId
                ) {
                    result.set(runCatching { Outcome.valueOf(data.getString(STATUS).orEmpty()) }.getOrDefault(Outcome.UNAVAILABLE))
                    received.countDown()
                }
            }
        })
        return runCatching {
            val lease = AgentRuntimeConnection.acquire(context.applicationContext, AndroidAgentLogger)
                ?: return Outcome.UNAVAILABLE
            lease.use {
                val message = Message.obtain(null, AgentRuntimeWire.MSG_STEER).apply {
                    replyTo = reply
                    data = AgentRuntimeWire.ackBundle(runId).apply {
                        putBoolean(MARKER, true)
                        putString(TOKEN, token)
                        putString(KIND, kind)
                        putString(AgentRuntimeWire.KEY_CONVERSATION_ID, conversationKey)
                        putString(AgentRuntimeWire.KEY_PROMPT, text)
                    }
                }
                AgentWireText.send(lease.messenger, message)
                if (received.await(8, TimeUnit.SECONDS)) result.get() else Outcome.UNAVAILABLE
            }
        }.getOrElse { Outcome.UNAVAILABLE }
    }
}

/** Injectable run operations keep exact-owner/one-shot behavior testable without Binder. */
internal data class ApprovalRunTarget(
    val runId: String,
    val conversationKey: String,
    val active: Boolean,
    val accept: (String) -> Boolean,
    val pause: () -> Unit,
    val resume: () -> Unit,
)

internal class ApprovalRunRouter {
    private data class Receipt(val runId: String, val owner: String, val text: String, val manual: Boolean)
    private val receipts = linkedMapOf<String, Receipt>()

    @Synchronized
    fun deliver(
        token: String,
        runId: String,
        owner: String,
        text: String,
        manual: Boolean,
        target: ApprovalRunTarget?,
    ): AgentApprovalFollowUpRuntime.Outcome {
        if (token.isBlank() || runId.isBlank() || text.isBlank()) return AgentApprovalFollowUpRuntime.Outcome.INACTIVE
        val receipt = Receipt(runId, owner, text, manual)
        receipts[token]?.let {
            return if (it == receipt) AgentApprovalFollowUpRuntime.Outcome.ACCEPTED
            else AgentApprovalFollowUpRuntime.Outcome.OWNER_MISMATCH
        }
        if (target == null || !target.active) return AgentApprovalFollowUpRuntime.Outcome.INACTIVE
        if (target.runId != runId || (owner.isNotBlank() && owner != target.conversationKey)) {
            return AgentApprovalFollowUpRuntime.Outcome.OWNER_MISMATCH
        }
        if (!target.accept(text)) return AgentApprovalFollowUpRuntime.Outcome.INACTIVE
        if (manual) target.pause() else target.resume()
        receipts[token] = receipt
        return AgentApprovalFollowUpRuntime.Outcome.ACCEPTED
    }

    @Synchronized
    fun pause(runId: String, owner: String, target: ApprovalRunTarget?): AgentApprovalFollowUpRuntime.Outcome {
        if (runId.isBlank() || target == null || !target.active) return AgentApprovalFollowUpRuntime.Outcome.INACTIVE
        if (target.runId != runId || (owner.isNotBlank() && target.conversationKey != owner)) {
            return AgentApprovalFollowUpRuntime.Outcome.OWNER_MISMATCH
        }
        target.pause()
        return AgentApprovalFollowUpRuntime.Outcome.ACCEPTED
    }
}

/** Explicitly addressed RuntimeClient operations; use from a worker thread like its other sync APIs. */
internal fun AgentRuntimeClient.deliverApproval(context: Context, resolution: ApprovalResolution) =
    AgentApprovalFollowUpRuntime.deliver(context, resolution)

internal fun AgentRuntimeClient.pauseApproval(context: Context, token: String, conversationKey: String, runId: String?) =
    AgentApprovalFollowUpRuntime.pause(context, token, conversationKey, runId)
