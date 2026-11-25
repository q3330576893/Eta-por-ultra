package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
            "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED",
        )

        /** 上下文超限错误码：上层据此给出压缩或新开会话的建议。 */
        const val CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"

        /** 上下文超限：给一句可操作的中文建议（工具档调低 / 新开会话）。 */
        private const val CONTEXT_OVERFLOW_HINT =
            "模型上下文超过容量限制。可把顶栏「工具档」调低（标准或极简），或新开会话继续。"

        /** 上游内容策略拦截的常见标记；命中即判定为被过滤而不是参数错误。 */
        private val CONTENT_FILTER_MARKERS = listOf(
            "request_filtered",
            "content_filter",
            "content_policy",
            "content management policy",
            "flagged by our safety",
            "blocked by content",
        )

        private val invalidApiKeyReasons = setOf("API_KEY_INVALID", "API_KEY_EXPIRED")

        fun http(status: Int, body: String, includeParameterDetails: Boolean = false): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            authenticationFailure(error)?.let { return it }
            if (status == 403) gatewayAccessFailure(error)?.let { return it }
            // 上游内容策略过滤：不是参数写错，重试也不会好，必须如实告诉用户原因。
            contentFilteredFailure(error, body)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                CONTEXT_OVERFLOW, false, CONTEXT_OVERFLOW_HINT,
            )
            val permanent = isPermanent(error, body)
            if (status == 400 && includeParameterDetails && !permanent) {
                ProviderParameterDiagnostics.describe(error)?.let { details ->
                    return AgentModelFailure("HTTP_400", false, "模型请求参数无效（HTTP 400；$details），请检查模型配置。")
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
                else when (status) {
                    400 -> {
                        // 只有显式请求诊断时才回显上游字段：默认文案绝不能带上响应内容，
                        // 否则上游把敏感串塞进 code/type 就会被原样展示。
                        val upstream = if (includeParameterDetails) {
                            runCatching {
                                JSONObject(body).optJSONObject("error")
                                    ?.let { it.optString("code").ifBlank { it.optString("type") } }
                                    ?: JSONObject(body).optString("code")
                            }.getOrNull()?.takeIf(::isTrustedDiagnosticToken).orEmpty()
                        } else {
                            ""
                        }
                        if (upstream.isBlank()) {
                            "模型请求参数无效（HTTP 400），请检查模型配置。"
                        } else {
                            "模型请求参数无效（HTTP 400；$upstream），请检查模型配置。"
                        }
                    }
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    else -> "模型接口返回 HTTP $status"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            gatewayAccessFailure(error)?.let { return it }
            contentFilteredFailure(error, message)?.let { return it }
            if (isContextOverflow(error)) return AgentModelFailure(
                CONTEXT_OVERFLOW, false, CONTEXT_OVERFLOW_HINT,
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optString("status"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        private fun gatewayAccessFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            if (error.optString("code") == "client_not_allowed") return AgentModelFailure(
                "PROVIDER_CLIENT_NOT_ALLOWED", false,
                "服务商拒绝当前客户端标识。请检查该连接的客户端兼容配置与 User-Agent；这不等同于 API Key 无效。",
            )
            if (error.optString("type") == "permission_error" &&
                error.optString("message").contains("not assigned to any group", ignoreCase = true)
            ) return AgentModelFailure(
                "PROVIDER_GROUP_UNASSIGNED", false,
                "服务商尚未给这把 API Key 分配可用分组。请在服务商后台设置分组或联系站方；更改客户端标识不能授予分组权限。",
            )
            return null
        }

        /**
         * 上游内容策略拦截（例如网关返回 `request_filtered`）。
         *
         * 这类失败与「参数写错」完全不是一回事：改模型配置没有用，重试也没有用，
         * 必须让用户知道是被内容策略拦下的，并给出可行的下一步。
         */
        private fun contentFilteredFailure(error: JSONObject?, rawBody: String): AgentModelFailure? {
            val probes = listOf(
                error?.optString("code").orEmpty(),
                error?.optString("type").orEmpty(),
                error?.optString("status").orEmpty(),
                error?.optString("message").orEmpty(),
            ) + rawBody
            val matched = probes.any { probe ->
                val lower = probe.lowercase()
                CONTENT_FILTER_MARKERS.any { it in lower }
            }
            if (!matched) return null
            return AgentModelFailure(
                "PROVIDER_CONTENT_FILTERED", false,
                "上游按内容策略拦截了这次请求（request_filtered），不是模型配置错误，重试也不会通过。"
                    + "常见原因是请求里包含被服务端判定为敏感的片段：可尝试精简当前消息、"
                    + "关闭「按模型请求提示词」后重试，或新建会话重新描述问题。",
            )
        }

        /**
         * 诊断回显只接受「协议错误码」形态。
         *
         * 上游完全可以把密钥或自由文本塞进 code/type，所以这里既限制字符集与长度，
         * 也排除含 key/secret/token/password 等敏感词的取值——宁可少显示，也不能泄露。
         */
        private fun isTrustedDiagnosticToken(value: String): Boolean {
            if (value.length !in 1..64) return false
            if (!value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) return false
            val lower = value.lowercase()
            return SENSITIVE_TOKEN_MARKERS.none { lower.contains(it) }
        }

        /** 上游字段里一旦出现这些词，就不允许回显。 */
        private val SENSITIVE_TOKEN_MARKERS = listOf(
            "key", "secret", "token", "password", "passwd", "credential", "auth", "bearer", "fixture",
        )

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds") ||
                message.contains("input token count") && message.contains("exceeds the maximum")
        }

        private fun authenticationFailure(error: JSONObject?): AgentModelFailure? {
            if (error == null) return null
            val details = error.optJSONArray("details")
            val invalidKey = (0 until (details?.length() ?: 0)).any { index ->
                details?.optJSONObject(index)?.optString("reason") in invalidApiKeyReasons
            }
            return if (invalidKey || error.optString("status") == "UNAUTHENTICATED") {
                AgentModelFailure("PROVIDER_AUTH_FAILED", false, "模型接口认证失败，请检查 API Key。")
            } else null
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
