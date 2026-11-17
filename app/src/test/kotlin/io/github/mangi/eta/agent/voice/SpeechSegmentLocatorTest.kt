package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechSegmentLocatorTest {
    @Test fun resumeTailStartsAfterEarlierDuplicate() {
        val text = "重复句子。接着说明。重复句子。最后一句。"
        val from = text.lastIndexOf("重复句子。")
        val ranges = SpeechSegmentLocator.locate(text, listOf("重复句子。", "最后一句。"), fromChar = from)
        assertEquals(from until from + "重复句子。".length, ranges[0])
        assertEquals(text.indexOf("最后一句。") until text.length, ranges[1])
    }

    @Test fun missingTailDoesNotFallBackBeforeRequestedOffset() {
        assertEquals(listOf<IntRange?>(null), SpeechSegmentLocator.locate("开头独有句子。末尾", listOf("开头独有句子。"), 8))
    }

    @Test fun fromCharIsClampedAndDefaultStillLocatesInOrder() {
        val text = "第一句。第一句。"
        assertEquals(listOf(0..3, 4..7), SpeechSegmentLocator.locate(text, listOf("第一句。", "第一句。")))
        assertEquals(listOf<IntRange?>(null), SpeechSegmentLocator.locate(text, listOf("第一句。"), Int.MAX_VALUE))
        assertEquals(listOf(0..3), SpeechSegmentLocator.locate(text, listOf("第一句。"), -2))
    }
}
