package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.util.Locale
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemTtsSpeakerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = mutableListOf<SystemTtsClient>()
    @After fun close() { clients.forEach { it.release() }; scope.cancel() }

    private fun client(engine: FakeEngine, init: Boolean = true, initTimeout: Long = 1_000, speechTimeout: Long = 1_000) =
        SystemTtsClient(createEngine = { _, callback -> if (init) callback(TextToSpeech.SUCCESS); engine },
            initTimeoutMs = initTimeout, utteranceTimeoutMs = speechTimeout).also(clients::add)

    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
            Thread.sleep(1)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("System speech did not reach expected state", condition())
    }

    @Test fun picksMatchingOfflineVoiceAndForwardsOnlyCurrentUtteranceRanges() {
        val offline = voice("offline", Locale.SIMPLIFIED_CHINESE, network = false)
        val cloud = voice("cloud", Locale.SIMPLIFIED_CHINESE, network = true)
        val other = voice("english", Locale.US, network = false)
        val engine = FakeEngine(setOf(cloud, other, offline))
        val client = client(engine)
        val ranges = mutableListOf<Pair<Int, Int>>()
        var completed = false
        val task = scope.launch { client.speak(context, "第一词第二词", language = "zh-CN", onRange = { a, b -> ranges += a to b }); completed = true }
        until { engine.id != null }
        assertEquals(Locale.SIMPLIFIED_CHINESE, engine.locale)
        assertEquals(offline, engine.selected)
        engine.callback!!.onRangeStart("old-id", 0, 3, 0)
        engine.callback!!.onDone("old-id")
        assertTrue(ranges.isEmpty())
        assertFalse(completed)
        engine.callback!!.onRangeStart(engine.id, 3, 6, 1)
        assertEquals(listOf(3 to 6), ranges)
        engine.callback!!.onDone(engine.id)
        until { task.isCompleted }
        assertTrue(completed)
    }

    @Test fun canceledUtteranceAndLateCallbacksCannotFinishOrHighlightItsReplacement() {
        val engine = FakeEngine()
        val client = client(engine)
        var oldCompleted = false
        var currentCompleted = false
        val currentRanges = mutableListOf<Pair<Int, Int>>()
        val first = scope.launch { client.speak(context, "旧句子"); oldCompleted = true }
        until { engine.id != null }
        val oldCallback = engine.callback!!
        val oldId = engine.id!!
        first.cancel()
        until { first.isCompleted }
        val second = scope.launch {
            client.speak(context, "新句子", onRange = { a, b -> currentRanges += a to b })
            currentCompleted = true
        }
        until { engine.id != oldId }
        oldCallback.onRangeStart(oldId, 0, 1, 0)
        oldCallback.onDone(oldId)
        oldCallback.onError(oldId, TextToSpeech.ERROR)
        engine.callback!!.onDone(oldId)
        assertFalse(oldCompleted)
        assertFalse(currentCompleted)
        assertTrue(currentRanges.isEmpty())
        engine.callback!!.onRangeStart(engine.id, 0, 1, 0)
        assertEquals(listOf(0 to 1), currentRanges)
        engine.callback!!.onDone(engine.id)
        until { second.isCompleted }
        assertTrue(currentCompleted)
    }

    @Test fun initializationTimeoutReleasesEngineAndIgnoresLateSuccess() {
        val engines = mutableListOf<FakeEngine>()
        val callbacks = mutableListOf<(Int) -> Unit>()
        val client = SystemTtsClient(createEngine = { _, callback ->
            callbacks += callback
            FakeEngine().also(engines::add)
        }, initTimeoutMs = 30, utteranceTimeoutMs = 1_000).also(clients::add)
        var failure: SpeechFailure? = null
        val first = scope.launch { try { client.speak(context, "旧句子") } catch (error: SpeechFailure) { failure = error } }
        until { first.isCompleted }
        assertEquals(SpeechErrorCode.TIMEOUT, failure?.code)
        assertTrue(engines[0].shutdowns > 0)
        val second = scope.launch { client.speak(context, "新句子") }
        until { engines.size == 2 }
        callbacks[0](TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(engines[0].id)
        assertFalse(second.isCompleted)
        callbacks[1](TextToSpeech.SUCCESS)
        until { engines[1].id != null }
        engines[1].callback!!.onDone(engines[1].id)
        until { second.isCompleted }
    }

    @Test fun utteranceTimeoutStopsAudioAndLateDoneDoesNotReportSuccess() {
        val engine = FakeEngine()
        val client = client(engine, speechTimeout = 30)
        var failure: SpeechFailure? = null
        var completed = false
        val task = scope.launch {
            try { client.speak(context, "正文"); completed = true } catch (error: SpeechFailure) { failure = error }
        }
        until { engine.id != null }
        val callback = engine.callback!!
        val id = engine.id
        until { task.isCompleted }
        callback.onDone(id)
        assertFalse(completed)
        assertEquals(SpeechErrorCode.TIMEOUT, failure?.code)
        assertTrue(engine.stops > 0)
    }

    @Test fun cancellationDuringInitializationShutsDownAndNextRequestCanStart() {
        val engines = mutableListOf<FakeEngine>()
        val callbacks = mutableListOf<(Int) -> Unit>()
        val client = SystemTtsClient(createEngine = { _, callback ->
            callbacks += callback; FakeEngine().also(engines::add)
        }).also(clients::add)
        val first = scope.launch { client.speak(context, "旧句子") }
        until { engines.size == 1 }
        first.cancel()
        until { first.isCompleted }
        assertTrue(engines[0].shutdowns > 0)
        val second = scope.launch { client.speak(context, "新句子") }
        until { engines.size == 2 }
        callbacks[0](TextToSpeech.SUCCESS)
        callbacks[1](TextToSpeech.SUCCESS)
        until { engines[1].id != null }
        assertNull(engines[0].id)
        engines[1].callback!!.onDone(engines[1].id)
        until { second.isCompleted }
    }

    @Test fun unsupportedLanguageAndRejectedOfflineVoiceFailBeforeSpeaking() {
        for (missingData in listOf(false, true)) {
            val engine = FakeEngine().apply {
                languageResult = if (missingData) TextToSpeech.LANG_MISSING_DATA else TextToSpeech.LANG_NOT_SUPPORTED
            }
            val client = client(engine)
            var failure: SpeechFailure? = null
            val task = scope.launch { try { client.speak(context, "正文", language = "zh-CN") } catch (error: SpeechFailure) { failure = error } }
            until { task.isCompleted }
            assertEquals(SpeechErrorCode.CONFIGURATION, failure?.code)
            assertTrue(failure!!.userMessage.contains(if (missingData) "缺少" else "所选语言"))
            assertNull(engine.id)
        }
        val engine = FakeEngine(setOf(voice("offline", Locale.US, false))).apply { voiceResult = TextToSpeech.ERROR }
        val client = client(engine)
        var failure: SpeechFailure? = null
        val task = scope.launch { try { client.speak(context, "text", language = "en-US") } catch (error: SpeechFailure) { failure = error } }
        until { task.isCompleted }
        assertEquals(SpeechErrorCode.CONFIGURATION, failure?.code)
        assertNull(engine.id)
    }

    private fun voice(name: String, locale: Locale, network: Boolean) =
        Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network, emptySet())

    private class FakeEngine(override val voices: Set<Voice> = emptySet()) : SystemTtsEngine {
        var locale: Locale? = null
        var selected: Voice? = null
        var languageResult = TextToSpeech.LANG_COUNTRY_AVAILABLE
        var voiceResult = TextToSpeech.SUCCESS
        var callback: UtteranceProgressListener? = null
        var id: String? = null
        var stops = 0
        var shutdowns = 0
        override fun language(locale: Locale): Int { this.locale = locale; return languageResult }
        override fun voice(voice: Voice): Int { selected = voice; return voiceResult }
        override fun tone(rate: Float, pitch: Float) = Unit
        override fun listener(listener: UtteranceProgressListener?) { callback = listener }
        override fun speak(text: String, id: String): Int { this.id = id; return TextToSpeech.SUCCESS }
        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }
    }
}
