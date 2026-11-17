package io.github.mangi.eta.agent.display.dashboard

import io.github.mangi.eta.agent.runtime.ApprovalRunRouter
import io.github.mangi.eta.agent.runtime.AgentApprovalFollowUpRuntime
import io.github.mangi.eta.agent.runtime.ApprovalRunTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalResolutionTest {
    private fun pending(
        token: String = "token-a",
        conversation: String = "conversation-a",
        runId: String? = "run-a",
        createdAt: Long = System.currentTimeMillis(),
        allowMultiple: Boolean = false,
    ) = PendingApproval(
        token = token,
        risk = ConfirmRisk.IRREVERSIBLE,
        title = "提交操作",
        fields = listOf(ConfirmField("页面", "订单")),
        quiet = false,
        options = listOf(
            PromptOption(PromptOptionId.APPROVE, "批准", "继续"),
            PromptOption(PromptOptionId.REJECT, "拒绝", "停止"),
            PromptOption(PromptOptionId.MANUAL, "我自己来"),
        ),
        allowMultiple = allowMultiple,
        conversationKey = conversation,
        runId = runId,
        createdAt = createdAt,
    )

    @Test
    fun approvalResolutionKeepsExactOwnerAndRun() {
        val answer = ApprovalAnswers.option(pending(), PromptOptionId.APPROVE)
        assertNotNull(answer)
        assertEquals("token-a", answer!!.token)
        assertEquals("conversation-a", answer.conversationKey)
        assertEquals("run-a", answer.runId)
        assertTrue(answer.followUp.contains("observe_screen"))
        assertTrue(answer.followUp.contains("不要重放"))
    }

    @Test
    fun invalidOptionDoesNotCreateResolution() {
        assertNull(ApprovalAnswers.option(pending(), "wrong-option"))
        assertNull(ApprovalAnswers.freeform(pending(), " ", emptyList()))
        assertNull(ApprovalAnswers.many(pending(), listOf(PromptOptionId.APPROVE)))
    }

    @Test
    fun manualCompletionKeepsOwnerAndUsesIndependentDeliveryToken() {
        val manual = ApprovalAnswers.option(pending(), PromptOptionId.MANUAL)
        assertEquals(ApprovalResolution.Kind.MANUAL, manual?.kind)
        assertEquals("token-a", manual?.token)
        val completed = ApprovalAnswers.manualComplete(pending())
        assertEquals(ApprovalResolution.Kind.MANUAL_COMPLETE, completed.kind)
        assertEquals("token-a:manual-complete", completed.token)
        assertEquals("conversation-a", completed.conversationKey)
        assertEquals("run-a", completed.runId)
        assertTrue(completed.followUp.contains("observe_screen"))
    }

    @Test
    fun ledgerRejectsWrongOwnerAndExpiredToken() {
        val persisted = mutableListOf<ApprovalResolution?>()
        val ledger = ApprovalLedger(persist = { _, _, answer ->
            persisted += answer
            true
        })
        val now = System.currentTimeMillis()
        ledger.restore(listOf(pending(createdAt = now), pending("expired", createdAt = now - PendingApproval.LIFETIME_MS - 1)))
        assertNull(ledger.resolve("token-a", "conversation-b") { ApprovalAnswers.option(it, PromptOptionId.APPROVE) })
        assertNull(ledger.resolve("expired", "conversation-a") { ApprovalAnswers.option(it, PromptOptionId.APPROVE) })
        assertEquals(1, ledger.all().size)
        assertEquals(0, persisted.filterNotNull().size)
    }

    @Test
    fun ledgerConsumesOnceAndRetainsAnswerWhenRequested() {
        val persisted = mutableListOf<ApprovalResolution?>()
        val ledger = ApprovalLedger(persist = { _, _, answer ->
            persisted += answer
            true
        })
        ledger.restore(listOf(pending()))
        val first = ledger.resolve("token-a", "conversation-a") { ApprovalAnswers.option(it, PromptOptionId.REJECT) }
        val second = ledger.resolve("token-a", "conversation-a") { ApprovalAnswers.option(it, PromptOptionId.REJECT) }
        assertNotNull(first)
        assertNull(second)
        assertEquals(1, persisted.filterNotNull().size)
        assertEquals("conversation-a", persisted.filterNotNull().single().conversationKey)
    }

    @Test
    fun legacyAnswerDoesNotAlsoQueueDelivery() {
        val answers = mutableListOf<ApprovalResolution?>()
        val ledger = ApprovalLedger(persist = { _, _, answer -> answers += answer; true })
        ledger.restore(listOf(pending()))
        val answer = ledger.resolve("token-a", "conversation-a", retainAnswer = false) {
            ApprovalAnswers.option(it, PromptOptionId.APPROVE)
        }
        assertNotNull(answer)
        assertTrue(answers.filterNotNull().isEmpty())
        assertTrue(ledger.all().isEmpty())
    }

    @Test
    fun failedDurableWriteLeavesCardAvailable() {
        val ledger = ApprovalLedger(persist = { _, _, _ -> false })
        ledger.restore(listOf(pending()))
        assertNull(ledger.resolve("token-a", "conversation-a") { ApprovalAnswers.option(it, PromptOptionId.APPROVE) })
        assertEquals("token-a", ledger.all().single().token)
    }

    @Test
    fun manualCompletionConsumesOriginalTokenExactlyOnceAndKeepsOtherCard() {
        val ledger = ApprovalLedger(persist = { _, _, _ -> true })
        ledger.restore(listOf(pending(), pending("token-b", "conversation-b", "run-b")))
        assertNotNull(ledger.resolve("token-a", "conversation-a") { ApprovalAnswers.option(it, PromptOptionId.MANUAL) })
        assertNull(ledger.completeManual("token-a", "conversation-b"))
        val complete = ledger.completeManual("token-a", "conversation-a")
        assertEquals("token-a:manual-complete", complete?.token)
        assertEquals("run-a", complete?.runId)
        assertNull(ledger.completeManual("token-a", "conversation-a"))
        assertEquals("token-b", ledger.all().single().token)
    }

    @Test
    fun manualPauseAndCompletionResumeOnlyOriginalRun() {
        val paused = mutableSetOf("run-b")
        val accepted = mutableListOf<String>()
        val target = ApprovalRunTarget(
            "run-a", "conversation-a", true,
            { accepted += it; true },
            { paused += "run-a" },
            { paused -= "run-a" },
        )
        val router = ApprovalRunRouter()
        assertEquals(
            AgentApprovalFollowUpRuntime.Outcome.ACCEPTED,
            router.deliver("token-a", "run-a", "conversation-a", "manual", true, target),
        )
        assertEquals(setOf("run-a", "run-b"), paused)
        assertEquals(
            AgentApprovalFollowUpRuntime.Outcome.ACCEPTED,
            router.deliver("token-a:manual-complete", "run-a", "conversation-a", "observe_screen", false, target),
        )
        assertEquals(setOf("run-b"), paused)
        assertEquals(listOf("manual", "observe_screen"), accepted)
    }

    @Test
    fun routerRequiresExactRunAndOwnerAndIsIdempotent() {
        var accepted = 0
        var resumed = 0
        val target = ApprovalRunTarget(
            runId = "run-a",
            conversationKey = "conversation-a",
            active = true,
            accept = { accepted += 1; true },
            pause = {},
            resume = { resumed += 1 },
        )
        val router = ApprovalRunRouter()
        assertEquals(
            AgentApprovalFollowUpRuntime.Outcome.OWNER_MISMATCH,
            router.deliver("token-a", "run-a", "conversation-b", "answer", false, target),
        )
        assertEquals(
            AgentApprovalFollowUpRuntime.Outcome.ACCEPTED,
            router.deliver("token-a", "run-a", "conversation-a", "answer", false, target),
        )
        assertEquals(
            AgentApprovalFollowUpRuntime.Outcome.ACCEPTED,
            router.deliver("token-a", "run-a", "conversation-a", "answer", false, target),
        )
        assertEquals(1, accepted)
        assertEquals(1, resumed)
    }

    @Test
    fun routerLeavesInactiveRunForConversationOutbox() {
        val router = ApprovalRunRouter()
        val inactive = ApprovalRunTarget("run-a", "conversation-a", false, { true }, {}, {})
        assertEquals(
            AgentApprovalFollowUpRuntime.Outcome.INACTIVE,
            router.deliver("token-a", "run-a", "conversation-a", "answer", false, inactive),
        )
        assertEquals(
            AgentApprovalFollowUpRuntime.Outcome.INACTIVE,
            router.deliver("token-a", "", "conversation-a", "answer", false, null),
        )
    }
}
