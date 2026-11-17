package io.github.mangi.eta.agent.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 常驻系统引擎，初始化和每次 utterance 均可取消且有超时。 */
internal object SystemTtsSpeaker {
    private val client = SystemTtsClient()

    fun stop() = client.stop()
    fun release() = client.release()

    suspend fun speak(
        context: Context,
        text: String,
        rate: Float = 1f,
        pitch: Float = 1f,
        language: String = "",
        onStart: () -> Unit = {},
        onRange: (Int, Int) -> Unit = { _, _ -> },
    ) = client.speak(context, text, rate, pitch, language, onStart, onRange)
}

/** 窄的引擎接口使回归测试能发送真实的 id/range/迟到回调，避免依赖厂商实现。 */
internal interface SystemTtsEngine {
    val voices: Set<Voice>?
    fun language(locale: Locale): Int
    fun voice(voice: Voice): Int
    fun tone(rate: Float, pitch: Float)
    fun listener(listener: UtteranceProgressListener?)
    fun speak(text: String, id: String): Int
    fun stop()
    fun shutdown()
}

@Suppress("DEPRECATION")
private class AndroidSystemTtsEngine(context: Context, onInit: (Int) -> Unit) : SystemTtsEngine {
    private val engine = TextToSpeech(context.applicationContext) { onInit(it) }
    override val voices get() = engine.voices
    override fun language(locale: Locale) = engine.setLanguage(locale)
    override fun voice(voice: Voice) = engine.setVoice(voice)
    override fun tone(rate: Float, pitch: Float) {
        if (engine.setSpeechRate(rate) == TextToSpeech.ERROR || engine.setPitch(pitch) == TextToSpeech.ERROR) {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "系统语音引擎未接受语速或音调设置")
        }
    }
    override fun listener(listener: UtteranceProgressListener?) { engine.setOnUtteranceProgressListener(listener) }
    override fun speak(text: String, id: String) = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    override fun stop() { engine.stop() }
    override fun shutdown() { engine.shutdown() }
}

internal class SystemTtsClient(
    private val createEngine: (Context, (Int) -> Unit) -> SystemTtsEngine = ::AndroidSystemTtsEngine,
    private val initTimeoutMs: Long = 10_000,
    private val utteranceTimeoutMs: Long = 60_000,
) {
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private var instance: SystemTtsEngine? = null
    private var generation = 0L
    private var cancelPending: (() -> Unit)? = null

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    fun stop() = onMain { stopEngine(release = false) }
    fun release() = onMain { stopEngine(release = true) }

    private fun stopEngine(release: Boolean) {
        generation++
        val tts = instance
        val cancel = cancelPending
        cancelPending = null
        if (release) instance = null
        tts?.listener(null)
        tts?.stop()
        if (release) tts?.shutdown()
        // 取消等待可能同步恢复下一个持有 mutex 的请求，旧引擎操作须全部先完成。
        cancel?.invoke()
    }

    suspend fun speak(
        context: Context,
        text: String,
        rate: Float = 1f,
        pitch: Float = 1f,
        language: String = "",
        onStart: () -> Unit = {},
        onRange: (Int, Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            val tts = engine(context)
            configure(tts, language, rate, pitch)
            val ticket = ++generation
            val utteranceId = "eta-$ticket"
            val started = onStart
            val rangeStarted = onRange
            var succeeded = false
            try {
                withTimeout(utteranceTimeoutMs) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        fun active(id: String?) = id == utteranceId && ticket == generation && continuation.isActive
                        fun fail(id: String?, detail: String) = onMain {
                            if (active(id)) continuation.resumeWithException(
                                SpeechFailure(SpeechErrorCode.AUDIO, "系统语音播报失败", "$detail len=${text.length}"),
                            )
                        }
                        cancelPending = { continuation.cancel(CancellationException("System speech stopped")) }
                        tts.listener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) = onMain { if (active(id)) started() }
                            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) = onMain {
                                if (active(id) && start in 0 until end && end <= text.length) rangeStarted(start, end)
                            }
                            override fun onDone(id: String?) = onMain { if (active(id)) continuation.resume(Unit) }
                            @Deprecated("Android callback")
                            override fun onError(id: String?) = fail(id, "onError")
                            override fun onError(id: String?, errorCode: Int) = fail(id, "onError code=$errorCode")
                            override fun onStop(id: String?, interrupted: Boolean) = fail(id, "onStop interrupted=$interrupted")
                        })
                        continuation.invokeOnCancellation {
                            onMain {
                                if (ticket == generation) {
                                    generation++
                                    cancelPending = null
                                    tts.listener(null)
                                    tts.stop()
                                }
                            }
                        }
                        if (tts.speak(text, utteranceId) == TextToSpeech.ERROR) fail(utteranceId, "speak returned ERROR")
                    }
                }
                succeeded = true
            } catch (_: TimeoutCancellationException) {
                throw SpeechFailure(SpeechErrorCode.TIMEOUT, "系统语音播报超时，请重试")
            } finally {
                if (ticket == generation) {
                    generation++
                    cancelPending = null
                    tts.listener(null)
                    if (!succeeded) tts.stop()
                }
            }
        }
    }

    private fun configure(tts: SystemTtsEngine, language: String, rate: Float, pitch: Float) {
        val locale = if (language.isBlank()) Locale.getDefault() else Locale.forLanguageTag(language.replace('_', '-'))
        if (locale.language.isBlank() || locale.language == "und") {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "系统语音的语言配置无效")
        }
        val result = tts.language(locale)
        if (result < TextToSpeech.LANG_AVAILABLE) {
            val reason = if (result == TextToSpeech.LANG_MISSING_DATA) "系统语音缺少所选语言数据" else "系统语音未支持所选语言"
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, reason, "language=${locale.toLanguageTag()} result=$result")
        }
        // 优先语言和地区均匹配的离线音色，其次同语言离线音色；排序让选择稳定。
        val offline = tts.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }.sortedWith(compareBy<Voice> { if (it.locale == locale) 0 else if (it.locale.country == locale.country) 1 else 2 }
            .thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()
        if (offline != null && tts.voice(offline) == TextToSpeech.ERROR) {
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "系统语音离线音色配置失败")
        }
        tts.tone(rate.coerceIn(0.25f, 3f), pitch.coerceIn(0.25f, 3f))
    }

    private suspend fun engine(context: Context): SystemTtsEngine {
        instance?.let { return it }
        val ticket = ++generation
        var created: SystemTtsEngine? = null
        var accepted = false
        try {
            return withTimeout(initTimeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    cancelPending = { continuation.cancel(CancellationException("System speech initialization stopped")) }
                    // 始终投递到主线程下一轮：同步 onInit 也能看到已赋值的 created。
                    created = createEngine(context) { status -> main.post {
                        val tts = created
                        if (ticket != generation || !continuation.isActive) {
                            if (instance !== tts) tts?.shutdown()
                        } else if (status == TextToSpeech.SUCCESS && tts != null) {
                            instance = tts
                            accepted = true
                            cancelPending = null
                            continuation.resume(tts)
                        } else {
                            continuation.resumeWithException(
                                SpeechFailure(SpeechErrorCode.CONFIGURATION, "手机没有可用的语音引擎"),
                            )
                        }
                    } }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw SpeechFailure(SpeechErrorCode.TIMEOUT, "系统语音引擎初始化超时，请重试")
        } finally {
            if (ticket == generation) { generation++; cancelPending = null }
            if (!accepted) created?.shutdown()
        }
    }
}
