package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.core.LayeredSetting
import io.github.mangi.eta.core.SampleSettings
import io.github.mangi.eta.core.SettingsLayers
import io.github.mangi.eta.data.repository.AgentMemorySnapshot

/**
 * 文档知识库（KB）可复用实体：一个被索引的文档片段。
 *
 * 轻量版只做“文件名 + 全文关键词”倒排索引，不引入 embedding/向量库；
 * 成本与体积可控，边界见 [AgentFileKbIndex]。
 */
internal data class AgentKbDocument(
    val docId: String,
    val displayName: String,
    val absolutePath: String,
    /** 切分后的正文片段；全文关键词索引基于它，原文不常驻内存。 */
    val excerpt: String,
    val bytes: Long,
    val revision: String,
)

/** 单条 KB 检索命中：文件名命中与正文命中分别计分，结果有序。 */
internal data class AgentKbHit(
    val document: AgentKbDocument,
    val score: Int,
    val matchedTokens: List<String>,
)

/**
 * KB pin：把某份文档钉进会话级召回上下文，类似记忆 pin。
 * 与 [AgentMemoryContext] 不同，KB pin 只保存引用与分数，不把正文注入系统提示。
 */
internal data class AgentKbPin(
    val docId: String,
    val displayName: String,
    val absolutePath: String,
    val reason: String,
    val score: Int,
)

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    /** 本会话钉住的 KB 引用；默认空，保持旧行为与旧测试兼容。 */
    val kbPins: List<AgentKbPin> = emptyList(),
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        // 钉住章节：标题带 ⛔ / 「硬约束」的段落无论写在哪都必须进注入，
        // 否则文件一长就会滑出预算，规则等于没写（2026-10-26 踩过）。
        val pinned = extractPinned(snapshot.content)
        val core = listOf(pinned, extractCore(snapshot.content))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val lines = snapshot.content.lineSequence().filter { it.isNotBlank() }.toList()
        val pinnedHeadings = lines.filter { line -> PIN_HEADING.containsMatchIn(line) }
        val headings = (pinnedHeadings + lines.filter { line -> HEADING.matches(line.trimEnd()) })
            .distinct()
            .joinToString("\n")
            .take(MAX_HEADING_INDEX_CHARS)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = core.take(coreBudget),
            coreTruncated = core.length > coreBudget,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
        )
    }

    /**
     * 上下文窗口对应的核心记忆预算。
     *
     * 这是配置分层模型（3.4）的样例接入点：比例计算结果作为 DEFAULT 层注入 [LayeredSetting]，
     * 再经 [SettingsLayers.resolve] 解析。当前没有任何远端/设备/用户覆盖源接入，
     * 因此行为与改造前逐字节一致；后续接 DataStore/云控只需在此填入对应层。
     * 旧签名保持不变，所有既有调用方与测试无需改动。
     */
    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        val computedDefault = (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
        val layered = LayeredSetting(
            key = SampleSettings.MEMORY_CORE_BUDGET_CHARS,
            default = computedDefault,
        )
        return SettingsLayers.resolve(layered)
    }

    /** 标题形如 `## ⛔ …` / `## 硬约束 …` 的段落整体抽取，永远进注入。 */
    private fun extractPinned(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val out = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (PIN_HEADING.containsMatchIn(line)) {
                val start = index
                var end = index + 1
                while (end < lines.size && !lines[end].startsWith("#")) end++
                if (out.isNotEmpty()) out += ""
                out += lines.subList(start, end)
                index = end
            } else {
                index++
            }
        }
        return out.joinToString("\n").trim()
    }

    private fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        // 旧文件没有「# 核心记忆」时曾经静默返回空串，等于核心记忆一条都不注入；
        // 兜底取文件开头一段，并保证钉住章节已在 build() 里先行加入。
        if (start < 0) return lines.take(CORE_FALLBACK_LINES).joinToString("\n")
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    private const val CORE_HEADING = "# 核心记忆"
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val MAX_HEADING_INDEX_CHARS = 6_000
    private const val CORE_FALLBACK_LINES = 260
    private val PIN_HEADING = Regex("^#{1,3}\\s*(⛔|硬约束|最高优先级)")
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
