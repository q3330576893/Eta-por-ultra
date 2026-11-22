package io.github.mangi.eta.data.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Per-model request prompt hook. The runtime owns the actual projection into a provider request. */
@Serializable
data class ModelRequestHook(
    val enabled: Boolean = false,
    val channel: ModelRequestHookChannel = ModelRequestHookChannel.SYSTEM,
    val prompt: String = "",
) {
    val active: Boolean
        get() = enabled && prompt.isNotBlank()

    /** Stable diagnostic revision; the enabled switch is deliberately excluded. */
    val revision: String
        get() = sha256(channel.name + '\u0000' + prompt.trim()).take(REVISION_LENGTH)

    companion object {
        const val MAX_PROMPT_CHARS = 16_000
        const val REVISION_LENGTH = 12

        /** Original short template for research-oriented model configuration. */
        val RESEARCH_PROMPT: String =
            "你是当前模型的研究与执行助手。先识别任务目标、约束和可验证事实；需要工具时先取证再下结论；" +
                "把观察、推断和未知分开；不要编造已执行的操作、来源或结果；用户要求代码时给出可运行且有边界的实现；" +
                "回答直接、结构清晰，优先完成当前任务。"

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

@Serializable
enum class ModelRequestHookChannel {
    SYSTEM,
    USER,
    BOTH,
}
