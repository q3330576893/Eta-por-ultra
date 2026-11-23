package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

/** Request-only projection. The source remains the canonical conversation, without injected text. */
internal object ModelRequestHookProjection {
    private const val TOKEN_CACHE_LIMIT = 32
    private val tokenCache = object : LinkedHashMap<String, Int>(TOKEN_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > TOKEN_CACHE_LIMIT
    }
    fun appliesTo(purpose: ProviderRequestPurpose): Boolean =
        purpose == ProviderRequestPurpose.CHAT || purpose == ProviderRequestPurpose.COMPARISON

    fun validate(hook: ModelRequestHook) {
        if (!hook.enabled) return
        require(hook.prompt.isNotBlank()) { "请填写模型请求提示词，或关闭请求 Hook" }
        require(hook.prompt.length <= ModelRequestHook.MAX_PROMPT_CHARS) {
            "模型请求提示词最多 ${ModelRequestHook.MAX_PROMPT_CHARS} 字符"
        }
    }

    /** Reserve the same rendered payload that will be sent, including both copies in BOTH mode. */
    fun estimatedTokens(hook: ModelRequestHook): Int {
        if (!hook.active) return 0
        return synchronized(tokenCache) {
            tokenCache[hook.revision] ?: run {
                val copies = if (hook.channel == ModelRequestHookChannel.BOTH) 2 else 1
                val value = copies * (AgentHistoryTrimmer.estimateTokens(render(hook)) + 8)
                tokenCache[hook.revision] = value
                value
            }
        }
    }

    /**
     * Called once for each ProviderRequest, always from its unmodified messages.
     * No substring sentinel: a user quoting the same text must not suppress a configured hook.
     * Retrying a request reuses its projection; a later tool round projects the canonical source again.
     */
    fun project(source: JSONArray, hook: ModelRequestHook): JSONArray {
        validate(hook)
        if (!hook.active) return source
        val projected = JSONArray(source.toString())
        val block = render(hook)
        if (hook.channel != ModelRequestHookChannel.SYSTEM) {
            val target = (projected.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
                projected.optJSONObject(index)?.takeIf(::isRealProviderUserMessage)
            }
            target?.let { message ->
                val original = message.opt("content")
                message.put("content", when (original) {
                    is String -> "$block\n\n$original"
                    is JSONArray -> JSONArray().put(JSONObject().put("type", "text").put("text", "$block\n\n")).also { parts ->
                        for (index in 0 until original.length()) parts.put(original.get(index))
                    }
                    null, JSONObject.NULL -> block
                    else -> throw AgentModelFailure(
                        "MODEL_REQUEST_HOOK_CONTENT", false,
                        "当前用户消息格式不支持请求 Hook，原消息已保留。",
                    )
                })
            }
        }
        if (hook.channel != ModelRequestHookChannel.USER) {
            val insertAt = systemInsertionIndex(projected)
            for (index in projected.length() downTo insertAt + 1) {
                projected.put(index, projected.get(index - 1))
            }
            projected.put(insertAt, JSONObject().put("role", "system").put("content", block))
        }
        return projected
    }

    fun systemInsertionIndex(source: JSONArray): Int =
        (0 until source.length()).firstOrNull { index ->
            source.optJSONObject(index)?.optString("role") !in INSTRUCTION_ROLES
        } ?: source.length()

    private fun render(hook: ModelRequestHook): String =
        "<model_request_hook revision=\"${hook.revision}\">\n${hook.prompt.trim()}\n</model_request_hook>"

    /** 每次调用都会走一遍消息，集合必须提到循环外，不能逐条新建。 */
    private val INSTRUCTION_ROLES = setOf("system", "developer")
}
