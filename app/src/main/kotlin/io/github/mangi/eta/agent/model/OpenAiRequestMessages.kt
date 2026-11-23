package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import org.json.JSONArray
import org.json.JSONObject

/** 将 Eta 会话消息投影为 OpenAI-compatible 请求所需的系统指令结构。 */
internal object OpenAiRequestMessages {
    fun forChatCompletions(source: JSONArray): JSONArray {
        // 一次遍历同时完成三件事：收集 system 指令、复制会被修复逻辑改写的消息、保留原始索引。
        val instructions = ArrayList<String>(2)
        val copied = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.opt(index)
            if (item !is JSONObject) {
                copied.put(item)
                continue
            }
            if (item.optString("role") in SYSTEM_ROLES) {
                providerMessageText(item.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(instructions::add)
                // 这条消息稍后会被合并成单条 system 并从请求里移除，且修复逻辑不会改写它，
                // 所以直接保留原对象：省掉一次「序列化 + 重新解析」的深拷贝。
                copied.put(item)
            } else {
                copied.put(JSONObject(item.toString()))
            }
        }
        val system = instructions.joinToString("\n\n")
        // system 合并前保留原始边界，不能把跨消息的工具结果误配为完整批次。
        ChatToolCallHistory.repairCompleteBatches(copied)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until copied.length()) {
                val message = copied.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) messages.put(message.apply {
                    remove("_eta_context_summary")
                    remove("_eta_compacted_users")
                    remove("_eta_summary_through_user")
                    remove("_eta_observation")
                    remove("_eta_message_id")
                    remove("_eta_character_profile")
                    remove(RoleplayRunContext.DEPTH_USER_MARKER)
                })
            }
        }
    }

    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
