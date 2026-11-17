package io.github.mangi.eta.agent.voice

import java.io.Closeable

/** Kept separate from capture so tests can replay finite PCM without loading Vosk/JNA. */
internal interface DuplexVoiceDecoder : Closeable {
    val wordTimingsEnabled: Boolean
    val partialWordTimingsEnabled: Boolean
    fun accept(frame: ByteArray): Boolean
    fun partial(): String
    fun result(): String
    fun finalResult(): String
    fun reset()
}

internal data class DuplexFrameResult(
    val partial: String,
    val final: String? = null,
    val bargeIn: Boolean = false,
    val idleExpired: Boolean = false,
    val level: Float = 0f,
    val metrics: DuplexVoiceMetrics,
    val words: List<VoiceWordTiming> = emptyList(),
)

/** Single IO owner. Never calls an LLM, TTS, a remote recognizer, or a user callback. */
internal class DuplexVoiceProcessor(private val decoder: DuplexVoiceDecoder, silenceMs: Int) {
    private val vad = NativeVoiceActivityDetector()
    private val endpoint = DuplexVoiceEndpoint(silenceMs)
    private val bargeIn = DuplexBargeInGate()
    private var transcript = OfflineSpeechTranscript()
    private var recognizedPartial = ""
    private var samples = 0L
    private var completedTurns = 0
    private var bargeIns = 0
    private var blockedByPlayback = false
    private var lastObservation: NativeVoiceActivityDetector.Observation? = null

    fun frame(pcm: ByteArray, assistantSpeaking: Boolean, audio: VoiceCaptureStatus,
        finishRequested: Boolean = false): DuplexFrameResult {
        // Reset before observing the first recovered frame: speaker echo must never contribute to
        // the 250 ms continuous user-speech evidence when AEC or the headphone route becomes usable.
        val blocked = assistantSpeaking && !audio.bargeInAvailable
        if (blocked != blockedByPlayback) resetTurn()
        blockedByPlayback = blocked
        val observation = vad.observe(pcm)
        lastObservation = observation
        samples += observation.samples
        // With speaker playback and failed AEC, feeding echo to ASR could also submit a false turn.
        // Preserve the mic stream while quarantining that interval, then start a clean recognizer turn.
        if (blocked) {
            val end = endpoint.observe(observation.copy(voiced = false, speechActive = false,
                continuousSpeechMs = 0), "")
            return result("", null, false, observation, end, blocked = true)
        }
        val segmented = decoder.accept(pcm)
        val text = if (segmented) {
            // Query the current partial before consuming result: a completed native segment alone is
            // not accepted as textual evidence for an interruption.
            val currentPartial = OfflineSpeechTranscript().partial(decoder.partial())
            if (currentPartial.isNotBlank()) recognizedPartial = currentPartial
            transcript.appendResult(decoder.result())
        } else {
            val raw = decoder.partial()
            recognizedPartial = OfflineSpeechTranscript().partial(raw)
            transcript.partial(raw)
        }
        val interrupted = bargeIn.observe(assistantSpeaking, audio,
            observation.continuousSpeechMs, recognizedPartial)
        if (interrupted) bargeIns++
        val end = endpoint.observe(observation, text)
        val shouldFinish = finishRequested || end != null
        val final = if (shouldFinish) finishText() else null
        val result = result(if (shouldFinish) "" else text, final, interrupted, observation, end)
        if (shouldFinish) resetTurn()
        return result
    }

    fun finish(): DuplexFrameResult {
        val final = finishText()
        val observation = lastObservation ?: NativeVoiceActivityDetector.Observation(
            0, 0.0, 0.0, 0.0, false, false, 0, 0f)
        val result = result("", final, false, observation, VoiceTurnEnd.MANUAL)
        resetTurn()
        return result
    }

    private fun finishText(): String? {
        val text = transcript.appendResult(decoder.finalResult()).trim()
        if (!endpoint.hasSpeech || text.isBlank()) return null
        completedTurns++
        return text
    }

    private fun result(partial: String, final: String?, interrupted: Boolean,
        observation: NativeVoiceActivityDetector.Observation, end: VoiceTurnEnd?, blocked: Boolean = false
    ) = DuplexFrameResult(
        partial = partial,
        final = final,
        bargeIn = interrupted,
        idleExpired = endpoint.idleExpired,
        level = observation.level,
        words = if (blocked) emptyList() else transcript.words,
        metrics = DuplexVoiceMetrics(
            capturedMs = samples * 1_000 / 16_000,
            utteranceMs = endpoint.utteranceMs,
            speechMs = endpoint.speechMs,
            continuousSpeechMs = if (blocked) 0 else observation.continuousSpeechMs,
            silenceMs = endpoint.silenceMs,
            rms = observation.rms,
            noiseFloor = observation.noiseFloor,
            speechThreshold = observation.threshold,
            endpointSilenceMs = endpoint.hint.silenceMs,
            semanticHint = when {
                blocked -> "扬声器播放且 AEC 未启用，识别等待播放结束或耳机路由"
                end == VoiceTurnEnd.MAX_UTTERANCE -> "单句达到 60 秒，结束本句后继续聆听"
                else -> endpoint.hint.explanation
            },
            completedTurns = completedTurns,
            bargeIns = bargeIns,
            wordTimingsEnabled = decoder.wordTimingsEnabled,
            partialWordTimingsEnabled = decoder.partialWordTimingsEnabled,
        ),
    )

    private fun resetTurn() {
        decoder.reset()
        transcript = OfflineSpeechTranscript()
        recognizedPartial = ""
        endpoint.reset()
        vad.resetSpeech()
        bargeIn.reset()
    }
}
