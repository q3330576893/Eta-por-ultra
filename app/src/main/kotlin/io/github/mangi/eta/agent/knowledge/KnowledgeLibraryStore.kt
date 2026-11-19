package io.github.mangi.eta.agent.knowledge

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class KnowledgeException(val code: String, message: String) : IOException(message)

internal data class KnowledgeCollection(val id: String, val name: String)

/** A snapshot is retained only while its source remains readable with this exact authority. */
internal data class KnowledgeDocument(
    val id: String,
    val collectionId: String,
    val filename: String,
    val path: String,
    val environment: String,
    val identity: String,
    val revision: String,
    val content: String,
    val origin: String = "file",
    val sourceUri: String = "",
    val environmentKey: String = environment,
) {
    val bytes: Int get() = content.toByteArray(Charsets.UTF_8).size
    fun metadata(): JSONObject = JSONObject().put("document_id", id).put("collection_id", collectionId)
        .put("filename", filename).put("path", path).put("environment", environment)
        .put("identity", identity).put("revision", revision).put("bytes", bytes)
        .put("origin", origin).put("environment_key", environmentKey)
}

internal data class KnowledgeHit(
    val document: KnowledgeDocument,
    val startLine: Int,
    val endLine: Int,
    val excerpt: String,
    val score: Int,
    val matchedTokens: List<String>,
) {
    fun toJson(): JSONObject {
        val citation = requireNotNull(KnowledgeCitationLinks.reference(document.collectionId, document.id, document.revision, startLine, endLine))
        return document.metadata().put("start_line", startLine).put("end_line", endLine)
        .put("citation_url", KnowledgeCitationLinks.build(citation)).put("citation", KnowledgeCitationLinks.markdown(document.filename, citation))
        .put("excerpt", excerpt).put("score", score).put("matched_tokens", JSONArray(matchedTokens))
        .put("source", JSONObject().put("document_id", document.id).put("collection_id", document.collectionId)
            .put("revision", document.revision).put("start_line", startLine).put("end_line", endLine))
    }
}

internal data class KnowledgeLibrarySnapshot(
    val collections: List<KnowledgeCollection>,
    val documents: List<KnowledgeDocument>,
    val attachedCollectionIds: Set<String>,
    val pinnedDocumentIds: Set<String>,
)

internal data class KnowledgeSearchResult(val hits: List<KnowledgeHit>, val invalidated: List<String>)

/**
 * Bounded, versioned App-private manifest. Each mutation reloads under one process-wide lock,
 * then AtomicFile commits content, indexes and conversation selections together. Derived chunks
 * are rebuilt from the retained text, so replacing a source also removes every former chunk.
 */
