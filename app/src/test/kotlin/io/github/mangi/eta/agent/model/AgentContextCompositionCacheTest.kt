package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompositionCacheTest {

    @Test
    fun estimate_returnsCachedTokensForSameToolsObject() {
        val tools = JSONArray().put(tool("first", "abcdefgh"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "abcd"))

        val first = AgentContextComposition.estimate(messages, systemCount = 0, tools = tools)
        val second = AgentContextComposition.estimate(messages, systemCount = 0, tools = tools)

        assertEquals(first, second)
    }

    @Test
    fun estimate_recomputesWhenToolsContentChangesInPlace() {
        val tools = JSONArray().put(tool("first", "abcdefgh"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "abcd"))
        val before = AgentContextComposition.estimate(messages, systemCount = 0, tools = tools)

        tools.getJSONObject(0).getJSONObject("function").put("description", "abcd")
        val after = AgentContextComposition.estimate(messages, systemCount = 0, tools = tools)

        assertEquals(AgentHistoryTrimmer.estimateTokens(tools.toString()), after.toolDefinitionTokens)
        assertTrue(before.toolDefinitionTokens != after.toolDefinitionTokens)
    }

    @Test
    fun estimate_usesContentDigestForSameContentsInDifferentObjects() {
        val left = JSONArray().put(tool("first", "abcdefgh"))
        val right = JSONArray().put(tool("first", "abcdefgh"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "abcd"))

        assertEquals(
            AgentContextComposition.estimate(messages, systemCount = 0, tools = left),
            AgentContextComposition.estimate(messages, systemCount = 0, tools = right),
        )
    }

    @Test
    fun estimate_doesNotChangeConversationEstimationWhenToolsAreCached() {
        val tools = JSONArray().put(tool("first", "abcdefgh"))
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "abcd"))
            .put(JSONObject().put("role", "user").put("content", "abcdefgh"))
        AgentContextComposition.estimate(messages, systemCount = 1, tools = tools)

        val cached = AgentContextComposition.estimate(messages, systemCount = 1, tools = tools)
        val plain = AgentContextComposition.estimate(messages, systemCount = 1, tools = JSONArray())

        assertEquals(1, cached.systemPromptTokens)
        assertEquals(2, cached.conversationTokens)
        assertEquals(plain.systemPromptTokens, cached.systemPromptTokens)
        assertEquals(plain.conversationTokens, cached.conversationTokens)
        assertTrue(cached.toolDefinitionTokens > 0)
    }

    private fun tool(name: String, description: String) = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description),
        )
}
