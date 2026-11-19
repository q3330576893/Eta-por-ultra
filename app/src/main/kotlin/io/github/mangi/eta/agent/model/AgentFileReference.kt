package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentKbDocument
import io.github.mangi.eta.agent.memory.AgentKbHit

enum class AgentFileReferenceKind {
    File,
    Directory,
}

data class AgentFileReference(
    val displayName: String,
    val absolutePath: String,
    val kind: AgentFileReferenceKind,
    /**
     * 4.3 动作标记：“把该引用索引到 KB”。默认 false，旧构造点与编解码完全兼容；
     * 仅为内存态标记，不写进用户消息的格式化文本（不改动 format/parse 协议）。
     */
    val indexToKb: Boolean = false,
)

internal data class AgentFileReferencePrompt(
    val request: String,
    val references: List<AgentFileReference>,
)

internal object AgentFileReferencePolicy {
    fun canSend(
        references: List<AgentFileReference>,
        terminalToolsEnabled: Boolean,
    ): Boolean = references.isEmpty() || terminalToolsEnabled

    /**
     * 4.3：索引到 KB 复用附件权限——只有可发送（即终端/附件权限已开启）的引用才允许索引；
     * 无权限时不产生“绕过附件权限把文件内容长期留下”的旁路。
     */
    fun canIndexToKb(reference: AgentFileReference, terminalToolsEnabled: Boolean): Boolean =
        canSend(listOf(reference), terminalToolsEnabled)

    fun titleSource(
        request: String,
        references: List<AgentFileReference>,
    ): String = request.ifBlank { references.firstOrNull()?.displayName.orEmpty() }
}

/** 生成并解析 Eta 自己写入用户消息的本地路径上下文。 */
internal object AgentFileReferencePromptCodec {
    private const val FILES_HEADER = "# Files mentioned by the user:"
    private const val REQUEST_HEADER = "## My request:"
    private const val ENTRY_PREFIX = "## "

    fun format(
        request: String,
        references: List<AgentFileReference>,
    ): String {
        val uniqueReferences = references.distinctBy { it.absolutePath }
        if (uniqueReferences.isEmpty()) return request

        return buildString {
            appendLine(FILES_HEADER)
            appendLine()
            uniqueReferences.forEachIndexed { index, reference ->
                if (index > 0) appendLine()
                append(ENTRY_PREFIX)
                append(reference.displayLabel)
                append(": ")
                appendLine(reference.absolutePath)
            }
            appendLine()
            append(REQUEST_HEADER)
            if (request.isNotEmpty()) {
                appendLine()
                append(request)
            }
        }
    }

    /**
     * 解析 Eta 自己写进用户消息的结构化块：先拆文件引用，再剥掉斜杠命令注入的技能块。
     *
     * 展示、搜索、导出都走这里，所以技能块只需要在这一处剥，气泡里就不会出现整段 SKILL.md。
     */
    fun parse(content: String): AgentFileReferencePrompt {
        val parsed = parseFileReferences(content)
        val request = AgentSkillCommandPromptCodec.strip(parsed.request)
        return if (request === parsed.request) parsed else parsed.copy(request = request)
    }

    private fun parseFileReferences(content: String): AgentFileReferencePrompt {
        val prefix = "$FILES_HEADER\n\n"
        if (!content.startsWith(prefix)) {
            return AgentFileReferencePrompt(request = content, references = emptyList())
        }
        val requestDelimiter = "\n\n$REQUEST_HEADER"
        val requestHeaderIndex = content.indexOf(requestDelimiter, startIndex = prefix.length)
        if (requestHeaderIndex < 0) {
            return AgentFileReferencePrompt(request = content, references = emptyList())
        }

        val entriesText = content.substring(prefix.length, requestHeaderIndex)
        val references = entriesText
            .split("\n\n")
            .mapNotNull(::parseReference)
        if (references.isEmpty() || references.size != entriesText.split("\n\n").size) {
            return AgentFileReferencePrompt(request = content, references = emptyList())
        }

        val requestStart = requestHeaderIndex + requestDelimiter.length
        val request = when {
            requestStart == content.length -> ""
            content.getOrNull(requestStart) == '\n' -> content.substring(requestStart + 1)
            else -> return AgentFileReferencePrompt(request = content, references = emptyList())
        }
        return AgentFileReferencePrompt(
            request = request,
            references = references.distinctBy { it.absolutePath },
        )
    }

    private fun parseReference(line: String): AgentFileReference? {
        if (!line.startsWith(ENTRY_PREFIX) || line.contains('\n')) return null
        val body = line.removePrefix(ENTRY_PREFIX)
        val delimiterIndex = body.lastIndexOf(": /")
        if (delimiterIndex <= 0) return null
        val rawLabel = body.substring(0, delimiterIndex)
        val absolutePath = body.substring(delimiterIndex + 2)
        val isDirectory = rawLabel.endsWith('/')
        val displayName = rawLabel.removeSuffix("/")
        if (
            displayName.isBlank() ||
            displayName.hasUnsupportedControlCharacter() ||
            !absolutePath.startsWith('/') ||
            absolutePath.hasUnsupportedControlCharacter()
        ) {
            return null
        }
        return AgentFileReference(
            displayName = displayName,
            absolutePath = absolutePath,
            kind = if (isDirectory) AgentFileReferenceKind.Directory else AgentFileReferenceKind.File,
        )
    }

    private val AgentFileReference.displayLabel: String
        get() = displayName + if (kind == AgentFileReferenceKind.Directory) "/" else ""
}

