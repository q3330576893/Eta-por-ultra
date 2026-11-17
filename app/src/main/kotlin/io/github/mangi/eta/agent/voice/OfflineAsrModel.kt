package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.R
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class OfflineAsrModelStatus(val ready: Boolean, val archiveBytes: Long, val expandedBytes: Long)

/** Prepare the fixed model on demand; a verified private installation survives app upgrades. */
internal object OfflineAsrModel {
    const val ASSET_DIRECTORY = "offline-asr"
    const val MANIFEST_ASSET = "$ASSET_DIRECTORY/manifest.json"
    const val MODEL_ID = "vosk-model-small-cn-0.22"
    const val ARCHIVE_SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
    const val ARCHIVE_BYTES = 43_898_754L
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private val installMutex = Mutex()

    suspend fun status(context: Context): OfflineAsrModelStatus = withContext(Dispatchers.IO) {
        val manifest = manifest(context)
        val target = File(context.filesDir, "$ASSET_DIRECTORY/$MODEL_ID")
        OfflineAsrModelStatus(isReady(target, manifest), manifest.archiveBytes, manifest.expandedBytes)
    }

    suspend fun prepare(context: Context, onProgress: (String) -> Unit = {}): File =
        prepareWithSource(context, onProgress) { file, progress -> OfflineAsrDownload.download(file, progress) }

    internal suspend fun prepareWithSource(
        context: Context,
        onProgress: (String) -> Unit = {},
        download: suspend (File, (Long) -> Unit) -> Unit,
    ): File = installMutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val manifest = manifest(app)
            val root = File(app.filesDir, ASSET_DIRECTORY).apply { mkdirs() }
            val target = File(root, MODEL_ID)
            if (isReady(target, manifest)) return@withContext target
            onProgress(app.getString(R.string.offline_asr_preparing_model))
            val staging = File(root, ".install-${UUID.randomUUID()}")
            val archive = File(staging, "model.zip")
            try {
                check(staging.mkdirs())
                var progressPercent = -1
                download(archive) { bytes ->
                    val percent = (bytes * 100 / manifest.archiveBytes).toInt()
                    if (percent != progressPercent) {
                        progressPercent = percent
                        onProgress(app.getString(R.string.offline_asr_download_progress, percent))
                    }
                }
                onProgress(app.getString(R.string.offline_asr_preparing_model))
                val unpacked = File(staging, "unpacked").apply { mkdirs() }
                val coroutineContext = currentCoroutineContext()
                archive.inputStream().use { input ->
                    OfflineAsrArchive.extract(input, unpacked, manifest.files, MODEL_ID) { coroutineContext.ensureActive() }
                }
                require(isModelComplete(unpacked, manifest)) { "Bundled model layout is incomplete" }
                File(unpacked, ".ready").writeText(manifest.archiveSha256)
                // Target is in app-private storage. A failed/cancelled staging tree is never selected by recognition.
                currentCoroutineContext().ensureActive()
                if (target.exists()) target.deleteRecursively()
                try {
                    Files.move(unpacked.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    // Same-filesystem rename still commits the complete ready tree in one operation.
                    check(unpacked.renameTo(target)) { "Unable to commit offline model" }
                }
                onProgress(app.getString(R.string.offline_asr_model_ready))
                target
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException || error is SpeechFailure) throw error
                if (error is java.net.SocketTimeoutException || error is java.net.UnknownHostException || error is java.net.ConnectException) {
                    throw SpeechFailure(SpeechErrorCode.NETWORK, app.getString(R.string.offline_asr_download_failed), error.javaClass.simpleName)
                }
                throw SpeechFailure(SpeechErrorCode.STORAGE, app.getString(R.string.offline_asr_model_failed), error.javaClass.simpleName)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun manifest(context: Context): OfflineModelManifest {
        val raw = context.assets.open(MANIFEST_ASSET).use { input ->
            val bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1)
            require(bytes.size <= MAX_MANIFEST_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
        val json = JSONObject(raw)
        require(json.getString("id") == MODEL_ID)
        require(json.getString("archive") == "$MODEL_ID.zip")
        require(json.getString("archiveSha256") == ARCHIVE_SHA256 && json.getLong("archiveBytes") == ARCHIVE_BYTES)
        require(json.getString("source") == OfflineAsrDownload.SOURCE)
        val files = json.getJSONArray("files")
        val entries = linkedMapOf<String, OfflineModelFile>()
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val path = file.getString("path")
            require(path !in entries)
            entries[path] = OfflineModelFile(file.getLong("bytes"), file.getString("sha256"))
        }
        return OfflineModelManifest(json.getString("archive"), json.getLong("archiveBytes"),
            json.getString("archiveSha256"), json.getLong("expandedBytes"), entries)
    }

    private fun isReady(target: File, manifest: OfflineModelManifest): Boolean = runCatching {
        target.isDirectory && File(target, ".ready").takeIf { it.isFile && it.length() <= 128 }?.readText() == manifest.archiveSha256 &&
            isModelComplete(target, manifest)
    }.getOrDefault(false)

    private fun isModelComplete(target: File, manifest: OfflineModelManifest): Boolean =
        manifest.files.isNotEmpty() && manifest.files.all { (name, record) ->
            File(target, name).let { it.isFile && it.length() == record.bytes }
        }

    private data class OfflineModelManifest(
        val archive: String,
        val archiveBytes: Long,
        val archiveSha256: String,
        val expandedBytes: Long,
        val files: Map<String, OfflineModelFile>,
    )
}

internal data class OfflineModelFile(val bytes: Long, val sha256: String)

/** Bounded, allow-listed archive extraction; no archive path or link is trusted. */
internal object OfflineAsrArchive {
    const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    const val MAX_FILE_BYTES = 96L * 1024 * 1024
    const val MAX_ENTRIES = 128

