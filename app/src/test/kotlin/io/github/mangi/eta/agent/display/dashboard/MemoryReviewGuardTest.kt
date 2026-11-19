package io.github.mangi.eta.agent.display.dashboard

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.runtime.RunControl
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MemoryReviewGuardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun resetPendingPreferences() {
        PendingPromptStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        AgentConfirmGuard::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        context.getSharedPreferences("eta_pending_prompt", Context.MODE_PRIVATE).edit().clear().commit()
        AgentConfirmGuard.init(context)
    }

    private fun askReview(): String {
        val result = AgentConfirmGuard.ask(context, "复盘候选", "用户偏好简洁回答", listOf(
            PromptOption(PromptOptionId.MEMORY_REVIEW_WRITE, "保存"), PromptOption(PromptOptionId.MEMORY_REVIEW_DISCARD, "丢弃")),
            runId = "finished-run", conversationKey = "origin", source = PendingApproval.SOURCE_MEMORY_REVIEW,
            memoryReviewCandidates = listOf("用户偏好简洁回答"))
        val json = JSONObject(result)
        assertTrue(json.optBoolean("ok"))
        return json.getString("token")
    }

    @Test fun explicitOriginalOwnerSurvivesRunCleanupAndViewedConversationChanges() {
        RunControl.bindRun("finished-run", "origin")
        RunControl.clearRun("finished-run", "test.completed")
        AgentConfirmGuard.bindConversation("new-chat")
        val token = askReview()
        assertEquals("origin", AgentConfirmGuard.approval(token)?.conversationKey)
        assertNull(AgentConfirmGuard.pendingFor("new-chat"))
        assertNotNull(AgentConfirmGuard.pendingFor("origin"))
        assertEquals(AgentConfirmGuard.MemoryReviewDecision.NO_LONGER_PENDING,
            AgentConfirmGuard.decideMemoryReview(context, token, "new-chat", false))
        assertNotNull(AgentConfirmGuard.approval(token))
    }

    @Test fun pendingCandidatesRoundTripAndDiscardRemovesThemWithoutModelFollowup() {
        val token = askReview()
        val restored = PendingPromptStore.load().single { it.token == token }
        assertEquals(listOf("用户偏好简洁回答"), restored.memoryReviewCandidates)
        assertEquals(PendingApproval.SOURCE_MEMORY_REVIEW, restored.source)
        assertEquals(AgentConfirmGuard.MemoryReviewDecision.DISCARDED,
            AgentConfirmGuard.decideMemoryReview(context, token, "origin", false))
        assertNull(AgentConfirmGuard.approval(token))
        assertTrue(PendingPromptStore.followUps.value.isEmpty())
        assertTrue(PendingPromptStore.load().isEmpty())
        assertEquals(AgentConfirmGuard.MemoryReviewDecision.NO_LONGER_PENDING,
            AgentConfirmGuard.decideMemoryReview(context, token, "origin", false))
    }

    @Test fun upgradePrunesLegacyOwnerlessMemoryCardButKeepsNormalApprovals() {
        val prefs = context.getSharedPreferences("eta_pending_prompt", Context.MODE_PRIVATE)
        val legacy = JSONObject().put("token", "old").put("source", "ask").put("risk", "IRREVERSIBLE")
            .put("title", "后台复盘提炼出几条可长期保存的经验，是否写入记忆？").put("createdAt", System.currentTimeMillis())
        val normal = JSONObject().put("token", "normal").put("source", "guard").put("risk", "MONEY")
            .put("title", "付款").put("conversationKey", "origin").put("createdAt", System.currentTimeMillis())
        prefs.edit().putString("current", JSONArray().put(legacy).put(normal).toString()).commit()
        AgentConfirmGuard::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        AgentConfirmGuard.init(context)
        assertNull(AgentConfirmGuard.approval("old"))
        assertNotNull(AgentConfirmGuard.approval("normal"))
        assertEquals(listOf("normal"), PendingPromptStore.load().map { it.token })
    }
}
