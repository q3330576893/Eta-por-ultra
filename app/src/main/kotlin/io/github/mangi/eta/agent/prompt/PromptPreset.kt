package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.model.EndpointKind
import kotlinx.serialization.Serializable
import org.json.JSONObject

/**
 * 一份可导入导出的提示词预设。
 *
 * 包格式：zip（推荐）或裸 JSON 文件，里面只需要一个 `preset.json`；
 * 字段与自定义提示词一一对应，不带任何运行期状态（版本号、历史都不进包）。
 */
internal data class PromptPreset(
    val name: String,
    val description: String = "",
    val mode: PromptCustomizationMode = PromptCustomizationMode.APPEND,
    val baseSystemPrompt: String = "",
    val personaName: String = "",
    val personaStyle: String = "",
    val appendInstructions: String = "",
    val author: String = "",
    val createdAt: Long = 0L,
    val builtin: Boolean = false,
    /** 预设可以一并带工具范围：切换预设时提示词与工具集一起换，像 harness 的"模式"。 */
    val toolMode: ToolPolicyMode = ToolPolicyMode.ALLOW_ALL,
    val allowedTools: Set<String> = emptySet(),
) {
    val contentLength: Int
        get() = baseSystemPrompt.length + personaName.length + personaStyle.length + appendInstructions.length

    val hasContent: Boolean
        get() = baseSystemPrompt.isNotBlank() || personaName.isNotBlank() ||
            personaStyle.isNotBlank() || appendInstructions.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("name", name)
        .put("description", description)
        .put("mode", mode.name)
        .put("base_system_prompt", baseSystemPrompt)
        .put("persona_name", personaName)
        .put("persona_style", personaStyle)
        .put("append_instructions", appendInstructions)
        .put("author", author)
        .put("created_at", createdAt)
        .put("tool_mode", toolMode.name)
        .put("allowed_tools", org.json.JSONArray().also { array -> allowedTools.sorted().forEach(array::put) })

    fun sameContentAs(other: PromptPreset): Boolean =
        mode == other.mode && baseSystemPrompt == other.baseSystemPrompt &&
            personaName == other.personaName && personaStyle == other.personaStyle &&
            appendInstructions == other.appendInstructions &&
            toolMode == other.toolMode && allowedTools == other.allowedTools

    companion object {
        const val SCHEMA = 2
        const val MAX_FIELD_CHARS = 20_000
        const val MAX_BUNDLE_BYTES = 256 * 1024

        fun fromCustomization(
            customization: PromptCustomization,
            name: String,
            description: String = "",
            now: Long = System.currentTimeMillis(),
        ): PromptPreset = PromptPreset(
            name = name,
            description = description,
            mode = customization.mode,
            baseSystemPrompt = customization.baseSystemPrompt,
            personaName = customization.personaName,
            personaStyle = customization.personaStyle,
            appendInstructions = customization.appendInstructions,
            createdAt = now,
        )

        fun fromCustomization(
            customization: PromptCustomization,
            policy: AgentToolPolicy,
            name: String,
            description: String = "",
            now: Long = System.currentTimeMillis(),
        ): PromptPreset = fromCustomization(customization, name, description, now).copy(
            toolMode = policy.mode,
            allowedTools = policy.allowedTools,
        )

        fun fromJson(json: JSONObject): PromptPreset? {
            val schema = json.optInt("schema", SCHEMA)
            if (schema > SCHEMA) return null
            val name = json.optString("name").trim()
            if (name.isEmpty()) return null
            fun field(key: String): String? = json.optString(key).takeIf { it.length <= MAX_FIELD_CHARS }
            val base = field("base_system_prompt") ?: return null
            val personaName = field("persona_name") ?: return null
            val personaStyle = field("persona_style") ?: return null
            val append = field("append_instructions") ?: return null
            return PromptPreset(
                name = name.take(60),
                description = json.optString("description").take(280),
                mode = runCatching { PromptCustomizationMode.valueOf(json.optString("mode")) }
                    .getOrDefault(PromptCustomizationMode.APPEND),
                baseSystemPrompt = base,
                personaName = personaName,
                personaStyle = personaStyle,
                appendInstructions = append,
                author = json.optString("author").take(60),
                createdAt = json.optLong("created_at", 0L),
                toolMode = runCatching { ToolPolicyMode.valueOf(json.optString("tool_mode")) }
                    .getOrDefault(ToolPolicyMode.ALLOW_ALL),
                allowedTools = json.optJSONArray("allowed_tools")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optString(index).takeIf(String::isNotBlank)
                    }.toSet()
                }.orEmpty(),
            )
        }
    }
}

/**
 * 会话级「采样参数 + Instruct 模板」覆盖层。
 *
 * 与全局 [PromptPreset] 的区别：这是按会话细调的一层薄覆盖，不写全局预设库、不带版本号；
 * null 字段表示"该项不覆盖，跟随全局/运行配置"。只有显式设置了值的字段才会注入请求。
 *
 * 注意：并非所有 provider 都支持同名字段。实际注入前必须经过
 * [SessionSamplingCapabilities] 逐字段判定，不支持的字段标注并跳过（能力降级），
 * UI 上标注"仅对支持的模型生效"。
 */