    fun extract(
        input: InputStream,
        destination: File,
        expected: Map<String, OfflineModelFile>,
        prefix: String,
        checkCancelled: () -> Unit = {},
    ) {
        require(expected.isNotEmpty() && expected.size <= MAX_ENTRIES)
        val destinationPath = destination.canonicalFile.toPath()
        val seen = mutableSetOf<String>()
        var expanded = 0L
        var entryCount = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                require(++entryCount <= MAX_ENTRIES) { "Too many offline model entries" }
                val name = entry.name
                require(name.isNotEmpty() && name.length <= 256 && '\\' !in name && '\u0000' !in name && ':' !in name)
                require(!name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) { "Invalid archive path" }
                require(name == "$prefix/" || name.startsWith("$prefix/")) { "Unexpected archive root" }
                val relative = name.removePrefix("$prefix/").trimEnd('/')
                if (entry.isDirectory) {
                    require(relative.isEmpty() || expected.keys.any { it.startsWith("$relative/") })
                    zip.closeEntry()
                    continue
                }
                require(relative.isNotEmpty() && seen.add(relative)) { "Duplicate archive file" }
                val record = requireNotNull(expected[relative]) { "Unexpected archive file" }
                require(record.bytes in 0..MAX_FILE_BYTES)
                require(record.sha256.matches(Regex("[0-9a-f]{64}")))
                if (entry.size >= 0) require(entry.size == record.bytes)
                val output = destinationPath.resolve(relative).normalize()
                require(output.startsWith(destinationPath) && output != destinationPath) { "Archive path escaped model directory" }
                Files.createDirectories(output.parent)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                Files.newOutputStream(output).use { file ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        count += read
                        expanded += read
                        require(count <= record.bytes && count <= MAX_FILE_BYTES && expanded <= MAX_EXPANDED_BYTES) {
                            "Expanded offline model exceeds limits"
                        }
                        digest.update(buffer, 0, read)
                        file.write(buffer, 0, read)
                    }
                }
                require(count == record.bytes && digest.digest().hex() == record.sha256) { "Model file integrity check failed" }
                zip.closeEntry()
            }
        }
        require(seen == expected.keys) { "Offline model files are missing" }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
