package io.github.mangi.eta.agent.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.Closeable
import kotlinx.coroutines.CancellationException

/** 只协调本进程的录音和播放；所有入口共用一个租约，资源仍由入口生命周期持有。 */
/** 录音租约总在熄屏时结束；播放租约由 [interruptOnScreenOff] 控制。 */
internal class SpeechAudioLease(
    private val context: Context,
    private val interruptOnScreenOff: Boolean = true,
    private val interrupt: () -> Unit,
) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change -> if (change < 0 && current === this) interrupt() }, Handler(Looper.getMainLooper()))
        .build()
    private var registered = false
    private var focused = false
    private var stopOnScreenOff = true
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val interrupts = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopOnScreenOff
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> true
                else -> false
            }
            if (interrupts && current === this@SpeechAudioLease) interrupt()
        }
    }

    /** [recording] 默认为录音租约；准备中的 TTS 显式传 false，仍可推迟申请播放焦点。 */
    fun acquire(playback: Boolean, recording: Boolean = !playback, beforeFocus: () -> Unit = {}) {
        current?.interrupt?.invoke()
        current = this
        stopOnScreenOff = recording || interruptOnScreenOff
        try {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (stopOnScreenOff) addAction(Intent.ACTION_SCREEN_OFF)
            }, Context.RECEIVER_NOT_EXPORTED)
            registered = true
            // 熄屏后网络合成与播放仍要跑：短时持一把 partial wakelock，超时自释避免漏电。
            if (wakeLock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eta:speech").apply {
                    setReferenceCounted(false)
                    runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
                }
            }
            if (playback) requestPlaybackFocus(beforeFocus)
        } catch (error: RuntimeException) {
            close()
            throw error
        }
    }

    fun requestPlaybackFocus(beforeFocus: () -> Unit = {}) {
        if (current !== this) throw CancellationException("Speech owner replaced")
        beforeFocus()
        focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focused) throw SpeechFailure(SpeechErrorCode.AUDIO, "当前无法获得音频焦点，请稍后重试")
    }

    override fun close() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        if (registered) context.unregisterReceiver(receiver)
        registered = false
        if (focused) manager.abandonAudioFocusRequest(focus)
        focused = false
        if (current === this) current = null
    }

    private companion object {
        var current: SpeechAudioLease? = null
        const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
