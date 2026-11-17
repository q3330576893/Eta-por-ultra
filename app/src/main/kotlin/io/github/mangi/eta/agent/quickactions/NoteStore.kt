package io.github.mangi.eta.agent.quickactions

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量本地备忘存储：只写 App 私有 SharedPreferences，不联网、不加载模型。
 *
 * 正文原样保存（路由阶段已限长 80 字，这里再兜底截断），总条数有界（最多 [MAX_NOTES] 条，
 * 新记录插在最前，超出丢弃最旧）。序列化用纯字符串的转义格式（不依赖 org.json），
 * 这样核心逻辑在普通 JVM 单元测试里也能直接跑，不需要 Robolectric。
 */
internal object NoteStore {
    internal const val PREF_NAME = "eta_daily_notes"
    internal const val KEY_NOTES = "notes"
    internal const val MAX_NOTES = 50
    internal const val MAX_NOTE_LENGTH = 80

    internal data class Note(val text: String, val createdAtMillis: Long)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun add(context: Context, text: String, createdAtMillis: Long): List<Note> =
        add(prefs(context), text, createdAtMillis)

    fun all(context: Context): List<Note> = all(prefs(context))

    internal fun add(prefs: SharedPreferences, text: String, createdAtMillis: Long): List<Note> {
        val bounded = text.take(MAX_NOTE_LENGTH)
        val updated = (listOf(Note(bounded, createdAtMillis)) + all(prefs)).take(MAX_NOTES)
        prefs.edit().putString(KEY_NOTES, encode(updated)).apply()
        return updated
    }

    internal fun all(prefs: SharedPreferences): List<Note> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        // 每条最多 MAX_NOTE_LENGTH 字，转义后至多 6 倍，再加时间/分隔符余量；超限视为损坏数据。
        if (raw.length > MAX_NOTES * (MAX_NOTE_LENGTH * 6 + 48) + 16) return emptyList()
        return decode(raw)
    }

    // 记录格式：字段用 0x1F 分隔、记录用 0x1E 分隔；文本内 0x1F/0x1E/反斜杠用反斜杠转义。
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'
    private const val ESCAPE = '\\'

    internal fun encode(notes: List<Note>): String = buildString {
        notes.take(MAX_NOTES).forEachIndexed { index, note ->
            if (index > 0) append(RECORD)
            append(escape(note.text.take(MAX_NOTE_LENGTH)))
            append(FIELD)
            append(note.createdAtMillis)
        }
    }

    private fun decode(raw: String): List<Note> {
        if (raw.isEmpty()) return emptyList()
        val result = mutableListOf<Note>()
        for (record in raw.split(RECORD)) {
            if (record.isEmpty() || result.size >= MAX_NOTES) continue
            val parts = record.split(FIELD, limit = 2)
            if (parts.size != 2) continue
            val text = unescape(parts[0]).take(MAX_NOTE_LENGTH)
            if (text.isEmpty()) continue
            val at = parts[1].toLongOrNull() ?: 0L
            result += Note(text, at)
        }
        return result
    }

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                ESCAPE, FIELD, RECORD -> { append(ESCAPE); append(ch) }
                else -> append(ch)
            }
        }
    }

    private fun unescape(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == ESCAPE && index + 1 < text.length) {
                append(text[index + 1])
                index += 2
            } else {
                append(ch)
                index++
            }
        }
    }
}
