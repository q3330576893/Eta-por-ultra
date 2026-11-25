package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorEndpointValidatorTest {

    @Test
    fun httpsEndpointIsAcceptedWithoutWarning() {
        val check = validateConnectorEndpoint("https://mcp.example.com/mcp")
        assertTrue("https 地址应通过：${check.error}", check.isValid)
        assertNull(check.error)
        assertNull("公网 https 不需要明文提醒", check.warning)
    }

    @Test
    fun publicHttpEndpointIsRejected() {
        val check = validateConnectorEndpoint("http://mcp.example.com/mcp")
        assertFalse(check.isValid)
        assertTrue(
            "应提示必须使用 https：${check.error}",
            check.error.orEmpty().contains("https"),
        )
    }

    @Test
    fun publicHttpByIpIsRejected() {
        val check = validateConnectorEndpoint("http://8.8.8.8/mcp")
        assertFalse(check.isValid)
        assertNotNull(check.error)
    }

    @Test
    fun loopbackHttpIsAllowedWithPlaintextWarning() {
        listOf(
            "http://localhost:3000/mcp",
            "http://127.0.0.1:3000/mcp",
            "http://127.0.0.1/mcp",
        ).forEach { raw ->
            val check = validateConnectorEndpoint(raw)
            assertTrue("$raw 应放行：${check.error}", check.isValid)
            assertEquals(
                "$raw 应提示明文风险",
                ConnectorEndpointValidator.INTERNAL_HTTP_WARNING,
                check.warning,
            )
        }
    }

    @Test
    fun rfc1918HttpIsAllowed() {
        listOf(
            "http://10.0.0.5:8080/mcp",
            "http://192.168.1.5/mcp",
            "http://172.16.0.1/mcp",
            "http://172.31.255.254/mcp",
        ).forEach { raw ->
            val check = validateConnectorEndpoint(raw)
            assertTrue("$raw 应放行：${check.error}", check.isValid)
            assertNotNull("$raw 应带明文提醒", check.warning)
        }
    }

    @Test
    fun adjacentPrivateRangesAreNotTreatedAsInternal() {
        // 172.32 与 172.15 不在 RFC1918 的 172.16/12 里，不能因为「172 开头」就放行。
        listOf("http://172.32.0.1/mcp", "http://172.15.0.1/mcp", "http://11.0.0.1/mcp").forEach { raw ->
            assertFalse("$raw 不应被视为内网", validateConnectorEndpoint(raw).isValid)
        }
    }

    @Test
    fun userInfoIsRejected() {
        val check = validateConnectorEndpoint("https://user:pass@example.com/mcp")
        assertFalse(check.isValid)
        assertTrue(
            "应提示不能包含用户名密码：${check.error}",
            check.error.orEmpty().contains("用户名密码"),
        )
    }

    @Test
    fun fragmentIsRejected() {
        val check = validateConnectorEndpoint("https://example.com/mcp#section")
        assertFalse(check.isValid)
        assertTrue(
            "应提示去掉 # 片段：${check.error}",
            check.error.orEmpty().contains("#"),
        )
    }

    @Test
    fun emptyInputIsRejected() {
        listOf("", "   ").forEach { raw ->
            val check = validateConnectorEndpoint(raw)
            assertFalse("空白地址必须拒绝", check.isValid)
            assertNotNull(check.error)
        }
    }

    @Test
    fun bareDomainSuggestsHttpsPrefix() {
        val check = validateConnectorEndpoint("mcp.example.com/mcp")
        assertFalse(check.isValid)
        assertTrue(
            "应给出补全建议：${check.error}",
            check.error.orEmpty().contains("https://mcp.example.com/mcp"),
        )
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        val check = validateConnectorEndpoint("ftp://example.com/mcp")
        assertFalse(check.isValid)
        assertTrue(check.error.orEmpty().contains("https"))
    }

    @Test
    fun missingHostIsRejected() {
        listOf("https://", "https:///mcp").forEach { raw ->
            assertFalse("$raw 缺少主机名", validateConnectorEndpoint(raw).isValid)
        }
    }

    @Test
    fun invalidPortIsRejected() {
        listOf(
            "https://example.com:0/mcp",
            "https://example.com:70000/mcp",
            "https://example.com:abc/mcp",
        ).forEach { raw ->
            assertFalse("$raw 端口无效", validateConnectorEndpoint(raw).isValid)
        }
    }

    @Test
    fun validPortAndPathAreAccepted() {
        val check = validateConnectorEndpoint("https://example.com:8443/api/mcp?x=1")
        assertTrue("${check.error}", check.isValid)
        assertNull(check.warning)
    }

    @Test
    fun ipv6LoopbackHttpIsAllowed() {
        val check = validateConnectorEndpoint("http://[::1]:3000/mcp")
        assertTrue("${check.error}", check.isValid)
        assertNotNull(check.warning)
    }

    @Test
    fun whitespaceInsideUrlIsRejected() {
        val check = validateConnectorEndpoint("https://exa mple.com/mcp")
        assertFalse(check.isValid)
    }

    @Test
    fun ipv6LoopbackVariantsAreTreatedAsPrivate() {
        // 同一台机器的等价写法都应放行，否则用户会以为校验器坏了。
        listOf(
            "http://[::1]:3000/mcp",
            "http://[0:0:0:0:0:0:0:1]:3000/mcp",
            "http://[::ffff:127.0.0.1]:3000/mcp",
        ).forEach { url ->
            assertTrue("应放行本机地址：$url", validateConnectorEndpoint(url).isValid)
        }
    }

    @Test
    fun blankOrWhitespaceEndpointIsNeverValid() {
        listOf("", "   ", "\t", "\n").forEach { raw ->
            assertFalse("空白地址必须判为无效：${raw.length}", validateConnectorEndpoint(raw).isValid)
        }
    }
}