internal class KnowledgeLibraryStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "knowledge-library/manifest-v1.json"))

    private val lock = locks.getOrPut(file.absolutePath) { Any() }
    private data class State(
        val collections: MutableList<KnowledgeCollection> = mutableListOf(),
        val documents: MutableList<KnowledgeDocument> = mutableListOf(),
        val attached: MutableMap<String, MutableSet<String>> = linkedMapOf(),
        val pins: MutableMap<String, MutableSet<String>> = linkedMapOf(),
    )

    fun snapshot(conversationId: String = ""): KnowledgeLibrarySnapshot = synchronized(lock) {
        val state = load()
        KnowledgeLibrarySnapshot(state.collections.toList(), state.documents.toList(),
            state.attached[conversationId]?.toSet().orEmpty(), state.pins[conversationId]?.toSet().orEmpty())
    }

    fun createCollection(name: String): KnowledgeCollection = synchronized(lock) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 100 || clean.any { it.isISOControl() }) {
            throw KnowledgeException("KB_INVALID_NAME", "资料库名称需为 1–100 个可见字符")
        }
        val state = load()
        if (state.collections.size >= MAX_COLLECTIONS) throw KnowledgeException("KB_LIMIT", "资料库数量达到上限")
        val collection = KnowledgeCollection(UUID.randomUUID().toString(), clean)
        state.collections += collection
        save(state)
        collection
    }

    fun attach(conversationId: String, collectionId: String, selected: Boolean) = synchronized(lock) {
        requireConversation(conversationId)
        val state = load()
        requireCollection(state, collectionId)
        if (selected) {
            if (conversationId !in state.attached && state.attached.size >= MAX_CONVERSATIONS) {
                throw KnowledgeException("KB_LIMIT", "资料库会话绑定数量达到上限")
            }
            state.attached.getOrPut(conversationId) { linkedSetOf() }.add(collectionId)
        } else {
            state.attached[conversationId]?.remove(collectionId)
            state.pins[conversationId]?.removeAll(state.documents.filter { it.collectionId == collectionId }.map { it.id }.toSet())
            if (state.attached[conversationId]?.isEmpty() == true) state.attached.remove(conversationId)
        }
        save(state)
    }

    fun pin(conversationId: String, documentId: String, selected: Boolean) = synchronized(lock) {
        requireConversation(conversationId)
        val state = load()
        if (state.documents.none { it.id == documentId }) throw KnowledgeException("KB_SOURCE_MISSING", "索引已删除")
        if (selected) {
            if (conversationId !in state.pins && state.pins.size >= MAX_CONVERSATIONS) {
                throw KnowledgeException("KB_LIMIT", "资料库会话绑定数量达到上限")
            }
            state.pins.getOrPut(conversationId) { linkedSetOf() }.add(documentId)
        } else state.pins[conversationId]?.remove(documentId)
        save(state)
    }

    fun selectedCollection(conversationId: String, requested: String?): String = synchronized(lock) {
        requireConversation(conversationId)
        val state = load()
        val selected = state.attached[conversationId].orEmpty()
        if (!requested.isNullOrBlank() && requested in selected) return@synchronized requested
        if (requested.isNullOrBlank() && selected.size == 1) return@synchronized selected.first()
        throw KnowledgeException("KB_SELECTION_REQUIRED", "请在资料库管理页创建或选择资料库，并附加到当前会话；多个已选库需填写 collection_id")
    }

    fun upsert(document: KnowledgeDocument): KnowledgeDocument = synchronized(lock) {
        validateText(document.content)
        if (document.filename.length > 255 || document.path.length > 4096 || document.revision.length > 256 ||
            document.environmentKey.length > 4096 || document.sourceUri.length > 4096 ||
            document.path.any { it.isISOControl() } || document.filename.any { it.isISOControl() }) {
            throw KnowledgeException("KB_INVALID_SOURCE", "资料路径或版本无效")
        }
        val state = load()
        requireCollection(state, document.collectionId)
        val id = sourceId(document.collectionId, document.environmentKey, document.identity, document.path)
        val replacement = document.copy(id = id)
        val next = state.documents.filterNot { it.id == id } + replacement
        validateBounds(next)
        state.documents.clear()
        state.documents.addAll(next)
        save(state)
        replacement
    }

    fun deleteCollection(collectionId: String) = synchronized(lock) {
        val state = load()
        val ids = state.documents.filter { it.collectionId == collectionId }.map { it.id }.toSet()
        state.collections.removeAll { it.id == collectionId }
        state.documents.removeAll { it.id in ids }
        state.attached.values.forEach { it.remove(collectionId) }
        state.pins.values.forEach { it.removeAll(ids) }
        save(state)
    }

    fun deleteDocument(documentId: String) = synchronized(lock) {
        val state = load()
        removeDocuments(state, setOf(documentId))
        save(state)
    }

    /** Revocation affects all references to this authority, including other attached collections. */
    fun revoke(document: KnowledgeDocument) = synchronized(lock) {
        val state = load()
        removeDocuments(state, state.documents.filter {
            it.path == document.path && it.environmentKey == document.environmentKey && it.identity == document.identity
        }.map { it.id }.toSet())
        save(state)
    }

    fun source(collectionId: String, documentId: String, revision: String, validate: (KnowledgeDocument) -> Unit): KnowledgeDocument = synchronized(lock) {
        val document = load().documents.firstOrNull { it.collectionId == collectionId && it.id == documentId }
            ?: throw KnowledgeException("KB_SOURCE_MISSING", "索引已删除")
        if (document.revision != revision) throw KnowledgeException("KB_SOURCE_CHANGED", "索引已更新，请重新搜索后打开来源")
        try { validate(document) } catch (failure: Exception) { revoke(document); throw failure }
        document
    }

    fun search(conversationId: String, query: String, limit: Int = 10, validate: (KnowledgeDocument) -> Unit): KnowledgeSearchResult = synchronized(lock) {
        requireConversation(conversationId)
        if (query.isBlank() || query.length > 500) throw KnowledgeException("KB_INVALID_QUERY", "关键词需为 1–500 个字符")
        val state = load()
        val selected = state.documents.filter {
            it.collectionId in state.attached[conversationId].orEmpty() || it.id in state.pins[conversationId].orEmpty()
        }
        if (state.attached[conversationId].isNullOrEmpty() && state.pins[conversationId].isNullOrEmpty()) {
            throw KnowledgeException("KB_SELECTION_REQUIRED", "请先在资料库管理页选择资料并附加到当前会话")
        }
        val invalid = linkedSetOf<String>()
        val valid = selected.filter { document ->
            try { validate(document); true } catch (_: Exception) {
                state.documents.filter { it.path == document.path && it.environmentKey == document.environmentKey && it.identity == document.identity }
                    .forEach { invalid += it.id }
                false
            }
        }
        if (invalid.isNotEmpty()) { removeDocuments(state, invalid); save(state) }
        if (selected.isNotEmpty() && valid.isEmpty()) throw KnowledgeException("KB_SOURCES_UNAVAILABLE", "所选原文已变化、删除或读取许可失效，旧索引已移除；请重新导入")
        val rawTokens = KnowledgeKeywords.tokenize(query)
        val hasHanPhrases = rawTokens.any { token -> token.length > 1 && token.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
        val tokens = if (hasHanPhrases) rawTokens.filterNot { token -> token.length == 1 && Character.UnicodeScript.of(token[0].code) == Character.UnicodeScript.HAN }.toSet() else rawTokens
        val hits = valid.asSequence().flatMap { document ->
            val nameTokens = KnowledgeKeywords.tokenize(document.filename)
            chunks(document.content).asSequence().mapNotNull { chunk ->
                val bodyTokens = KnowledgeKeywords.tokenize(chunk.text)
                val matched = tokens.filter { it in nameTokens || it in bodyTokens }
                val score = tokens.sumOf { (if (it in nameTokens) 3 else 0) + (if (it in bodyTokens) 1 else 0) }
                if (score == 0) null else KnowledgeHit(document, chunk.startLine, chunk.endLine, chunk.text, score, matched)
            }
        }.sortedWith(compareByDescending<KnowledgeHit> { it.score }.thenBy { it.document.id }.thenBy { it.startLine })
            .take(limit.coerceIn(1, 20)).toList()
        KnowledgeSearchResult(hits, invalid.toList())
    }

    private fun requireConversation(id: String) {
        if (id.isBlank() || id.length > 512 || id.any { it.isISOControl() }) {
            throw KnowledgeException("KB_CONVERSATION_REQUIRED", "请从当前会话打开资料库并选择资料")
        }
    }
    private fun requireCollection(state: State, id: String) {
        if (state.collections.none { it.id == id }) throw KnowledgeException("KB_COLLECTION_MISSING", "资料库已删除，请重新选择")
    }
    private fun removeDocuments(state: State, ids: Set<String>) {
        state.documents.removeAll { it.id in ids }
        state.pins.values.forEach { it.removeAll(ids) }
    }

    private fun load(): State {
        val atomic = AtomicFile(file)
        val text = try {
            atomic.openRead().use { input ->
                val bytes = input.readBytesBounded(MAX_MANIFEST_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
        } catch (_: java.io.FileNotFoundException) { return State() }
        try {
            val json = JSONObject(text)
            if (json.getInt("version") != 1) throw KnowledgeException("KB_STORE_VERSION", "资料库格式版本不受支持")
            val state = State()
            json.getJSONArray("collections").objects().forEach { state.collections += KnowledgeCollection(it.getString("id"), it.getString("name")) }
            json.getJSONArray("documents").objects().forEach { item ->
                state.documents += KnowledgeDocument(item.getString("document_id"), item.getString("collection_id"),
                    item.getString("filename"), item.getString("path"), item.getString("environment"), item.getString("identity"),
                    item.getString("revision"), item.getString("content"), item.getString("origin"), item.optString("source_uri"),
                    item.optString("environment_key", item.getString("environment")))
            }
            fun readSelections(key: String, target: MutableMap<String, MutableSet<String>>) {
                val selections = json.getJSONObject(key)
                selections.keys().forEach { id ->
                    val array = selections.getJSONArray(id)
                    target[id] = (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
                }
            }
            readSelections("attached", state.attached)
            readSelections("pins", state.pins)
            validateBounds(state.documents)
            if (state.collections.size > MAX_COLLECTIONS || state.attached.size > MAX_CONVERSATIONS || state.pins.size > MAX_CONVERSATIONS) {
                throw KnowledgeException("KB_LIMIT", "资料库 manifest 超过限制")
            }
            return state
        } catch (failure: KnowledgeException) { throw failure }
        catch (_: Exception) { throw KnowledgeException("KB_STORE_CORRUPT", "资料库 manifest 损坏，读取已停止") }
    }

    private fun save(state: State) {
        val json = JSONObject().put("version", 1)
            .put("collections", JSONArray(state.collections.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("documents", JSONArray(state.documents.map { it.metadata().put("content", it.content).put("source_uri", it.sourceUri) }))
        fun selections(values: Map<String, Set<String>>) = JSONObject().also { target -> values.forEach { (id, selected) -> target.put(id, JSONArray(selected.toList())) } }
        json.put("attached", selections(state.attached)).put("pins", selections(state.pins))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MANIFEST_BYTES) throw KnowledgeException("KB_LIMIT", "资料库 manifest 超过字节限制")
        if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw KnowledgeException("KB_STORE_IO", "资料库目录创建失败")
        val atomic = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
            // Some platform/host implementations log a failed rename instead of throwing.
            // Preserve atomic replacement and verify bytes before reporting success.
            val pending = File(file.path + ".new")
            if (pending.exists()) {
                val retained = pending.inputStream().use { it.readBytesBounded(MAX_MANIFEST_BYTES) }
                if (!retained.contentEquals(bytes)) throw IOException("Atomic pending content differs")
                java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            val committed = file.inputStream().use { it.readBytesBounded(MAX_MANIFEST_BYTES) }
            if (!committed.contentEquals(bytes)) throw IOException("Atomic commit verification failed")
        } catch (_: IOException) {
            if (output != null) atomic.failWrite(output)
            throw KnowledgeException("KB_STORE_IO", "资料库原子提交或写后校验失败，本次保存未确认")
        }
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 512 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
        const val MAX_DOCUMENTS = 64
        const val MAX_COLLECTIONS = 32
        const val MAX_CONVERSATIONS = 512
        const val MAX_CHUNKS = 8192
        private const val MAX_MANIFEST_BYTES = 20 * 1024 * 1024
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun sourceId(collection: String, environmentKey: String, identity: String, path: String): String =
            digest(listOf(collection, environmentKey, identity, path).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        fun validateText(text: String) {
            if (text.length > MAX_DOCUMENT_BYTES || !Charsets.UTF_8.newEncoder().canEncode(text) || '\u0000' in text ||
                text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
                throw KnowledgeException("NOT_UTF8_TEXT", "资料需为有效 UTF-8 文本")
            }
            if (text.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) throw KnowledgeException("FILE_TOO_LARGE", "单份资料最多 512 KiB")
        }
        private fun validateBounds(documents: List<KnowledgeDocument>) {
            if (documents.size > MAX_DOCUMENTS || documents.sumOf { it.bytes.toLong() } > MAX_TOTAL_BYTES ||
                documents.sumOf { chunks(it.content).size } > MAX_CHUNKS) throw KnowledgeException("KB_LIMIT", "资料库文档、总字节或分片数量达到上限")
        }
        private data class Chunk(val text: String, val startLine: Int, val endLine: Int)
        private fun chunks(text: String): List<Chunk> {
            val result = ArrayList<Chunk>()
            var start = 0
            var line = 1
            while (start < text.length) {
                var end = minOf(text.length, start + 1600)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                var lines = 0
                for (index in start until end) if (text[index] == '\n' && ++lines == 40) { end = index + 1; break }
                val chunk = text.substring(start, end)
                val newlineCount = chunk.count { it == '\n' }
                result += Chunk(chunk, line, line + newlineCount - if (chunk.endsWith('\n')) 1 else 0)
                line += newlineCount
                start = end
            }
            return result
        }
    }
}

/** Latin words and CJK unigrams/bigrams keep Chinese substring queries useful without embeddings. */
internal object KnowledgeKeywords {
    private val words = Regex("[\\p{L}\\p{N}]+")
    fun tokenize(text: String): Set<String> = buildSet {
        words.findAll(text.lowercase(Locale.ROOT)).forEach { word ->
            val value = word.value
            if (value.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
                value.forEachIndexed { i, char ->
                    add(char.toString())
                    if (i + 1 < value.length) add(value.substring(i, i + 2))
                }
                add(value)
            } else add(value)
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream(minOf(limit, 16_000))
    val block = ByteArray(8192)
    while (true) {
        val count = read(block)
        if (count < 0) break
        if (buffer.size() + count > limit) throw KnowledgeException("FILE_TOO_LARGE", "资料超过允许的字节大小")
        buffer.write(block, 0, count)
    }
    return buffer.toByteArray()
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
