package io.github.mangi.eta.agent.planning

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class TaskWorkspaceException(val code: String, message: String) : IllegalStateException(message)

/** App-private UTF-8 documents. Callers provide only fixed filenames or SHA-256 identifiers. */
internal class TaskWorkspaceFiles(root: File) {
    private val appRoot = root.canonicalFile
    private val directory = File(appRoot, "agent_task_workspace")

    fun <T> locked(block: () -> T): T {
        val candidate = Any()
        val processLock = processLocks.putIfAbsent(directory.absolutePath, candidate) ?: candidate
        return synchronized(processLock) {
            ensureDirectory()
            val lockFile = safeFile("workspace.lock")
            FileOutputStream(lockFile, true).channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    fun read(name: String, maxBytes: Int): String? {
        val file = safeFile(name)
        if (!file.exists()) return null
        if (!file.isFile || file.length() > maxBytes) throw TaskWorkspaceException("STORE_CORRUPT", "任务资料文件类型或大小异常")
        val bytes = file.inputStream().use { input -> input.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) throw TaskWorkspaceException("STORE_TOO_LARGE", "任务资料超过读取上限")
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun write(name: String, content: String, maxBytes: Int) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw TaskWorkspaceException("STORE_TOO_LARGE", "任务资料超过存储上限")
        val target = safeFile(name)
        val temporary = Files.createTempFile(directory.toPath(), ".write-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // Same-directory atomic rename: an interrupted write preserves the preceding document.
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath())) throw TaskWorkspaceException("STORE_PATH_INVALID", "任务目录不可为符号链接")
        if (!directory.exists() && !directory.mkdirs()) throw TaskWorkspaceException("STORE_UNAVAILABLE", "任务资料目录创建失败")
        if (!directory.isDirectory || directory.canonicalFile.parentFile != appRoot) {
            throw TaskWorkspaceException("STORE_PATH_INVALID", "任务资料目录路径异常")
        }
    }

    private fun safeFile(name: String): File {
        if (!NAME.matches(name)) throw TaskWorkspaceException("STORE_PATH_INVALID", "任务资料文件名无效")
        val file = File(directory, name)
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory.canonicalFile) {
            throw TaskWorkspaceException("STORE_PATH_INVALID", "任务资料文件路径异常")
        }
        return file
    }

    private companion object {
        val processLocks = ConcurrentHashMap<String, Any>()
        val NAME = Regex("[a-z0-9][a-z0-9._-]{0,100}")
    }
}
