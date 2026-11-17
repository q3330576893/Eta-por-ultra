package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** A single native decoder owner; cancellation stops AudioRecord and closes native resources in IO finally. */
internal suspend fun recognizeOfflineVosk(
    context: Context,
    modelDirectory: java.io.File,
    frames: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>,
    endpointSilenceMs: Int,
    onCaptureReady: () -> Unit,
    onPreview: (String) -> Unit,
    onEndpoint: () -> Unit,
): String = withContext(Dispatchers.IO) {
    try {
        Model(modelDirectory.absolutePath).use { model ->
            Recognizer(model, SpeechRecorder.RATE.toFloat()).use { recognizer ->
                recognizer.setWords(false)
                recognizer.setPartialWords(false)
                // Vosk's native delays are seconds; keep segmentation short while our endpoint gate obeys user settings.
                recognizer.setEndpointerDelays(60f, 0.8f, 60f)
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate) { onCaptureReady() }
                decodeOfflineFrames(
                    frames = frames,
                    decoder = object : OfflineSpeechDecoder {
                        override fun accept(frame: ByteArray): Boolean = recognizer.acceptWaveForm(frame, frame.size)
                        override fun partial(): String = recognizer.partialResult
                        override fun result(): String = recognizer.result
                        override fun finalResult(): String = recognizer.finalResult
                    },
                    endpointSilenceMs = endpointSilenceMs,
                    onPreview = onPreview,
                    onEndpoint = onEndpoint,
                )
            }
        }
    } catch (error: LinkageError) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, context.getString(R.string.offline_asr_engine_failed), "native_load")
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException || error is SpeechFailure) throw error
        throw SpeechFailure(SpeechErrorCode.AUDIO, context.getString(R.string.offline_asr_engine_failed), error.javaClass.simpleName)
    }
}

internal interface OfflineSpeechDecoder {
    fun accept(frame: ByteArray): Boolean
    fun partial(): String
    fun result(): String
    fun finalResult(): String
}

/** Engine-independent coordinator is exercised with finite PCM streams in JVM tests. */
internal suspend fun decodeOfflineFrames(
    frames: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>,
    decoder: OfflineSpeechDecoder,
    endpointSilenceMs: Int,
    onPreview: (String) -> Unit,
    onEndpoint: () -> Unit,
): String {
    val transcript = OfflineSpeechTranscript()
    val endpoint = OfflineSpeechEndpoint(endpointSilenceMs)
    for (frame in frames) {
        currentCoroutineContext().ensureActive()
        require(frame.isNotEmpty() && frame.size % 2 == 0)
        val segmented = decoder.accept(frame)
        val text = if (segmented) transcript.appendResult(decoder.result()) else transcript.partial(decoder.partial())
        if (text.isNotBlank()) onPreview(text)
        if (endpoint.observe(frame, transcript.text.isNotBlank(), segmented)) {
            onEndpoint()
            break
        }
    }
    currentCoroutineContext().ensureActive()
    return transcript.appendResult(decoder.finalResult())
}

/** Vosk returns only the latest utterance; retain finalized segments while partial text is replaceable. */
internal class OfflineSpeechTranscript {
    private val completed = mutableListOf<String>()
    private var pending = ""
    private var completedWords = emptyList<VoiceWordTiming>()
    private var partialWords = emptyList<VoiceWordTiming>()
    val words: List<VoiceWordTiming> get() = (completedWords + partialWords).takeLast(VoiceWordTimings.MAX_WORDS)
    val text: String get() = joinSpeechText(completed + pending)

    fun partial(json: String): String {
        val data = JSONObject(json)
        pending = normalize(data.optString("partial"))
        val lastEnd = completedWords.lastOrNull()?.endMs ?: 0L
        partialWords = VoiceWordTimings.parse(data, partial = true).filter { it.startMs >= lastEnd }
        return text
    }

    fun appendResult(json: String): String {
        val data = JSONObject(json)
        val segment = normalize(data.optString("text"))
        pending = ""
        partialWords = emptyList()
        if (segment.isNotEmpty()) {
            completed += segment
            val lastEnd = completedWords.lastOrNull()?.endMs ?: 0L
            completedWords = (completedWords + VoiceWordTimings.parse(data, partial = false)
                .filter { it.startMs >= lastEnd }).takeLast(VoiceWordTimings.MAX_WORDS)
        }
        return text
    }

    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}

/** Endpoint duration counts PCM samples, independent of decoder CPU speed and wall-clock scheduling. */
internal class OfflineSpeechEndpoint(private val silenceMs: Int) {
    private var speechSeen = false
    private var silenceSamples = 0L
    private var ended = false

    fun observe(frame: ByteArray, textSeen: Boolean, segmented: Boolean): Boolean {
        if (ended || silenceMs <= 0) return false
        var energy = 0.0
        for (index in frame.indices step 2) {
            val sample = ((frame[index].toInt() and 255) or (frame[index + 1].toInt() shl 8)).toShort().toDouble()
            energy += sample * sample
        }
        val samples = frame.size / 2
        val audible = samples > 0 && kotlin.math.sqrt(energy / samples) >= 260.0
        if (audible) {
            speechSeen = true
            silenceSamples = 0
        } else if (speechSeen && textSeen) {
            silenceSamples += samples
        }
        // Native segmentation confirms speech, but a user-selected longer pause still takes precedence.
        if (segmented && textSeen) speechSeen = true
        if (speechSeen && textSeen && silenceSamples * 1000 >= silenceMs.toLong() * SpeechRecorder.RATE) {
            ended = true
            return true
        }
        return false
    }
}

internal fun joinSpeechText(parts: List<String>): String = parts.filter(String::isNotBlank).joinToString(" ")
    .replace(Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])"), "")
    .trim()
