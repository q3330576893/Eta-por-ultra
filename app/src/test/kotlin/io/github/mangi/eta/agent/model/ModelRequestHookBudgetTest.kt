package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestHookBudgetTest {
    @Test
    fun hookReservationThatExhaustsHistoryBudgetStillTrimsWholeOldRounds() {
        val window = 4_096
        val hook = ModelRequestHook(true, ModelRequestHookChannel.BOTH, "研".repeat(600))
        val hookTokens = ModelRequestHookProjection.estimatedTokens(hook)
        val reserved = 1_200 + hookTokens
        assertTrue(hookTokens < window)
        assertTrue(reserved > (window * 0.55).toInt())
        val original = JSONArray().put(message("system", "retained system"))
        repeat(20) { index ->
            original.put(message("user", "user-$index"))
            original.put(message("assistant", "").put("tool_calls", JSONArray().put(
                JSONObject().put("id", "call-$index").put("type", "function").put("function",
                    JSONObject().put("name", "fixture").put("arguments", "{}")),
            )))
            original.put(message("tool", "result-$index").put("tool_call_id", "call-$index"))
            original.put(message("assistant", "answer-$index"))
        }
        original.put(message("user", "retained current input"))
        val canonical = original.toString()
        val request = JSONArray(canonical)

        val outcome = AgentHistoryTrimmer.trimInPlace(request, systemCount = 1, contextWindow = window,
            reservedTokens = reserved)

        assertEquals(10, outcome.omittedUserRounds)
        assertEquals(40, outcome.omittedMessages)
        assertEquals(42, request.length())
        assertEquals("retained system", request.getJSONObject(0).getString("content"))
        assertEquals("retained current input", request.getJSONObject(request.length() - 1).getString("content"))
        repeat(10) { retained ->
            val index = retained + 10
            val start = 1 + retained * 4
            assertEquals("user-$index", request.getJSONObject(start).getString("content"))
            assertEquals("call-$index", request.getJSONObject(start + 1).getJSONArray("tool_calls")
                .getJSONObject(0).getString("id"))
            assertEquals("call-$index", request.getJSONObject(start + 2).getString("tool_call_id"))
            assertEquals("answer-$index", request.getJSONObject(start + 3).getString("content"))
        }
        assertEquals(canonical, original.toString())
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)
}
