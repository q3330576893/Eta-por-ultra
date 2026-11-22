package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestSnapshotTest {
    @Test
    fun capturesEffectiveCountsWithoutRecordingHookOrUserText() {
        val hook = ModelRequestHook(true, ModelRequestHookChannel.BOTH, "按条列出示例结果")
        val regularSystem = "<model_request_hook>普通系统文本</model_request_hook>"
        val userParts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "private-user-text"))
            .put(JSONObject().put("type", "image_url").put("image_url", "fixture-image-data"))
        val source = JSONArray()
            .put(message("system", regularSystem))
            .put(message("developer", "developer-note"))
            .put(message("user", userParts))
        val original = source.toString()
        val request = request(source, hook)

        val snapshot = AgentRequestSnapshot.summarize(request, now = 123)
        val system = snapshot.getJSONArray("system")
        val metadata = snapshot.getJSONObject("request_hook")
        val injected = system.getJSONObject(1)
        val user = snapshot.getJSONArray("messages").getJSONObject(1)
        val encoded = snapshot.toString()

        assertEquals(123L, snapshot.getLong("at"))
        assertEquals(2, system.length())
        assertEquals(regularSystem, system.getJSONObject(0).getString("text"))
        assertEquals(2, injected.getInt("i"))
        assertTrue(injected.getBoolean("request_hook"))
        assertEquals(hook.revision, injected.getString("revision"))
        assertEquals(request.effectiveMessages.getJSONObject(2).getString("content").length, injected.getInt("chars"))
        assertFalse(injected.has("text"))
        assertTrue(metadata.getBoolean("active"))
        assertEquals("BOTH", metadata.getString("channel"))
        assertEquals(hook.revision, metadata.getString("revision"))
        assertEquals(ModelRequestHookProjection.estimatedTokens(hook), metadata.getInt("estimated_tokens"))
        assertTrue(metadata.getInt("estimated_tokens") > 0)
        assertEquals("user", user.getString("role"))
        assertEquals(request.effectiveMessages.getJSONObject(3).get("content").toString().length, user.getInt("chars"))
        assertFalse(user.has("text"))
        assertFalse(encoded.contains(hook.prompt))
        assertFalse(encoded.contains("private-user-text"))
        assertFalse(encoded.contains("fixture-image-data"))
        assertFalse(encoded.contains("developer-note"))
        assertEquals(original, source.toString())
    }

    @Test
    fun userHookDoesNotChangeToolGroupingAndInapplicableRequestsStayInactive() {
        val source = JSONArray().put(message("system", "base")).put(message("user", "question"))
        val hook = ModelRequestHook(true, ModelRequestHookChannel.USER, "按条回答")
        val testRequest = request(source, hook, tools = sampleTools())
        val active = AgentRequestSnapshot.summarize(testRequest)
        val disabled = AgentRequestSnapshot.summarize(request(source, hook.copy(enabled = false), tools = sampleTools()))
        val compact = AgentRequestSnapshot.summarize(testRequest.copy(purpose = ProviderRequestPurpose.COMPACTION))

        assertEquals(disabled.getString("signature"), active.getString("signature"))
        assertEquals("base", active.getJSONArray("system").getJSONObject(0).getString("text"))
        assertEquals(1, active.getJSONArray("system").length())
        assertTrue(active.getJSONObject("request_hook").getBoolean("active"))
        assertFalse(disabled.getJSONObject("request_hook").getBoolean("active"))
        assertEquals(0, disabled.getJSONObject("request_hook").getInt("estimated_tokens"))
        assertFalse(compact.getJSONObject("request_hook").getBoolean("active"))
        assertEquals(0, compact.getJSONObject("request_hook").getInt("estimated_tokens"))
        assertEquals(0, compact.getJSONArray("tools").length())
        assertEquals("question".length, compact.getJSONArray("messages").getJSONObject(0).getInt("chars"))
    }

    @Test
    fun summaryIsIndependentOfSubsequentSourceArrayChanges() {
        val source = JSONArray().put(message("system", "before"))
        val tools = sampleTools()
        val request = request(source, ModelRequestHook(), tools = tools)
        val snapshot = AgentRequestSnapshot.summarize(request)
        val frozen = snapshot.toString()

        source.getJSONObject(0).put("content", "changed-after-capture")
        tools.getJSONObject(0).getJSONObject("function").put("description", "changed-after-capture")

        assertEquals(frozen, snapshot.toString())
        assertEquals("before", snapshot.getJSONArray("system").getJSONObject(0).getString("text"))
        assertEquals("describe fixture", snapshot.getJSONArray("tools").getJSONObject(0).getString("description"))
    }

    private fun message(role: String, content: Any): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun sampleTools(): JSONArray = JSONArray().put(
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", "fixture").put("description", "describe fixture"),
        ),
    )

    private fun request(
        messages: JSONArray,
        hook: ModelRequestHook,
        tools: JSONArray = JSONArray(),
    ): ProviderRequest = ProviderRequest(
        config = AgentModelClient.ModelConfig(
            baseUrl = "http://localhost:8080/v1",
            apiKey = "",
            model = "snapshot-fixture",
            systemPrompt = "base",
            requestHook = hook,
        ),
        messages = messages,
        tools = tools,
    )
}
