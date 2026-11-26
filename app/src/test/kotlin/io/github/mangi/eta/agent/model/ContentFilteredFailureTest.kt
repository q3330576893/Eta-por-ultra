package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游内容策略拦截的回归。
 *
 * 真实故障：网关返回 `request_filtered`，App 把它压成通用的「参数无效，请检查模型配置」，
 * 用户改配置、反复重试都没有用——因为根本不是参数问题。
 */
class ContentFilteredFailureTest {

    @Test
    fun requestFilteredIsReportedAsContentPolicyNotParameterError() {
        val failure = AgentModelFailure.http(
            400,
            """{"error":{"code":"request_filtered","message":"Request rejected by policy"}}""",
        )

        assertEquals("PROVIDER_CONTENT_FILTERED", failure.code)
        assertFalse("内容策略拦截不应重试", failure.retryable)
        val message = failure.message.orEmpty()
        assertTrue("应说明是内容策略拦截：$message", message.contains("内容策略"))
        assertTrue("应说明重试无用：$message", message.contains("重试"))
        assertFalse("不能再说成参数无效：$message", message.contains("参数无效"))
    }

    @Test
    fun markerInRawBodyAloneIsEnough() {
        // 有些网关把原因只放在纯文本 body 里，没有 JSON error 对象。
        val failure = AgentModelFailure.http(400, "request_filtered: blocked by content policy")
        assertEquals("PROVIDER_CONTENT_FILTERED", failure.code)
    }

    @Test
    fun streamContentFilterIsAlsoClassified() {
        val error = org.json.JSONObject()
            .put("code", "content_filter")
            .put("message", "blocked by content management policy")
        val failure = AgentModelFailure.stream(error, "内容被拦截")
        assertEquals("PROVIDER_CONTENT_FILTERED", failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun ordinaryParameterErrorStillReportsParameterProblem() {
        val failure = AgentModelFailure.http(
            400,
            """{"error":{"code":"invalid_request_error","message":"messages: role invalid"}}""",
        )
        assertEquals("HTTP_400", failure.code)
        assertTrue(failure.message.orEmpty().contains("参数无效"))
    }

    @Test
    fun rateLimitStaysRetryable() {
        val failure = AgentModelFailure.http(429, """{"error":{"code":"rate_limit_exceeded"}}""")
        assertEquals("HTTP_429", failure.code)
        assertTrue("限流仍应可重试", failure.retryable)
    }

    @Test
    fun upstreamCancellationIsNeverRetried() {
        // 用户看到的现象：一直重试、上游 error、结束原因 client_gone。
        // 这类失败重试三轮也只是白等，必须直接终止。
        listOf(
            "client_gone",
            "context canceled",
            "run_cancelled",
            "request aborted",
        ).forEach { marker ->
            val error = org.json.JSONObject().put("code", marker).put("message", "upstream aborted")
            val failure = AgentModelFailure.stream(error, "上游中断")
            assertEquals("标记 $marker 应归类为上游取消", "PROVIDER_UPSTREAM_CANCELLED", failure.code)
            assertFalse("标记 $marker 不应重试", failure.retryable)
            assertTrue("应说明重试无效", failure.message.orEmpty().contains("重试不会改变结果"))
        }
    }

    @Test
    fun ordinaryStreamErrorStaysRetryable() {
        val error = org.json.JSONObject().put("code", "server_error").put("message", "temporary")
        val failure = AgentModelFailure.stream(error, "服务端暂时错误")
        assertTrue("普通服务端错误仍应可重试", failure.retryable)
    }
}