@Serializable
internal data class SessionSamplingOverride(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    /** Instruct 模板正文：作为一条额外 system 指令注入，替换全局的追加指令语境。 */
    val instructTemplate: String = "",
    /** 绑定的角色预设名（挂在 agent/roleplay 的角色元数据由调用方解析，这里只存名字）。 */
    val rolePresetName: String = "",
) {
    val hasSampling: Boolean
        get() = temperature != null || topP != null || maxOutputTokens != null

    val hasInstruct: Boolean get() = instructTemplate.isNotBlank()

    val isEmpty: Boolean get() = !hasSampling && !hasInstruct && rolePresetName.isBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("temperature", temperature ?: JSONObject.NULL)
        .put("top_p", topP ?: JSONObject.NULL)
        .put("max_output_tokens", maxOutputTokens ?: JSONObject.NULL)
        .put("instruct_template", instructTemplate)
        .put("role_preset_name", rolePresetName)

    companion object {
        const val INSTRUCT_MAX_CHARS = 8_000

        fun fromJson(json: JSONObject): SessionSamplingOverride = SessionSamplingOverride(
            temperature = json.optDouble("temperature").takeIf { !it.isNaN() && json.has("temperature") && !json.isNull("temperature") },
            topP = json.optDouble("top_p").takeIf { !it.isNaN() && json.has("top_p") && !json.isNull("top_p") },
            maxOutputTokens = json.optInt("max_output_tokens", 0).takeIf { it > 0 && json.has("max_output_tokens") },
            instructTemplate = json.optString("instruct_template").take(INSTRUCT_MAX_CHARS),
            rolePresetName = json.optString("role_preset_name").take(120),
        )
    }
}

/** 某个采样字段在给定 provider/endpoint 上是否可注入。 */
internal enum class SamplingField(val jsonKey: String, val displayName: String) {
    TEMPERATURE("temperature", "temperature"),
    TOP_P("top_p", "top_p"),
    MAX_OUTPUT_TOKENS("max_output_tokens", "最大输出 token"),
}

internal object SessionSamplingCapabilities {
    /**
     * Gemini Interactions 把采样字段放进 generation_config（temperature/top_p/max_output_tokens），
     * 其余走顶层/OpenAI 兼容字段。Codex（ChatGPT 登录的 Responses）明确拒绝 temperature/top_p/max_output_tokens。
     */
    fun supports(field: SamplingField, endpoint: EndpointKind, providerType: String, authMode: String): Boolean =
        when (field) {
            SamplingField.TEMPERATURE,
            SamplingField.TOP_P,
            SamplingField.MAX_OUTPUT_TOKENS -> when {
                // Codex 订阅通道拒绝这三个字段（与 CodexResponsesRequest.UNSUPPORTED_FIELDS 对齐）。
                authMode == io.github.mangi.eta.data.model.ProviderAuthModes.OPENAI_CHATGPT -> false
                endpoint == EndpointKind.GEMINI_INTERACTIONS ||
                    endpoint == EndpointKind.CHAT_COMPLETIONS ||
                    endpoint == EndpointKind.RESPONSES ||
                    endpoint == EndpointKind.ANTHROPIC_MESSAGES -> true
                else -> false
            }
        }

    /** 该字段在请求体里的目标位置；Gemini 的采样字段要进 generation_config。 */
    fun jsonContainer(endpoint: EndpointKind): String? =
        if (endpoint == EndpointKind.GEMINI_INTERACTIONS) "generation_config" else null
}

/**
 * 把一次会话覆盖投影成"可注入字段 + 被跳过的字段说明"。
 *
 * 各 Provider Request 构造在组装请求体末尾调用本对象：
 * - [injectable] 是要写进请求 JSON 的字段（Gemini 由调用方转进 generation_config）；
 * - [skipped] 是能力不支持而被跳过的字段名，调用方随结果回传给 UI 标注。
 */
internal data class SamplingInjection(
    val injectable: Map<String, Any>,
    val skipped: List<String>,
    val instructTemplate: String,
) {
    val isEmpty: Boolean get() = injectable.isEmpty() && instructTemplate.isBlank()

    companion object {
        fun project(
            override: SessionSamplingOverride?,
            endpoint: EndpointKind,
            providerType: String,
            authMode: String,
        ): SamplingInjection {
            if (override == null || override.isEmpty) {
                return SamplingInjection(emptyMap(), emptyList(), "")
            }
            val fields = linkedMapOf<String, Any>()
            val skipped = mutableListOf<String>()
            override.temperature?.let { value ->
                if (SessionSamplingCapabilities.supports(SamplingField.TEMPERATURE, endpoint, providerType, authMode)) {
                    fields[SamplingField.TEMPERATURE.jsonKey] = value
                } else skipped += SamplingField.TEMPERATURE.displayName
            }
            override.topP?.let { value ->
                if (SessionSamplingCapabilities.supports(SamplingField.TOP_P, endpoint, providerType, authMode)) {
                    fields[SamplingField.TOP_P.jsonKey] = value
                } else skipped += SamplingField.TOP_P.displayName
            }
            override.maxOutputTokens?.let { value ->
                if (SessionSamplingCapabilities.supports(SamplingField.MAX_OUTPUT_TOKENS, endpoint, providerType, authMode)) {
                    // Anthropic 用 max_tokens，OpenAI 兼容用 max_output_tokens/max_tokens，Gemini 用 max_output_tokens。
                    fields[SamplingField.MAX_OUTPUT_TOKENS.jsonKey] = value
                } else skipped += SamplingField.MAX_OUTPUT_TOKENS.displayName
            }
            return SamplingInjection(fields, skipped, override.instructTemplate)
        }
    }
}
