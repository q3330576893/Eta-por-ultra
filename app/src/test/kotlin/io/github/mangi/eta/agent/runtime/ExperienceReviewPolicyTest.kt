package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class ExperienceReviewPolicyTest {
    @Test
    fun skillReviewRequiresDifficultGuiWorkOrExplicitRequest() {
        assertFalse(ExperienceReviewPolicy.hasSkillSignal("你好", listOf(finished("tap", true))))
        assertTrue(
            ExperienceReviewPolicy.hasSkillSignal(
                "打开便签",
                List(8) { finished("observe_screen", true) })
        )
        assertTrue(
            ExperienceReviewPolicy.hasSkillSignal(
                "打开便签",
                listOf(finished("tap_element", false), finished("tap_element", true))
            )
        )
        assertFalse(
            ExperienceReviewPolicy.hasSkillSignal(
                "查天气",
                listOf(finished("web_search", false), finished("web_search", true))
            )
        )
        assertTrue(ExperienceReviewPolicy.hasSkillSignal("记住这个操作方法", emptyList()))
    }

    @Test
    fun reviewCannotClearMemoryOperateDeviceOrInstallRemoteCode() {
        assertTrue(
            ExperienceReviewPolicy.permits(
                "memory_write",
                JSONObject().put("mode", "append"),
                true,
                true
            )
        )
        assertFalse(
            ExperienceReviewPolicy.permits(
                "memory_write",
                JSONObject().put("mode", "clear"),
                true,
                true
            )
        )
        assertFalse(
            ExperienceReviewPolicy.permits(
                "memory_write",
                JSONObject().put("mode", "replace_range"),
                true,
                true
            )
        )
        for (tool in listOf("terminal", "tap", "skills_install_from_github", "tasks_create"))
            assertFalse(ExperienceReviewPolicy.permits(tool, JSONObject(), true, true))
    }

    @Test
    fun optOutRevokesWritesAndReadsOnNextDispatch() {
        assertFalse(ExperienceReviewPolicy.permits("memory_get", JSONObject(), false, true))
        assertFalse(ExperienceReviewPolicy.permits("skills_manage", JSONObject(), true, false))
        assertTrue(ExperienceReviewPolicy.permits("skills_read", JSONObject(), false, true))
    }

    @Test
    fun distillationDropsSensitiveToolOutputsAndCredentials() {
        // 敏感工具判定：mcp_ / personal_ 前缀 + 全局策略里的凭据类工具。
        assertTrue(ExperienceReviewPolicy.isSensitiveForDistillation("mcp_github_read"))
        assertTrue(ExperienceReviewPolicy.isSensitiveForDistillation("personal_search_orders"))
        assertTrue(ExperienceReviewPolicy.isSensitiveForDistillation("read_sms_code"))
        assertFalse(ExperienceReviewPolicy.isSensitiveForDistillation("observe_screen"))

        // 凭据命中即整批作废。
        assertTrue(ExperienceReviewPolicy.containsCredential("api_key: sk-abcdefghijklmnop"))
        assertTrue(ExperienceReviewPolicy.containsCredential("密码 = hunter2"))
        assertTrue(ExperienceReviewPolicy.containsCredential("验证码 123456"))
        assertFalse(ExperienceReviewPolicy.containsCredential("偏好深色主题"))

        // 候选最多 3 条；含敏感/凭据/超长行时整批拒绝。
        val ok = ExperienceReviewPolicy.distillCandidates("- 偏好深色主题\n- 住在杭州\n- 用 Gmail")
        assertEquals(3, ok.size)
        assertEquals("偏好深色主题", ok[0])
        assertTrue(
            ExperienceReviewPolicy.distillCandidates("- 偏好深色主题\n- token=abc123xyz").isEmpty(),
        )
        assertTrue(
            ExperienceReviewPolicy.distillCandidates("api_key: sk-abcdefghijklmnop").isEmpty(),
        )
        assertTrue(ExperienceReviewPolicy.distillCandidates("").isEmpty())
    }

    @Test
    fun digestRedactsCredentialsInTranscript() {
        val response = AgentModelClient.ModelResponse.Text(
            "ok",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "设置 api_key: sk-abcdefghijklmnop 之后可用",
                ),
            ),
        )
        val digest = ExperienceReviewPolicy.digest("任务", response, emptyList(), 2000)
        assertFalse(digest.contains("sk-abcdefghijklmnop"))
        assertTrue(digest.contains("[REDACTED]"))
    }

    @Test
    fun sensitiveToolCallIdsExcludesTheirTranscriptMessages() {
        val started = AgentEvent.ToolStarted(1, "call-secret", "mcp_github_read", "args")
        val response = AgentModelClient.ModelResponse.Text(
            "ok",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    toolCallsJson = """{"tool_calls":[{"id":"call-secret","name":"mcp_github_read"}]}""",
                ),
                AgentModelClient.ConversationMessage(role = "tool", toolCallId = "call-secret", content = "SECRET_PAYLOAD"),
                AgentModelClient.ConversationMessage(role = "tool", toolCallId = "call-ok", content = "公开事实"),
            ),
        )
        val ids = ExperienceReviewPolicy.sensitiveToolCallIds(response, listOf(started))
        assertTrue("call-secret" in ids)
        val digest = ExperienceReviewPolicy.digest("任务", response, listOf(started), 4000)
        assertFalse(digest.contains("SECRET_PAYLOAD"))
        assertTrue(digest.contains("公开事实"))
    }

    @Test
    fun digestOmitsReasoningAndMarksTruncatedEvidence() {
        val response = AgentModelClient.ModelResponse.Text(
            "完成",
            reasoningContent = "secret-reasoning",
            transcript = List(10) {
                AgentModelClient.ConversationMessage(
                    "assistant",
                    "x".repeat(2000),
                    reasoningContent = "secret-reasoning"
                )
            })
        val digest = ExperienceReviewPolicy.digest("任务", response, emptyList(), 1000)
        assertFalse(digest.contains("secret-reasoning"))
        assertTrue(JSONObject(digest).getBoolean("evidence_truncated"))
        assertTrue(digest.length < 2000)
    }

    private fun finished(name: String, success: Boolean) =
        AgentEvent.ToolFinished(1, "call", name, "result", 0, 0, success)
}
