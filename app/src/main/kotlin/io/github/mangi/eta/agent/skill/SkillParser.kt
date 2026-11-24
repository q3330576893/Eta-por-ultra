package io.github.mangi.eta.agent.skill

import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * SKILL.md 文件名识别规则——安装、GitHub 发现、索引与加载共用同一套判断。
 *
 * 生态里的仓库常用 `skill.md` / `Skill.md` 等大小写变体（Windows/macOS 编辑器、手工搬运、
 * 大小写不敏感文件系统导出的归档）。大小写不应决定一个 Skill 是否可见，因此识别按 ASCII
 * 小写比较；写入与落盘仍保留归档中的原始文件名，不做重命名。
 */
internal object SkillFileNames {
    const val CANONICAL = "SKILL.md"
    private const val CANONICAL_LOWER = "skill.md"
    private const val MAX_MESSAGE_NAME_LENGTH = 80

    /** 仅接受与 `SKILL.md` 长度相同、按 ROOT locale 小写后完全一致的名称。 */
    fun isSkillFileName(name: String): Boolean =
        name.length == CANONICAL_LENGTH && name.lowercase(Locale.ROOT) == CANONICAL_LOWER

    /**
     * 同一目录出现多个大小写变体时选出唯一的规范名：优先原始 `SKILL.md`，其次按字典序，
     * 保证索引结果稳定，不会因目录枚举顺序而抖动。
     */
    fun preferredVariant(names: Collection<String>): String? {
        var best: String? = null
        names.forEach { name ->
            if (!isSkillFileName(name)) return@forEach
            val current = best
            best = when {
                current == null -> name
                name == CANONICAL && current != CANONICAL -> name
                (name == CANONICAL) == (current == CANONICAL) && name < current -> name
                else -> current
            }
        }
        return best
    }

    /**
     * 在 [directory] 中定位 Skill 文件。只读取目录列表，不跟随任何符号链接；文件本身是符号
     * 链接时返回 null，让调用方按“不是 Skill”处理，而不是读到目录之外。
     */
    fun find(directory: File): File? {
        val names = runCatching { directory.list() }.getOrNull() ?: return null
        val match = preferredVariant(names.asList()) ?: return null
        val file = File(directory, match)
        return file.takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
    }

    /** 错误信息里回显的条目名，去掉控制字符并截断，避免把归档内容原样带进提示。 */
    fun describeForMessage(name: String): String {
        val sanitized = buildString {
            name.forEach { character ->
                append(if (character.isISOControl()) '?' else character)
            }
        }
        return if (sanitized.length <= MAX_MESSAGE_NAME_LENGTH) {
            sanitized
        } else {
            sanitized.take(MAX_MESSAGE_NAME_LENGTH) + "…"
        }
    }

    private const val CANONICAL_LENGTH = 8
}

/**
 * SKILL.md 解析器——从 YAML frontmatter + Markdown body 中提取结构化信息。
 *
 * 支持 `>` / `|` 多行块、缩进子块，以及普通的 `key: value` 行。
 * 纯字符串处理，不依赖外部 YAML 库。
 */
internal object SkillParser {

