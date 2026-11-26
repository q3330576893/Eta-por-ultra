package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * 重试链路的两类缺陷回归。
 *
 * 1) 重试事件只带 `delayMs` 与 `attempt/maxAttempts`，用户看不到「为什么」在重试：
 *    同样是「几秒后重试」，限流（等一会就好）与证书错误（等多久都不会好）
 *    对用户意味着完全不同的动作。文案必须带上原因。
 *
 * 2) `hostedToolStarted` 分支曾用 `classified.message.orEmpty()`：上游不给 message 时
 *    得到一个空串，最终展示成一条没有任何文字的失败提示。
 */
class AgentModelRetryNoticeTest {

    @Test
    fun retryNoticeStatesAttemptCountTotalAndReason() {
        val event = AgentEvent.ModelRetryScheduled(
            round = 3,
            attempt = 2,
            maxAttempts = 3,
            delayMs = 4_000,
            reasonCode = "HTTP_429",
            reasonText = "模型接口暂时限流（HTTP 429）。请稍等片刻再试。",
        )
        val message = event.displayMessage
        assertTrue("必须说明是第几次重试：$message", message.contains("第 2 次"))
        assertTrue("必须说明总次数：$message", message.contains("共 3 次"))
        assertTrue("必须说明原因：$message", message.contains("限流"))
        assertTrue("必须说明等待时长：$message", message.contains("4 秒"))
        assertFalse("非最后一次不应提示：$message", message.contains("最后一次"))
    }

    @Test
    fun lastAttemptIsCalledOutSoUsersDoNotWaitForever() {
        val event = AgentEvent.ModelRetryScheduled(1, 3, 3, 8_000, "MODEL_TIMEOUT", "请求超时")
        val message = event.displayMessage
        assertTrue("最后一次重试必须显式提示：$message", message.contains("最后一次"))
        assertTrue(message.contains("第 3 次"))
        assertTrue(message.contains("共 3 次"))
    }

    @Test
    fun reasonLabelDistinguishesRateLimitFromCertificateProblems() {
        // 同样是「等一下再试」，限流与证书错误对用户的含义完全不同。
        val rateLimit = AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "HTTP_429")
        val tls = AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "MODEL_TLS_TRUST_FAILED")
        assertEquals("服务商限流", rateLimit.reasonLabel)
        assertEquals("证书校验未通过", tls.reasonLabel)
        assertTrue(tls.displayMessage.contains("证书校验未通过"))
        assertFalse(tls.displayMessage.contains("限流"))
    }

    @Test
    fun legacyEventWithoutReasonTextStillShowsSomethingActionable() {
        // 旧版本 Runtime 不发送 reason_text，回放时也不能退化成「没有原因」。
        val legacy = AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "HTTP_503")
        assertTrue(legacy.reasonLabel.isNotBlank())
        assertTrue(legacy.displayMessage.contains("原因："))
        val unknown = AgentEvent.ModelRetryScheduled(1, 1, 3, 2_000, "SOME_NEW_CODE")
        assertTrue(unknown.reasonLabel.isNotBlank())
        assertTrue(unknown.displayMessage.contains("SOME_NEW_CODE"))
    }

    @Test
    fun subSecondDelayNeverReadsAsZeroSeconds() {
        val event = AgentEvent.ModelRetryScheduled(1, 1, 3, 400, "MODEL_TIMEOUT")
        assertFalse("不能显示「0 秒后重试」：${event.displayMessage}", event.displayMessage.contains("0 秒"))
    }

    @Test
    fun hostedToolFailureCarriesReadableReasonInsteadOfBlankMessage() {
        // 上游只给 code 不给 message 时，旧实现 `message.orEmpty()` 会得到空串。
        // 这里用真实的空 message 分类结果（stream 兜底后仍是非空白），
        // 并断言 hosted 分支不会把它变成空文案。
        val blank = AgentModelFailure.stream(JSONObject().put("type", "server_error"), "")
        val failure = assertThrows(AgentModelFailure::class.java) {
            retry().complete(
                initialRound = 1,
                initialRequest = request(),
                provider = provider { _, emit ->
                    emit(ProviderEvent.RequestStarted)
                    // hosted 工具已开始：重放会重复副作用，必须直接失败且不重试。
                    emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                    throw blank
                },
                controller = AgentRunController(),
                onEvent = {},
                onProviderEvent = { _, _ -> },
                discardAttemptReasoning = {},
            )
        }
        assertTrue("hosted 工具失败也不能是空文案：'${failure.message}'", failure.message.orEmpty().isNotBlank())
        assertFalse("hosted 工具已执行，不得重试", failure.retryable)
        assertEquals("PROVIDER_STREAM_ERROR", failure.code)
    }

    @Test
    fun exhaustedRetriesKeepTheReasonInTheFinalMessage() {
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            retry().complete(
                initialRound = 1,
                initialRequest = request(),
                provider = provider { _, _ ->
                    calls++
                    throw SocketTimeoutException("timeout")
                },
                controller = AgentRunController(),
                onEvent = {},
                onProviderEvent = { _, _ -> },
                discardAttemptReasoning = {},
            )
        }
        // 重试上限与退避算法不变：1 次首发 + 3 次重试。
        assertEquals(4, calls)
        val message = failure.message.orEmpty()
        assertTrue("最终文案必须保留原因：$message", message.contains("超时"))
        assertTrue("最终文案必须说明已重试次数：$message", message.contains("已重试 3 次"))
        assertFalse(failure.retryable)
    }

    @Test
    fun scheduledRetryEventCarriesTheClassifierReason() {
        // 端到端：分类结果必须进入 ModelRetryScheduled，而不是只留一个错误码。
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val result = retry().complete(
            initialRound = 1,
            initialRequest = request(),
            provider = provider { _, _ ->
                if (calls++ == 0) throw SocketTimeoutException("timeout")
                ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))
            },
            controller = AgentRunController(),
            onEvent = { events += it },
            onProviderEvent = { _, _ -> },
            discardAttemptReasoning = {},
        )
        assertEquals(2, result.round)
        val scheduled = events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single()
        assertTrue("重试事件必须带可读原因", scheduled.reasonText.isNotBlank())
        assertTrue("原因应说明超时", scheduled.reasonText.contains("超时"))
        assertTrue(scheduled.displayMessage.contains("原因："))
        assertTrue(scheduled.displayMessage.contains("超时"))
    }

    private fun retry() = AgentModelRetry(waitBeforeRetry = { _, _ -> })

    private fun request() = ProviderRequest(
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        ),
        JSONArray(), JSONArray(),
    )

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities =
                ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse = action(request, onEvent)
        }
}
