package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechPlaybackProgressTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = mutableListOf<SpeechPlaybackController>()
    private val config = SpeechSettings(tts = TtsProvider.GEMINI)
    private val secrets = SpeechCredentials(geminiTts = "test-key")
    private val text = "甲".repeat(500) + "乙".repeat(500) + "尾句。"

    @After fun close() { controllers.forEach { it.stop() }; scope.cancel() }
    private fun controller(output: SpeechAudioOutput, synthesis: suspend (SpeechSettings, SpeechCredentials, String, (ByteArray) -> Unit) -> Unit =
        { _, _, _, audio -> audio(ByteArray(480)) }) = SpeechPlaybackController(context, scope,
        streamSynthesize = null, synthesize = synthesis, createOutput = { output }).also(controllers::add)

    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Playback did not reach expected state", condition())
    }

    @Test fun prefetchAndBufferedWritesDoNotAdvanceCaptionsOrCompletion() {
        val output = HeadOutput()
        var completed = 0
        val player = controller(output)
        player.speak("answer", text, config, secrets, onCompleted = { completed++ })
        until { output.finished }
        assertEquals(3, player.state.value.synthesizedChunks)
        assertEquals(0, player.state.value.spokenChunks)
        assertEquals(0, player.state.value.completedChunks)
        assertNull(player.state.value.caption)
        assertNull(player.state.value.metrics.firstAudioMs)
        assertEquals(0, completed)

        output.head = 1
        until { player.state.value.spokenChunks == 1 }
        assertEquals("甲".repeat(500), player.state.value.caption)
        assertEquals(0..499, player.state.value.highlight)
        assertEquals(SpeechCaptionTiming.CHUNK, player.state.value.captionTiming)
        assertNotNull(player.state.value.metrics.firstAudioMs)
        assertEquals(0, player.state.value.completedChunks)

        output.head = 240
        until { player.state.value.completedChunks == 1 }
        assertEquals(1, player.state.value.spokenChunks)
        output.head = 241
        until { player.state.value.spokenChunks == 2 }
        assertEquals(500..999, player.state.value.highlight)
        assertEquals("乙".repeat(500), player.state.value.caption)
        output.head = output.bytes / 2
        until { output.closed }
        assertEquals(1, completed)
        assertEquals(3, player.state.value.completedChunks)
    }

    @Test fun resumeUsesHeardBoundaryAndLocatesLaterDuplicate() {
        val outputs = CopyOnWriteArrayList<HeadOutput>()
        val requests = CopyOnWriteArrayList<String>()
        val repeated = "同".repeat(500)
        val player = SpeechPlaybackController(context, scope, streamSynthesize = null,
            synthesize = { _, _, chunk, audio -> requests += chunk; audio(ByteArray(480)) },
            createOutput = { HeadOutput().also(outputs::add) }).also(controllers::add)
        player.speak("repeat", repeated + repeated, config, secrets)
        until { outputs.firstOrNull()?.finished == true }
        outputs[0].head = 240
        until { player.state.value.completedChunks == 1 }
        player.stop()
        requests.clear()
        player.speak("repeat", repeated + repeated, config, secrets, resume = true)
        until { outputs.size == 2 && outputs[1].finished }
        assertEquals(listOf(repeated), requests)
        outputs[1].head = 1
        until { player.state.value.highlight != null }
        assertEquals(500..999, player.state.value.highlight)
    }

    @Test fun stoppingWhileFirstChunkIsOnlyBufferedResumesFromBeginning() {
        val outputs = CopyOnWriteArrayList<HeadOutput>()
        val requests = CopyOnWriteArrayList<String>()
        val player = SpeechPlaybackController(context, scope, streamSynthesize = null,
            synthesize = { _, _, chunk, audio -> requests += chunk; audio(ByteArray(480)) },
            createOutput = { HeadOutput().also(outputs::add) }).also(controllers::add)
        player.speak("answer", "正文。", config, secrets)
        until { outputs.firstOrNull()?.finished == true }
        player.stop()
        requests.clear()
        player.speak("answer", "正文。", config, secrets, resume = true)
        until { outputs.size == 2 && outputs[1].finished }
        assertEquals(listOf("正文。"), requests)
    }

    @Test fun streamWithoutSegmentTimestampsKeepsCaptionUnknown() {
        val output = HeadOutput()
        val finishStream = CompletableDeferred<Unit>()
        val player = SpeechPlaybackController(context, scope,
            streamSynthesize = { _, _, _, audio -> audio(ByteArray(960)); finishStream.await() },
            createOutput = { output }).also(controllers::add)
        player.speak("stream", text, SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "key"))
        until { output.bytes > 0 }
        output.head = 20
        until { player.state.value.metrics.firstAudioMs != null }
        assertEquals(SpeechCaptionTiming.UNKNOWN, player.state.value.captionTiming)
        assertNull(player.state.value.caption)
        assertNull(player.state.value.highlight)
        assertEquals(0, player.state.value.spokenChunks)
        assertEquals(0, player.state.value.completedChunks)
        finishStream.complete(Unit)
        until { output.finished }
        output.head = output.bytes / 2
        until { output.closed }
        assertEquals(3, player.state.value.completedChunks)
    }

    @Test fun stopAndErrorNeverInvokeNaturalCompletionCallback() {
        var completed = 0
        val output = HeadOutput()
        val player = controller(output)
        player.speak("stop", "正文。", config, secrets, onCompleted = { completed++ })
        until { output.finished }
        player.stop()
        assertEquals(0, completed)
        val failing = controller(HeadOutput()) { _, _, _, _ -> throw SpeechFailure(SpeechErrorCode.NETWORK, "中断") }
        failing.speak("error", "正文。", config, secrets, onCompleted = { completed++ })
        until { failing.state.value.error != null }
        assertEquals(0, completed)
    }

    @Test fun systemIgnoresUnreadableCloudSecretsAndUsesRealWordRange() {
        context.getSharedPreferences("eta_speech_secrets", Context.MODE_PRIVATE).edit().putString("credentials", "broken").commit()
        val done = CompletableDeferred<Unit>()
        var speak: (() -> Unit)? = null
        var word: ((Int, Int) -> Unit)? = null
        var completed = 0
        val time = AtomicLong(1_000)
        val player = SpeechPlaybackController(context, scope,
            monotonicMs = time::get,
            systemSpeak = { _, _, start, range -> speak = start; word = range; done.await() },
            stopSystem = {}, resetSystem = {}).also(controllers::add)
        player.speak("system", "第一词第二词", SpeechSettings(tts = TtsProvider.SYSTEM), onCompleted = { completed++ })
        until { speak != null }
        time.set(1_125)
        speak!!.invoke()
        word!!.invoke(3, 6)
        until { player.state.value.caption != null }
        assertEquals("第二词", player.state.value.caption)
        assertEquals(3..5, player.state.value.highlight)
        assertEquals(SpeechCaptionTiming.RANGE, player.state.value.captionTiming)
        assertEquals(125L, player.state.value.metrics.firstAudioMs)
        time.set(1_400)
        done.complete(Unit)
        until { player.state.value.messageId == null }
        assertEquals(1, completed)
        assertEquals(400L, player.state.value.metrics.elapsedMs)
        context.getSharedPreferences("eta_speech_secrets", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun systemRetryPreservesUnconfirmedWordsRegardlessOfElapsedTime() {
        val calls = mutableListOf<String>()
        val time = AtomicLong(0)
        val player = SpeechPlaybackController(context, scope, monotonicMs = time::get,
            systemSpeak = { chunk, _, start, range ->
                calls += chunk
                start()
                if (calls.size == 1) {
                    range(2, 3)
                    time.set(1_000_000)
                    throw SpeechFailure(SpeechErrorCode.AUDIO, "重试")
                }
            }, resetSystem = {}, stopSystem = {}).also(controllers::add)
        player.speak("retry", "甲乙丙丁戊己庚辛", SpeechSettings(tts = TtsProvider.SYSTEM))
        until { player.state.value.messageId == null }
        assertEquals("丙丁戊己庚辛", calls.drop(1).joinToString(""))
        assertNull(player.state.value.error)
    }

    @Test fun screenOffFollowsTtsSettingEvenWhilePreparing() {
        for (continues in listOf(false, true)) {
            val player = controller(HeadOutput()) { _, _, _, _ -> awaitCancellation() }
            player.speak("screen", "正文。", config.copy(speakOnScreenOff = continues), secrets)
            until { player.state.value.totalChunks == 1 }
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(continues, player.state.value.messageId != null)
            player.stop()
        }
    }

    private class HeadOutput : SpeechAudioOutput {
        @Volatile var bytes = 0L
        @Volatile var head = 0L
        @Volatile var finished = false
        @Volatile var closed = false
        override fun write(bytes: ByteArray) { check(!closed); this.bytes += bytes.size }
        override fun playedFrames() = head
        override fun finish() { finished = true }
        override fun drained() = finished && head >= bytes / 2
        override fun close() { closed = true }
    }
}
