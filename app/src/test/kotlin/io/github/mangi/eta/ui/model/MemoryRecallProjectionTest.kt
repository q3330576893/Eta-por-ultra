package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.repository.AgentMemoryReadResult
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallProjectionTest {
    private fun snapshot() = AgentMemorySnapshot("", "rev", 0, 0)

    @Test
    fun detectsHotAndColdZoneFromSearchPrefix() {
        assertEquals(MemoryRecallZone.HOT, MemoryRecallProjection.zoneOf("1: 偏好深色"))
        assertEquals(MemoryRecallZone.COLD, MemoryRecallProjection.zoneOf("（冷区 MEMORY_archive.md）\nA1: 旧事实"))
    }

    @Test
    fun projectsHitLinesWithDatesAndMatchFlags() {
        val result = AgentMemoryReadResult(
            snapshot = snapshot(),
            content = "1: 用户 2026-01-02 说偏好深色\n2: 上下文行\n…\n4: 杭州",
            startLine = 1,
            endLine = 4,
            hasMore = true,
            matchedLines = 2,
        )
        val projected = MemoryRecallProjection.project(result, "深色")
        assertEquals(MemoryRecallZone.HOT, projected.zone)
        assertEquals(3, projected.hits.size)
        assertEquals(2, projected.matchedLines)
        assertTrue(projected.truncated)
        assertTrue(projected.hits[0].isMatched)
        assertFalse(projected.hits[1].isMatched)
        assertEquals("2026-01-02", projected.hits[0].date)
        assertNull(projected.hits[1].date)
        // search() 暂不返回分数，投影层留空等待父代理升级签名。
        assertNull(projected.hits[0].score)
    }

    @Test
    fun usesActualRankedTokensInsteadOfRequiringWholeQueryInOneLine() {
        val result = AgentMemoryReadResult(snapshot(), "1: 偏好深色\n2: 是上下文\n3: 正在做安卓项目", 1, 3, false, 2,
            scoredHits = listOf(io.github.mangi.eta.data.repository.MemoryScoredHit(3, 12, listOf("安卓"), false),
                io.github.mangi.eta.data.repository.MemoryScoredHit(1, 7, listOf("深色"), false)),
            latestUpdateDate = "2026-11-19")
        val projected = MemoryRecallProjection.project(result, "深色 安卓")
        assertTrue(projected.hits[0].isMatched)
        assertFalse(projected.hits[1].isMatched)
        assertTrue(projected.hits[2].isMatched)
        assertEquals(12f, projected.hits[2].score!!, 0f)
        assertEquals(7f, projected.hits[0].score!!, 0f)
        assertEquals("2026-11-19", projected.latestUpdateDate)
    }

    @Test
    fun masksCredentialsAndRaisesLocalNotice() {
        val result = AgentMemoryReadResult(
            snapshot = snapshot(),
            content = "1: api_key: sk-abcdefghijklmnop\n2: 偏好深色",
            startLine = 1,
            endLine = 2,
            hasMore = false,
            matchedLines = 2,
        )
        val projected = MemoryRecallProjection.project(result, "偏好")
        assertNotNull(projected.sensitivityNotice)
        assertFalse(projected.hits[0].text.contains("sk-abcdefghijklmnop"))
        assertTrue(projected.hits[0].text.contains("[已隐藏]"))
    }

    @Test
    fun coldZoneUsesAPrefixedLineNumbers() {
        val result = AgentMemoryReadResult(
            snapshot = snapshot(),
            content = "（冷区 MEMORY_archive.md）\nA3: 2025/12/01 旧地址",
            startLine = 3,
            endLine = 3,
            hasMore = false,
            matchedLines = 1,
        )
        val projected = MemoryRecallProjection.project(result, "旧地址")
        assertEquals(MemoryRecallZone.COLD, projected.zone)
        assertEquals(1, projected.hits.size)
        assertEquals(3, projected.hits[0].lineNumber)
        assertEquals("2025-12-01", projected.hits[0].date)
    }
}
