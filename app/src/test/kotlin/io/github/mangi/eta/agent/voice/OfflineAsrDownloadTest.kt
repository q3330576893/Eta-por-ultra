package io.github.mangi.eta.agent.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineAsrDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun validStreamIsCheckedWithoutKeepingASecondMemoryCopy() {
        val bytes = ByteArray(150_000) { (it % 127).toByte() }
        val target = File(temporary.root, "model.zip")
        val progress = mutableListOf<Long>()
        OfflineAsrDownload.copyVerified(ByteArrayInputStream(bytes), target, bytes.size.toLong(), hash(bytes),
            onProgress = { progress += it })
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { it.second > it.first })
    }

    @Test fun wrongHashTruncatedAndOversizeBodiesAreRemoved() {
        val bytes = "model".toByteArray()
        for ((size, digest) in listOf(bytes.size.toLong() to "0".repeat(64), 8L to hash(bytes), 3L to hash(bytes))) {
            val target = File(temporary.newFolder(), "model.zip")
            assertThrows(IllegalArgumentException::class.java) {
                OfflineAsrDownload.copyVerified(ByteArrayInputStream(bytes), target, size, digest)
            }
            assertFalse(target.exists())
        }
    }

    @Test fun cancellationClosesInputAndDeletesThePartialArchive() {
        var closed = false
        val source = object : ByteArrayInputStream(ByteArray(150_000)) {
            override fun close() { closed = true; super.close() }
        }
        val target = File(temporary.root, "cancelled.zip")
        var chunks = 0
        assertThrows(CancellationException::class.java) {
            OfflineAsrDownload.copyVerified(source, target, 150_000, "0".repeat(64),
                onProgress = { chunks++ }, checkCancelled = { if (chunks > 0) throw CancellationException("stop") })
        }
        assertTrue(closed)
        assertFalse(target.exists())
    }

    @Test fun httpUsesNoCredentialsAndRequiresExactContentLengthAndHash() = runBlocking {
        val bytes = "verified model".toByteArray()
        var headersChecked = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            assertEquals("identity", chain.request().header("Accept-Encoding"))
            headersChecked = true
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build()
        val target = File(temporary.root, "verified.zip")
        OfflineAsrDownload.download(target, {}, client, "https://example.test/model.zip", bytes.size.toLong(), hash(bytes))
        assertTrue(headersChecked)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun httpErrorOrUnexpectedLengthNeverPublishesAnArchive() = runBlocking {
        for (code in listOf(302, 404, 200)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body("bad".toResponseBody()).build()
            }.build()
            val target = File(temporary.newFolder(), "failed.zip")
            try {
                OfflineAsrDownload.download(target, {}, client, "https://example.test/model.zip", 30, "0".repeat(64))
                fail("Expected download failure")
            } catch (_: SpeechFailure) { }
            assertFalse(target.exists())
        }
    }

    @Test fun cancellingABlockedNetworkReadClosesTheCallBeforeStagingCleanup() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = File(temporary.root, "network-cancel.zip")
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 200000\r\nConnection: close\r\n\r\nx".toByteArray())
                        flush()
                    }
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            try {
                val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
                val task = launch(Dispatchers.Default) {
                    OfflineAsrDownload.download(target, {}, client, "http://127.0.0.1:${server.localPort}/model.zip", 200_000, "0".repeat(64))
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                withTimeout(2_000) { task.cancelAndJoin() }
                assertFalse(target.exists())
            } finally {
                release.countDown()
                worker.join(1_000)
            }
        }
    }
}
