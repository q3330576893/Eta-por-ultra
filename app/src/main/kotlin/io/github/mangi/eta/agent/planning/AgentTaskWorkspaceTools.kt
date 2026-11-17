package io.github.mangi.eta.agent.planning

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTaskWorkspaceToolCatalog
import org.json.JSONArray
import org.json.JSONObject

/** Real tool dispatcher using the same AgentLocalTools/ToolStarted/ToolFinished execution path. */
internal class AgentTaskWorkspaceTools(
    context: Context,
    runId: String,
    private val writable: Boolean,
    private val plan: AgentTaskPlanStore = AgentTaskPlanStore(context.applicationContext.filesDir, runId),
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult = try {
        val json = when (name) {
            "get_plan" -> {
                fields(args, emptySet())
                plan.snapshot().toJson()
            }
            "update_plan" -> {
                fields(args, setOf("expected_revision", "title", "steps"))
                plan.update(revision(args), text(args, "title"),
                    AgentTaskPlanStore.parseSteps(args.getJSONArray("steps"))).toJson()
            }
            else -> throw TaskWorkspaceException("UNKNOWN_TOOL", "未知任务工作区工具")
        }
        AgentModelClient.ToolResult(json.toString(), sensitive = false)
    } catch (error: TaskWorkspaceException) {
        failure(error.code, error.message ?: "任务资料操作失败")
    } catch (error: IllegalArgumentException) {
        failure("INVALID_ARGUMENT", error.message ?: "任务参数无效")
    } catch (_: Exception) {
        failure("TASK_WORKSPACE_ERROR", "任务资料读取或提交失败；请先重新读取当前版本")
    }

    /** Only actual successful execution results from this instance may back completed plan steps. */
    fun recordResult(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult) {
        if (call.name in AgentTaskWorkspaceToolCatalog.names) return
        val json = runCatching { JSONObject(result.content) }.getOrNull() ?: return
        if (json.opt("ok") != true || json.optString("status") == "unconfirmed" || json.opt("verified") == false ||
            json.optJSONArray("pending_observation_signals")?.length()?.let { it > 0 } == true
        ) return
        runCatching { plan.recordSuccessfulTool(call.id, call.name) }
    }

    private fun failure(code: String, message: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).put("message", message).toString(), sensitive = true)

    private fun fields(args: JSONObject, allowed: Set<String>) {
        if (args.keys().asSequence().any { it !in allowed }) throw TaskWorkspaceException("INVALID_ARGUMENT", "工具参数存在额外字段")
    }

    private fun revision(args: JSONObject): Long = integer(args, "expected_revision", 0L, Long.MAX_VALUE - 1L)

    private fun integer(args: JSONObject, name: String, minimum: Long, maximum: Long): Long {
        val value = args.opt(name) as? Number ?: throw TaskWorkspaceException("INVALID_ARGUMENT", "$name 需要整数")
        val result = value.toLong()
        if (value.toDouble() != result.toDouble() || result !in minimum..maximum) {
            throw TaskWorkspaceException("INVALID_ARGUMENT", "$name 超出整数范围")
        }
        return result
    }

    private fun text(args: JSONObject, name: String): String {
        val value = args.opt(name) as? String
        if (value == null || value.isBlank() || value.length > 200) {
            throw TaskWorkspaceException("INVALID_ARGUMENT", "$name 需要 1 至 200 个字符")
        }
        return value
    }
}
