package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/**
 * Provider 边界只分类失败；重试预算与上下文由 Loop 持有。
 *
 * 构造时兜底空白 message：失败对象最终会经 `RunFailed` 直接显示给用户，
 * 一旦 message 为空，界面就是一条没有任何文字的失败提示（静默失败）。
 * 这里把空串换成本地兜底文案，保证任何分类路径都不会产出空白错误。
 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message.ifBlank { defaultModelFailureMessage(code) }, cause) {

    /**
     * 实际展示给用户的原因，永远非空白。
     *
     * 父类构造函数已经用兜底文案替换过空白 message，这里再做一次收敛：
     * 上游缺 message、或调用方传了空白串时，界面都不会出现「没有文字」的失败提示。
     */
    val userMessage: String
        get() = message?.takeIf { it.isNotBlank() } ?: defaultModelFailureMessage(code)

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

        /** 上游主动取消/结束的标记；命中即判定为不可重试。 */
        private val UPSTREAM_CANCEL_MARKERS = listOf(
            "client_gone",
            "context canceled",
            "context cancelled",
            "run_cancelled",
            "run canceled",
            "request aborted",
        )

        /** 上游内容策略拦截的常见标记；命中即判定为被过滤而不是参数错误。 */
        private val CONTENT_FILTER_MARKERS = listOf(
            "request_filtered",
            "content_filter",
            "content_policy",
            "content management policy",
            "flagged by our safety",
            "blocked by content",
        )

        /**
         * 额度/计费类失败的强特征短语。
         *
         * 只认协议字段里的固定说法，不再对整段正文做裸词包含判断：网关正文里
         * `https://…/billing` 这类链接、或 `usage limit resets in 60s` 这类
         * 可恢复限流说明，都曾被误判成「永久失败」，直接让用户以为账户没钱了。
         */
        private val PERMANENT_PHRASES = listOf(
            "insufficient_quota",
            "quota_exceeded",
            "quota exceeded",
            "billing_error",
            "usage_limit_reached",
            "exceeded your current quota",
            "insufficient balance",
            "insufficient_balance",
            "credit balance is too low",
            "payment required",
            "out of budget",
        )

        /** `billing` 只有紧邻失败语境才算计费失败；文档链接、套餐介绍不算。 */
        private val BILLING_FAILURE_PATTERN = Regex(
            """billing\s*(?:is\s*|was\s*)?(?:required|disabled|not\s+enabled|error|failed|failure|hard\s+limit|issue)""",
            RegexOption.IGNORE_CASE,
        )

        /** `usage limit` 只有明确「已到上限」才算永久；「60 秒后重置」属于可重试限流。 */
        private val USAGE_LIMIT_PATTERN = Regex(
            """usage\s+limit\s+(?:has\s+been\s+)?(?:reached|exceeded|hit)""",
            RegexOption.IGNORE_CASE,
        )

        /** 非 JSON 正文只有在很短时才能当错误说明读，避免把整页 HTML 当成错误原因。 */
        private const val MAX_UNSTRUCTURED_PERMANENT_BODY = 512

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
                    408 -> "模型接口等待请求超时（HTTP 408），请检查网络与代理后重试。"
                    429 -> "模型接口暂时限流（HTTP 429）。请稍等片刻再试，或换用并发更宽松的模型。"
                    in 500..599 ->
                        "模型服务商暂时不可用（HTTP $status）。请稍后重试；若持续出现，请检查服务商状态页。"
                    else -> "模型接口返回异常状态（HTTP $status）。请检查接口地址与账户状态。"
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            authenticationFailure(error)?.let { return it }
            gatewayAccessFailure(error)?.let { return it }
            contentFilteredFailure(error, message)?.let { return it }
            // 上游主动结束或取消本次会话：客户端已断开后上游才回这个错误，
            // 继续重试只会再撞一次同样的结果，必须直接终止并说清原因。
            upstreamCancelledFailure(error, message)?.let { return it }
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
                // 上游常常只给 code 不给 message（例如 `{"error":{"type":"server_error"}}`）。
                // 直接把空串透出去，用户会看到一条没有任何文字的失败提示。
                message = message.ifBlank { defaultModelFailureMessage("PROVIDER_STREAM_ERROR") },
            )
        }

        fun incompleteStream(message: String) =
            AgentModelFailure(
                "STREAM_INCOMPLETE", true,
                message.ifBlank { defaultModelFailureMessage("STREAM_INCOMPLETE") },
            )

        /**
         * 传输层异常分类。
         *
         * 返回 null 表示「不是模型请求失败」，调用方会原样抛出原始异常——这条路径
         * 最终只显示 `javaClass.simpleName`，用户看到 `SSLException` 这种英文类名，
         * 既不知道发生了什么，也不知道该怎么办。因此 TLS 与协议错误必须分类，
         * 且明确标注为不可重试（证书不被信任时重试三次只是白等）。
         */
        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is ProtocolException -> AgentModelFailure(
                "MODEL_PROTOCOL_ERROR", true,
                "模型接口返回了无法解析的响应（HTTP 协议层错误）。请确认接口地址指向兼容端点；"
                    + "若使用了代理或网关，请检查其是否改写了响应。",
                failure,
            )
            is SSLException -> sslFailure(failure)
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        /**
         * TLS 失败分两类：证书不被信任（永久，改配置才行）与握手/传输中断（可重试）。
         *
         * 证书链、主机名不匹配、证书过期都归到「信任」一侧：这类问题重试不会自愈，
         * 而且用户最需要知道的是「不是 API Key 的问题」。
         */
        private fun sslFailure(failure: SSLException): AgentModelFailure {
            val detail = generateSequence<Throwable>(failure) { it.cause }
                .take(4)
                .joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }
                .lowercase()
            val trustRelated = listOf(
                "certificate", "certpath", "certificat", "trust anchor", "self signed",
                "selfsigned", "hostname", "not trusted", "pkix",
            ).any { it in detail }
            return if (trustRelated) AgentModelFailure(
                "MODEL_TLS_TRUST_FAILED", false,
                "模型服务商的证书校验未通过（证书过期、主机名不匹配或使用了自签名证书）。"
                    + "请检查系统时间与代理证书设置；这不属于 API Key 问题，重试不会通过。",
                failure,
            ) else AgentModelFailure(
                "MODEL_TLS_FAILED", true,
                "与模型服务商的加密连接建立失败。请检查网络、代理与接口地址后重试。",
                failure,
            )
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

        /**
         * 上游主动取消/结束（`client_gone`、`context canceled`、`run_cancelled` 等）。
         *
         * 用户看到的现象是「一直重试然后失败」：每次重试都会先发完整请求、
         * 再被上游以同样理由取消，白等三轮。这类失败必须不可重试。
         */
        private fun upstreamCancelledFailure(error: JSONObject?, rawMessage: String): AgentModelFailure? {
            val probes = listOf(
                error?.optString("code").orEmpty(),
                error?.optString("type").orEmpty(),
                error?.optString("status").orEmpty(),
                error?.optString("message").orEmpty(),
                rawMessage,
            )
            val matched = probes.any { probe ->
                val lower = probe.lowercase()
                UPSTREAM_CANCEL_MARKERS.any { it in lower }
            }
            if (!matched) return null
            return AgentModelFailure(
                "PROVIDER_UPSTREAM_CANCELLED", false,
                "上游已取消本次请求（client_gone / context canceled）。这通常意味着请求在服务端被中断，"
                    + "重试不会改变结果。可稍等片刻再发一次；若持续出现，请检查该模型在服务商侧是否可用，"
                    + "或换用其他模型。",
            )
        }

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

        /**
         * 永久失败判定。
         *
         * 旧实现只要正文里出现裸词 `billing` / `usage limit` 就判定永久失败，
         * 于是网关正文里的 `https://platform.example/billing` 帮助链接、
         * 或 `usage limit resets in 60s`（限流，可恢复）都被误判成「额度或计费受限」，
         * 直接放弃重试并让用户以为账户欠费。现在只认：
         * 1) `code` / `type` 字段里的精确错误码；
         * 2) 正文里的固定失败短语；
         * 3) `billing` / `usage limit` 紧邻失败语境的写法。
         */
        private fun isPermanent(error: JSONObject?, body: String): Boolean {
            val code = error?.optString("code").orEmpty()
            val type = error?.optString("type").orEmpty()
            if (code in permanentCodes || type in permanentCodes) return true
            // 正文可能很长（HTML 错误页）；只有短正文才当作错误说明来读。
            val haystack = buildString {
                append(error?.optString("message").orEmpty())
                if (body.length <= MAX_UNSTRUCTURED_PERMANENT_BODY) append(' ').append(body)
            }.lowercase()
            if (haystack.isBlank()) return false
            if (PERMANENT_PHRASES.any { it in haystack }) return true
            if (BILLING_FAILURE_PATTERN.containsMatchIn(haystack)) return true
            return USAGE_LIMIT_PATTERN.containsMatchIn(haystack)
        }
    }
}

