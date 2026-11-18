package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentFileKbIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileKbIndexTest {
    private fun doc(name: String, path: String, body: String) = AgentKbDocument(
        docId = AgentFileKbIndex.docIdFor(path),
        displayName = name,
        absolutePath = path,
        excerpt = body,
        bytes = body.toByteArray(Charsets.UTF_8).size.toLong(),
        revision = "r1",
    )

    @Test
    fun docIdIsStableForSamePath() {
        assertEquals(AgentFileKbIndex.docIdFor("/a/b.md"), AgentFileKbIndex.docIdFor("/a/b.md"))
        assertFalse(AgentFileKbIndex.docIdFor("/a/b.md") == AgentFileKbIndex.docIdFor("/a/c.md"))
        assertEquals(16, AgentFileKbIndex.docIdFor("/a/b.md").length)
    }

    @Test
    fun searchMatchesFileNameAndBodyWithNameWeight() {
        val index = AgentFileKbIndex()
        index.index(doc("会议纪要.md", "/docs/meeting.md", "预算 与 排期 讨论"))
        // 中文连续串是整词 token：文件名须含精确 token「预算」才会命中文件名加权。
        index.index(doc("预算.md", "/docs/budget.md", "会议 中提到的数字"))

        val hits = index.search("预算")
        assertEquals(2, hits.size)
        // 文件名命中（预算.md）权重 3 > 正文命中权重 1
        assertEquals("预算.md", hits.first().document.displayName)
        assertEquals(3, hits.first().score)
        assertTrue(hits.first().matchedTokens.contains("预算"))
    }

    @Test
    fun searchIsCaseInsensitiveAndMultiToken() {
        val index = AgentFileKbIndex()
        index.index(doc("README.md", "/docs/readme.md", "Kotlin Agent Guide"))
        val hits = index.search("kotlin agent")
        assertEquals(1, hits.size)
        assertEquals(2, hits.first().score) // 两个正文词各命中一次
        assertEquals(setOf("kotlin", "agent"), hits.first().matchedTokens.toSet())
    }

    @Test
    fun emptyQueryAndNoMatchesReturnEmpty() {
        val index = AgentFileKbIndex()
        index.index(doc("a.md", "/a.md", "hello world"))
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("不存在的词").isEmpty())
    }

    @Test
    fun largeBodyIsChunkedIntoMultipleDocuments() {
        val index = AgentFileKbIndex()
        val body = "关键词 ".repeat(5000) // 30000 chars，必被切成多片
        val added = index.index(doc("大文档.md", "/big.md", body))
        assertTrue(added > 1)
        assertEquals(added, index.size())
        assertFalse(index.search("关键词").isEmpty())
    }

    @Test
    fun reindexSameDocIdIsIdempotent() {
        val index = AgentFileKbIndex()
        val d = doc("a.md", "/a.md", "same content")
        assertEquals(1, index.index(d)) // 首次新增 1 片
        assertEquals(0, index.index(d)) // 重复索引不再新增
        assertEquals(1, index.size())
    }

    @Test
    fun clearRemovesEverything() {
        val index = AgentFileKbIndex()
        index.index(doc("a.md", "/a.md", "content"))
        index.clear()
        assertEquals(0, index.size())
        assertTrue(index.search("content").isEmpty())
    }
}
