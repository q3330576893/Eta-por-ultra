package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.TreeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
    /** 多信号打分后的有序命中（分数降序、同分行号升序）；未使用 query 时为空。 */
    val scoredHits: List<MemoryScoredHit> = emptyList(),
    /** 本次读取语料中出现过的最新日期（YYYY-MM 或 YYYY-MM-DD）。 */
    val latestUpdateDate: String? = null,
    /** 同章节内相隔较久的旧事实提示。 */
    val staleHints: List<MemoryConflictHint> = emptyList(),
)

/** 一行记忆的多信号打分结果。 */
internal data class MemoryScoredHit(
    val lineNumber: Int,
    val score: Int,
    val matchedTokens: List<String>,
    val isHeading: Boolean,
)

/** 同章节同主题、日期相隔较久的新旧冲突提示。 */
internal data class MemoryConflictHint(
    val section: String,
    val newerLine: Int,
    val olderLine: Int,
    val newerDate: String,
    val olderDate: String,
    val message: String,
)

/** query 分词后的一个词元；[isEntity] 表示专名/实体候选，检索时优先。 */
private data class MemoryQueryToken(
    val text: String,
    val isEntity: Boolean,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    data class Success(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储。 */
private data class CompactCandidate(
    val position: Int,
    val start: Int,
    val end: Int,
    val bodyBytes: Int,
    val date: String,
)

internal class AgentMemoryStore(
    rootDir: File,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val archiveAtomicFile = AtomicFile(File(memoryDir, ARCHIVE_FILE_NAME))
    private val lock = Any()

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars, archive = false).let { hot ->
                if (hot.matchedLines > 0) return@let hot
                search(snapshot, query.trim(), boundedChars, archive = true)
            }
        }
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        val compacted = maybeCompact(updated)
        writeLocked(compacted)
        AgentMemoryWriteResult.Success(snapshotOf(compacted))
    }

    /** The receipt and candidates share one AtomicFile commit; replay after a crash cannot append twice. */
    fun appendReviewedCandidates(token: String, candidates: List<String>): AgentMemorySnapshot = synchronized(lock) {
        require(token.isNotBlank() && token.length <= 200)
        require(candidates.size in 1..3 && candidates.all { it.isNotBlank() && it.length <= 300 && it.none(Char::isISOControl) })
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val marker = "<!-- eta-review:$digest -->"
        val current = snapshotLocked()
        val archive = readArchiveText()
        if (current.content.contains(marker) || archive.contains(marker)) return@synchronized current
        val existing = (current.content + "\n" + archive).lineSequence().map { it.trim().removePrefix("- ") }.toSet()
        val fresh = candidates.distinct().filterNot { it in existing }
        val section = buildString {
            append("## 用户确认的经验 ").append(java.time.LocalDate.now()).append('\n')
            fresh.forEach { append("- ").append(it).append('\n') }
            append(marker)
        }
        val updated = if (current.content.isBlank()) section else current.content.trimEnd('\n') + "\n\n" + section
        // Compact on a later ordinary write/startup: this approval remains a single-file atomic commit.
        writeLocked(updated)
        snapshotOf(updated)
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        val compacted = maybeCompact(content)
        writeLocked(compacted)
        snapshotOf(compacted)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        val compacted = maybeCompact(content)
        writeLocked(compacted)
        AgentMemoryWriteResult.Success(snapshotOf(compacted))
    }

    /** 启动时收敛一次：热区超限且自动压缩开启时立即压一轮，不等待下一次写入。 */
    fun compactOnStartup() {
        synchronized(lock) {
            val current = snapshotLocked()
            val compacted = maybeCompact(current.content)
            if (compacted != current.content) {
                writeLocked(compacted)
            }
        }
    }

    /** 冷区（MEMORY-ARCHIVE.md）全文；不参与上下文注入，只供检索与归档。 */
    fun archiveSnapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotOf(readArchiveText())
    }

    fun appendArchive(content: String): AgentMemorySnapshot = synchronized(lock) {
        if (content.isEmpty()) return@synchronized snapshotOf(readArchiveText())
        val existing = readArchiveText()
        val merged = if (existing.isEmpty()) content else existing.trimEnd('\n') + "\n" + content
        val bytes = merged.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆冷区文件超过 1 MiB 安全上限",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            archiveAtomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆冷区文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            finishChecked(archiveAtomicFile, output, bytes)
        } catch (throwable: Throwable) {
            archiveAtomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆冷区文件",
                cause = throwable,
            )
        }
        snapshotOf(merged, bytes)
    }

    private fun readArchiveText(): String {
        val file = archiveAtomicFile.baseFile
        if (!file.exists()) return ""
        return try {
            archiveAtomicFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆冷区文件",
                cause = throwable,
            )
        }
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            finishChecked(atomicFile, output, bytes)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    /** Verify the actual commit, including platforms where AtomicFile logs a failed rename instead of throwing. */
    private fun finishChecked(file: AtomicFile, output: java.io.FileOutputStream, bytes: ByteArray) {
        file.finishWrite(output)
        // Do not openRead() before checking: it discards an unrenamed .new when the old base exists.
        val pending = File(file.baseFile.path + ".new")
        if (pending.exists()) {
            if (!pending.readBytes().contentEquals(bytes)) throw IOException("Atomic memory staging bytes differ")
            java.nio.file.Files.move(pending.toPath(), file.baseFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        if (!file.baseFile.readBytes().contentEquals(bytes)) throw IOException("Atomic memory commit read-back differs")
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    @Volatile
    private var autoCompactEnabled: Boolean = true

    fun setAutoCompactEnabled(enabled: Boolean) {
        autoCompactEnabled = enabled
    }

    fun isAutoCompactEnabled(): Boolean = autoCompactEnabled

    /** 把首个 ## 标题之前的无标题旧段落整块移入冷区，消除热区盲区（旧格式段落无标题，永远无法被 compactOnce 命中）。 */
    private fun compactPrefix(content: String): String {
        val lines = content.memoryLines()
        val firstHeading = lines.indices.firstOrNull { lines[it].startsWith(SECTION_PREFIX) } ?: return content
        if (firstHeading == 0) return content
        if ((0 until firstHeading).any { lines[it].trim() == ARCHIVE_SENTINEL }) return content
        val prefixBytes = (0 until firstHeading).sumOf { lines[it].toByteArray(Charsets.UTF_8).size + 1 }
        if (prefixBytes < MIN_SECTION_BYTES) return content
        val archive = StringBuilder()
        for (index in 0 until firstHeading) archive.append(lines[index]).append('\n')
        appendArchive(archive.toString().trimEnd('\n') + "\n")
        val summary = (0 until firstHeading)
            .map { lines[it].trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("- ") }
            ?.let { if (it.length <= SUMMARY_MAX_CHARS) it else it.take(SUMMARY_MAX_CHARS) + "…" }
        val rebuilt = ArrayList<String>(lines.size - firstHeading + 2)
        if (summary != null) rebuilt += summary
        rebuilt += ARCHIVE_SENTINEL
        for (index in firstHeading until lines.size) rebuilt += lines[index]
        return rebuilt.joinToString("\n")
    }

    /** 写入后若热区超限，就把无标题前缀与最旧的段落正文移入冷区。保护段落热区保留标题 + 一行摘要，其余只留归档标记。 */
    private fun maybeCompact(content: String): String {
        if (!autoCompactEnabled) return content
        var current = normalizeSentinels(content)
        if (current.toByteArray(Charsets.UTF_8).size <= MAX_HOT_BYTES) return current
        repeat(MAX_COMPACT_ROUNDS) {
            if (current.toByteArray(Charsets.UTF_8).size <= TARGET_HOT_BYTES) return current
            var next = compactPrefix(current)
            if (next == current) next = compactOnce(current)
            if (next == current) next = compactOldestSection(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    /** 单轮压缩：把最旧、正文最大的段落正文移入冷区，直到降到目标值或没有可搬段落。 */
    private fun compactOnce(content: String): String {
        val lines = content.memoryLines()
        val headings = lines.indices.filter { index -> lines[index].startsWith(SECTION_PREFIX) }
        if (headings.size < 2) return content

        val ranges = headings.mapIndexed { position, start ->
            val next = headings.getOrNull(position + 1) ?: lines.size
            start to (next - 1)
        }

        val candidates = ranges.mapIndexedNotNull { position, range ->
            val (start, end) = range
            val bodyLines = (start + 1)..end
            var bodyBytes = 0
            bodyLines.forEach { index -> bodyBytes += lines[index].toByteArray(Charsets.UTF_8).size + 1 }
            if (bodyBytes < MIN_SECTION_BYTES) return@mapIndexedNotNull null
            val date = MEMORY_DATE_REGEX.find(lines[start])?.value.orEmpty()
            CompactCandidate(position, start, end, bodyBytes, date)
        }
        if (candidates.isEmpty()) return content

        var hotBytes = content.toByteArray(Charsets.UTF_8).size
        val moved = mutableSetOf<Int>()
        val archive = StringBuilder()
        val ordered = candidates.sortedWith(compareBy({ it.date.ifEmpty { "9999-99" } }, { -it.bodyBytes }))
        for (candidate in ordered) {
            if (hotBytes <= TARGET_HOT_BYTES) break
            moved += candidate.position
            hotBytes -= candidate.bodyBytes
            if (archive.isNotEmpty()) archive.append('\n')
            archive.append(lines[candidate.start]).append('\n')
            for (index in (candidate.start + 1)..candidate.end) archive.append(lines[index]).append('\n')
        }
        if (moved.isEmpty()) return content

        val rebuilt = ArrayList<String>(lines.size)
        if (headings.first() > 0) rebuilt += lines.subList(0, headings.first())
        ranges.forEachIndexed { position, range ->
            val (start, end) = range
            rebuilt += lines[start]
            if (moved.contains(position)) {
                if (isProtectedHeading(lines[start])) {
                    summarizeBody(lines, start, end)?.let { rebuilt += "- $it" }
                }
                rebuilt += ARCHIVE_SENTINEL
            } else {
                for (index in (start + 1)..end) rebuilt += lines[index]
            }
        }
        appendArchive(archive.toString().trimEnd('\n') + "\n")
        return rebuilt.joinToString("\n")
    }

    /** 把旧版长归档标记归一化为当前短标记，一次性压低存量地板。 */
    private fun normalizeSentinels(content: String): String {
        val legacy = "- 正文已归档到 MEMORY-ARCHIVE.md（检索记忆时自动命中冷区）。"
        if (legacy == ARCHIVE_SENTINEL) return content
        return if (content.contains(legacy)) content.replace(legacy, ARCHIVE_SENTINEL) else content
    }

    /** 治本：正文已无可搬且仍超限时，把最旧的非保护段整段沉入冷区，热区留一个汇总指针行。 */
    private fun compactOldestSection(content: String): String {
        val lines = content.memoryLines()
        val headings = lines.indices.filter { lines[it].startsWith(SECTION_PREFIX) }
        if (headings.isEmpty()) return content

        val ranges = headings.mapIndexed { position, start ->
            val next = headings.getOrNull(position + 1) ?: lines.size
            start to (next - 1)
        }
        val targetIndex = ranges.indices.firstOrNull { position ->
            !isProtectedHeading(lines[ranges[position].first])
        } ?: return content
        val (start, end) = ranges[targetIndex]

        val archive = StringBuilder()
        for (index in start..end) archive.append(lines[index]).append('\n')
        appendArchive(archive.toString().trimEnd('\n') + "\n")

        val pointerRegex = Regex("""^- 更早 (\d+) 条记忆已归档""")
        var prevCount = 0
        val pointerPos = lines.indices.firstOrNull { pointerRegex.containsMatchIn(lines[it]) }
        if (pointerPos != null) {
            pointerRegex.find(lines[pointerPos])?.let { prevCount = it.groupValues[1].toInt() }
        }
        val pointerLine = "- 更早 ${prevCount + 1} 条记忆已归档（检索自动命中冷区）。"

        val kept = lines.filterIndexed { index, _ -> index != pointerPos && index !in start..end }
        val rebuilt = ArrayList<String>(kept.size + 1)
        rebuilt += pointerLine
        rebuilt += kept
        return rebuilt.joinToString("\n")
    }

    /** 取段落正文首个有意义的行作为热区摘要，超长截断。 */
    private fun summarizeBody(lines: List<String>, start: Int, end: Int): String? {
        for (index in (start + 1)..end) {
            val line = lines[index].trim()
            if (line.isEmpty()) continue
            val text = line.removePrefix("- ").trim()
            if (text.isEmpty() || text.startsWith("正文已归档")) continue
            return if (text.length <= SUMMARY_MAX_CHARS) text else text.take(SUMMARY_MAX_CHARS) + "…"
        }
        return null
    }

    private fun isProtectedHeading(heading: String): Boolean =
        PROTECTED_HEADING_KEYWORDS.any { keyword -> heading.contains(keyword) }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
            latestUpdateDate = lines.mapNotNull(::parseMemoryDate).maxWithOrNull(compareBy {
                approximateDayNumber(it) ?: Int.MIN_VALUE
            }),
            staleHints = detectStaleHints(lines),
        )
    }

    /**
     * 多信号本地检索：query 分词逐 token 匹配，叠加词频、标题命中、实体词权重，
     * 返回按分数降序的有序命中。复杂度 O(行数 × query 词数)，无模型/网络/常驻索引。
     */
    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
        archive: Boolean,
    ): AgentMemoryReadResult {
        val lines = if (archive) readArchiveText().memoryLines() else snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val queryTokens = tokenizeForScoring(query)
        if (queryTokens.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }

        // 单遍扫描：为每行累计得分，词频带上限防止长行刷分。
        val scored = ArrayList<MemoryScoredHit>()
        lines.forEachIndexed { index, line ->
            val lowered = line.lowercase()
            var score = 0
            val hitTokens = ArrayList<String>(queryTokens.size)
            queryTokens.forEach { token ->
                var count = 0
                var from = 0
                while (true) {
                    val at = lowered.indexOf(token.text, from)
                    if (at < 0) break
                    count++
                    if (count >= TOKEN_FREQUENCY_CAP) break
                    from = at + token.text.length
                }
                if (count == 0) return@forEach
                hitTokens += token.text
                score += count * POINT_PER_OCCURRENCE
                if (token.isEntity) score += POINT_ENTITY_HIT
            }
            if (score == 0) return@forEachIndexed
            val heading = isHeadingLine(line)
            if (heading) score += POINT_HEADING_HIT
            // lineNumber 用 1-based，与渲染出的 "N: ..." 及 startLine/endLine 口径一致。
            scored += MemoryScoredHit(index + 1, score, hitTokens, heading)
        }
        if (scored.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }

        // 分数降序、同行号升序；渲染时仍按文件行号展开上下文，保留人类可读顺序。
        val ranked = scored.sortedWith(compareByDescending<MemoryScoredHit> { it.score }.thenBy { it.lineNumber })
        val included = TreeSet<Int>()
        ranked.forEach { hit ->
            // hit.lineNumber 是 1-based；included 用 0-based 下标，故减 1。
            val zeroBased = hit.lineNumber - 1
            for (candidate in (zeroBased - SEARCH_CONTEXT_LINES)..(zeroBased + SEARCH_CONTEXT_LINES)) {
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder(if (archive) "（冷区 $ARCHIVE_FILE_NAME）" else "")
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = if (archive) "A${index + 1}: ${lines[index]}" else "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                archive && lastIncluded == null -> "\n"
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (lastIncluded != null && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(rendered.take((maxChars - output.length - gap.length).coerceAtLeast(0)))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = scored.size,
            scoredHits = ranked,
            latestUpdateDate = lines.mapNotNull(::parseMemoryDate).maxWithOrNull(compareBy {
                approximateDayNumber(it) ?: Int.MIN_VALUE
            }),
            staleHints = detectStaleHints(lines),
        )
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    /** 复用压缩排序既有口径：从行内解析 YYYY-MM-DD / YYYY-MM，取不到返回 null。 */
    private fun parseMemoryDate(line: String): String? =
        MEMORY_DATE_REGEX.find(line)?.value

    /** 近似「月份序号」，只用于比较新旧与计算天数差，不构造日历对象。 */
    private fun approximateDayNumber(date: String): Int? {
        val parts = date.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        val year = parts[0]
        val month = parts.getOrNull(1)?.coerceIn(1, 12) ?: 1
        val day = parts.getOrNull(2)?.coerceIn(1, 31) ?: 15
        return year * 372 + month * 31 + day
    }

    /**
     * 同章节内按主题关键词配对新旧事实，间隔超过 [STALE_FACT_DAYS] 时给出「旧条目可能过期」提示。
     * 只为同一章节、共享一个主题词的两行生成提示；线性扫描，不构造全量配对矩阵。
     */
    private fun detectStaleHints(lines: List<String>): List<MemoryConflictHint> {
        val dated = mutableListOf<Pair<Int, String>>()
        var section = ""
        val hints = ArrayList<MemoryConflictHint>()
        fun flush() {
            if (dated.size < 2) {
                dated.clear()
                return
            }
            val newest = dated.maxByOrNull { (_, date) -> approximateDayNumber(date) ?: Int.MIN_VALUE }
                ?: run { dated.clear(); return }
            val newDay = approximateDayNumber(newest.second) ?: run { dated.clear(); return }
            for ((index, date) in dated) {
                if (index == newest.first) continue
                val oldDay = approximateDayNumber(date) ?: continue
                if (newDay - oldDay < STALE_FACT_DAYS) continue
                val shared = sharedTopic(lines[index], lines[newest.first]) ?: continue
                hints += MemoryConflictHint(
                    section = section,
                    newerLine = newest.first + 1,
                    olderLine = index + 1,
                    newerDate = newest.second,
                    olderDate = date,
                    message = "「$shared」在 ${newest.second}（第 ${newest.first + 1} 行）与 $date（第 ${index + 1} 行）" +
                        "相隔较久，旧条目可能已过期，请以较新日期为准。",
                )
            }
            dated.clear()
        }
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.startsWith(SECTION_PREFIX)) {
                flush()
                section = line.removePrefix(SECTION_PREFIX).trim()
                return@forEachIndexed
            }
            val date = parseMemoryDate(line) ?: return@forEachIndexed
            dated += index to date
        }
        flush()
        return hints
    }

    /** 两行是否共享一个够长的主题词（排除日期/标点），作为「同主题」的轻量判据。 */
    private fun sharedTopic(first: String, second: String): String? =
        tokenizeForScoring(first).map { it.text }.firstOrNull { token ->
            token.length >= 2 && tokenizeForScoring(second).any { it.text == token }
        }

    /** 中英文/数字混合分词，供打分与主题比较共用；保留原始大小写用于实体判定。 */
    private fun tokenizeForScoring(text: String): List<MemoryQueryToken> {
        val matches = SCORING_TOKEN_REGEX.findAll(text).map { it.value }.toList()
        val tokens = ArrayList<MemoryQueryToken>(matches.size)
        val seen = HashSet<String>()
        for (raw in matches) {
            val key = raw.lowercase()
            if (key.length < 2 || key in MEMORY_STOPWORDS || !seen.add(key)) continue
            val entity = raw.length >= 4 && key.all { it.isLetterOrDigit() } &&
                (raw[0].isUpperCase() || key.any { it.isDigit() }) &&
                ENTITY_HINT_WORDS.none { key.contains(it) }
            tokens += MemoryQueryToken(key, entity)
        }
        return tokens
    }

    private fun isHeadingLine(line: String): Boolean =
        line.trimStart().startsWith(SECTION_PREFIX)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_HOT_BYTES = 48 * 1024
        const val TARGET_HOT_BYTES = 40 * 1024
        const val ARCHIVE_FILE_NAME = "MEMORY-ARCHIVE.md"
        private const val SECTION_PREFIX = "## "
        private const val MIN_SECTION_BYTES = 160
        private const val MAX_COMPACT_ROUNDS = 80
        private const val SUMMARY_MAX_CHARS = 48
        private const val ARCHIVE_SENTINEL = "- 正文已归档，检索命中冷区。"
        private val PROTECTED_HEADING_KEYWORDS = listOf(
            "环境包",
            "共享文件夹",
            "硬边界",
            "分区",
            "偏好",
            "ZenProxy",
            "白泽",
            "记忆维护规则",
            "进行中",
            "待你",
            "待用户",
            "出包",
            "签名",
            "可复用",
            "构建",
        )
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500

        /** 事实有效期：同章节同主题的两条记录间隔超过此天数时提示旧条目可能过期。 */
        const val STALE_FACT_DAYS = 30

        /** 多信号打分权重。 */
        private const val POINT_PER_OCCURRENCE = 2
        private const val POINT_ENTITY_HIT = 5
        private const val POINT_HEADING_HIT = 4
        private const val TOKEN_FREQUENCY_CAP = 4

        /** 日期解析与压缩排序复用同一口径。 */
        val MEMORY_DATE_REGEX = Regex("20\\d{2}-\\d{2}(?:-\\d{2})?")

        /** 检索分词：ASCII 单词/数字与 CJK 连续字串分别成词。 */
        private val SCORING_TOKEN_REGEX = Regex("[a-z][a-z0-9_]*|\\d+|[\\u4e00-\\u9fff]{2,}", RegexOption.IGNORE_CASE)

        /** 参与检索与主题判定时忽略的高频虚词。 */
        private val MEMORY_STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "was", "were", "are",
            "的", "了", "和", "与", "在", "是", "有", "把", "被", "一个", "这个", "那个", "用户",
        )

        /** 命中这些通用词时不判为实体，避免把「项目/配置」误当专名。 */
        private val ENTITY_HINT_WORDS = listOf("记忆", "项目", "配置", "偏好", "文件", "folder")
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_CONTEXT_LINES = 1
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    private val autoCompactScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
            autoCompactScope.launch {
                repeat(3) {
                    val collected = runCatching {
                        SettingsDataStore.memoryAutoCompactEnabledFlow().collect { enabled ->
                            store.setAutoCompactEnabled(enabled)
                        }
                    }.isSuccess
                    if (collected) return@launch
                    delay(1_000)
                }
            }
            // 启动即收敛：开关就绪后异步压一轮，避免存量超限记忆一直挂到下一次写入
            autoCompactScope.launch {
                delay(1_200)
                val enabled = runCatching { SettingsDataStore.settings().memoryAutoCompactEnabled }.getOrDefault(true)
                store.setAutoCompactEnabled(enabled)
                runCatching { store.compactOnStartup() }
            }
        }
    }

    fun autoCompactEnabledFlow(): Flow<Boolean> = SettingsDataStore.memoryAutoCompactEnabledFlow()

    suspend fun setAutoCompactEnabled(enabled: Boolean) {
        SettingsDataStore.setMemoryAutoCompactEnabled(enabled)
        store.setAutoCompactEnabled(enabled)
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun appendReviewedCandidates(token: String, candidates: List<String>): AgentMemorySnapshot {
        ensureInitialized()
        return store.appendReviewedCandidates(token, candidates)
    }

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    fun archiveSnapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.archiveSnapshot()
    }

    fun appendArchive(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.appendArchive(content)
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