/**
 * 空白失败文案的统一兜底（按错误码族给出可操作中文）。
 *
 * 放在文件级而不是 companion 内部：主构造函数的属性初始化需要调用它，
 * 此时 companion 尚未初始化完成。失败对象最终会经 `RunFailed` 原样显示，
 * 因此任何分类路径都不得产出空白错误。
 */
internal fun defaultModelFailureMessage(code: String): String = when {
    code.startsWith("HTTP_4") ->
        "模型接口拒绝了这次请求（$code）。请检查模型名称、接口地址与账户权限。"
    code.startsWith("HTTP_5") ->
        "模型服务商暂时不可用（$code）。请稍后重试；若持续出现，请检查服务商状态页。"
    code.startsWith("HTTP_") ->
        "模型接口返回异常状态（$code）。请检查接口地址与账户状态。"
    code == "MODEL_TIMEOUT" ->
        "模型请求等待超时。请检查网络后重试；若持续出现，可换用响应更快的模型。"
    code == "MODEL_CONNECTION_FAILED" ->
        "模型连接中断或无法建立。请检查网络、代理与服务商地址后重试。"
    code == "MODEL_TLS_FAILED" ->
        "与模型服务商的加密连接失败。请检查系统时间、代理证书与接口地址后重试。"
    code == "MODEL_TLS_TRUST_FAILED" ->
        "模型服务商的证书未被信任（或系统时间不正确）。请检查系统时间与代理证书设置；" +
            "这不属于 API Key 问题，重试不会通过。"
    code == "MODEL_PROTOCOL_ERROR" ->
        "模型接口返回了无法解析的响应。请检查接口地址是否指向兼容端点后重试。"
    code == "PROVIDER_STREAM_ERROR" ->
        "模型接口在流式响应中返回错误（未提供具体原因）。请稍后重试；若持续出现，请换用其他模型。"
    code == "STREAM_INCOMPLETE" ->
        "模型响应未完整结束。请重试本次请求。"
    else -> "模型请求失败（$code）。请检查网络与模型配置后重试。"
}
