package io.github.mangi.eta.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A single capture owner. Frames are little-endian 16 kHz mono PCM16. A fake may return null at EOF.
 * [cancel] must unblock [read]; [close] releases every native capture/effect resource exactly once.
 */
interface VoiceCapture : Closeable {
    val status: VoiceCaptureStatus
    suspend fun start()
    suspend fun read(): ByteArray?
    fun cancel()
}

/** VOICE_COMMUNICATION provides the platform echo-reference path; actual effect state is read back. */
internal class AndroidVoiceCapture(context: Context) : VoiceCapture {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var audio: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null
    @Volatile private var effects = VoiceCaptureStatus()

    override val status: VoiceCaptureStatus
        get() = audio?.let { current -> routeStatus(current, effects) } ?: effects

    @SuppressLint("MissingPermission")
    override suspend fun start() = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(!cancelled.get() && !closed.get()) { "Voice capture already closed" }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备未支持 16 kHz 单声道 PCM16 录音" }
        val created = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, FRAME_BYTES * 8))
        synchronized(lock) {
            if (cancelled.get() || closed.get()) {
                created.release()
                throw kotlinx.coroutines.CancellationException("Voice capture stopped during startup")
            }
            audio = created
        }
        try {
            check(created.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            val aecStatus = configureAec(created.audioSessionId)
            val nsStatus = configureNoiseSuppressor(created.audioSessionId)
            effects = VoiceCaptureStatus(aec = aecStatus, noiseSuppressor = nsStatus)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (cancelled.get()) throw kotlinx.coroutines.CancellationException("Voice capture stopped")
                created.startRecording()
            }
            check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风录音启动失败" }
            effects = routeStatus(created, effects)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) return@withContext null
        val current = checkNotNull(audio) { "Voice capture has not started" }
        val frame = ByteArray(FRAME_BYTES)
        // A short cancellable poll also releases capture when the owning scope is cancelled without
        // an explicit stop(). A blocked AudioRecord read would otherwise delay its own finally.
        var count: Int
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) return@withContext null
            count = current.read(frame, 0, frame.size, AudioRecord.READ_NON_BLOCKING)
            if (count != 0) break
            delay(10)
        }
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) return@withContext null
        check(count > 0 && count % 2 == 0) { "麦克风 PCM 读取失败: $count" }
        if (count == frame.size) frame else frame.copyOf(count)
    }

    override fun cancel() {
        cancelled.set(true)
        synchronized(lock) { runCatching { audio?.stop() } }
    }

    override fun close() {
        cancel()
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            runCatching { suppressor?.release() }
            runCatching { aec?.release() }
            suppressor = null
            aec = null
            runCatching { audio?.release() }
            audio = null
        }
    }

    private fun configureAec(session: Int): VoiceEffectStatus {
        val available = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        if (!available) return VoiceEffectStatus(detail = "设备未提供 AcousticEchoCanceler")
        return try {
            val effect = AcousticEchoCanceler.create(session)
                ?: return VoiceEffectStatus(available = true, detail = "AEC 创建失败")
            aec = effect
            val result = effect.setEnabled(true)
            val enabled = effect.enabled
            VoiceEffectStatus(true, enabled, if (enabled) "AEC available=true, enabled=true"
                else "AEC 启用失败: result=$result, enabled=false")
        } catch (error: RuntimeException) {
            VoiceEffectStatus(true, false, "AEC 启用失败: ${error.javaClass.simpleName}")
        }
    }

    private fun configureNoiseSuppressor(session: Int): VoiceEffectStatus {
        val available = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)
        if (!available) return VoiceEffectStatus(detail = "设备未提供 NoiseSuppressor")
        return try {
            val effect = NoiseSuppressor.create(session)
                ?: return VoiceEffectStatus(available = true, detail = "降噪效果创建失败")
            suppressor = effect
            val result = effect.setEnabled(true)
            val enabled = effect.enabled
            VoiceEffectStatus(true, enabled, if (enabled) "NS available=true, enabled=true"
                else "降噪启用失败: result=$result, enabled=false")
        } catch (error: RuntimeException) {
            VoiceEffectStatus(true, false, "降噪启用失败: ${error.javaClass.simpleName}")
        }
    }

    private fun routeStatus(current: AudioRecord, base: VoiceCaptureStatus): VoiceCaptureStatus {
        val inputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }.distinct() }
            .getOrDefault(emptyList())
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.distinct() }
            .getOrDefault(emptyList())
        val input = runCatching { current.routedDevice?.type }.getOrNull()
        val communication = runCatching { manager.communicationDevice?.type }.getOrNull()
        val selectedOutput = communication ?: runCatching { manager.availableCommunicationDevices }
            .getOrDefault(emptyList()).singleOrNull { it.type in HEADSET_OUTPUT_TYPES }?.type
                ?.takeIf { input in HEADSET_INPUT_TYPES }
        val headsetConnected = inputs.any { it in HEADSET_INPUT_TYPES } || outputs.any { it in HEADSET_OUTPUT_TYPES }
        // Merely having a Bluetooth/wired device connected does not prove that playback avoids the speaker.
        val headsetRoute = selectedOutput in HEADSET_OUTPUT_TYPES
        val actualAec = aec?.let { effect -> runCatching { effect.enabled }.getOrDefault(false) } ?: false
        val actualNs = suppressor?.let { effect -> runCatching { effect.enabled }.getOrDefault(false) } ?: false
        return base.copy(
            aec = base.aec.copy(enabled = actualAec,
                detail = if (base.aec.enabled && !actualAec) "AEC 已失效，扬声器插话已暂停" else base.aec.detail),
            noiseSuppressor = base.noiseSuppressor.copy(enabled = actualNs),
            inputDeviceTypes = inputs,
            outputDeviceTypes = outputs,
            routedInputType = input,
            routedOutputType = selectedOutput,
            headsetConnected = headsetConnected,
            headsetRoute = headsetRoute,
            routeDescription = "input=${input ?: "unknown"}, output=${selectedOutput ?: "unknown"}, headset=$headsetRoute",
        )
    }

    internal companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 640 // 20 ms, so 250 ms interruption evidence is measured with PCM samples.
        private val HEADSET_INPUT_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        private val HEADSET_OUTPUT_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
