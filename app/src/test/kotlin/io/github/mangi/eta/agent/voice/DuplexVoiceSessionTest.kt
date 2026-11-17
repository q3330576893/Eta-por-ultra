package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.SpeechSettings
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

/** Finite PCM/fake-decoder regressions cover coordination, not native Vosk accuracy or device AEC. */
class DuplexVoiceSessionTest {
    @Test fun rmsVadLearnsQuietNoiseAndCountsOnlyUnbrokenVoicedSamples() {
        val detector = NativeVoiceActivityDetector()
        repeat(100) { assertFalse(detector.observe(pcm(180)).voiced) }
        val noise = detector.observe(pcm(180))
        assertTrue(noise.noiseFloor > 150.0)
        assertTrue(noise.threshold > 450.0)
        repeat(12) { assertTrue(detector.observe(pcm(-3_000)).voiced) }
        val speech = detector.observe(pcm(-3_000))
        assertEquals(260L, speech.continuousSpeechMs)
        assertEquals(3_000.0, speech.rms, 0.01)
        val quiet = detector.observe(pcm(0))
        assertFalse(quiet.voiced)
        assertEquals(0L, quiet.continuousSpeechMs)
        assertTrue(quiet.speechActive) // hangover is explicitly excluded from interruption evidence.
        assertEquals(20L, detector.observe(pcm(3_000)).continuousSpeechMs)
        assertThrows(IllegalArgumentException::class.java) { detector.observe(byteArrayOf(1)) }
    }

    @Test fun semanticHintExtendsShortContinuationSuffixesAndHonorsManualMode() {
        for (suffix in listOf("然后", "帮我", "如果", "还有", "的")) {
            val hint = SemanticVoiceEndpoint.hint(1_500, "打开设置$suffix，")
            assertEquals(2_500, hint.silenceMs)
            assertTrue(hint.explanation.contains(suffix))
        }
        assertEquals(1_000, SemanticVoiceEndpoint.hint(200, "打开设置").silenceMs)
        assertEquals(0, SemanticVoiceEndpoint.hint(0, "帮我").silenceMs)
        assertEquals(1_500, SemanticVoiceEndpoint.hint(1_500, "很长的说明".repeat(8) + "然后").silenceMs)
    }

    @Test fun bargeInRequiresSpeechPartialAssistantSpeakingAndActualAecOrHeadset() {
        val gate = DuplexBargeInGate()
        val aec = protectedAudio()
        val failedAec = aec.copy(aec = VoiceEffectStatus(available = true, enabled = false))
        assertFalse(gate.observe(false, aec, 300, "停止"))
        assertFalse(gate.observe(true, aec, 249, "停止"))
        assertFalse(gate.observe(true, aec, 300, ""))
        assertFalse(gate.observe(true, failedAec, 300, "扬声器回声"))
        assertFalse(gate.observe(true, failedAec.copy(headsetConnected = true), 300, "停止"))
        assertTrue(gate.observe(true, aec, 250, "停止"))
        assertFalse(gate.observe(true, aec, 900, "停止"))
        gate.reset()
        assertTrue(gate.observe(true, failedAec.copy(headsetRoute = true), 300, "换个任务"))
    }

    @Test fun processorRejectsEnergyOnlyBargeInAndResetsGateForEachFullTurn() {
        val decoder = FakeDecoder("停止")
        val processor = DuplexVoiceProcessor(decoder, 1_000)
        decoder.text = ""
        repeat(30) { assertFalse(processor.frame(pcm(3_000), true, protectedAudio()).bargeIn) }
        decoder.text = "停止"
        assertTrue(processor.frame(pcm(3_000), true, protectedAudio()).bargeIn)
        repeat(5) { assertFalse(processor.frame(pcm(3_000), true, protectedAudio()).bargeIn) }
        var final: String? = null
        repeat(50) { processor.frame(pcm(0), true, protectedAudio()).final?.let { final = it } }
        assertEquals("停止", final)
        var interruptions = 0
        repeat(13) { if (processor.frame(pcm(3_000), true, protectedAudio()).bargeIn) interruptions++ }
        assertEquals(1, interruptions)
    }

