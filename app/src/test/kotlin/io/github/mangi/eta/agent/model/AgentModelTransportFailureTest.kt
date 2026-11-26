package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 传输层失败必须分类成可读的中文原因。
 *
 * 真实故障：`transport()` 对 `SSLException` / `ProtocolException` 返回 null，
 * 调用方 `AgentModelRetry` 于是 `?: throw failure` 把原始异常抛出去，
 * `AgentRuntimeRunExecutor` 最终用 `throwable.message ?: javaClass.simpleName`
 * 当展示文案——用户看到的是一条英文类名（`SSLHandshakeException`），
 * 既不知道发生了什么，也不知道该改什么，属于「没有任何错误提示」的静默失败。
 */
class AgentModelTransportFailureTest {

    @Test
    fun untrustedCertificateIsActionableAndNotRetried() {
        // 证书过期 / 自签名 / 主机名不匹配：重试三次只是白等，必须直接说清并停止重试。
        val rawFailures = listOf(
            SSLHandshakeException("java.security.cert.CertificateException: PKIX path building failed"),
            SSLPeerUnverifiedException("Hostname example.invalid not verified"),
            SSLException("Trust anchor for certification path not found"),
            SSLException("self signed certificate in certificate chain"),
            SSLException("Certificate expired", CertificateExpiredException()),
        )
        for (raw in rawFailures) {
            val failure = requireNotNull(AgentModelFailure.transport(raw)) {
                "TLS 失败必须被分类：${raw.message}"
            }
            assertEquals("MODEL_TLS_TRUST_FAILED", failure.code)
            assertFalse("证书不被信任时不应重试：${raw.message}", failure.retryable)
            val message = failure.message.orEmpty()
            assertTrue("必须说明是证书问题：$message", message.contains("证书"))
            assertTrue("必须说明重试无用：$message", message.contains("重试"))
            assertTrue("不能是空串", message.isNotBlank())
        }
    }

    @Test
    fun transientTlsHandshakeFailureStaysRetryable() {
        // 握手被中断（无证书语义）属于可恢复的网络问题，保持重试。
        val failure = requireNotNull(
            AgentModelFailure.transport(SSLException("Connection reset during handshake")),
        )
        assertEquals("MODEL_TLS_FAILED", failure.code)
        assertTrue(failure.retryable)
        assertTrue(failure.message.orEmpty().contains("加密连接"))
    }

    @Test
    fun protocolErrorIsClassifiedInsteadOfLeakingRawException() {
        val failure = requireNotNull(AgentModelFailure.transport(ProtocolException("unexpected status line"))) {
            "协议错误必须被分类，否则用户只看到类名"
        }
        assertEquals("MODEL_PROTOCOL_ERROR", failure.code)
        val message = failure.message.orEmpty()
        assertTrue("必须是可读中文：$message", message.contains("无法解析"))
        assertFalse("不能把英文类名当文案：$message", message.contains("ProtocolException"))
    }

    @Test
    fun timeoutAndConnectionFailuresKeepTheirExistingCodes() {
        // 不改变既有错误码：MODEL_TIMEOUT / MODEL_CONNECTION_FAILED 必须保持。
        val timeout = requireNotNull(AgentModelFailure.transport(SocketTimeoutException()))
        assertEquals("MODEL_TIMEOUT", timeout.code)
        assertTrue(timeout.retryable)
        assertEquals("MODEL_TIMEOUT", requireNotNull(AgentModelFailure.transport(InterruptedIOException())).code)
        val broken = requireNotNull(AgentModelFailure.transport(IOException("reset")))
        assertEquals("MODEL_CONNECTION_FAILED", broken.code)
        assertTrue(broken.retryable)
    }

    @Test
    fun nonTransportExceptionsStillReturnNull() {
        // 非传输层异常不是本分类器的职责，仍由调用方原样抛出。
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertNull(AgentModelFailure.transport(IllegalStateException("logic")))
    }

    @Test
    fun alreadyClassifiedFailurePassesThroughUnchanged() {
        val classified = AgentModelFailure("HTTP_429", true, "模型接口暂时限流（HTTP 429）。")
        val same = requireNotNull(AgentModelFailure.transport(classified))
        assertEquals(classified.code, same.code)
        assertEquals(classified.message, same.message)
    }

    @Test
    fun blankMessageNeverReachesTheUser() {
        // 静默失败的根因：空 message 会变成一条没有任何文字的失败提示。
        val blank = AgentModelFailure("PROVIDER_STREAM_ERROR", true, "")
        assertTrue("构造时必须兜底成非空白文案", blank.message.orEmpty().isNotBlank())
        assertTrue(blank.userMessage.isNotBlank())
        assertEquals(blank.message, blank.userMessage)

        val whitespace = AgentModelFailure("MODEL_TIMEOUT", true, "   \n\t ")
        assertTrue(whitespace.message.orEmpty().isNotBlank())
        assertTrue(whitespace.userMessage.isNotBlank())
        assertTrue(whitespace.userMessage.contains("超时"))
    }

    @Test
    fun defaultMessageIsActionableForEveryCodeFamily() {
        // 兜底文案必须可操作：既不能是空串，也不能只是错误码。
        val codes = listOf(
            "HTTP_400", "HTTP_401", "HTTP_403", "HTTP_404", "HTTP_418",
            "HTTP_500", "HTTP_503",
            "MODEL_TIMEOUT", "MODEL_CONNECTION_FAILED", "MODEL_TLS_FAILED",
            "MODEL_TLS_TRUST_FAILED", "MODEL_PROTOCOL_ERROR",
            "PROVIDER_STREAM_ERROR", "STREAM_INCOMPLETE", "SOME_FUTURE_CODE",
        )
        for (code in codes) {
            val message = defaultModelFailureMessage(code)
            assertTrue("$code 兜底文案不能为空", message.isNotBlank())
            assertTrue("$code 兜底文案不能只有错误码：$message", message.length > code.length)
            assertTrue("$code 兜底文案必须是中文：$message", message.any { it in '\u4e00'..'\u9fff' })
        }
    }

    @Test
    fun certPathValidatorCauseIsAlsoTreatedAsTrustFailure() {
        val raw = SSLHandshakeException("handshake failed")
        raw.initCause(CertPathValidatorException("Path does not chain with any of the trust anchors"))
        val failure = requireNotNull(AgentModelFailure.transport(raw))
        assertEquals("MODEL_TLS_TRUST_FAILED", failure.code)
        assertFalse(failure.retryable)
    }
}
