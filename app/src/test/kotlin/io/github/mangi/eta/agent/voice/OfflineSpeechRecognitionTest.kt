package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineSpeechRecognitionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun freeLocalSelectionNeverChoosesCloudAndOldSystemFallsBack() {
        for (provider in listOf(AsrProvider.AUTO, AsrProvider.SYSTEM)) {
            assertEquals(AsrProvider.VOSK, selectLocalAsrEngine(provider, false))
            assertEquals(AsrProvider.SYSTEM, selectLocalAsrEngine(provider, true))
        }
        assertEquals(AsrProvider.VOSK, selectLocalAsrEngine(AsrProvider.VOSK, true))
        for (provider in AsrProvider.displayOrder.filter { it.isLocal }) {
            validateSpeechSettings(SpeechSettings(asr = provider), SpeechCredentials(), false)
        }
    }

    @Test fun systemStartupFailureFallsBackOnceButPermissionAndUserFinishDoNot() {
        val unavailable = EtaSpeechIssue(android.speech.SpeechRecognizer.ERROR_CLIENT, EtaSpeechIssueKind.NO_SERVICE)
        assertTrue(shouldFallbackToOffline(unavailable, false, ""))
        assertTrue(shouldFallbackToOffline(EtaSpeechIssue(android.speech.SpeechRecognizer.ERROR_CLIENT), false, ""))
        assertFalse(shouldFallbackToOffline(unavailable, true, ""))
        assertFalse(shouldFallbackToOffline(unavailable, false, "已有预览"))
        assertFalse(shouldFallbackToOffline(EtaSpeechIssue(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS), false, ""))
        assertFalse(shouldFallbackToOffline(EtaSpeechIssue(android.speech.SpeechRecognizer.ERROR_NO_MATCH), false, ""))
    }

    @Test fun finalizedSegmentsAndReplaceablePartialsProduceOneChineseTranscript() {
        val transcript = OfflineSpeechTranscript()
        assertEquals("你好", transcript.partial("""{"partial":"你 好"}"""))
        assertEquals("你好世界", transcript.partial("""{"partial":"你 好 世界"}"""))
        assertEquals("你好", transcript.appendResult("""{"text":"你 好"}"""))
        assertEquals("你好帮我打开", transcript.partial("""{"partial":"帮 我 打开"}"""))
        assertEquals("你好帮我打开设置", transcript.appendResult("""{"text":"帮 我 打开 设置"}"""))
        assertEquals("你好帮我打开设置", transcript.appendResult("""{"text":""}"""))
    }

    @Test fun englishWordBoundariesArePreserved() {
        val transcript = OfflineSpeechTranscript()
        transcript.appendResult("""{"text":"open settings"}""")
        assertEquals("open settings please", transcript.partial("""{"partial":"please"}"""))
    }

    @Test fun manualFinishConsumesFinalResultOnceAfterFramesClose() = runBlocking {
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        frames.send(pcm(1000, 1600))
        frames.close()
        var finals = 0
        var endpoints = 0
        val text = decodeOfflineFrames(frames, object : OfflineSpeechDecoder {
            override fun accept(frame: ByteArray) = false
            override fun partial() = """{"partial":"打开 设置"}"""
            override fun result() = """{"text":""}"""
            override fun finalResult(): String { finals++; return """{"text":"打开 设置"}""" }
        }, 0, {}, { endpoints++ })
        assertEquals("打开设置", text)
        assertEquals(1, finals)
        assertEquals(0, endpoints)
    }

    @Test fun canceledPcmStreamDoesNotDeliverFinalTranscript() = runBlocking {
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        frames.cancel(CancellationException("user cancelled"))
        var finals = 0
        try {
            decodeOfflineFrames(frames, object : OfflineSpeechDecoder {
                override fun accept(frame: ByteArray) = false
                override fun partial() = "{}"
                override fun result() = "{}"
                override fun finalResult(): String { finals++; return """{"text":"late"}""" }
            }, 0, {}, {})
            fail("Cancellation should leave no result")
        } catch (_: CancellationException) { }
        assertEquals(0, finals)
    }

    @Test fun autoEndpointHonorsSelectedPcmSilenceAndDisabledModeWaits() {
        val endpoint = OfflineSpeechEndpoint(1500)
        assertFalse(endpoint.observe(pcm(1200, 1600), true, false))
        repeat(14) { assertFalse(endpoint.observe(pcm(0, 1600), true, false)) }
        assertTrue(endpoint.observe(pcm(0, 1600), true, false))
        assertFalse(endpoint.observe(pcm(0, 1600), true, false))
        val manual = OfflineSpeechEndpoint(0)
        repeat(50) { assertFalse(manual.observe(pcm(0, 1600), true, true)) }
    }

    @Test fun archiveExtractionRequiresExactAllowListAndPerFileHash() {
        val out = temporary.newFolder("valid")
        val content = "model".toByteArray()
        OfflineAsrArchive.extract(ByteArrayInputStream(zip("test-model/am/final.mdl" to content)), out,
            mapOf("am/final.mdl" to record(content)), "test-model")
        assertArrayEquals(content, File(out, "am/final.mdl").readBytes())
    }

    @Test fun archiveRejectsTraversalUnexpectedFileAndHashMismatch() {
        val content = "model".toByteArray()
        for (path in listOf("test-model/../../escaped", "test-model/../escaped", "test-model/./x", "/test-model/x", "test-model\\x", "other/x", "test-model/extra")) {
            assertThrows(IllegalArgumentException::class.java) {
                OfflineAsrArchive.extract(ByteArrayInputStream(zip(path to content)), temporary.newFolder(),
                    mapOf("am/final.mdl" to record(content)), "test-model")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineAsrArchive.extract(ByteArrayInputStream(zip("test-model/am/final.mdl" to content)), temporary.newFolder(),
                mapOf("am/final.mdl" to OfflineModelFile(content.size.toLong(), "0".repeat(64))), "test-model")
        }
    }

    @Test fun archiveRejectsOversizeDeclaredFileAndMissingFile() {
        val content = "model".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            OfflineAsrArchive.extract(ByteArrayInputStream(zip("test-model/am/final.mdl" to content)), temporary.newFolder(),
                mapOf("am/final.mdl" to OfflineModelFile(OfflineAsrArchive.MAX_FILE_BYTES + 1, "0".repeat(64))), "test-model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineAsrArchive.extract(ByteArrayInputStream(zip()), temporary.newFolder(),
                mapOf("am/final.mdl" to record(content)), "test-model")
        }
    }

    @Test fun archiveCancellationStopsBeforeReadyPublication() {
        val content = "model".toByteArray()
        val out = temporary.newFolder("cancelled")
        assertThrows(CancellationException::class.java) {
            OfflineAsrArchive.extract(ByteArrayInputStream(zip("test-model/am/final.mdl" to content)), out,
                mapOf("am/final.mdl" to record(content)), "test-model") { throw CancellationException("cancel") }
        }
        assertFalse(File(out, ".ready").exists())
    }

    private fun record(bytes: ByteArray) = OfflineModelFile(bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { out -> files.forEach { (name, bytes) ->
            out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
        } }
    }.toByteArray()

    private fun pcm(amplitude: Int, samples: Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        for (index in 0 until samples) { bytes[index * 2] = amplitude.toByte(); bytes[index * 2 + 1] = (amplitude shr 8).toByte() }
    }
}
