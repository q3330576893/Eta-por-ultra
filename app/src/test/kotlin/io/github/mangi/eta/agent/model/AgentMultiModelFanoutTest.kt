package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMultiModelFanoutTest {
    @Test
    fun fanout_capsAtThreeModelsAndMarksTheRestSkipped() = runBlocking {
        val targets = (1..5).map { index -> target("m$index") }
        val started = java.util.Collections.synchronizedList(mutableListOf<String>())
        val results = AgentMultiModelFanout().fanout(FanoutRequest("hi", targets)) { t ->
            started += t.key
            FanoutBranchResult(t.key, FanoutBranchStatus.Succeeded, content = "answer-${t.key}")
        }

        assertEquals(3, started.size)
        assertEquals(5, results.size)
        assertEquals(3, results.count { it.status == FanoutBranchStatus.Succeeded })
        assertEquals(2, results.count { it.status == FanoutBranchStatus.Skipped })
        assertTrue(results.filter { it.status == FanoutBranchStatus.Skipped }
            .all { it.error!!.contains("最多 3") })
    }

    @Test
    fun fanout_deduplicatesTargetsByKey() = runBlocking {
        val targets = listOf(target("same"), target("same"), target("other"))
        // 分支是并发执行的：用线程安全列表收集，避免测试自身丢写入。
        val started = java.util.Collections.synchronizedList(mutableListOf<String>())
        AgentMultiModelFanout().fanout(FanoutRequest("hi", targets)) { t ->
            started += t.key
            FanoutBranchResult(t.key, FanoutBranchStatus.Succeeded)
        }
        assertEquals(setOf("same", "other"), started.toSet())
        assertEquals(2, started.size)
    }

    @Test
    fun fanout_oneFailingBranchDoesNotAbortTheOthers() = runBlocking {
        val targets = listOf(target("ok1"), target("bad"), target("ok2"))
        val results = AgentMultiModelFanout().fanout(FanoutRequest("hi", targets)) { t ->
            if (t.key == "bad") throw IllegalStateException("boom")
            FanoutBranchResult(t.key, FanoutBranchStatus.Succeeded, content = t.key)
        }
        assertEquals(2, results.count { it.status == FanoutBranchStatus.Succeeded })
        results.first { it.status == FanoutBranchStatus.Failed }.let {
            assertEquals("bad", it.targetKey)
            assertTrue(it.error!!.contains("boom"))
        }
    }

    @Test
    fun totalFanoutTokens_sumsAllBranchUsage() {
        val results = listOf(
            FanoutBranchResult("a", FanoutBranchStatus.Succeeded,
                usage = AgentTokenUsage(inputTokens = 10, outputTokens = 5)),
            FanoutBranchResult("b", FanoutBranchStatus.Succeeded,
                usage = AgentTokenUsage(inputTokens = 20, outputTokens = 7)),
            FanoutBranchResult("c", FanoutBranchStatus.Failed),
        )
        assertEquals(42, totalFanoutTokens(results))
    }

    private fun target(key: String) = FanoutModelTarget(
        key = key,
        modelId = key,
        displayName = key,
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1", apiKey = "k", model = key, systemPrompt = "",
        ),
    )
}