internal fun String.hasUnsupportedControlCharacter(): Boolean =
    any { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }

/**
 * 4.3 轻量文档知识库倒排索引（文件名 + 全文关键词，无 embedding）。
 *
 * 索引格式（进程内，不落盘）：
 * - [docId]：文档的稳定标识，取 canonical absolutePath 的 SHA-256 前 16 位；
 * - 文档实体 [AgentKbDocument]：displayName / absolutePath / excerpt / bytes / revision；
 * - 倒排表 token(小写) -> LinkedHashSet<docId>；文件名 token 与正文 token 分开计分；
 * - 分片：正文按 [CHUNK_CHARS] 切成多片，每片一个 [AgentKbDocument]，避免大文件一次入内存。
 *
 * 边界：
 * - 只索引 UTF-8 文本，二进制/超大文件跳过并返回原因；
 * - 关键词用正则词切分（字母数字/中文连续串），不引入分词依赖；
 * - 查询为 O(命中 token 的倒排表大小)，不做模糊/同义/向量检索；
 * - 权限与敏感策略在工具层复用附件权限（AgentFileReferencePolicy.canSend）与
 *   AgentSensitiveToolPolicy，索引本身只处理已获授权传入的文本；
 * - 命中按“文件名命中 * NAME_WEIGHT + 正文命中”计分降序，带 matchedTokens 便于解释。
 */
internal class AgentFileKbIndex {
    private data class Entry(
        val document: AgentKbDocument,
        val nameTokens: Set<String>,
        val bodyTokens: Set<String>,
    )

    private val originals = LinkedHashMap<String, AgentKbDocument>()
    private val entries = LinkedHashMap<String, Entry>()
    private val nameInverted = HashMap<String, LinkedHashSet<String>>()
    private val bodyInverted = HashMap<String, LinkedHashSet<String>>()

    /** 索引一个文档（按正文分片）；返回新增文档片数。 */
    fun index(document: AgentKbDocument): Int {
        if (originals[document.docId] == document) return 0
        require(document.excerpt.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "KB document exceeds 512 KiB" }
        val next = originals.filterKeys { it != document.docId }.values + document
        require(next.size <= 64 && next.sumOf { it.excerpt.toByteArray(Charsets.UTF_8).size.toLong() } <= 8 * 1024 * 1024) { "KB index capacity reached" }
        val obsolete = entries.keys.filter { it == document.docId || it.startsWith("${document.docId}#") }.toSet()
        entries.keys.removeAll(obsolete)
        nameInverted.values.forEach { it.removeAll(obsolete) }
        bodyInverted.values.forEach { it.removeAll(obsolete) }
        originals[document.docId] = document
        val bodyChunks = document.excerpt.chunked(CHUNK_CHARS)
        if (bodyChunks.isEmpty()) return 0
        var added = 0
        bodyChunks.forEachIndexed { index, chunk ->
            val id = if (bodyChunks.size == 1) document.docId else "${document.docId}#$index"
            if (entries.containsKey(id)) return@forEachIndexed
            val nameTokens = tokenize(document.displayName)
            val bodyTokens = tokenize(chunk)
            entries[id] = Entry(document.copy(docId = id, excerpt = chunk), nameTokens, bodyTokens)
            nameTokens.forEach { nameInverted.getOrPut(it) { LinkedHashSet() }.add(id) }
            bodyTokens.forEach { bodyInverted.getOrPut(it) { LinkedHashSet() }.add(id) }
            added++
        }
        return added
    }

    /** 查询：query 分词后同时匹配文件名与正文，文件名命中权重更高。 */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<AgentKbHit> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val scores = HashMap<String, Int>()
        val matched = HashMap<String, LinkedHashSet<String>>()
        tokens.forEach { token ->
            nameInverted[token]?.forEach { id ->
                scores[id] = (scores[id] ?: 0) + NAME_WEIGHT
                matched.getOrPut(id) { LinkedHashSet() }.add(token)
            }
            bodyInverted[token]?.forEach { id ->
                scores[id] = (scores[id] ?: 0) + 1
                matched.getOrPut(id) { LinkedHashSet() }.add(token)
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit.coerceIn(1, DEFAULT_LIMIT))
            .mapNotNull { (id, score) ->
                entries[id]?.let { AgentKbHit(it.document, score, matched[id]?.toList().orEmpty()) }
            }
    }

    fun size(): Int = entries.size

    fun clear() {
        originals.clear()
        entries.clear()
        nameInverted.clear()
        bodyInverted.clear()
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val NAME_WEIGHT = 3

        /**
         * KB 检索结果可能来自敏感附件，工具名需纳入 AgentSensitiveToolPolicy 的排除集合，
         * 避免原始参数/结果进入持久会话。策略文件不属本代理独占，由父代理接入；
         * 这里只暴露稳定名称集合供接入点引用。
         */
        val SENSITIVE_KB_TOOLS: Set<String> = setOf("index_file_to_kb", "search_kb")

        private const val CHUNK_CHARS = 4_000
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")

        /** 稳定 docId：路径的 SHA-256 前 16 位。无 JDK 依赖之外的实现。 */
        fun docIdFor(absolutePath: String): String {
            val bytes = absolutePath.toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            // 必须按无符号转换：直接用 %02x 格式化负 Byte 会得到 8 个字符并造成 docId 碰撞。
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
        }

        private fun tokenize(text: String): Set<String> =
            TOKEN.findAll(text).map { it.value.lowercase(java.util.Locale.ROOT) }.toSet()
    }
}

