package io.github.mangi.eta.agent.display.dashboard

import android.app.Application
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MemoryReviewLifecycleTest {
    @get:Rule val directory = TemporaryFolder()
    private val now = System.currentTimeMillis()
    private fun card(owner: String = "owner-a", token: String = "review-one") = PendingApproval(
        token = token, source = PendingApproval.SOURCE_MEMORY_REVIEW,
        risk = ConfirmRisk.IRREVERSIBLE, title = "复盘候选", quiet = true, fields = emptyList(),
        conversationKey = owner, runId = "finished-run", createdAt = now,
        memoryReviewCandidates = listOf("用户喜欢简洁回答"),
        options = listOf(PromptOption(PromptOptionId.MEMORY_REVIEW_WRITE, "保存"), PromptOption(PromptOptionId.MEMORY_REVIEW_DISCARD, "丢弃")))

    @Test fun exactOwnerRemainsResolvableAfterOriginalRunHasFinished() {
        val pending = card()
        var retained = listOf(pending)
        val outbox = mutableListOf<ApprovalResolution>()
        val ledger = ApprovalLedger({ cards, _, answer -> retained = cards; answer?.let(outbox::add); true }, { now })
        ledger.restore(retained)
        var writes = 0
        assertFalse(ledger.consumeLocal(pending.token, "owner-b") { writes++ })
        assertEquals(1, ledger.all().size)
        assertTrue(ledger.consumeLocal(pending.token, "owner-a") { writes++ })
        assertEquals(1, writes)
        assertTrue(ledger.all().isEmpty())
        assertTrue(outbox.isEmpty())
    }

    @Test fun skippingPermanentlyRemovesCardAndNeverWritesMemoryOrQueuesAModelTurn() {
        val pending = card()
        var storedCards = listOf(pending)
        var outboxWrites = 0
        val ledger = ApprovalLedger({ cards, _, answer -> storedCards = cards; if (answer != null) outboxWrites++; true }, { now })
        ledger.restore(storedCards)
        assertTrue(ledger.consumeLocal(pending.token, "owner-a") { /* discard only */ })
        val restored = ApprovalLedger({ _, _, _ -> true }, { now })
        restored.restore(storedCards)
        assertTrue(restored.all().isEmpty())
        assertFalse(restored.consumeLocal(pending.token, "owner-a") { fail("Old card executed") })
        assertEquals(0, outboxWrites)
    }

    @Test fun repeatedApprovalAppendsOnceEvenWhenPreferenceCommitFailsOrProcessRestarts() {
        val store = AgentMemoryStore(directory.root)
        val pending = card()
        var persistSucceeded = false
        val ledger = ApprovalLedger({ _, _, answer -> assertNull(answer); persistSucceeded }, { now })
        ledger.restore(listOf(pending))
        assertFalse(ledger.consumeLocal(pending.token, "owner-a") { store.appendReviewedCandidates(it.token, it.memoryReviewCandidates) })
        val firstRevision = store.snapshot().revision
        // Simulates restart after the memory-file commit but before the card transaction committed.
        val reopened = AgentMemoryStore(directory.root)
        persistSucceeded = true
        ledger.restore(listOf(pending))
        assertTrue(ledger.consumeLocal(pending.token, "owner-a") { reopened.appendReviewedCandidates(it.token, it.memoryReviewCandidates) })
        assertEquals(firstRevision, reopened.snapshot().revision)
        assertEquals(1, reopened.snapshot().content.lineSequence().count { it == "- 用户喜欢简洁回答" })
        assertFalse(ledger.consumeLocal(pending.token, "owner-a") { fail("Duplicate token") })
    }

    @Test fun oldOwnerlessOrUnstructuredReviewCardsArePrunedWithoutWriting() {
        val legacy = card("").copy(source = PendingApproval.SOURCE_ASK,
            title = "后台复盘提炼出几条可长期保存的经验，是否写入记忆？", memoryReviewCandidates = emptyList())
        val ledger = ApprovalLedger({ _, _, answer -> assertNull(answer); true }, { now })
        ledger.restore(listOf(legacy, card(token = "current-review")))
        assertEquals(listOf(legacy), ledger.pruneInvalid())
        assertEquals(listOf("owner-a"), ledger.all().map { it.conversationKey })
    }

    @Test fun expiredCardCannotExecuteAndIsRemovedEvenWhenViewedFromAnotherOwner() {
        var clock = now
        val pending = card()
        val ledger = ApprovalLedger({ _, _, answer -> assertNull(answer); true }, { clock })
        ledger.restore(listOf(pending))
        clock += PendingApproval.LIFETIME_MS + 1
        assertNull(ledger.resolve(pending.token, "owner-b") { fail("Expired callback"); null })
        assertTrue(ledger.all().isEmpty())
    }

    @Test fun distinctApprovedReviewsActuallyReplaceThePreviousFileOnDisk() {
        val store = AgentMemoryStore(directory.root)
        store.appendReviewedCandidates("token-one", listOf("第一个偏好"))
        store.appendReviewedCandidates("token-two", listOf("第二个偏好"))
        val reopened = AgentMemoryStore(directory.root).snapshot().content
        assertTrue(reopened.contains("第一个偏好"))
        assertTrue(reopened.contains("第二个偏好"))
    }

    @Test fun clockRollbackCannotKeepAnOldApprovalAliveIndefinitely() {
        var clock = now
        val pending = card()
        val ledger = ApprovalLedger({ _, _, _ -> true }, { clock })
        ledger.restore(listOf(pending))
        clock -= PendingApproval.LIFETIME_MS
        assertEquals(listOf(pending), ledger.pruneInvalid())
        assertTrue(ledger.all().isEmpty())
    }

    @Test fun localWriteFailureKeepsCardAvailableForRetry() {
        val pending = card()
        val ledger = ApprovalLedger({ _, _, _ -> true }, { now })
        ledger.restore(listOf(pending))
        assertThrows(java.io.IOException::class.java) {
            ledger.consumeLocal(pending.token, "owner-a") { throw java.io.IOException("disk") }
        }
        assertNotNull(ledger.find(pending.token))
    }
}
