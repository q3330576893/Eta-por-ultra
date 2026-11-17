package io.github.mangi.eta.agent.voice

import kotlin.math.sqrt

/**
 * On-device adaptive RMS VAD over PCM16, independent of recognizer timing. “Native” describes the
 * device audio path, not a neural VAD model. Acoustic voice evidence alone never permits barge-in.
 */
class NativeVoiceActivityDetector(private val sampleRate: Int = 16_000) {
    private var noise = INITIAL_NOISE_FLOOR
    private var active = false
    private var quietSamples = 0L
    private var speechRunSamples = 0L

    data class Observation(
        val samples: Int,
        val rms: Double,
        val noiseFloor: Double,
        val threshold: Double,
        /** Raw hysteresis threshold decision; hangover is excluded from interruption evidence. */
        val voiced: Boolean,
        val speechActive: Boolean,
        val continuousSpeechMs: Long,
        val level: Float,
    )

    init { require(sampleRate > 0) }

    fun observe(pcm16: ByteArray): Observation {
        require(pcm16.isNotEmpty() && pcm16.size % 2 == 0) { "Expected non-empty PCM16 frame" }
        val samples = pcm16.size / 2
        var energy = 0.0
        for (index in pcm16.indices step 2) {
            val sample = ((pcm16[index].toInt() and 255) or (pcm16[index + 1].toInt() shl 8))
                .toShort().toDouble()
            energy += sample * sample
        }
        val rms = sqrt(energy / samples)
        val threshold = maxOf(MIN_SPEECH_RMS, noise * NOISE_MULTIPLIER)
        val voiced = rms >= if (active) threshold * RELEASE_RATIO else threshold
        if (voiced) {
            active = true
            quietSamples = 0
            speechRunSamples += samples
        } else {
            speechRunSamples = 0
            quietSamples += samples
            if (quietSamples * 1_000 >= HANGOVER_MS * sampleRate) active = false
            // Adapt only outside speech. A loud utterance must not train itself into the noise floor.
            if (!active) {
                val alpha = (samples.toDouble() / sampleRate / NOISE_ADAPT_SECONDS).coerceIn(0.0, 1.0)
                noise += (rms.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR) - noise) * alpha
            }
        }
        return Observation(samples, rms, noise, threshold, voiced, active,
            speechRunSamples * 1_000 / sampleRate, (rms / 4_000).toFloat().coerceIn(0f, 1f))
    }

    /** Preserve the learned environment between utterances while clearing speech/hangover history. */
    fun resetSpeech() {
        active = false
        quietSamples = 0
        speechRunSamples = 0
    }

    private companion object {
        const val INITIAL_NOISE_FLOOR = 90.0
        const val MIN_NOISE_FLOOR = 35.0
        const val MAX_NOISE_FLOOR = 1_600.0
        const val MIN_SPEECH_RMS = 280.0
        const val NOISE_MULTIPLIER = 3.0
        const val RELEASE_RATIO = 0.72
        const val HANGOVER_MS = 140L
        const val NOISE_ADAPT_SECONDS = 0.7
    }
}
