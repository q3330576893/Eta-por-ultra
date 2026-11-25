package io.github.mangi.eta.data.model

/**
 * 连接器地址的本地校验结果。
 *
 * [error] 非空表示不能提交；[warning] 非空表示可以提交但要提醒用户。
 * 两者互斥：内网 http 地址可以放行，同时给出明文传输提醒。
 */
internal data class ConnectorEndpointCheck(
    val error: String? = null,
    val warning: String? = null,
) {
    val isValid: Boolean get() = error == null

    companion object {
        fun ok(warning: String? = null): ConnectorEndpointCheck = ConnectorEndpointCheck(warning = warning)

        fun fail(message: String): ConnectorEndpointCheck = ConnectorEndpointCheck(error = message)
    }
}

/**
 * 新增连接时的地址格式校验：纯字符串判断，不联网、不做 DNS 解析。
 *
 * 规则（顺序即提示优先级）：
 * 1. 空串直接拒绝，避免用户对着空白框反复点「连接」；
 * 2. 不允许 `#` 片段，MCP 端点不会收到 fragment，留着只会掩盖真正的路径错误；
 * 3. 不允许空白字符，粘贴时最容易带进来；
 * 4. 必须带协议头；缺协议时给出补全建议而不是只说「无效」；
 * 5. 只接受 https；http 仅限本机与 RFC1918 内网地址，并提示明文风险；
 * 6. 不允许 userinfo（`user:pass@`），凭据只能进令牌框，不能藏在地址里；
 * 7. 主机名不能为空，端口必须是 1-65535 的数字。
 *
 * 放在这里而不是 Composable 里，是为了能单测，也方便以后别处复用。
 */
internal object ConnectorEndpointValidator {

    /** 内网明文连接的风险提示。 */
    const val INTERNAL_HTTP_WARNING: String = "内网明文连接，凭据可能被同网段看到"

    fun check(raw: String): ConnectorEndpointCheck {
        val text = raw.trim()
        if (text.isEmpty()) return ConnectorEndpointCheck.fail("请填写 MCP 地址")

        if (text.contains('#')) {
            return ConnectorEndpointCheck.fail("请去掉 # 片段：地址中 # 之后的内容不会被服务端收到")
        }
        if (text.any { it.isWhitespace() }) {
            return ConnectorEndpointCheck.fail("地址不能包含空格，请删掉多余空白")
        }
        if (!text.contains("://")) {
            val host = text.removePrefix("//")
            return ConnectorEndpointCheck.fail("地址缺少协议头，请补全为 https://$host")
        }

        val scheme = text.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") {
            return ConnectorEndpointCheck.fail("地址必须使用 https：当前协议 $scheme 不受支持")
        }

        val rest = text.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?')
        if (authority.isEmpty()) {
            return ConnectorEndpointCheck.fail("地址缺少主机名，例如 https://example.com/mcp")
        }
        if (authority.contains('@')) {
            return ConnectorEndpointCheck.fail("地址不能包含用户名密码：请删掉 user:pass@ 部分，令牌填到下面的令牌框")
        }

        val hostAndPort = splitHostAndPort(authority)
            ?: return ConnectorEndpointCheck.fail("IPv6 地址需要用方括号包起来，例如 http://[::1]:3000/mcp")
        val host = hostAndPort.first
        val port = hostAndPort.second
        if (host.isEmpty()) {
            return ConnectorEndpointCheck.fail("地址缺少主机名，例如 https://example.com/mcp")
        }
        if (port != null && !isValidPort(port)) {
            return ConnectorEndpointCheck.fail("端口无效：应为 1-65535 的数字")
        }

        if (scheme == "http") {
            if (!isPrivateHost(host)) {
                return ConnectorEndpointCheck.fail("地址必须使用 https：http 只允许本机或内网地址")
            }
            return ConnectorEndpointCheck.ok(INTERNAL_HTTP_WARNING)
        }
        return ConnectorEndpointCheck.ok()
    }

    /**
     * 拆出主机与端口；方括号 IPv6 拆不出来时返回 null 交给调用方报错。
     */
    private fun splitHostAndPort(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val host = authority.substring(1, end)
            val tail = authority.substring(end + 1)
            if (tail.isEmpty()) return host to null
            if (!tail.startsWith(":")) return null
            return host to tail.removePrefix(":")
        }
        val colonCount = authority.count { it == ':' }
        if (colonCount > 1) return null
        if (colonCount == 0) return authority to null
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':')
        return host to if (port.isEmpty()) null else port
    }

    private fun isValidPort(port: String): Boolean {
        if (port.length !in 1..5) return false
        if (!port.all { it in '0'..'9' }) return false
        val value = port.toIntOrNull() ?: return false
        return value in 1..65535
    }

    /** 本机回环与 RFC1918 私网地址：只有这些允许走明文 http。 */
    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase().trim('[', ']')
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        // IPv6 回环有多种等价写法；IPv4 映射形式与唯一本地地址同样视作内网。
        if (normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
        if (normalized == "::ffff:127.0.0.1" || normalized == "0:0:0:0:0:ffff:7f00:1") return true
        if (normalized.contains(':') &&
            (normalized.startsWith("fc") || normalized.startsWith("fd"))
        ) return true

        val octets = normalized.split('.')
        if (octets.size != 4) return false
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 }) return false
        val (a, b) = values
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }
}

/** 供界面与测试直接调用的入口，避免到处写 `ConnectorEndpointValidator.check`。 */
internal fun validateConnectorEndpoint(raw: String): ConnectorEndpointCheck =
    ConnectorEndpointValidator.check(raw)