    @Test fun failedAecQuarantinesSpeakerEchoAndRestartsSpeechEvidenceAfterRecovery() {
        val decoder = FakeDecoder("播放回声")
        val processor = DuplexVoiceProcessor(decoder, 1_000)
        val speaker = VoiceCaptureStatus()
        repeat(40) {
            val result = processor.frame(pcm(3_000), true, speaker)
            assertFalse(result.bargeIn)
            assertNull(result.final)
            assertEquals("", result.partial)
            assertEquals(0L, result.metrics.continuousSpeechMs)
        }
        assertEquals(0, decoder.accepted)
        decoder.text = "耳机用户新句"
        val headset = speaker.copy(headsetConnected = true, headsetRoute = true)
        repeat(12) { assertFalse(processor.frame(pcm(3_000), true, headset).bargeIn) }
        assertTrue(processor.frame(pcm(3_000), true, headset).bargeIn)
    }

    @Test fun pcmEndpointHonorsSelectedSilenceAndContinuationHint() {
        val decoder = FakeDecoder("打开设置然后")
        val processor = DuplexVoiceProcessor(decoder, 1_000)
        repeat(13) { processor.frame(pcm(3_000), false, protectedAudio()) }
        repeat(99) { assertNull(processor.frame(pcm(0), false, protectedAudio()).final) }
        val ended = processor.frame(pcm(0), false, protectedAudio())
        assertEquals("打开设置然后", ended.final)
        assertEquals(2_000L, ended.metrics.silenceMs)
        assertEquals(2_000, ended.metrics.endpointSilenceMs)
        assertTrue(ended.metrics.semanticHint.contains("然后"))
        assertEquals(1, ended.metrics.completedTurns)
        assertTrue(ended.metrics.wordTimingsEnabled)
        assertTrue(ended.metrics.partialWordTimingsEnabled)
    }

    @Test fun sixtySecondTurnCapContinuesAndIdleLimitSurvivesTurnResets() {
        val processor = DuplexVoiceProcessor(FakeDecoder("长句"), 0)
        repeat(2_999) { assertNull(processor.frame(pcm(3_000), false, protectedAudio()).final) }
        val ended = processor.frame(pcm(3_000), false, protectedAudio())
        assertEquals("长句", ended.final)
        assertEquals(60_000L, ended.metrics.utteranceMs)
        assertTrue(ended.metrics.semanticHint.contains("60"))
        val quiet = pcm(0)
        repeat(5_999) { assertFalse(processor.frame(quiet, false, protectedAudio()).idleExpired) }
        assertTrue(processor.frame(quiet, false, protectedAudio()).idleExpired)
    }

    @Test fun busyAndSpeakingKeepOneCaptureAndDecoderWhileNewFullSentencesArrive() {
        val harness = Harness()
        try {
            harness.session.start(SpeechSettings(autoSendSilenceMs = 1_000))
            harness.pump()
            assertTrue(harness.session.state.value.active)
            harness.sentence()
            harness.pump()
            assertEquals(listOf("第一句"), harness.finals)
            assertTrue(harness.session.state.value.active)
            assertEquals(0, harness.capture.closeCount)
            harness.session.setAssistantBusy(true)
            harness.session.setAssistantSpeaking(true)
            harness.decoder.text = "第二句"
            harness.sentence()
            harness.pump()
            assertEquals(listOf("第一句", "第二句"), harness.finals)
            assertEquals(1, harness.bargeIns)
            assertTrue(harness.states.any { it.assistantBusy && it.assistantSpeaking })
            assertEquals(1, harness.decoderCreates)
            assertEquals(1, harness.capture.startCount)
            harness.capture.frames.close()
            harness.pump()
            assertEquals(listOf("before", "after"), harness.hooks)
            assertEquals(1, harness.capture.closeCount)
            assertEquals(1, harness.decoder.closeCount)
            assertEquals(DuplexVoicePhase.STOPPED, harness.session.state.value.phase)
        } finally { harness.close() }
    }

