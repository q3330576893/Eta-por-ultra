package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request

/** Stream the fixed public model into a private staging file; never buffer the archive in memory. */
internal object OfflineAsrDownload {
    const val SOURCE = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    private val client by lazy {
        AgentHttpClient.client.newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(10, TimeUnit.MINUTES)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    suspend fun download(
        destination: File,
        onProgress: (Long) -> Unit,
        http: OkHttpClient = client,
        source: String = SOURCE,
        expectedBytes: Long = OfflineAsrModel.ARCHIVE_BYTES,
        expectedSha256: String = OfflineAsrModel.ARCHIVE_SHA256,
    ) = coroutineScope {
        val call = http.newCall(Request.Builder().url(source).header("Accept-Encoding", "identity").build())
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            // The structured child owns both streams. Cancellation waits for their close before
            // the caller removes the staging directory, including during a blocked network read.
            launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (response.code != 200) throw SpeechFailure(SpeechErrorCode.NETWORK,
                            "离线语音模型下载失败（HTTP ${response.code}），请检查网络后重试")
                        val length = response.body.contentLength()
                        if (length >= 0 && length != expectedBytes) throw SpeechFailure(
                            SpeechErrorCode.PROTOCOL, "离线语音模型下载长度异常，请重试")
                        val owner = currentCoroutineContext()
                        copyVerified(response.body.byteStream(), destination, expectedBytes, expectedSha256,
                            onProgress = onProgress, checkCancelled = {
                                owner.ensureActive()
                                if (!continuation.isActive) throw CancellationException("Model download cancelled")
                            })
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    internal fun copyVerified(
        input: InputStream,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ) {
        require(expectedBytes in 1..64L * 1024 * 1024)
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            input.use { source ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= expectedBytes) { "Offline model exceeds pinned size" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(count)
                    }
                    checkCancelled()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(count == expectedBytes && hash == expectedSha256) { "Offline model integrity mismatch" }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }
}
