package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 永久失败判定的误判回归。
 *
 * 真实缺陷：`isPermanent` 对整段响应正文做裸词包含判断
 * （`listOf("billing", "usage limit", …).any { body.contains(it) }`）。
 * 于是只要正文里出现 `billing` 字样——哪怕只是帮助文档链接
 * `https://platform.example/billing`、或套餐说明——就判定为「永久失败」：
 * 不重试，并告诉用户「额度或计费受限，请检查服务商账户」。
 * 用户于是去充值、查账单，实际只是限流。
 *
 * 同理 `usage limit` 出现在 `usage limit resets in 60s`（可恢复限流）里也会误判。
 */
class AgentModelPermanentFailureClassificationTest {

    @Test
    fun billingWordInUnrelatedBodyDoesNotMakeFailurePermanent() {
        // 帮助链接里带 billing：这是可重试的服务端错误，不是欠费。
        val body = """{"error":{"type":"server_error",
            "message":"upstream failed, see https://platform.example/billing for account details"}}"""
        val failure = AgentModelFailure.http(503, body)
        assertEquals("HTTP_503", failure.code)
        assertTrue("仅出现 billing 链接不应判定永久失败", failure.retryable)
        assertFalse(
            "不能提示用户检查账户余额：${failure.message}",
            failure.message.orEmpty().contains("额度或计费受限"),
        )
    }

    @Test
    fun resettableUsageLimitStaysRetryable() {
        // 「60 秒后重置」是可恢复限流，必须重试。
        val body = """{"error":{"code":"rate_limit_exceeded",
            "message":"usage limit resets in 60s, please retry"}}"""
        val failure = AgentModelFailure.http(429, body)
        assertTrue("可重置的限流必须可重试", failure.retryable)
        assertFalse(
            "不能判定为计费受限：${failure.message}",
            failure.message.orEmpty().contains("额度或计费受限"),
        )
    }

    @Test
    fun realBillingFailuresAreStillPermanent() {
        val bodies = listOf(
            """{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}""",
            """{"error":{"type":"billing_error","message":"billing_error"}}""",
            """{"error":{"message":"Your credit balance is too low to access the API"}}""",
            """{"error":{"message":"billing is required to continue"}}""",
            """{"error":{"message":"You have hit your usage limit reached for this month"}}""",
            """{"error":{"message":"insufficient_balance"}}""",
        )
        for (body in bodies) {
            val failure = AgentModelFailure.http(429, body)
            assertFalse("真实计费失败不应重试：$body", failure.retryable)
            assertTrue(
                "应说明是额度或计费问题：$body → ${failure.message}",
                failure.message.orEmpty().contains("额度或计费受限"),
            )
        }
    }

    @Test
    fun htmlErrorPageIsNotParsedAsBillingReason() {
        // 网关错误页很长且可能含 billing 字样；短正文才当错误说明读。
        val filler = "<p>documentation</p>".repeat(60)
        val html = "<html><body>billing $filler</body></html>"
        assertTrue(html.length > 512)
        val failure = AgentModelFailure.http(502, html)
        assertTrue("长 HTML 错误页不应被当成计费原因", failure.retryable)
    }

    @Test
    fun streamPermanentCheckUsesMessageNotRawBody() {
        // 流式失败只看 error.message，不再对任意文本做裸词判断。
        val resettable = JSONObject()
            .put("code", "rate_limit_exceeded")
            .put("message", "usage limit resets in 60s")
        assertTrue(AgentModelFailure.stream(resettable, "模型接口 SSE 返回错误").retryable)

        val billing = JSONObject()
            .put("code", "insufficient_quota")
            .put("message", "quota exceeded")
        assertFalse(AgentModelFailure.stream(billing, "模型接口 SSE 返回错误").retryable)
    }

    @Test
    fun blankStreamMessageFallsBackToActionableChinese() {
        // 上游只给 code 不给 message：`{"error":{"type":"server_error"}}`。
        // 旧实现把空串直接透出，用户看到一条没有文字的失败提示。
        val failure = AgentModelFailure.stream(JSONObject().put("type", "server_error"), "")
        assertEquals("PROVIDER_STREAM_ERROR", failure.code)
        val message = failure.message.orEmpty()
        assertTrue("不能是空串", message.isNotBlank())
        assertTrue("必须是可读中文：$message", message.any { it in '\u4e00'..'\u9fff' })
        assertFalse("不能把英文类名当文案：$message", message.contains("PROVIDER_STREAM_ERROR 未提供"))
        assertTrue("仍应可重试", failure.retryable)
    }

    @Test
    fun whitespaceOnlyStreamMessageAlsoFallsBack() {
        val failure = AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "   ")
        assertTrue(failure.message.orEmpty().isNotBlank())
    }

    @Test
    fun realStreamMessageIsPreservedVerbatim() {
        // 上游给了可读原因时不能被兜底覆盖。
        val upstream = "模型接口 SSE 返回错误 (code=overloaded)：服务过载，请稍后重试"
        val failure = AgentModelFailure.stream(JSONObject().put("code", "overloaded"), upstream)
        assertEquals(upstream, failure.message)
    }

    @Test
    fun blankIncompleteStreamMessageFallsBack() {
        val failure = AgentModelFailure.incompleteStream("")
        assertEquals("STREAM_INCOMPLETE", failure.code)
        assertTrue(failure.message.orEmpty().isNotBlank())
        assertTrue(failure.retryable)
    }

    @Test
    fun httpStatusMessagesAreNeverEmptyOrBareCode() {
        // 覆盖所有状态分支：文案必须可操作，不能只有「模型接口返回 HTTP 5xx」。
        for (status in listOf(400, 401, 403, 404, 408, 409, 418, 422, 429, 500, 501, 502, 503, 504, 524, 529, 599)) {
            val failure = AgentModelFailure.http(status, "")
            val message = failure.message.orEmpty()
            assertTrue("HTTP $status 文案不能为空", message.isNotBlank())
            assertTrue("HTTP $status 文案必须含中文建议：$message", message.any { it in '\u4e00'..'\u9fff' })
        }
    }
}
