package io.github.mangi.eta.agent.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceWordTimingsTest {
    @Test fun nativeWordsRetainActualOffsetsAndOptionalConfidence() {
        val words = VoiceWordTimings.parse(JSONObject("""{"result":[
            {"word":"打开","start":0.25,"end":0.6,"conf":0.95},
            {"word":"设置","start":0.6,"end":1.1}] }"""), partial = false)
        assertEquals(listOf(VoiceWordTiming("打开", 250, 600, 0.95), VoiceWordTiming("设置", 600, 1100)), words)
        assertTrue(VoiceWordTimings.parse(JSONObject("""{"text":"没有时间"}"""), false).isEmpty())
    }

    @Test fun partialReplacementAndFinalCommitNeverDuplicateNativeSegments() {
        val transcript = OfflineSpeechTranscript()
        transcript.partial("""{"partial":"开","partial_result":[{"word":"开","start":0.1,"end":0.2}]}""")
        assertEquals("开", transcript.words.single().word)
        transcript.partial("""{"partial":"打开","partial_result":[{"word":"打开","start":0.1,"end":0.4}]}""")
        assertEquals(1, transcript.words.size)
        transcript.appendResult("""{"text":"打开","result":[{"word":"打开","start":0.1,"end":0.4,"conf":0.8}]}""")
        transcript.partial("""{"partial":"设置","partial_result":[{"word":"设置","start":0.5,"end":0.9}]}""")
        assertEquals(listOf("打开", "设置"), transcript.words.map { it.word })
        transcript.appendResult("""{"text":"设置","result":[{"word":"设置","start":0.5,"end":0.9}]}""")
        assertEquals("打开设置", transcript.text)
        assertEquals(listOf("打开", "设置"), transcript.words.map { it.word })
        transcript.appendResult("""{"text":""}""")
        assertEquals(2, transcript.words.size)
        assertTrue(OfflineSpeechTranscript().words.isEmpty())
    }

    @Test fun malformedUnorderedAndOversizedObservationsRemainBounded() {
        val data = JSONObject("""{"result":[
            {"word":"negative","start":-1,"end":1},
            {"word":"reverse","start":2,"end":1},
            {"word":"string","start":"0","end":1},
            {"word":"missing","start":0},
            {"word":"valid","start":0.1,"end":0.2,"conf":9},
            {"word":"overlap","start":0.15,"end":0.3},
            {"word":"later","start":0.3,"end":0.4}] }""")
        val words = VoiceWordTimings.parse(data, false)
        assertEquals(listOf("valid", "later"), words.map { it.word })
        assertNull(words.first().confidence)
        val rows = JSONArray()
        repeat(200) { rows.put(JSONObject().put("word", "w$it").put("start", it).put("end", it + 0.5)) }
        assertEquals(128, VoiceWordTimings.parse(JSONObject().put("result", rows), false).size)
        assertTrue(VoiceWordTimings.parse(JSONObject().put("result", "unavailable"), false).isEmpty())
    }
}
