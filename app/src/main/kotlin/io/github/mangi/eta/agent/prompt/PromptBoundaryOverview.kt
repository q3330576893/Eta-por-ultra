package io.github.mangi.eta.agent.prompt

/**
 * 「提示词与工具边界」当前生效范围的纯文本概览。
 *
 * 用户在这个页面里改的是两份东西：一段每轮都注入的提示词，和一组决定模型能看到哪些工具的
 * 白名单。它们的代价不在同一个维度上——提示词吃的是每轮上下文，工具吃的是请求体积与决策
 * 难度——所以分开说，并且都用"这会让模型/请求发生什么"来表述，而不是只报数字。
 */
internal data class PromptBoundaryOverview(
    val lines: List<String>,
    val promptChars: Int,
    val estimatedTokens: Int,
    val allowedToolCount: Int,
) {
    companion object {
        /** 中文为主的提示词按 1 字 ≈ 1 token 估，比按字符数报更接近实际占用。 */
        private const val CHARS_PER_TOKEN = 1.0
        private const val LARGE_PROMPT_CHARS = 6_000

        fun of(
            customization: PromptCustomization,
            toolPolicy: AgentToolPolicy,
        ): PromptBoundaryOverview {
            val promptChars = listOf(
                customization.baseSystemPrompt,
                customization.personaName,
                customization.personaStyle,
                customization.appendInstructions,
            ).sumOf(String::length)
            val estimated = (promptChars / CHARS_PER_TOKEN).toInt()
            val allowed = when (toolPolicy.mode) {
                ToolPolicyMode.ALLOW_ALL -> -1
                ToolPolicyMode.ALLOW_LIST -> toolPolicy.allowedTools.size
            }
            val lines = buildList {
                add(
                    when {
                        !customization.enabled -> "提示词：未启用，当前不注入任何自定义内容。"
                        promptChars == 0 -> "提示词：已启用但没有内容，等同于关闭。"
                        else -> "提示词：约 $estimated tokens，每轮请求都会带上这一段。" +
                            if (promptChars > LARGE_PROMPT_CHARS) "篇幅偏大，会明显压缩可用于对话的上下文。" else ""
                    },
                )
                add(
                    when {
                        customization.mode == PromptCustomizationMode.REPLACE ->
                            "生效方式：替换内置身份段；工具、设备、屏幕观察等能力契约仍会注入，否则工具调用会失效。"
                        else ->
                            "生效方式：追加在内置身份段之后；内置身份与能力契约都保留。"
                    },
                )
                add(
                    when {
                        allowed < 0 -> "工具边界：全部开放，模型能看到所有已启用的工具。"
                        allowed == 0 -> "工具边界：白名单为空，请求里不会带任何工具，模型无法调用工具。"
                        else -> "工具边界：只开放 $allowed 个工具；模型看不到其余工具，提示词里提到它们会造成空转。"
                    },
                )
                add("作用范围：全局。所有模型共用这一份设置，模型编辑页不再有单独的提示词入口。")
            }
            return PromptBoundaryOverview(lines, promptChars, estimated, if (allowed < 0) 0 else allowed)
        }
    }
}