    @Test fun manualFinishOnlyEndsCurrentTurnAndDisabledRecognitionAcquiresNothing() {
        val harness = Harness()
        try {
            harness.session.start(SpeechSettings(recognitionEnabled = false))
            harness.pump()
            assertEquals(0, harness.decoderCreates)
            assertTrue(harness.hooks.isEmpty())
            harness.session.start(SpeechSettings(autoSendSilenceMs = 0))
            harness.pump()
            harness.sentence()
            harness.pump()
            assertTrue(harness.finals.isEmpty())
            harness.session.finishTurn()
            harness.capture.send(pcm(0))
            harness.pump()
            assertEquals(listOf("第一句"), harness.finals)
            assertTrue(harness.session.state.value.active)
            assertEquals(0, harness.capture.closeCount)
        } finally { harness.close() }
    }

    @Test fun replacementFencesQueuedMainPartialAndOldFinalThenPairsForegroundExactlyOnce() {
        val harness = Harness()
        val secondCapture = FakeCapture()
        val secondDecoder = FakeDecoder("新句")
        try {
            harness.session.start(SpeechSettings())
            harness.pump()
            harness.capture.send(pcm(3_000))
            // Suspend old IO at delivery to Main, then replace it before that callback executes.
            assertTrue(harness.io.runNext())
            harness.captures.add(secondCapture)
            harness.decoders.add(secondDecoder)
            harness.session.start(SpeechSettings())
            harness.pump()
            assertTrue(harness.partials.isEmpty())
            assertTrue(harness.finals.isEmpty())
            assertEquals(listOf("before", "after", "before"), harness.hooks)
            assertEquals(1, harness.capture.closeCount)
            assertEquals(1, harness.decoder.closeCount)
            secondCapture.send(pcm(3_000))
            secondCapture.frames.close()
            harness.pump()
            assertEquals(listOf("新句"), harness.finals)
            assertEquals(listOf("before", "after", "before", "after"), harness.hooks)
            harness.session.stop()
            harness.session.stop()
            harness.pump()
            assertEquals(1, secondCapture.closeCount)
            assertEquals(1, secondDecoder.closeCount)
        } finally { harness.close() }
    }

    @Test fun cancellingOwnerScopeReleasesPendingReadNativeOwnerAndForegroundWithoutFinal() {
        val harness = Harness()
        harness.session.start(SpeechSettings())
        harness.pump()
        harness.capture.send(pcm(3_000))
        harness.pump()
        assertEquals(listOf("第一句"), harness.partials)
        harness.scope.cancel()
        harness.pump()
        assertTrue(harness.finals.isEmpty())
        assertEquals(1, harness.capture.closeCount)
        assertEquals(1, harness.decoder.closeCount)
        assertEquals(listOf("before", "after"), harness.hooks)
        assertFalse(harness.session.state.value.active)
    }

    @Test fun captureStartupFailurePairsCleanupAndReportsError() {
        val harness = Harness()
        try {
            harness.capture.startError = SecurityException("fixture permission")
            harness.session.start(SpeechSettings())
            harness.pump()
            assertEquals(DuplexVoicePhase.ERROR, harness.session.state.value.phase)
            assertEquals(listOf("before", "after"), harness.hooks)
            assertEquals(1, harness.capture.closeCount)
            assertEquals(1, harness.decoder.closeCount)
            assertTrue(harness.finals.isEmpty())
        } finally { harness.close() }
    }

    @Test fun timelineKeepsObservedTurnsAndPlaybackTransitionsBounded() {
        val harness = Harness()
        try {
            harness.session.start(SpeechSettings(autoSendSilenceMs = 1_000))
            harness.pump()
            repeat(20) {
                harness.session.setAssistantSpeaking(true)
                harness.pump()
                harness.session.setAssistantSpeaking(false)
                harness.pump()
            }
            val timeline = harness.session.state.value.timeline
            assertEquals(32, timeline.size)
            assertEquals(VoiceTimelineKind.ASSISTANT_ENDED, timeline.last().kind)
            assertTrue(timeline.all { it.capturedMs == null && it.elapsedMs >= 0 })
            harness.sentence()
            harness.pump()
            val last = harness.session.state.value.timeline.last()
            assertEquals(VoiceTimelineKind.USER_FINAL, last.kind)
            assertEquals("第一句", last.text)
            assertNotNull(last.capturedMs)
            assertTrue(last.words.isEmpty())
            assertEquals(32, harness.session.state.value.timeline.size)
        } finally { harness.close() }
    }

