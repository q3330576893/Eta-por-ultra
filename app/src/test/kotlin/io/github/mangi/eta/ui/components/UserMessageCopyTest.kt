package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import org.junit.Assert.*
import org.junit.Test

class UserMessageCopyTest {
    @Test fun ordinaryUserTextPreservesUnicodeWhitespaceAndNewlinesInClipboard() {
        val original = "  我发的消息 😀\n第二行\t缩进  "
        var clipboard = "previous"
        assertTrue(copyUserMessageText(original) { clipboard = it })
        assertEquals(original, clipboard)
    }

    @Test fun copyOnlyContainsTheTextDisplayedBesideFileAttachments() {
        val text = AgentFileReferencePromptCodec.format("请分析这个文件", listOf(
            AgentFileReference("report.txt", "/workspace/report.txt", AgentFileReferenceKind.File)))
        var clipboard = ""
        assertTrue(copyUserMessageText(text) { clipboard = it })
        assertEquals("请分析这个文件", clipboard)
        assertFalse(clipboard.contains("Files mentioned"))
    }

    @Test fun copiedSkillRequestNeverIncludesInjectedInstructions() {
        val content = "<skill_command id=\"test\">\nPRIVATE_SKILL_BODY\n</skill_command>\n\n帮我处理"
        var clipboard = ""
        assertTrue(copyUserMessageText(content) { clipboard = it })
        assertEquals("帮我处理", clipboard)
        assertFalse(clipboard.contains("PRIVATE_SKILL_BODY"))
    }

    @Test fun emptyOrImageOnlyMessageNeverClearsExistingClipboard() {
        var clipboard = "existing"
        assertFalse(copyUserMessageText("  \n ") { clipboard = it })
        assertEquals("existing", clipboard)
    }
}
