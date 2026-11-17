package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.data.model.SpeechSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Explicitly started by a visible Service after its conversation switch is enabled. The caller owns
 * visibility, permission, foreground type and TTS/execution; this class only captures and transcribes.
 * A model and recognizer stay alive across turns, including while the assistant works or speaks.
 */
class DuplexVoiceSession internal constructor(
    private val scope: CoroutineScope,
    private val callbacks: DuplexVoiceCallbacks,
    private val dependencies: DuplexVoiceDependencies,
) {
    constructor(context: Context, scope: CoroutineScope, callbacks: DuplexVoiceCallbacks) : this(
        scope, callbacks, DuplexVoiceDependencies.android(context.applicationContext),
    )

    private val mutableState = MutableStateFlow(DuplexVoiceState())
    val state = mutableState.asStateFlow()
    private val generation = AtomicLong(0)
    private val assistantSpeaking = AtomicBoolean(false)
    private val assistantBusy = AtomicBoolean(false)
    private val lifecycleLock = Any()
    // A replacement waits until native close and the old foreground cleanup have both completed.
    private val nativeOwner = Mutex()
    @Volatile private var current: Run? = null
    private var timelineStartedNanos = 0L

    fun start(settings: SpeechSettings) {
        synchronized(lifecycleLock) {
            val ticket = generation.incrementAndGet()
            retireCurrent()
            if (!settings.recognitionEnabled) {
                post(ticket) { updateState(DuplexVoiceState(message = "语音输入已关闭")) }
                return
            }
            val run = Run(ticket)
            current = run
            post(ticket) {
                timelineStartedNanos = dependencies.nanoTime()
                updateState(DuplexVoiceState(phase = DuplexVoicePhase.STARTING,
                    message = "正在准备内置 Vosk 中文持续语音",
                    assistantSpeaking = assistantSpeaking.get(), assistantBusy = assistantBusy.get()))
            }
            run.job = scope.launch(dependencies.io, start = CoroutineStart.LAZY) {
                nativeOwner.withLock {
                    requireCurrent(run)
                    listen(run, settings)
                }
            }
            run.job?.start()
        }
    }

    fun setAssistantSpeaking(speaking: Boolean) {
        assistantSpeaking.set(speaking)
        val ticket = generation.get()
        post(ticket) {
            if (mutableState.value.active && mutableState.value.assistantSpeaking != speaking) {
                recordTimeline(if (speaking) VoiceTimelineKind.ASSISTANT_STARTED else VoiceTimelineKind.ASSISTANT_ENDED)
            }
            updateState(mutableState.value.copy(assistantSpeaking = speaking,
                assistantBusy = assistantBusy.get(), message = listeningMessage(mutableState.value.audio)))
        }
    }

    /** Busy changes status only; it never pauses AudioRecord or consumes an incoming sentence. */
    fun setAssistantBusy(busy: Boolean) {
        assistantBusy.set(busy)
        val ticket = generation.get()
        post(ticket) {
            updateState(mutableState.value.copy(assistantSpeaking = assistantSpeaking.get(),
                assistantBusy = assistantBusy.get(), message = listeningMessage(mutableState.value.audio)))
        }
    }

    /** Finish only this user utterance. The microphone/model remain ready for the next sentence. */
    fun finishTurn() { current?.finishRequested?.set(true) }

    /** Immediately invalidates queued callbacks and unblocks capture; IO finally owns native release. */
    fun stop() {
        synchronized(lifecycleLock) {
            val ticket = generation.incrementAndGet()
            retireCurrent()
            assistantSpeaking.set(false)
            assistantBusy.set(false)
            post(ticket) {
                updateState(mutableState.value.copy(phase = DuplexVoicePhase.STOPPED, preview = "", level = 0f,
                    message = "持续语音已停止", assistantSpeaking = false, assistantBusy = false))
            }
        }
    }

    private fun retireCurrent() {
        current?.let { previous ->
            previous.capture?.cancel()
            previous.job?.cancel(CancellationException("Duplex voice owner replaced"))
        }
        current = null
    }

    private suspend fun listen(run: Run, settings: SpeechSettings) {
        var decoder: DuplexVoiceDecoder? = null
        var capture: VoiceCapture? = null
        var terminalPhase = DuplexVoicePhase.STOPPED
        var terminalMessage = "持续语音已停止"
        try {
            val activeDecoder = dependencies.decoder { progress ->
                post(run.ticket) { updateState(mutableState.value.copy(message = progress)) }
            }
            decoder = activeDecoder
            requireCurrent(run)
            val processor = DuplexVoiceProcessor(activeDecoder, settings.autoSendSilenceMs)
            val activeCapture = dependencies.capture()
            capture = activeCapture
            run.capture = activeCapture
            withContext(dependencies.main) {
                requireCurrent(run)
                callbacks.beforeCapture()
                run.foregroundStarted = true
            }
            requireCurrent(run)
            activeCapture.start()
            requireCurrent(run)
            withContext(dependencies.main) {
                requireCurrent(run)
                updateState(mutableState.value.copy(phase = DuplexVoicePhase.LISTENING,
                    audio = activeCapture.status, message = listeningMessage(activeCapture.status)))
            }
            var previousPartial = ""
            var previousStatus: VoiceCaptureStatus? = null
            var lastStateMs = -STATE_INTERVAL_MS
            var lastSpeechNanos = dependencies.nanoTime()
            while (true) {
                requireCurrent(run)
                val frame = activeCapture.read()
                requireCurrent(run)
                if (frame == null) {
                    deliver(run, processor.finish(), activeCapture.status, previousPartial, reportState = true)
                    terminalMessage = "音频流已结束"
                    break
                }
                val status = activeCapture.status
                val result = processor.frame(frame, assistantSpeaking.get(), status,
                    run.finishRequested.getAndSet(false))
                if (result.metrics.continuousSpeechMs > 0) lastSpeechNanos = dependencies.nanoTime()
                val report = result.metrics.capturedMs - lastStateMs >= STATE_INTERVAL_MS ||
                    previousStatus != status || result.final != null || result.bargeIn
                if (report || result.partial != previousPartial) {
                    deliver(run, result, status, previousPartial, report)
                    previousPartial = result.partial
                    previousStatus = status
                    if (report) lastStateMs = result.metrics.capturedMs
                }
                val wallIdle = (dependencies.nanoTime() - lastSpeechNanos) / 1_000_000 >=
                    DuplexVoiceEndpoint.MAX_IDLE_MS
                if (result.idleExpired || wallIdle) {
                    terminalMessage = "连续 120 秒未检测到用户语音，持续语音已结束"
                    break
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            terminalPhase = DuplexVoicePhase.ERROR
            terminalMessage = when (error) {
                is SecurityException -> "麦克风权限或前台采集条件未满足"
                is SpeechFailure -> error.userMessage
                else -> "持续语音启动或采集失败（${error.javaClass.simpleName}）"
            }
        } catch (_: LinkageError) {
            terminalPhase = DuplexVoicePhase.ERROR
            terminalMessage = "内置 Vosk 原生引擎加载失败"
        } finally {
            run.capture = null
            runCatching { capture?.cancel() }
            runCatching { capture?.close() }
            runCatching { decoder?.close() }
            // Cleanup hooks belong to the capture lifetime. A new generation cannot enter this
            // block's nativeOwner until its old hook has returned, so it cannot stop a new foreground.
            withContext(NonCancellable + dependencies.main) {
                try {
                    if (run.foregroundStarted) {
                        run.foregroundStarted = false
                        callbacks.afterCapture()
                    }
                } finally {
                    if (generation.get() == run.ticket) {
                        if (current === run) current = null
                        updateState(mutableState.value.copy(phase = terminalPhase, preview = "", level = 0f,
                            message = terminalMessage))
                    }
                }
            }
        }
    }

    private suspend fun deliver(run: Run, result: DuplexFrameResult, audio: VoiceCaptureStatus,
        previousPartial: String, reportState: Boolean) = withContext(dependencies.main) {
        requireCurrent(run)
        mutableState.value = mutableState.value.copy(phase = DuplexVoicePhase.LISTENING,
            preview = result.partial, level = result.level, audio = audio, metrics = result.metrics, words = result.words,
            assistantSpeaking = assistantSpeaking.get(), assistantBusy = assistantBusy.get(),
            message = listeningMessage(audio))
        if (result.bargeIn && assistantSpeaking.get()) {
            recordTimeline(VoiceTimelineKind.BARGE_IN, result.partial, result)
            callbacks.onBargeIn()
        }
        requireCurrent(run)
        if (result.partial != previousPartial) callbacks.onPartial(result.partial)
        requireCurrent(run)
        result.final?.takeIf { it.isNotBlank() }?.let { text ->
            recordTimeline(VoiceTimelineKind.USER_FINAL, text, result)
            callbacks.onFinal(text)
        }
        requireCurrent(run)
        if (reportState) callbacks.onState(mutableState.value)
    }

    private fun recordTimeline(kind: VoiceTimelineKind, text: String = "", result: DuplexFrameResult? = null) {
        val entry = VoiceTimelineEntry(kind,
            elapsedMs = ((dependencies.nanoTime() - timelineStartedNanos) / 1_000_000).coerceAtLeast(0),
            capturedMs = result?.metrics?.capturedMs,
            text = text.take(240), words = result?.words.orEmpty().takeLast(32))
        mutableState.value = mutableState.value.copy(timeline = (mutableState.value.timeline + entry).takeLast(32))
    }

    private fun listeningMessage(audio: VoiceCaptureStatus): String = when {
        assistantSpeaking.get() && !audio.bargeInAvailable -> audio.bargeInReason
        assistantSpeaking.get() -> "正在聆听，可直接说话打断播报。${audio.bargeInReason}"
        assistantBusy.get() -> "助手执行中，麦克风继续聆听；新完整语句可作为新指令"
        else -> "持续聆听中。${audio.bargeInReason}"
    }

    private fun post(ticket: Long, action: () -> Unit) {
        scope.launch(dependencies.main) { if (generation.get() == ticket) action() }
    }

    private fun updateState(value: DuplexVoiceState) {
        mutableState.value = value
        callbacks.onState(value)
    }

    private suspend fun requireCurrent(run: Run) {
        currentCoroutineContext().ensureActive()
        if (generation.get() != run.ticket || current !== run) throw CancellationException("Stale duplex voice session")
    }

    private class Run(val ticket: Long) {
        val finishRequested = AtomicBoolean(false)
        @Volatile var capture: VoiceCapture? = null
        var job: Job? = null
        var foregroundStarted = false
    }

    private companion object { const val STATE_INTERVAL_MS = 100L }
}

/** Injectable boundary for finite PCM regression tests; production always prepares the bundled model. */
internal class DuplexVoiceDependencies(
    val capture: () -> VoiceCapture,
    val decoder: suspend ((String) -> Unit) -> DuplexVoiceDecoder,
    val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val nanoTime: () -> Long = System::nanoTime,
) {
    companion object {
        fun android(context: Context) = DuplexVoiceDependencies(
            capture = { AndroidVoiceCapture(context) },
            decoder = { progress ->
                val directory = OfflineAsrModel.prepare(context, progress)
                currentCoroutineContext().ensureActive()
                VoskDuplexVoiceDecoder.open(directory.absolutePath)
            },
        )
    }
}

/** All recognizer/model methods, including close, execute on their one IO session owner. */
private class VoskDuplexVoiceDecoder private constructor(
    private val model: Model,
    private val recognizer: Recognizer,
    override val wordTimingsEnabled: Boolean,
    override val partialWordTimingsEnabled: Boolean,
) : DuplexVoiceDecoder {
    override fun accept(frame: ByteArray) = recognizer.acceptWaveForm(frame, frame.size)
    override fun partial(): String = recognizer.partialResult
    override fun result(): String = recognizer.result
    override fun finalResult(): String = recognizer.finalResult
    override fun reset() = recognizer.reset()
    override fun close() { try { recognizer.close() } finally { model.close() } }

    companion object {
        fun open(path: String): DuplexVoiceDecoder {
            val model = Model(path)
            var recognizer: Recognizer? = null
            try {
                val created = Recognizer(model, 16_000f)
                recognizer = created
                val words = try { created.setWords(true); true } catch (_: NoSuchMethodError) { false }
                val partialWords = try { created.setPartialWords(true); true } catch (_: NoSuchMethodError) { false }
                // Native segments are accumulated; the user's silence setting owns turn delivery.
                created.setEndpointerDelays(60f, 0.8f, 60f)
                return VoskDuplexVoiceDecoder(model, created, words, partialWords)
            } catch (error: Throwable) {
                try { recognizer?.close() } finally { model.close() }
                throw error
            }
        }
    }
}