    /**
     * 读取并解析 [skillFile]，返回 frontmatter map + body string。
     * 文件不存在、不是文件或是符号链接时返回 null。
     *
     * 允许 UTF-8 BOM：Windows 编辑器保存的 SKILL.md 常带 BOM，若直接比较首字符会把
     * frontmatter 整段丢掉，导致 name/description 变空。
     */
    fun parseSkillFile(skillFile: File): ParsedSkillFile? {
        if (!skillFile.exists() || !skillFile.isFile) return null
        if (Files.isSymbolicLink(skillFile.toPath())) return null
        val bytes = skillFile.readBytes()
        val raw = bytes.toString(Charsets.UTF_8)
        val revision = skillRevision(bytes)
        val bounds = frontmatterBounds(raw)
            ?: return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim(), revision = revision)
        return ParsedSkillFile(
            frontmatter = parseSimpleFrontmatter(bounds.frontmatter),
            body = raw.substring(bounds.bodyStart).trim(),
            revision = revision,
        )
    }

    /**
     * 定位 `---` 包围的 frontmatter，返回 frontmatter 文本与正文起始下标。
     *
     * 逐行比较而不是搜索 `"\n---"`：后者会把 `----`、`---extra` 之类的行当成结束标记，
     * 也会漏掉 CRLF 结尾之外的写法。BOM 在这里统一剥离。
     */
    internal fun frontmatterBounds(raw: String): FrontmatterBounds? {
        val start = if (raw.startsWith('\uFEFF')) 1 else 0
        val firstLineEnd = raw.indexOf('\n', start).let { if (it < 0) raw.length else it }
        if (raw.substring(start, firstLineEnd).trimEnd('\r') != FRONTMATTER_DELIMITER) return null
        var lineStart = firstLineEnd + 1
        while (lineStart <= raw.length) {
            val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            if (raw.substring(lineStart, lineEnd).trimEnd('\r') == FRONTMATTER_DELIMITER) {
                return FrontmatterBounds(
                    frontmatter = raw.substring(firstLineEnd + 1, lineStart).trimEnd('\r', '\n'),
                    bodyStart = lineEnd.coerceAtMost(raw.length),
                )
            }
            if (lineEnd == raw.length) break
            lineStart = lineEnd + 1
        }
        return null
    }

    internal data class FrontmatterBounds(
        val frontmatter: String,
        val bodyStart: Int,
    )

    /**
     * 简单 YAML frontmatter 解析。
     *
     * 支持：
     * - `key: value` 单行
     * - `key: >` 折叠多行块
     * - `key: |` 字面多行块
     * - `key:` 后跟缩进子块
     */
    fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val lines = frontmatter.lines()
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank()) {
                index += 1
                continue
            }
            val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
            if (keyMatch == null) {
                index += 1
                continue
            }
            val key = keyMatch.groupValues[1]
            val value = keyMatch.groupValues[2]
            if (YAML_BLOCK_SCALAR.matches(value)) {
                val literal = value.startsWith('|')
                val blockLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                    val next = lines[index]
                    blockLines += if (next.isBlank()) "" else next.trim()
                    index += 1
                }
                result[key] = if (literal) {
                    blockLines.joinToString("\n").trim()
                } else {
                    foldYamlLines(blockLines)
                }
                continue
            }
            if (value.isBlank()) {
                val builder = StringBuilder()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(lines[index].trimEnd())
                    index += 1
                }
                result[key] = builder.toString().trim()
                continue
            }
            result[key] = unquoteScalar(value)
            index += 1
        }
        return result
    }

    /** 支持 YAML 常见的单双引号标量；复杂转义仍交由 Skill 作者避免使用。 */
    private fun unquoteScalar(raw: String): String {
        val value = raw.trim()
        if (value.length < 2) return value
        val quoted = (value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\'')
        return if (quoted) value.substring(1, value.lastIndex) else value
    }

    /** `>` 折叠换行、保留空行形成的段落；尾部 chomp 对元数据没有语义差异。 */
    private fun foldYamlLines(lines: List<String>): String = buildString {
        var pendingBlankLines = 0
        lines.forEach { line ->
            if (line.isBlank()) {
                pendingBlankLines += 1
            } else {
                if (isNotEmpty()) {
                    if (pendingBlankLines == 0) append(' ')
                    else repeat(pendingBlankLines + 1) { append('\n') }
                }
                append(line)
                pendingBlankLines = 0
            }
        }
    }.trim()

    /**
     * 解析缩进子块为 key-value map（用于 metadata 字段）。
     */
    fun parseIndentedBlock(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].trim().trim('"')
        }.toMap()
    }

    /**
     * 从目录名和 frontmatter name 生成规范化的 skill id。
     *
     * 使用 ROOT locale：默认 locale 会把 `I` 小写成 `ı`（土耳其语），使 `SKILL` 之类的
     * 名字生成与安装校验不一致的 id。
     */
    fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase(Locale.ROOT) }
    }

    /**
     * 规范化查找字符串——用于 id/name/path 匹配。
     */
    fun normalizeSkillLookup(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")

    private const val FRONTMATTER_DELIMITER = "---"
    private val YAML_BLOCK_SCALAR = Regex("[>|][+-]?")

}
