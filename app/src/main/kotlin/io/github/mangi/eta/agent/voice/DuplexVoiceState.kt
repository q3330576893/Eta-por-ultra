package io.github.mangi.eta.agent.voice

/** Main callbacks are generation-fenced; paired foreground cleanup completes before a replacement starts. */
data class DuplexVoiceCallbacks(
    val onPartial: (String) -> Unit = {},
    val onFinal: (String) -> Unit = {},
    val onBargeIn: () -> Unit = {},
    val onState: (DuplexVoiceState) -> Unit = {},
    /** The visible owning Service supplies its microphone foreground hook here. */
    val beforeCapture: () -> Unit = {},
    /** Paired exactly once with a successful beforeCapture, including cancellation and failure. */
    val afterCapture: () -> Unit = {},
)

enum class DuplexVoicePhase { IDLE, STARTING, LISTENING, STOPPED, ERROR }

/** Availability alone never counts as working echo cancellation. */
data class VoiceEffectStatus(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val detail: String = "尚未检测",
)

data class VoiceCaptureStatus(
    val aec: VoiceEffectStatus = VoiceEffectStatus(),
    val noiseSuppressor: VoiceEffectStatus = VoiceEffectStatus(),
    val inputDeviceTypes: List<Int> = emptyList(),
    val outputDeviceTypes: List<Int> = emptyList(),
    val routedInputType: Int? = null,
    val routedOutputType: Int? = null,
    /** Connected devices are reported separately from a confirmed headphone route. */
    val headsetConnected: Boolean = false,
    val headsetRoute: Boolean = false,
    val routeDescription: String = "音频路由尚未就绪",
) {
    val bargeInAvailable: Boolean get() = (aec.available && aec.enabled) || headsetRoute
    val bargeInReason: String get() = when {
        aec.available && aec.enabled -> "AEC 已启用；持续语音与识别文字共同确认插话"
        headsetRoute -> "耳机路由已确认；持续语音与识别文字共同确认插话"
        else -> "AEC 未启用，扬声器播放时暂停插话识别；连接并使用耳机后可插话"
    }
}

data class DuplexVoiceMetrics(
    val capturedMs: Long = 0,
    val utteranceMs: Long = 0,
    val speechMs: Long = 0,
    val continuousSpeechMs: Long = 0,
    val silenceMs: Long = 0,
    val rms: Double = 0.0,
    val noiseFloor: Double = 0.0,
    val speechThreshold: Double = 0.0,
    val endpointSilenceMs: Int = 0,
    /** A deterministic suffix/length hint; this is not a neural semantic endpoint model. */
    val semanticHint: String = "",
    val completedTurns: Int = 0,
    val bargeIns: Int = 0,
    val wordTimingsEnabled: Boolean = false,
    val partialWordTimingsEnabled: Boolean = false,
)

data class DuplexVoiceState(
    val phase: DuplexVoicePhase = DuplexVoicePhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val message: String = "",
    val assistantSpeaking: Boolean = false,
    val assistantBusy: Boolean = false,
    val audio: VoiceCaptureStatus = VoiceCaptureStatus(),
    val metrics: DuplexVoiceMetrics = DuplexVoiceMetrics(),
    val words: List<VoiceWordTiming> = emptyList(),
    val timeline: List<VoiceTimelineEntry> = emptyList(),
) {
    val active: Boolean get() = phase == DuplexVoicePhase.STARTING || phase == DuplexVoicePhase.LISTENING
    val bargeInEnabled: Boolean get() = active && audio.bargeInAvailable
}
