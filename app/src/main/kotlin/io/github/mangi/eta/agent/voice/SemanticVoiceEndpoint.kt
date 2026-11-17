package io.github.mangi.eta.agent.voice

/** Explainable text-suffix hint layered over PCM silence; this uses rules rather than a neural model. */
internal object SemanticVoiceEndpoint {
    data class Hint(val silenceMs: Int, val explanation: String)

    fun hint(configuredSilenceMs: Int, text: String): Hint {
        if (configuredSilenceMs <= 0) return Hint(0, "自动发送已关闭，等待手动完成")
        val base = configuredSilenceMs.coerceIn(1_000, 30_000)
        val compact = text.replace(Regex("\\s+"), "").trimEnd('。', '，', '、', '！', '？', '.', ',', '!', '?')
        val suffix = CONTINUATION_SUFFIXES.firstOrNull { compact.endsWith(it) }
        return if (compact.length <= SHORT_SENTENCE_CHARS && suffix != null) {
            Hint((base + CONTINUATION_EXTRA_MS).coerceAtMost(30_000),
                "短句以“$suffix”结尾，语义提示规则延长等待 ${CONTINUATION_EXTRA_MS}ms")
        } else {
            Hint(base, "按语音设置的尾静音发送")
        }
    }

    private const val SHORT_SENTENCE_CHARS = 24
    private const val CONTINUATION_EXTRA_MS = 1_000
    private val CONTINUATION_SUFFIXES = listOf("然后", "帮我", "如果", "还有", "的")
}

internal enum class VoiceTurnEnd { SILENCE, MANUAL, MAX_UTTERANCE, STREAM_END }

/** Sample counts keep endpoint behavior deterministic even when native ASR runs faster/slower than realtime. */
internal class DuplexVoiceEndpoint(private val configuredSilenceMs: Int, private val sampleRate: Int = 16_000) {
    private var started = false
    private var utteranceSamples = 0L
    private var speechSamples = 0L
    private var silenceSamples = 0L
    private var idleSamples = 0L
    var hint = SemanticVoiceEndpoint.hint(configuredSilenceMs, "")
        private set
    val utteranceMs: Long get() = utteranceSamples * 1_000 / sampleRate
    val speechMs: Long get() = speechSamples * 1_000 / sampleRate
    val silenceMs: Long get() = silenceSamples * 1_000 / sampleRate
    val idleExpired: Boolean get() = idleSamples * 1_000 >= MAX_IDLE_MS * sampleRate
    val hasSpeech: Boolean get() = started

    fun observe(observation: NativeVoiceActivityDetector.Observation, text: String): VoiceTurnEnd? {
        hint = SemanticVoiceEndpoint.hint(configuredSilenceMs, text)
        if (observation.voiced) {
            idleSamples = 0
            started = true
            speechSamples += observation.samples
            silenceSamples = 0
        } else {
            idleSamples += observation.samples
            if (started) silenceSamples += observation.samples
        }
        if (started) utteranceSamples += observation.samples
        if (utteranceMs >= MAX_UTTERANCE_MS) return VoiceTurnEnd.MAX_UTTERANCE
        if (started && text.isNotBlank() && hint.silenceMs > 0 && silenceMs >= hint.silenceMs) {
            return VoiceTurnEnd.SILENCE
        }
        return null
    }

    fun reset() {
        started = false
        utteranceSamples = 0
        speechSamples = 0
        silenceSamples = 0
        hint = SemanticVoiceEndpoint.hint(configuredSilenceMs, "")
    }

    companion object {
        const val MAX_UTTERANCE_MS = 60_000L
        const val MAX_IDLE_MS = 120_000L
    }
}

/** Pure gate, shared by capture coordination and finite PCM tests. One interruption per user utterance. */
internal class DuplexBargeInGate {
    private var delivered = false

    fun observe(assistantSpeaking: Boolean, audio: VoiceCaptureStatus,
        continuousSpeechMs: Long, recognizedPartial: String): Boolean {
        if (delivered || !assistantSpeaking || !audio.bargeInAvailable ||
            continuousSpeechMs < MIN_SPEECH_MS || recognizedPartial.isBlank()) return false
        delivered = true
        return true
    }

    fun reset() { delivered = false }

    private companion object { const val MIN_SPEECH_MS = 250L }
}
