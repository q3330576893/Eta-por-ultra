package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.core.toSafeLogToken

internal sealed interface AgentEvent {
    fun toLogLine(): String

    enum class AssistantBlockKind {
        TEXT,
        THINKING,
        TOOL_CALL,
    }

    data class ContextCompaction(
        val operationId: String,
        val phase: String,
        val tokensBefore: Int? = null,
        val tokensAfter: Int? = null,
        val reasonCode: String = "",
    ) : AgentEvent {
        val displayMessage: String get() = when (phase) {
            PHASE_STARTED -> RUNNING_DETAIL
            PHASE_COMPLETED -> "上下文已压缩，用量将在下一次模型响应后更新。"
            else -> "上下文压缩失败，原始上下文已保留。"
        }
        override fun toLogLine(): String =
            "context_compaction phase=${phase.toSafeLogToken()}, before=$tokensBefore, after=$tokensAfter, code=${reasonCode.toSafeLogToken()}"

        companion object {
            const val PHASE_STARTED = "started"
            const val PHASE_COMPLETED = "completed"
            /** 进行中的展示文案同时是 UI 判定运行态的依据，改动必须与 UI 侧同步。 */
            const val RUNNING_DETAIL = "正在压缩上下文…"
        }
    }

    /** 请求侧裁掉了最老轮次：只在本次请求里生效，界面与数据库都不删。 */
    data class HistoryTrimmed(
        val omittedMessages: Int,
        val omittedUserRounds: Int,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "history_trimmed rounds=$omittedUserRounds, messages=$omittedMessages"
    }

    data class RunStarted(
        val initialImages: Int,
        val initialImageBytes: Int,
        val toolCount: Int,
        val terminalTools: Boolean
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_started images=$initialImages, image_bytes=$initialImageBytes, tools=$toolCount, terminal=$terminalTools"
    }

    data class RoundStarted(
        val round: Int,
        val messageCount: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "round_started round=$round, messages=$messageCount"
    }

    data class ModelRetryScheduled(
        val round: Int,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Int,
        val reasonCode: String,
        /**
         * 失败原因的用户可读文案（本地生成的中文，不含上游原文）。
         *
         * 旧版本 Runtime 不发送该字段，因此带默认值：缺省时按 [reasonCode] 兜底，
         * 保证跨进程回放也不会退化成没有原因的「暂时中断」。
         */
        val reasonText: String = "",
    ) : AgentEvent {
        /** 一句话原因：用户据此能判断「等一下会不会好」。 */
        val reasonLabel: String
            get() = when {
                reasonCode in RATE_LIMIT_CODES -> "服务商限流"
                reasonCode == "MODEL_TIMEOUT" -> "请求超时"
                reasonCode == "MODEL_CONNECTION_FAILED" -> "连接中断"
                reasonCode == "MODEL_TLS_FAILED" -> "加密连接失败"
                reasonCode == "MODEL_TLS_TRUST_FAILED" -> "证书校验未通过"
                reasonCode == "MODEL_PROTOCOL_ERROR" -> "响应无法解析"
                reasonCode == "STREAM_INCOMPLETE" -> "响应未完整结束"
                reasonCode == "PROVIDER_STREAM_ERROR" -> "服务商返回错误"
                reasonCode == "PROVIDER_UPSTREAM_CANCELLED" -> "上游已取消请求"
                reasonCode == "CONTEXT_OVERFLOW" -> "上下文超限"
                reasonCode.startsWith("HTTP_5") -> "服务商暂时不可用"
                reasonCode.startsWith("HTTP_4") -> "请求被服务商拒绝（$reasonCode）"
                else -> reasonText.compactReason().takeIf { it.isNotBlank() }
                    ?: "请求失败（$reasonCode）"
            }

        /** 可操作细节：本地生成的分类文案，超出长度时截断，避免通知卡片被撑爆。 */
        val reasonDetail: String
            get() = reasonText.compactReason().takeIf { it.isNotBlank() && it != reasonLabel }.orEmpty()

        val displayMessage: String
            get() = buildString {
                append("模型请求失败，正在第 ").append(attempt).append(" 次重试（共 ")
                append(maxAttempts).append(" 次），原因：").append(reasonLabel)
                reasonDetail.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
                append("；").append((delayMs / 1000).coerceAtLeast(1)).append(" 秒后重试")
                if (attempt >= maxAttempts) append("，这是最后一次重试")
                append("。此前工具结果已保留。")
            }

        override fun toLogLine(): String =
            "model_retry_scheduled round=$round, attempt=$attempt, max_attempts=$maxAttempts, " +
                "delay_ms=$delayMs, code=${reasonCode.toSafeLogToken()}"

        private fun String.compactReason(): String {
            val singleLine = replace('\n', ' ').replace('\r', ' ').trim()
            if (singleLine.length <= MAX_REASON_CHARS) return singleLine
            return singleLine.take(MAX_REASON_CHARS) + "…"
        }

        private companion object {
            val RATE_LIMIT_CODES = setOf("HTTP_429", "HTTP_529")
            const val MAX_REASON_CHARS = 120
        }
    }

