package io.github.mangi.eta.agent.voice

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class SpeechCaptionTiming { UNKNOWN, CHUNK, RANGE }

internal data class SpeechPlaybackMetrics(
    val elapsedMs: Long = 0,
    /** 从请求到首次确认出声的单调时钟延迟；缺少播放进度的输出保持 null。 */
    val firstAudioMs: Long? = null,
)

internal data class SpeechPlaybackState(
    val messageId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** 正在听到的片段在原文中的 UTF-16 区间，定位失败时为 null。 */
    val highlight: IntRange? = null,
    val spokenChunks: Int = 0,
    val totalChunks: Int = 0,
    val caption: String? = null,
    val captionTiming: SpeechCaptionTiming = SpeechCaptionTiming.UNKNOWN,
    val synthesizedChunks: Int = 0,
    val completedChunks: Int = 0,
    val metrics: SpeechPlaybackMetrics = SpeechPlaybackMetrics(),
)

internal class SpeechPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val beforePlayback: () -> Unit = {},
    private val afterPlayback: () -> Unit = {},
    private val synthesize: suspend (SpeechSettings, SpeechCredentials, String, (ByteArray) -> Unit) -> Unit = ::synthesizeSpeech,
    /** 现有整篇流接口仅返回音频，未提供逐段时间戳，因此其字幕时序为 UNKNOWN。 */
    private val streamSynthesize: (suspend (SpeechSettings, SpeechCredentials, List<String>, (ByteArray) -> Unit) -> Unit)? = ::streamSpeech,
    private val createOutput: () -> SpeechAudioOutput = ::SpeechPcmOutput,
    private val systemSpeak: suspend (String, SpeechSettings, () -> Unit, (Int, Int) -> Unit) -> Unit = { text, config, start, range ->
        SystemTtsSpeaker.speak(context, text, config.speechRate, config.speechPitch, config.language,
            onStart = start, onRange = range)
    },
    private val resetSystem: () -> Unit = SystemTtsSpeaker::release,
    private val stopSystem: () -> Unit = SystemTtsSpeaker::stop,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutableState = MutableStateFlow(SpeechPlaybackState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0L
    private var output: SpeechAudioOutput? = null
    private var lease: SpeechAudioLease? = null
    private var playbackActive = false
    private var systemActive = false
    /** 只记真实确认的 readable 偏移；预取和写入音轨均不推进。 */
    @Volatile private var lastRead: Pair<String, Int>? = null

    fun speak(
        messageId: String,
        text: String,
        settings: SpeechSettings? = null,
        credentials: SpeechCredentials? = null,
        resume: Boolean = false,
        /** 仅自然播放完成回调一次；用户停止、被替换、取消和错误均不调用。 */
        onCompleted: () -> Unit = {},
    ) {
        stop()
        val session = generation
        val startedAt = monotonicMs()
        var firstAudioMs: Long? = null
        fun metrics() = SpeechPlaybackMetrics((monotonicMs() - startedAt).coerceAtLeast(0), firstAudioMs)
        fun publish(change: (SpeechPlaybackState) -> SpeechPlaybackState) {
            scope.launch(Dispatchers.Main.immediate) {
                if (session == generation) mutableState.value = change(mutableState.value).copy(metrics = metrics())
            }
        }
        fun heardAudio() {
            scope.launch(Dispatchers.Main.immediate) {
                if (session != generation) return@launch
                if (firstAudioMs == null) firstAudioMs = (monotonicMs() - startedAt).coerceAtLeast(0)
                mutableState.value = mutableState.value.copy(loading = false, metrics = metrics())
            }
        }
        mutableState.value = SpeechPlaybackState(messageId, loading = true)
        val playbackJob = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            var completed = false
            var notifyCompleted = false
            val ticker = launch {
                while (true) {
                    delay(100)
                    publish { it }
                }
            }
            try {
                val config = settings ?: SpeechSettingsRepository.settings()
                lease = SpeechAudioLease(context, interruptOnScreenOff = !config.speakOnScreenOff) { stop() }
                    .also { it.acquire(playback = false, recording = false) }
                // 本机路径完全跳过云密钥存储，损坏的历史云凭据也不会阻断 SYSTEM。
                val secrets = if (config.tts == TtsProvider.SYSTEM || config.tts == TtsProvider.NONE) {
                    SpeechCredentials()
                } else credentials ?: SpeechSettingsRepository.credentials(context)
                validateSpeechSettings(config, secrets, synthesis = true)
                val readable = withContext(Dispatchers.Default) { SpeechText.readable(text) }
                if (readable.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "这条消息没有可朗读的正文")
                if (readable.length > 30_000) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "正文过长，请选择较短的回答朗读")
                val from = if (resume) lastRead?.takeIf { it.first == messageId }?.second?.coerceIn(0, readable.length) ?: 0 else 0
                if (from >= readable.length) return@launch
                if (!resume) lastRead = messageId to 0
                val limit = if (config.tts == TtsProvider.SYSTEM) SYSTEM_TTS_CHUNK_LIMIT else SpeechText.DEFAULT_LIMIT
                val allChunks = SpeechText.chunks(readable, limit)
                val allOffsets = chunkOffsets(readable, allChunks)
                val allRanges = withContext(Dispatchers.Default) { SpeechSegmentLocator.locate(text, allChunks) }
                // 先定位整篇，再从已确认字符之后定位尾部，重复句子不会回到第一处。
                val originalFrom = allOffsets.indexOfLast { it <= from }.takeIf { it >= 0 }?.let { index ->
                    allRanges[index]?.let { range ->
                        if (range.count() == allChunks[index].length) range.first + from - allOffsets[index]
                        else if (from == allOffsets[index]) range.first else text.length
                    }
                } ?: if (from == 0) 0 else allOffsets.indices.lastOrNull {
                    allOffsets[it] + allChunks[it].length <= from && allRanges[it] != null
                }?.let { allRanges[it]!!.last + 1 } ?: text.length
                val pending = readable.substring(from)
                val chunks = SpeechText.chunks(pending, limit)
                val offsets = chunkOffsets(pending, chunks)
                val ranges = withContext(Dispatchers.Default) { SpeechSegmentLocator.locate(text, chunks, fromChar = originalFrom) }
                publish { it.copy(totalChunks = chunks.size) }
                fun confirm(index: Int) {
                    if (index !in chunks.indices) return
                    publish {
                        val read = (from + offsets[index] + chunks[index].length).coerceAtMost(readable.length)
                        lastRead = messageId to maxOf(lastRead?.takeIf { it.first == messageId }?.second ?: 0, read)
                        it.copy(completedChunks = maxOf(it.completedChunks, index + 1))
                    }
                }
                fun caption(index: Int, start: Int = 0, end: Int = chunks[index].length, timing: SpeechCaptionTiming) {
                    if (index !in chunks.indices || start !in 0 until end || end > chunks[index].length) return
                    val range = ranges[index]?.takeIf { it.count() == chunks[index].length }
                        ?.let { (it.first + start) until (it.first + end) }
                    publish { it.copy(loading = false, highlight = range,
                        caption = chunks[index].substring(start, end), captionTiming = timing,
                        spokenChunks = maxOf(it.spokenChunks, index + 1)) }
                }
                lease?.requestPlaybackFocus { beforePlayback(); playbackActive = true }
                if (config.tts == TtsProvider.SYSTEM) {
                    systemActive = true
                    chunks.forEachIndexed { index, chunk ->
                        publish { it.copy(highlight = null, caption = null, captionTiming = SpeechCaptionTiming.UNKNOWN) }
                        speakSystemChunk(chunk, config, onStart = ::heardAudio) { start, end ->
                            if (session == generation) {
                                // 新 range 的起点证明其前文已播出，当前词仍保守保留供失败续读。
                                val read = from + offsets[index] + start
                                lastRead = messageId to maxOf(lastRead?.takeIf { it.first == messageId }?.second ?: 0, read)
                                caption(index, start, end, SpeechCaptionTiming.RANGE)
                            }
                        }
                        publish { it.copy(synthesizedChunks = index + 1) }
                        confirm(index)
                    }
                } else {
                    val player = createOutput()
                    output = player
                    playChunks(config, secrets, chunks, player,
                        onSynthesized = { count -> publish { it.copy(synthesizedChunks = count) } },
                        onAudioQueued = { publish { it.copy(loading = false) } },
                        onFirstAudio = ::heardAudio,
                        onChunk = { index -> caption(index, timing = SpeechCaptionTiming.CHUNK) },
                        onChunkFinished = ::confirm,
                        onUnknown = { publish { it.copy(highlight = null, caption = null, captionTiming = SpeechCaptionTiming.UNKNOWN) } },
                    )
                }
                if (session == generation) {
                    lastRead = messageId to readable.length
                    mutableState.value = mutableState.value.copy(completedChunks = chunks.size)
                    completed = true
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (session == generation) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech playback failed: code=${failure.code} type=${error.javaClass.simpleName} detail=${failure.detail}")
                    mutableState.value = mutableState.value.copy(error = failure.userMessage)
                }
            } finally {
                ticker.cancel()
                if (session == generation) {
                    // 先关闭本轮资源，再让主 Service 的回调开始下一轮录音。
                    val finalState = mutableState.value.copy(messageId = null, loading = false, highlight = null,
                        caption = null, captionTiming = SpeechCaptionTiming.UNKNOWN, metrics = metrics())
                    disposeAudio()
                    job = null
                    mutableState.value = finalState
                    notifyCompleted = completed
                    generation++ // 丢弃排队的本轮字幕与计时更新。
                }
            }
            if (notifyCompleted) onCompleted()
        }
        job = playbackJob
        playbackJob.start()
    }

    /** 合成可预取；段边界由实际写入帧数记录，由 playback head 确认开始与结束。 */
    private suspend fun playChunks(
        config: SpeechSettings,
        secrets: SpeechCredentials,
        chunks: List<String>,
        player: SpeechAudioOutput,
        onSynthesized: (Int) -> Unit,
        onAudioQueued: () -> Unit,
        onFirstAudio: () -> Unit,
        onChunk: (Int) -> Unit,
        onChunkFinished: (Int) -> Unit,
        onUnknown: () -> Unit,
    ) = coroutineScope {
        val queue = Channel<SpeechQueueItem>(Channel.UNLIMITED)
        val ahead = Semaphore(PREFETCH_CHUNKS)
        val boundaries = mutableListOf<SpeechAudioBoundary>()
        var writtenBytes = 0L
        var announcedAudio = false
        var announcedChunk: Int? = null
        fun progress() {
            synchronized(boundaries) {
                val head = player.playedFrames() ?: return
                if (head > 0 && !announcedAudio) { announcedAudio = true; onFirstAudio() }
                boundaries.forEach { boundary ->
                    val end = boundary.end
                    if (!boundary.completed && end != null && end > boundary.start && head >= end) {
                        boundary.completed = true
                        boundary.index?.let(onChunkFinished)
                    }
                }
                val active = boundaries.lastOrNull { head > it.start && (it.end == null || head < it.end!!) }
                val index = active?.index
                if (index != announcedChunk) {
                    announcedChunk = index
                    if (index == null) onUnknown() else onChunk(index)
                }
            }
        }
        val monitor = launch(Dispatchers.IO) {
            while (true) { progress(); delay(20) }
        }
        try {
            launch {
                try {
                    var streamed = false
                    val stream = streamSynthesize
                    if (stream != null && supportsSpeechSession(config.tts)) {
                        try {
                            ahead.acquire()
                            queue.send(SpeechQueueItem.Begin(null))
                            stream(config, secrets, chunks) { bytes ->
                                if (bytes.isNotEmpty()) { streamed = true; queue.trySend(SpeechQueueItem.Audio(bytes)) }
                            }
                            if (!streamed) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "语音服务没有返回音频")
                            onSynthesized(chunks.size)
                            queue.send(SpeechQueueItem.End)
                        } catch (error: Exception) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            if (streamed) throw error
                            AndroidAgentLogger.warn("Eta speech stream unavailable, using segments: code=${error.speechFailure().code}")
                            ahead.release()
                        }
                    }
                    if (!streamed) chunks.forEachIndexed { index, chunk ->
                        ahead.acquire()
                        queue.send(SpeechQueueItem.Begin(index))
                        var audio = false
                        synthesize(config, secrets, chunk) { bytes ->
                            if (bytes.isNotEmpty()) { audio = true; queue.trySend(SpeechQueueItem.Audio(bytes)) }
                        }
                        if (!audio) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "语音服务没有返回音频")
                        onSynthesized(index + 1)
                        queue.send(SpeechQueueItem.End)
                    }
                    queue.close()
                } catch (error: Throwable) {
                    queue.close(error)
                }
            }
            withContext(Dispatchers.IO) {
                val fade = SpeechSegmentFade()
                var current: SpeechAudioBoundary? = null
                suspend fun play(bytes: ByteArray) {
                    if (bytes.isEmpty()) return
                    runInterruptible { player.write(bytes) }
                    writtenBytes += bytes.size
                    onAudioQueued()
                    progress()
                }
                for (item in queue) when (item) {
                    is SpeechQueueItem.Begin -> {
                        current = SpeechAudioBoundary(item.index, writtenBytes / PCM_BYTES_PER_FRAME)
                        synchronized(boundaries) { boundaries += current!! }
                    }
                    is SpeechQueueItem.Audio -> { onAudioQueued(); play(fade.write(item.bytes)) }
                    SpeechQueueItem.End -> {
                        play(fade.end())
                        synchronized(boundaries) { current?.end = writtenBytes / PCM_BYTES_PER_FRAME }
                        ahead.release()
                        progress()
                    }
                }
                player.finish()
                withTimeout(180_000) {
                    while (!player.drained()) { progress(); delay(20) }
                }
                progress()
                // 无播放头的兼容输出只能确认整次排空，绝不编造中途字幕或已听位置。
                boundaries.forEach { it.index?.let(onChunkFinished) }
            }
        } finally {
            monitor.cancel()
            queue.cancel()
        }
    }

    /** 只用真实 range 的起点作为重试位置；未知已读范围时重读该小段，绝不按时间跳字。 */
    private suspend fun speakSystemChunk(
        text: String,
        config: SpeechSettings,
        depth: Int = 0,
        onStart: () -> Unit,
        onRange: (Int, Int) -> Unit,
    ) {
        var confirmed = 0
        try {
            systemSpeak(text, config, onStart) { start, end ->
                if (start in 0 until end && end <= text.length) {
                    confirmed = maxOf(confirmed, start)
                    onRange(start, end)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            if (depth >= SYSTEM_SPLIT_DEPTH || error.speechFailure().code !in
                setOf(SpeechErrorCode.AUDIO, SpeechErrorCode.TIMEOUT)
            ) throw error
            val tail = text.substring(confirmed)
            val parts = SpeechText.chunks(tail, maxOf(2, (tail.length + 1) / 2))
            if (parts.isEmpty() || (parts.size == 1 && confirmed == 0)) throw error
            resetSystem()
            val offsets = chunkOffsets(tail, parts)
            parts.forEachIndexed { index, part ->
                val offset = confirmed + offsets[index]
                speakSystemChunk(part, config, depth + 1, onStart) { start, end -> onRange(offset + start, offset + end) }
            }
        }
    }

    private fun chunkOffsets(text: String, chunks: List<String>): List<Int> {
        var cursor = 0
        return chunks.map { chunk ->
            val start = text.indexOf(chunk, cursor).takeIf { it >= 0 } ?: cursor
            cursor = start + chunk.length
            start
        }
    }

    private fun disposeAudio() {
        output?.close(); output = null
        if (systemActive) stopSystem()
        systemActive = false
        lease?.close(); lease = null
        if (playbackActive) afterPlayback()
        playbackActive = false
    }

    fun stop() {
        generation++
        job?.cancel(); job = null
        disposeAudio()
        mutableState.value = SpeechPlaybackState()
    }
}

private sealed interface SpeechQueueItem {
    data class Begin(val index: Int?) : SpeechQueueItem
    class Audio(val bytes: ByteArray) : SpeechQueueItem
    data object End : SpeechQueueItem
}

private class SpeechAudioBoundary(val index: Int?, val start: Long, var end: Long? = null, var completed: Boolean = false)
private const val PREFETCH_CHUNKS = 2
private const val SYSTEM_TTS_CHUNK_LIMIT = 100
private const val SYSTEM_SPLIT_DEPTH = 2
private const val PCM_BYTES_PER_FRAME = 2

internal interface SpeechAudioOutput : Closeable {
    fun write(bytes: ByteArray)
    fun finish()
    fun drained(): Boolean
    /** 实际已播出的单声道 PCM 帧数。旧 fake 默认未知，不能用写入量代替。 */
    fun playedFrames(): Long? = null
}
