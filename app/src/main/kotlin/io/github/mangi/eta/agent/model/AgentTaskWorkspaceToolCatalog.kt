package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Declarative schemas for the existing Runtime's run plan. */
internal object AgentTaskWorkspaceToolCatalog {
    val planNames = setOf("update_plan", "get_plan")
    val names = planNames

    fun appendTo(tools: JSONArray, writable: Boolean) {
        fun text(max: Int) = JSONObject().put("type", "string").put("minLength", 1).put("maxLength", max)
        fun revision() = JSONObject().put("type", "integer").put("minimum", 0)
        fun boolean() = JSONObject().put("type", "boolean")
        fun strings(maxItems: Int, maxChars: Int) = JSONObject().put("type", "array").put("minItems", 1)
            .put("maxItems", maxItems).put("items", text(maxChars))
        fun add(name: String, description: String, properties: JSONObject = JSONObject(), required: List<String> = emptyList()) {
            tools.put(AgentToolSchema.function(name, description, JSONObject().put("type", "object")
                .put("additionalProperties", false).put("properties", properties).put("required", JSONArray(required))))
        }
        add("get_plan", "Read this run's persisted task plan and recent successful tool receipts. Runtime fixes the run scope. revision=0 means no current plan yet. A continuation may include previous_plan with its original run_id, previous_plan_source and non-executable previous_receipt_refs: read-only history, never current completion evidence. Remaining steps belong to the new run and start pending; reobserve current UI before actions.")
        val step = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject().put("id", text(64).put("pattern", "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"))
                .put("title", text(160)).put("status", text(20).put("enum", JSONArray(listOf("pending", "in_progress", "completed", "blocked", "failed", "cancelled"))))
                .put("completion_note", text(240)).put("evidence_tool_call_ids", strings(8, 200).put("uniqueItems", true)))
            .put("required", JSONArray(listOf("id", "title", "status")))
        add("update_plan", "Create/update this run's actual multi-step task plan. Submit all 1-20 steps, unique ids, at most one in_progress; do not invent progress. Use expected_revision from get_plan or prior update (0 to create). completed requires a concise completion_note and evidence_tool_call_ids naming actual successful tools from this run; get_plan lists recent receipts. A receipt is execution evidence, not proof the user objective is satisfied: verify outcomes before completing. blocked/failed/cancelled report remaining work truthfully and are never success. Every status except completed omits completion evidence. Historical previous_plan/previous_receipt_refs never satisfy this run's evidence requirement.",
            JSONObject().put("expected_revision", revision()).put("title", text(120))
                .put("steps", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 20).put("items", step)),
            listOf("expected_revision", "title", "steps"))
    }
}
