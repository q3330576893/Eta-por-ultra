package io.github.mangi.eta.agent.voice

import android.content.Context
import android.speech.SpeechRecognizer
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class SpeechInputState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val progress: String = "",
    val error: String? = null,
    val systemIssue: EtaSpeechIssue? = null,
    val configureAvailable: Boolean = false,
    val downloadAvailable: Boolean = false,
    /** Actual engine, so AUTO/SYSTEM fallback is visible to the calling UI. */
    val engineLabel: String = "",
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

internal class SpeechInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val automaticEndpoint: Boolean = false,
    private val beforeCapture: () -> Unit = {},
    private val afterCapture: () -> Unit = {},
    private val refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner(),
    private val onResult: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var finishRequested = false
    private var pendingSystemResult: String? = null
    private var systemCompletion: CompletableDeferred<String>? = null
    private var job: Job? = null
    private var recorder: SpeechRecorder? = null
    private var system: EtaSpeechInput? = null
    private var lease: SpeechAudioLease? = null
    private var capturing = false
    private var autoSubmit = false
    private var refineTranscript = false

    /** Hold-to-talk and chat dictation wait for finish; AUTO never implicitly selects a cloud provider. */
    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null, holdToTalk: Boolean = false) {
        cancel()
        val session = generation
        mutableState.value = SpeechInputState(phase = EtaSpeechPhase.STARTING,
            progress = context.getString(R.string.offline_asr_connecting))
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val config = settings ?: SpeechSettingsRepository.settings()
                if (generation != session) throw CancellationException("Recognition owner replaced")
                if (!config.recognitionEnabled) return@launch
                val secrets = credentials ?: if (config.asr.isLocal) SpeechCredentials()
                    else SpeechSettingsRepository.credentials(context)
                validateSpeechSettings(config, secrets, synthesis = false)
                val endpointSilenceMs = if (automaticEndpoint && !holdToTalk) config.autoSendSilenceMs else 0
                autoSubmit = endpointSilenceMs > 0
                refineTranscript = config.refineTranscript
                lease = SpeechAudioLease(context) { cancel() }.also { it.acquire(playback = false) }
                val text = if (config.asr.isLocal) {
                    val engine = selectLocalAsrEngine(config.asr, SystemSpeechRecognizer.isAvailable(context))
                    if (engine == AsrProvider.SYSTEM) {
                        try {
                            runSystem(session, endpointSilenceMs)
                        } catch (failure: SystemRecognitionFailure) {
                            if (generation != session) throw CancellationException("Recognition owner replaced")
                            if (!shouldFallbackToOffline(failure.issue, finishRequested, state.value.preview)) throw failure
                            system?.cancel(); system = null
                            pendingSystemResult = null
                            runOffline(session, endpointSilenceMs, fallback = true)
                        }
                    } else {
                        runOffline(session, endpointSilenceMs, fallback = config.asr != AsrProvider.VOSK)
                    }
                } else {
                    runCloud(session, config, secrets, endpointSilenceMs)
                }
                if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, context.getString(R.string.offline_asr_no_speech))
                val final = refined(session, text)
                if (generation == session) {
                    cancel()
                    onResult(final)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == session) {
                    val issue = (error as? SystemRecognitionFailure)?.issue
                    val failure = if (issue == null) error.speechFailure() else
                        SpeechFailure(SpeechErrorCode.AUDIO, context.getString(speechIssueMessage(issue)))
                    AndroidAgentLogger.warn("Eta speech recognition failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    cancel()
                    mutableState.value = SpeechInputState(
                        error = failure.userMessage,
                        systemIssue = issue,
                        configureAvailable = failure.code == SpeechErrorCode.CONFIGURATION || failure.code == SpeechErrorCode.CREDENTIALS,
                    )
                }
            } finally {
                if (generation == session) cancel()
            }
        }
    }

    private suspend fun runOffline(session: Long, endpointSilenceMs: Int, fallback: Boolean): String {
        systemCompletion = null
        val label = context.getString(R.string.offline_asr_engine_vosk)
        fun update(transform: (SpeechInputState) -> SpeechInputState) {
            scope.launch(Dispatchers.Main.immediate) {
                if (generation == session) mutableState.value = transform(mutableState.value)
            }
        }
        mutableState.value = mutableState.value.copy(
            phase = EtaSpeechPhase.STARTING, level = 0f, engineLabel = label,
            progress = context.getString(if (fallback) R.string.offline_asr_using_fallback else R.string.offline_asr_preparing_model),
        )
        val modelDirectory = OfflineAsrModel.prepare(context) { progress ->
            update { it.copy(progress = progress, engineLabel = label) }
        }
        val audio = SpeechRecorder()
        recorder = audio
        return coroutineScope {
            var recording: Job? = null
            try {
                recognizeOfflineVosk(context, modelDirectory, audio.frames, endpointSilenceMs,
                    onCaptureReady = {
                        // The decoder is fully loaded before recording starts: a first-use unpack cannot overflow PCM frames.
                        recording = launch {
                            if (generation != session) throw CancellationException("Recognition owner replaced")
                            beginCapture()
                            try {
                                audio.record(
                                    onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, engineLabel = label,
                                        progress = context.getString(if (fallback) R.string.offline_asr_fallback_listening else R.string.offline_asr_listening)) } },
                                    onLevel = { level -> update { it.copy(level = level) } },
                                )
                            } finally {
                                scope.launch(Dispatchers.Main.immediate) { if (generation == session) releaseCapture() }
                            }
                            update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f,
                                progress = context.getString(R.string.offline_asr_transcribing)) }
                        }
                    },
                    onPreview = { preview -> update { it.copy(preview = preview) } },
                    onEndpoint = { audio.finish() },
                )
            } finally {
                audio.cancel()
                recording?.cancel()
            }
        }
    }

    private suspend fun runCloud(session: Long, config: SpeechSettings, secrets: SpeechCredentials, endpointSilenceMs: Int): String {
        beginCapture()
        val audio = SpeechRecorder()
        recorder = audio
        fun update(transform: (SpeechInputState) -> SpeechInputState) {
            scope.launch(Dispatchers.Main.immediate) {
                if (generation == session) mutableState.value = transform(mutableState.value)
            }
        }
        return coroutineScope {
            var recording: Job? = null
            val begin = {
                if (recording == null) {
                    recording = launch {
                        try {
                            audio.record(
                                onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听，点击完成结束", engineLabel = config.asr.name) } },
                                onLevel = { level -> update { it.copy(level = level) } },
                            )
                        } finally {
                            scope.launch(Dispatchers.Main.immediate) { if (generation == session) releaseCapture() }
                        }
                        update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f, progress = "正在识别") }
                    }
                }
                Unit
            }
            try {
                if (config.asr == AsrProvider.QWEN_REALTIME || config.asr == AsrProvider.DOUBAO) {
                    recognizeSpeechStream(config, secrets, audio.frames, begin,
                        onPreview = { preview -> update { it.copy(preview = preview) } },
                        onEndpoint = { audio.finish() }, endpointSilenceMs = endpointSilenceMs)
                } else {
                    begin()
                    val wav = withTimeout(65_000) { collectSpeechAudio(audio.frames) }
                    recognizeSpeechFile(context, config, secrets, wav) { progress ->
                        update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progress) }
                    }
                }
            } finally {
                audio.cancel()
                recording?.cancel()
            }
        }
    }

    private suspend fun runSystem(session: Long, endpointSilenceMs: Int): String {
        val completion = CompletableDeferred<String>()
        systemCompletion = completion
        fun update(transform: (SpeechInputState) -> SpeechInputState) {
            if (generation == session) mutableState.value = transform(mutableState.value)
        }
        update { it.copy(engineLabel = context.getString(R.string.offline_asr_engine_system)) }
        system = EtaSpeechInput(context,
            onListening = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听") } },
            onRecognizing = { update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别") } },
            onLevel = { level -> update { it.copy(level = level) } },
            onPartial = { text -> update { it.copy(preview = text) } },
            onResult = { text ->
                if (generation == session) {
                    if (autoSubmit || finishRequested) completion.complete(text)
                    else {
                        pendingSystemResult = text
                        lease?.close(); lease = null
                        update { it.copy(phase = EtaSpeechPhase.LISTENING, preview = text,
                            progress = "识别完成，点击完成插入", level = 0f) }
                    }
                }
            },
            onError = { issue -> if (generation == session) completion.completeExceptionally(SystemRecognitionFailure(issue)) },
            onDownloadStatus = { status -> update {
                SpeechInputState(progress = context.getString(when (status) {
                    EtaSpeechDownloadStatus.DOWNLOADING -> R.string.voice_model_downloading
                    EtaSpeechDownloadStatus.SCHEDULED -> R.string.voice_model_scheduled
                    EtaSpeechDownloadStatus.READY -> R.string.voice_model_ready
                    EtaSpeechDownloadStatus.FAILED -> R.string.voice_model_download_failed
                }), downloadAvailable = status == EtaSpeechDownloadStatus.FAILED)
            } },
            completeSilenceMs = endpointSilenceMs.takeIf { it > 0 },
        ).also { it.start() }
        try {
            return completion.await()
        } finally {
            if (systemCompletion === completion) systemCompletion = null
        }
    }

    private suspend fun refined(session: Long, text: String): String {
        if (!refineTranscript || generation != session) return text
        mutableState.value = mutableState.value.copy(
            phase = EtaSpeechPhase.RECOGNIZING, preview = text, progress = "正在校对", level = 0f,
        )
        return try { refiner.refine(text) } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            AndroidAgentLogger.warn("Eta speech refine skipped: type=${error.javaClass.simpleName}")
            text
        }
    }

    fun finish() {
        if (!mutableState.value.active || finishRequested) return
        finishRequested = true
        pendingSystemResult?.let { text -> systemCompletion?.complete(text); return }
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recorder?.finish()
        system?.finish()
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别", level = 0f)
    }

    fun downloadModel() { system?.downloadModel() }

    fun cancel() {
        generation++
        finishRequested = false
        autoSubmit = false
        refineTranscript = false
        pendingSystemResult = null
        systemCompletion?.cancel(); systemCompletion = null
        recorder?.cancel(); recorder = null
        releaseCapture()
        system?.cancel(); system = null
        job?.cancel(); job = null
        lease?.close(); lease = null
        mutableState.value = SpeechInputState()
    }

    private fun beginCapture() {
        beforeCapture()
        capturing = true
    }

    private fun releaseCapture() {
        if (capturing) afterCapture()
        capturing = false
    }

    private class SystemRecognitionFailure(val issue: EtaSpeechIssue) : Exception()
}

internal fun selectLocalAsrEngine(requested: AsrProvider, systemAvailable: Boolean): AsrProvider = when (requested) {
    AsrProvider.VOSK -> AsrProvider.VOSK
    AsrProvider.AUTO, AsrProvider.SYSTEM -> if (systemAvailable) AsrProvider.SYSTEM else AsrProvider.VOSK
    else -> requested
}

internal fun shouldFallbackToOffline(issue: EtaSpeechIssue, finishRequested: Boolean, preview: String): Boolean =
    !finishRequested && preview.isBlank() && issue.errorCode != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
        issue.errorCode != SpeechRecognizer.ERROR_NO_MATCH &&
        (issue.kind != EtaSpeechIssueKind.RECOGNITION || issue.errorCode in setOf(
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_AUDIO,
        ))