    data class ProviderRequestStarted(
        val round: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_request_started round=$round"
    }

    data class ProviderResponseStarted(
        val round: Int,
        val httpCode: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_response_started round=$round, http_code=$httpCode"
    }

    data class AssistantBlockStart(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_start round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}"
    }

    data class AssistantBlockDelta(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val deltaChars: Int,
        val delta: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_delta round=$round, kind=$kind, index=$index, chars=$deltaChars"
    }

    data class AssistantBlockEnd(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val contentChars: Int,
        val replacementContent: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_end round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}, chars=$contentChars"
    }

    data class SearchSuggestions(
        val round: Int,
        val index: Int,
        val html: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "search_suggestions round=$round, index=$index, chars=${html.length}"
    }

    data class AssistantReceived(
        val round: Int,
        val contentChars: Int,
        val reasoningContent: String,
        val toolNames: List<String>
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_received round=$round, content_chars=$contentChars, " +
                "reasoning_chars=${reasoningContent.length}, tool_count=${toolNames.size}, " +
                "tools=${toolNames.take(MAX_LOGGED_TOOL_NAMES).map { it.toSafeLogToken() }}"
    }

    data class UsageReceived(
        val round: Int,
        val usage: AgentTokenUsage
    ) : AgentEvent {
        override fun toLogLine(): String =
            "usage_received round=$round, ctx=${usage.contextTokens}, in=${usage.inputTokens}, out=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, cache=${usage.cachedTokens}"
    }

    data class UserSupplementReceived(
        val index: Int,
        val text: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "user_supplement_received index=$index, chars=${text.length}"
    }

    data class ToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val argsPreview: String,
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_started round=$round, name=${name.toSafeLogToken()}, " +
                "args_chars=${argsPreview.length}, command_chars=${command?.length ?: 0}"
    }

    data class ToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val resultSummary: String,
        val imageCount: Int,
        val imageBytes: Int,
        /** 可选：旧版本 Runtime 不发送，消费端缺省时回退到摘要文本判断。 */
        val success: Boolean? = null,
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_finished round=$round, name=${name.toSafeLogToken()}, " +
                "${resultSummary.toSafeResultLogFields()}, images=$imageCount, image_bytes=$imageBytes"
    }

    data class SubagentProgress(
        val round: Int,
        val toolCallId: String,
        val subagentId: String,
        val subagentType: String,
        val snapshotJson: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "subagent_progress round=$round, id=${subagentId.toSafeLogToken()}, " +
                "type=${subagentType.toSafeLogToken()}, chars=${snapshotJson.length}"
    }

    data class HostedToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_started round=$round, name=${name.toSafeLogToken()}"
    }

    data class HostedToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val success: Boolean,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_finished round=$round, name=${name.toSafeLogToken()}, success=$success"
    }

    data class ToolImagesAttached(
        val round: Int,
        val toolName: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_images_attached round=$round, name=${toolName.toSafeLogToken()}, " +
                "images=$imageCount, image_bytes=$imageBytes"
    }

    data class RunFinished(
        val round: Int,
        val contentChars: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_finished round=$round, content_chars=$contentChars"
    }

    data class RunFailed(
        val reason: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_failed reason_chars=${reason.length}"
    }
}

private const val MAX_LOGGED_TOOL_NAMES = 8
private const val RESULT_CODE_MARKER = "code="

/** 摘要字段分隔符：旧格式用逗号，人文化摘要用间隔号。 */
private val RESULT_FIELD_SEPARATORS = listOf(", ", " · ")

private fun String.toSafeResultLogFields(): String = buildString {
    append("summary_chars=").append(this@toSafeResultLogFields.length)
    extractResultCode()?.let { resultCode ->
        append(", code=").append(resultCode.toSafeLogToken())
    }
}

private fun String.extractResultCode(): String? {
    val fieldStart = when {
        startsWith(RESULT_CODE_MARKER) -> 0
        else -> RESULT_FIELD_SEPARATORS
            .mapNotNull { separator ->
                indexOf(separator + RESULT_CODE_MARKER)
                    .takeIf { it >= 0 }
                    ?.plus(separator.length)
            }
            .minOrNull() ?: return null
    }
    val valueStart = fieldStart + RESULT_CODE_MARKER.length
    val valueEnd = RESULT_FIELD_SEPARATORS
        .mapNotNull { separator ->
            indexOf(separator, startIndex = valueStart).takeIf { it >= 0 }
        }
        .minOrNull() ?: length
    return substring(valueStart, valueEnd)
}
