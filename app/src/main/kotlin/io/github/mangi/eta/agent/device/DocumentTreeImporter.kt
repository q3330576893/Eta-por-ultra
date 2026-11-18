package io.github.mangi.eta.agent.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** 将用户授权的文档树变成有界、完整的工作区快照；途中失败即清理本次导入。 */
internal object DocumentTreeImporter {
    const val MAX_FILES = 256
    const val MAX_BYTES = 128L * 1024 * 1024
    const val MAX_DEPTH = 8
    data class Entry(val id: String, val name: String, val directory: Boolean)
    interface Source {
        fun children(id: String): List<Entry>
        fun open(id: String): InputStream
    }

    fun import(context: Context, tree: Uri): AgentFileReferenceGateway.Resolution {
        val workspace = TerminalPrivateStorage.workspace(context.filesDir)
        val root = File(workspace, "imports/${UUID.randomUUID()}")
        return try {
            require(DocumentsContract.isTreeUri(tree))
            val resolver = context.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val source = object : Source {
                override fun children(id: String): List<Entry> {
                    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE)
                    return resolver.query(uri, columns, null, null, null)?.use { rows ->
                        val result = ArrayList<Entry>()
                        while (rows.moveToNext()) {
                            if (result.size >= MAX_FILES) throw BoundedFileCopy.TooLargeException()
                            result += Entry(rows.getString(0), rows.getString(1).orEmpty(), rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                        }
                        result
                    } ?: throw IOException("Document listing unavailable")
                }
                override fun open(id: String): InputStream = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    ?: throw IOException("Document stream unavailable")
            }
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val name = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "附件文件夹"
            val folder = copy(source, rootId, root, name)
            AgentFileReferenceGateway.Resolution.Success(AgentFileReference(folder.name, folder.absolutePath, AgentFileReferenceKind.Directory))
        } catch (_: SecurityException) {
            AgentFileReferenceGateway.Resolution.Failure(AgentFileReferenceGateway.Error.AccessDenied)
        } catch (_: BoundedFileCopy.TooLargeException) {
            AgentFileReferenceGateway.Resolution.Failure(AgentFileReferenceGateway.Error.ImportTreeTooLarge)
        } catch (_: Exception) {
            AgentFileReferenceGateway.Resolution.Failure(AgentFileReferenceGateway.Error.ImportFailed)
        }
    }

    internal fun copy(source: Source, rootId: String, staging: File, name: String): File {
        require(!staging.exists())
        val directory = File(staging, safeName(name))
        var total = 0L
        var count = 0
        val visited = mutableSetOf(rootId)
        try {
            check(directory.mkdirs())
            fun visit(id: String, destination: File, depth: Int) {
                if (depth > MAX_DEPTH) throw BoundedFileCopy.TooLargeException()
                val names = mutableSetOf<String>()
                for (child in source.children(id)) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    if (++count > MAX_FILES) throw BoundedFileCopy.TooLargeException()
                    require(visited.add(child.id)) { "Repeated document identifier" }
                    val childName = safeName(child.name)
                    require(names.add(childName.lowercase(java.util.Locale.ROOT))) { "Conflicting document names" }
                    val target = File(destination, childName)
                    require(target.canonicalFile.parentFile == destination.canonicalFile)
                    if (child.directory) {
                        check(target.mkdir())
                        visit(child.id, target, depth + 1)
                    } else {
                        source.open(child.id).use { input -> target.outputStream().use { output ->
                            BoundedFileCopy.copy(input, output, MAX_BYTES - total)
                        } }
                        total += target.length()
                    }
                }
            }
            visit(rootId, directory, 0)
            return directory
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun safeName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && name.length <= 200)
        require(name.none { it == '/' || it == '\\' || it.isISOControl() })
        return name
    }
}
