package io.github.mangi.eta.agent.planning

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTaskWorkspaceToolCatalog
import io.github.mangi.eta.agent.model.AgentToolCallValidator
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentTaskWorkspaceToolsTest {
    @Test fun schemasValidateUpdatesAndRejectInventedVerificationArgument() {
        val schemas = JSONArray().also { AgentTaskWorkspaceToolCatalog.appendTo(it, true) }
        val validator = AgentToolCallValidator(schemas)
        val planArgs = JSONObject().put("expected_revision", 0).put("title", "任务")
            .put("steps", JSONArray().put(JSONObject().put("id", "a").put("title", "观察页面").put("status", "pending")))
        assertNull(validator.validate(call("update_plan", planArgs)))
        assertNotNull(validator.validate(call("update_plan", JSONObject(planArgs.toString()).put("run_id", "other"))))
    }

    @Test fun capabilityProjectionPublishesPlanWithoutRootAndOmitsReadOnlyMutations() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false, memoryWritable = false,
            capabilities = AgentToolCapabilities(rootAvailable = false))
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue(names.containsAll(listOf("get_plan", "update_plan")))
    }

    @Test fun localDispatchPersistsPlanAndFormatterKeepsParseableTypedEnvelope() {
        val context = RuntimeEnvironment.getApplication()
        val executor = AgentLocalTools(context, logger(), browserRunId = "plan-test-dispatch", rootAvailable = { false })
        try {
            val empty = JSONObject(executor.execute(call("get_plan", JSONObject())).content)
            val args = JSONObject().put("expected_revision", empty.getLong("revision")).put("title", "检查任务")
                .put("steps", JSONArray().put(JSONObject().put("id", "check").put("title", "读取当前页面").put("status", "in_progress")))
            val result = executor.execute(call("update_plan", args))
            assertTrue(JSONObject(result.content).getBoolean("ok"))
            val summary = JSONObject(AgentTraceFormatter().summarizeResult("update_plan", result))
            assertEquals("eta.task_plan", summary.getString("kind"))
            assertEquals("in_progress", summary.getJSONObject("plan").getJSONArray("steps").getJSONObject(0).getString("status"))
            assertEquals("plan-test-dispatch", summary.getJSONObject("plan").getString("run_id"))
        } finally { executor.close() }
    }

    @Test fun planTraceRedactsCommonCredentialPatternsWithoutDroppingTypedSteps() {
        val raw = JSONObject().put("ok", true).put("kind", "eta.task_plan").put("schema_version", 1)
            .put("plan", JSONObject().put("run_id", "run").put("revision", 1).put("title", "token sk-private123456789")
                .put("steps", JSONArray().put(JSONObject().put("id", "a").put("title", "password=secret-value")
                    .put("status", "completed").put("completion_note", "Bearer secret-long-token-value")
                    .put("evidence_tool_call_ids", JSONArray().put("actual-call")))))
        val result = AgentTraceFormatter().summarizeResult("update_plan", AgentModelClient.ToolResult(raw.toString()))
        val json = JSONObject(result)
        assertEquals("eta.task_plan", json.getString("kind"))
        assertFalse(result.contains("secret-value"))
        assertFalse(result.contains("sk-private123456789"))
        assertFalse(result.contains("secret-long-token-value"))
        assertEquals("completed", json.getJSONObject("plan").getJSONArray("steps").getJSONObject(0).getString("status"))
    }

    @Test fun failedAndUnconfirmedExecutionResultsNeverBecomePlanEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val tools = AgentTaskWorkspaceTools(context, "receipt-test", writable = true)
        tools.recordResult(call("observe_screen", JSONObject()), AgentModelClient.ToolResult("{\"ok\":false}"))
        tools.recordResult(call("tap", JSONObject()), AgentModelClient.ToolResult("{\"ok\":true,\"status\":\"unconfirmed\"}"))
        val empty = JSONObject(tools.execute("get_plan", JSONObject()).content)
        assertEquals(0, empty.getJSONArray("recent_evidence").length())
        tools.recordResult(AgentModelClient.ToolCall("observed-call", "observe_screen", "{}"), AgentModelClient.ToolResult("{\"ok\":true}"))
        val observed = JSONObject(tools.execute("get_plan", JSONObject()).content)
        assertEquals("observed-call", observed.getJSONArray("recent_evidence").getJSONObject(0).getString("tool_call_id"))
    }

    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("test-$name", name, args.toString())
    private fun logger() = object : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
