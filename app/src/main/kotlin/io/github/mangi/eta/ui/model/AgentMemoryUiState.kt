package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryReadResult
import io.github.mangi.eta.data.repository.AgentMemoryStore

@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val autoMemoryEnabled: Boolean = true,
    val autoCompactEnabled: Boolean = true,
    val autoSkillsEnabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    val notice: String? = null,
    /** 2.4 召回自检的查询串；空串表示未发起召回。 */
    val recallQuery: String = "",
    /** 2.4 召回结果投影，由 AgentMemoryRepository.search() 的返回经 MemoryRecallProjection 得到。 */
    val recallResult: MemoryRecallResult? = null,
    val recallBusy: Boolean = false,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
}

/** 命中行所在的分区：热区=MEMORY.md 正文，冷区=归档文件。 */
enum class MemoryRecallZone { HOT, COLD }

/** 一行召回命中/上下文的展示投影。 */
@Immutable
data class MemoryRecallHit(
    val lineNumber: Int,
    val text: String,
    val zone: MemoryRecallZone,
    /** 该行是否直接命中 query；上下文行（前后若干行）为 false。 */
    val isMatched: Boolean,
    /** 从行文本解析到的日期（yyyy-MM-dd 等），无则 null。 */
    val date: String?,
    /** 匹配分数：当前 search() 不返回分数，预留字段供父代理升级多信号打分后填入。 */
    val score: Float? = null,
)

/** 一次召回的展示投影。 */
@Immutable
data class MemoryRecallResult(
    val query: String,
    val zone: MemoryRecallZone,
    val hits: List<MemoryRecallHit>,
    /** search() 报告的原始命中行数（不含上下文）。 */
    val matchedLines: Int,
    val truncated: Boolean,
    /** 命中内容里检测到疑似凭据时给出的本地提示；绝不外发。 */
    val sensitivityNotice: String? = null,
) {
    val isEmpty: Boolean get() = hits.isEmpty()
}

/**
 * 2.4：把 AgentMemoryRepository.search()（当前公开入口是 read(query=...)）的返回
 * 投影成可检视的展示模型。纯函数，不触碰 Repository，便于 JVM 单测。
 *
 * 兼容性说明：search() 当前只返回 content/startLine/endLine/matchedLines/hasMore，
 * 既没有结构化分区字段，也没有分数与日期。这里通过 content 的"（冷区"前缀判定分区、
 * 从行文本正则解析日期、score 一律留空——父代理升级 search() 后只需替换本投影的来源，
 * UI 展示层无需改动。
 */
internal object MemoryRecallProjection {
    private val LINE = Regex("""^A?(\d+):\s?(.*)$""")
    private val DATE = Regex("""(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})\s*日?""")
    private val CREDENTIAL = Regex(
        """(?i)(api[_ -]?key|password|passwd|secret|token|access[_ -]?key|bearer)\s*[:=]?\s*\S+""",
    )

    /** search() 的归档分支会给 content 加"（冷区 …）"前缀，据此判定分区。 */
    fun zoneOf(content: String): MemoryRecallZone =
        if (content.startsWith("（冷区") || content.startsWith("(冷区")) MemoryRecallZone.COLD
        else MemoryRecallZone.HOT

    fun dateOf(text: String): String? = DATE.find(text)?.let { match ->
        val (y, m, d) = match.destructured
        "$y-${m.padStart(2, '0')}-${d.padStart(2, '0')}"
    }

    /** 疑似凭据做本地遮罩，避免截图/录屏把记忆里的密钥直接带出去。 */
    fun maskSensitive(text: String): String =
        if (CREDENTIAL.containsMatchIn(text)) CREDENTIAL.replace(text, "[已隐藏]") else text

    /**
     * @param result AgentMemoryRepository.read(query=...) 的返回（内部调用私有 search()）。
     * @param query 用户输入的查询串，用于标出直接命中行。
     * @param rawResult 是否原样展示（false 时对本行做凭据遮罩）。默认遮罩，保证敏感性。
     */
    fun project(
        result: AgentMemoryReadResult,
        query: String,
        maskSensitive: Boolean = true,
    ): MemoryRecallResult {
        val zone = zoneOf(result.content)
        val hits = mutableListOf<MemoryRecallHit>()
        var sawCredential = false
        result.content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line == "…") return@forEach
            val match = LINE.matchEntire(line) ?: return@forEach
            val number = match.groupValues[1].toIntOrNull() ?: return@forEach
            val original = match.groupValues[2]
            if (CREDENTIAL.containsMatchIn(original)) sawCredential = true
            val text = if (maskSensitive) maskSensitive(original) else original
            hits += MemoryRecallHit(
                lineNumber = number,
                text = text,
                zone = zone,
                isMatched = query.isNotBlank() && original.contains(query, ignoreCase = true),
                date = dateOf(original),
                score = null,
            )
        }
        return MemoryRecallResult(
            query = query,
            zone = zone,
            hits = hits.toList(),
            matchedLines = result.matchedLines,
            truncated = result.hasMore,
            sensitivityNotice = if (sawCredential) "命中内容含疑似凭据，已本地隐藏；请勿截图或录屏外传" else null,
        )
    }
}
