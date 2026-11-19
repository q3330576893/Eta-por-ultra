package io.github.mangi.eta.agent.knowledge

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/** Real KB dispatch is intentionally separate from FileToolDispatcher's filesystem verbs. */
internal class KnowledgeToolExecutor(
    private val store: KnowledgeLibraryStore,
    private val conversationId: String,
    private val fileTool: (String, JSONObject) -> String,
    private val enabled: () -> Boolean,
    private val environmentKey: (String) -> String = { it },
    private val uriReader: ((String) -> ByteArray)? = null,
) {
    fun execute(name: String, args: JSONObject): String = try {
        requireEnabled()
        when (name) {
            "index_file_to_kb" -> {
                if (args.has("content")) throw KnowledgeException("KB_VERIFIED_READ_REQUIRED", "索引需由已授权文件后端读取原文；粘贴文本请使用资料库管理页导入")
                val collection = store.selectedCollection(conversationId, args.optString("collection_id").takeIf { it.isNotBlank() })
                val document = readFile(collection, args)
                // Recheck permissions and selection immediately before persisting a long paged read.
                requireEnabled()
                store.selectedCollection(conversationId, collection)
                validate(document)
                val saved = store.upsert(document)
                saved.metadata().put("ok", true).put("tool", name).put("search_mode", "keyword")
                    .put("persistent", true).put("message", "已原子保存关键词索引；相同路径、环境和身份的旧版本已替换").toString()
            }
            "search_kb" -> {
                val result = store.search(conversationId, args.getString("query"), args.optInt("limit", 10), ::validate)
                requireEnabled()
                JSONObject().put("ok", true).put("tool", name).put("search_mode", "keyword")
                    .put("conversation_id", conversationId).put("hits", JSONArray(result.hits.map { it.toJson() }))
                    .put("invalidated_document_ids", JSONArray(result.invalidated)).put("count", result.hits.size)
                    .put("note", "仅检索用户附加到本会话的资料库和固定来源；关键词检索，无 embedding").toString()
            }
            else -> throw KnowledgeException("UNKNOWN_TOOL", "未知资料库工具")
        }
    } catch (failure: KnowledgeException) {
        error(failure.code, failure.message ?: "资料库操作失败")
    } catch (_: org.json.JSONException) {
        error("INVALID_ARGUMENT", "资料库参数缺失或类型错误")
    } catch (_: Exception) {
        error("KB_IO_ERROR", "资料库读取或保存失败")
    }

    fun readFile(collectionId: String, args: JSONObject): KnowledgeDocument {
        requireEnabled()
        val path = args.getString("path")
        if (path.isBlank() || path.length > 4096 || path.any { it.isISOControl() } || "://" in path) {
            throw KnowledgeException("INVALID_PATH", "请输入已授权的文件系统路径；文档 URI 请通过系统文件选择器导入")
        }
        val request = JSONObject()
        listOf("path", "environment", "identity", "cwd", "expected_revision").forEach { if (args.has(it)) request.put(it, args.get(it)) }
        request.put("max_bytes", 16000).put("max_lines", 2000)
        val content = StringBuilder()
        var offset = 0L
        var canonicalPath = ""
        var revision = ""
        var identity = ""
        var environment = ""
        var size = -1L
        var complete = false
        repeat(512) {
            if (complete) return@repeat
            requireEnabled()
            val response = checkedFile("read_file", request)
            if (size < 0) {
                canonicalPath = response.getString("path")
                revision = response.getString("revision")
                environment = response.getString("environment")
                identity = response.getString("identity")
                size = response.getLong("size_bytes")
                if (size > KnowledgeLibraryStore.MAX_DOCUMENT_BYTES) throw KnowledgeException("FILE_TOO_LARGE", "单份 UTF-8 资料最多 512 KiB")
                request.put("path", canonicalPath).put("expected_revision", revision).put("identity", identity)
            } else if (response.getString("revision") != revision || response.getString("path") != canonicalPath ||
                response.getString("identity") != identity || response.getString("environment") != environment) {
                throw KnowledgeException("FILE_CHANGED", "读取期间来源、环境、身份或版本已变化")
            }
            val text = response.getString("content")
            val bytes = text.toByteArray(Charsets.UTF_8).size
            val next = response.getLong("next_offset_bytes")
            if (next != offset + bytes || next > size || (next == offset && next < size)) {
                throw KnowledgeException("KB_INCOMPLETE_READ", "文件后端返回的续读位置或内容不完整")
            }
            content.append(text)
            if (content.length > KnowledgeLibraryStore.MAX_DOCUMENT_BYTES) throw KnowledgeException("FILE_TOO_LARGE", "资料超过大小限制")
            offset = next
            complete = !response.optBoolean("truncated") && offset == size
            request.put("offset_bytes", offset)
        }
        if (!complete) throw KnowledgeException("KB_INCOMPLETE_READ", "文件未完整读取，索引保持原状态")
        val text = content.toString()
        KnowledgeLibraryStore.validateText(text)
        return KnowledgeDocument("", collectionId, java.io.File(canonicalPath).name, canonicalPath, environment,
            identity, revision, text, environmentKey = environmentKey(environment))
    }

    fun importUri(collectionId: String, uri: String, filename: String): KnowledgeDocument {
        requireEnabled()
        val bytes = uriReader?.invoke(uri) ?: throw KnowledgeException("KB_URI_PERMISSION", "文档读取许可已失效")
        val text = decodeUtf8(bytes)
        return store.upsert(KnowledgeDocument("", collectionId, filename.take(255), uri, "android", "user",
            KnowledgeLibraryStore.digest(bytes), text, "saf", uri))
    }

    fun validate(document: KnowledgeDocument) {
        requireEnabled()
        when (document.origin) {
            "file" -> {
                if (document.environmentKey != environmentKey(document.environment)) {
                    throw KnowledgeException("KB_ENVIRONMENT_CHANGED", "当前文件环境与索引环境不同，请重新导入")
                }
                checkedFile("read_file", JSONObject().put("path", document.path).put("environment", document.environment)
                    .put("identity", document.identity).put("expected_revision", document.revision).put("max_bytes", 4).put("max_lines", 1))
            }
            "saf" -> {
                val bytes = uriReader?.invoke(document.sourceUri) ?: throw KnowledgeException("KB_URI_PERMISSION", "文档读取许可已失效")
                if (KnowledgeLibraryStore.digest(bytes) != document.revision) throw KnowledgeException("FILE_CHANGED", "原文已变化，请重新导入")
            }
            "import" -> Unit // Explicit user-authored App-private copy, deleted through library management.
            else -> throw KnowledgeException("KB_INVALID_SOURCE", "资料来源类型无效")
        }
    }

    private fun checkedFile(name: String, args: JSONObject): JSONObject {
        val response = JSONObject(fileTool(name, args))
        if (!response.optBoolean("ok")) throw KnowledgeException(response.optString("code", "FILE_IO_ERROR"), response.optString("message", "原文读取失败"))
        return response
    }
    private fun requireEnabled() {
        if (!enabled()) {
            if (conversationId.isNotBlank()) {
                val snapshot = store.snapshot(conversationId)
                snapshot.documents.filter { it.collectionId in snapshot.attachedCollectionIds || it.id in snapshot.pinnedDocumentIds }
                    .forEach(store::revoke)
            }
            throw KnowledgeException("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件与附件读取许可")
        }
    }
    companion object {
        val names = setOf("index_file_to_kb", "search_kb")
        fun error(code: String, message: String): String = JSONObject().put("ok", false).put("code", code).put("message", message).toString()
        fun decodeUtf8(bytes: ByteArray): String {
            if (bytes.size > KnowledgeLibraryStore.MAX_DOCUMENT_BYTES) throw KnowledgeException("FILE_TOO_LARGE", "单份资料最多 512 KiB")
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { throw KnowledgeException("NOT_UTF8_TEXT", "请选择 UTF-8 文本文件") }
            KnowledgeLibraryStore.validateText(text)
            return text
        }
        fun uriReader(context: Context): (String) -> ByteArray = { value ->
            context.contentResolver.openInputStream(Uri.parse(value))?.use { it.readBytesBounded(KnowledgeLibraryStore.MAX_DOCUMENT_BYTES) }
                ?: throw KnowledgeException("KB_URI_PERMISSION", "系统文档读取失败")
        }
    }
}
