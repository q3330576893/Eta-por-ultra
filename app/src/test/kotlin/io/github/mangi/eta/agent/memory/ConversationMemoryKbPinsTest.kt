package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentFileKbIndex
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryKbPinsTest {
    private fun snapshot(content: String, revision: String) =
        AgentMemorySnapshot(content = content, revision = revision, byteSize = content.length, lineCount = 1)

    private fun pin(docId: String, score: Int) = AgentKbPin(
        docId = docId,
        displayName = "$docId.md",
        absolutePath = "/docs/$docId.md",
        reason = "test",
        score = score,
    )

    @Test
    fun kbPinRequiresAnExistingContextPin() {
        val id = "conv-kb-none-${System.nanoTime()}"
        assertFalse(ConversationMemoryPins.pinKb(id, pin("a", 1)))
    }

    @Test
    fun pinUnpinAndHigherScoreWins() {
        val id = "conv-kb-${System.nanoTime()}"
        ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\nx", "r1"), 128_000)

        assertTrue(ConversationMemoryPins.pinKb(id, pin("a", 5)))
        assertFalse(ConversationMemoryPins.pinKb(id, pin("a", 3))) // 低分不覆盖
        assertTrue(ConversationMemoryPins.pinKb(id, pin("a", 9))) // 高分覆盖
        assertTrue(ConversationMemoryPins.pinKb(id, pin("b", 1)))

        val pins = ConversationMemoryPins.kbPinsFor(id)
        assertEquals(2, pins.size)
        assertEquals(9, pins.first { it.docId == "a" }.score)
        assertTrue(ConversationMemoryPins.unpinKb(id, "a"))
        assertEquals(1, ConversationMemoryPins.kbPinsFor(id).size)
        ConversationMemoryPins.forget(id)
    }

    @Test
    fun kbPinsAreProjectedIntoContextWithoutBreakingMemoryCache() {
        val id = "conv-kb-ctx-${System.nanoTime()}"
        val first = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\nx", "r1"), 128_000)
        assertSame(first, ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\nx", "r1"), 128_000))
        assertTrue(first.kbPins.isEmpty())

        ConversationMemoryPins.pinKb(id, pin("a", 5))
        val projected = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\nx", "r1"), 128_000)
        assertEquals(1, projected.kbPins.size)
        assertEquals("a", projected.kbPins.first().docId)
        assertEquals(first.coreContent, projected.coreContent) // 记忆正文与 revision 不变
    }
}

class AgentFileReferenceKbTest {
    @Test
    fun indexToKbFlagDefaultsFalseAndDoesNotAlterCodec() {
        val ref = AgentFileReference("a.md", "/a.md", AgentFileReferenceKind.File)
        assertFalse(ref.indexToKb)
        val marked = ref.copy(indexToKb = true)
        assertTrue(marked.indexToKb)

        // 编解码协议不变：format 输出不含 KB 标记，parse 得到默认 false。
        val formatted = io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec.format("请读", listOf(marked))
        val parsed = io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec.parse(formatted)
        assertEquals(1, parsed.references.size)
        assertFalse(parsed.references.first().indexToKb)
    }

    @Test
    fun indexToKbReusesAttachmentPermission() {
        val ref = AgentFileReference("a.md", "/a.md", AgentFileReferenceKind.File)
        assertFalse(AgentFileReferencePolicy.canIndexToKb(ref, terminalToolsEnabled = false))
        assertTrue(AgentFileReferencePolicy.canIndexToKb(ref, terminalToolsEnabled = true))
    }

    @Test
    fun sensitiveKbToolsAreDeclaredForParentPolicyWiring() {
        assertEquals(setOf("index_file_to_kb", "search_kb"), AgentFileKbIndex.SENSITIVE_KB_TOOLS)
    }
}
