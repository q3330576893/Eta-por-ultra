package io.github.mangi.eta.agent.voice

import org.json.JSONObject

/** Native decoder offsets in milliseconds. They are scoped to one recognizer turn, not wall time. */
data class VoiceWordTiming(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Double? = null,
)

internal object VoiceWordTimings {
    const val MAX_WORDS = 128
    fun parse(json: JSONObject, partial: Boolean): List<VoiceWordTiming> {
        val rows = json.optJSONArray(if (partial) "partial_result" else "result") ?: return emptyList()
        val words = mutableListOf<VoiceWordTiming>()
        var previousEnd = 0L
        for (index in 0 until minOf(rows.length(), MAX_WORDS)) {
            val row = rows.optJSONObject(index) ?: continue
            val word = (row.opt("word") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 } ?: continue
            val start = (row.opt("start") as? Number)?.toDouble() ?: continue
            val end = (row.opt("end") as? Number)?.toDouble() ?: continue
            if (!start.isFinite() || !end.isFinite() || start < 0 || end < start || end > 86_400) continue
            val startMs = (start * 1_000).toLong()
            val endMs = (end * 1_000).toLong()
            if (startMs < previousEnd) continue
            val confidence = (row.opt("conf") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 }
            words += VoiceWordTiming(word, startMs, endMs, confidence)
            previousEnd = endMs
        }
        return words
    }
}

enum class VoiceTimelineKind { USER_FINAL, BARGE_IN, ASSISTANT_STARTED, ASSISTANT_ENDED }

data class VoiceTimelineEntry(
    val kind: VoiceTimelineKind,
    val elapsedMs: Long,
    val capturedMs: Long? = null,
    val text: String = "",
    val words: List<VoiceWordTiming> = emptyList(),
)