    private class Harness {
        val main = QueuedDispatcher()
        val io = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val capture = FakeCapture()
        val decoder = FakeDecoder("第一句")
        val captures = ArrayDeque<VoiceCapture>().apply { add(capture) }
        val decoders = ArrayDeque<DuplexVoiceDecoder>().apply { add(decoder) }
        val finals = mutableListOf<String>()
        val partials = mutableListOf<String>()
        val states = mutableListOf<DuplexVoiceState>()
        val hooks = mutableListOf<String>()
        var bargeIns = 0
        var decoderCreates = 0
        val session = DuplexVoiceSession(scope, DuplexVoiceCallbacks(
            onPartial = { assertTrue(main.running); partials += it },
            onFinal = { assertTrue(main.running); finals += it },
            onBargeIn = { assertTrue(main.running); bargeIns++ },
            onState = { assertTrue(main.running); states += it },
            beforeCapture = { assertTrue(main.running); hooks += "before" },
            afterCapture = { assertTrue(main.running); hooks += "after" },
        ), DuplexVoiceDependencies(
            capture = { captures.removeFirst() },
            decoder = { decoderCreates++; decoders.removeFirst() },
            main = main, io = io, nanoTime = { 0L },
        ))

        fun sentence() {
            repeat(13) { capture.send(pcm(3_000)) }
            repeat(50) { capture.send(pcm(0)) }
        }

        fun pump() {
            repeat(100_000) {
                val mainWorked = main.runNext()
                val ioWorked = io.runNext()
                if (!mainWorked && !ioWorked) return
            }
            fail("Fixture dispatcher failed to become idle")
        }

        fun close() { session.stop(); scope.cancel(); pump() }
    }

    /** Explicit queues exercise Main/IO ordering without wall-clock waits or coroutines-test. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        var running = false
            private set
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runNext(): Boolean {
            val block = work.pollFirst() ?: return false
            running = true
            try { block.run() } finally { running = false }
            return true
        }
    }

    private class FakeDecoder(var text: String) : DuplexVoiceDecoder {
        var accepted = 0
        var closeCount = 0
        private var hasSpeech = false
        override val wordTimingsEnabled = true
        override val partialWordTimingsEnabled = true
        override fun accept(frame: ByteArray): Boolean {
            accepted++
            if (frame.any { it.toInt() != 0 }) hasSpeech = true
            return false
        }
        override fun partial() = "{\"partial\":\"${if (hasSpeech) text else ""}\"}"
        override fun result() = "{\"text\":\"${if (hasSpeech) text else ""}\"}"
        override fun finalResult() = result()
        override fun reset() { hasSpeech = false }
        override fun close() { closeCount++ }
    }

    private class FakeCapture : VoiceCapture {
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        var closeCount = 0
        var startCount = 0
        var startError: Exception? = null
        override var status = protectedAudio()
        override suspend fun start() { startCount++; startError?.let { throw it } }
        override suspend fun read(): ByteArray? = frames.receiveCatching().getOrNull()
        override fun cancel() { frames.cancel(CancellationException("Fixture capture cancelled")) }
        override fun close() { closeCount++; cancel() }
        fun send(frame: ByteArray) { check(frames.trySend(frame).isSuccess) }
    }

    private companion object {
        fun protectedAudio() = VoiceCaptureStatus(aec = VoiceEffectStatus(available = true, enabled = true))
        fun pcm(amplitude: Int, samples: Int = 320) = ByteArray(samples * 2).also { bytes ->
            for (index in 0 until samples) {
                bytes[index * 2] = amplitude.toByte()
                bytes[index * 2 + 1] = (amplitude shr 8).toByte()
            }
        }
    }
}
