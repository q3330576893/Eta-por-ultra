package io.github.mangi.eta.agent.quickactions

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Local-only notes. Stable identities and idempotent completion survive process restarts. */
internal object NoteStore {
    internal const val PREF_NAME = "eta_daily_notes"
    internal const val KEY_NOTES = "notes"
    internal const val KEY_VERSIONED_NOTES = "notes_v2"
    internal const val MAX_NOTES = 50
    internal const val MAX_NOTE_LENGTH = 8_000
    private const val MAX_SERIALIZED_CHARS = MAX_NOTES * (MAX_NOTE_LENGTH * 6 + 400)
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    internal data class Note(
        val text: String,
        val createdAtMillis: Long,
        val id: String = legacyId(text, createdAtMillis),
        val completed: Boolean = false,
        val updatedAtMillis: Long = createdAtMillis,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun add(context: Context, text: String, createdAtMillis: Long): List<Note> =
        add(prefs(context), text, createdAtMillis).also { changed(context) }

    /** The share preview retains this id across recreation/repeated delivery. */
    fun addOnce(context: Context, text: String, createdAtMillis: Long, id: String): List<Note> =
        add(prefs(context), text, createdAtMillis, id).also { changed(context) }

    fun all(context: Context): List<Note> = all(prefs(context))

    fun setCompleted(context: Context, id: String, completed: Boolean): Boolean =
        setCompleted(prefs(context), id, completed).also { if (it) changed(context) }

    fun delete(context: Context, id: String): Boolean = delete(prefs(context), id).also { if (it) changed(context) }

    private fun changed(context: Context) {
        mutableChanges.update { it + 1 }
        io.github.mangi.eta.agent.notes.MemoWidgetProvider.updateAll(context.applicationContext)
    }

    @Synchronized
    internal fun add(prefs: SharedPreferences, text: String, createdAtMillis: Long, id: String? = null): List<Note> {
        require(text.isNotBlank()) { "备忘内容为空" }
        require(text.length <= MAX_NOTE_LENGTH) { "备忘最多 $MAX_NOTE_LENGTH 字，请缩短后保存" }
        val current = all(prefs)
        val proposed = id ?: legacyId(text, createdAtMillis).let { base ->
            if (current.none { it.id == base }) base else "note-${UUID.randomUUID()}"
        }
        require(proposed.isNotBlank() && proposed.length <= 150)
        current.firstOrNull { it.id == proposed }?.let {
            check(it.text == text) { "分享标识已用于另一条备忘" }
            return current
        }
        val updated = (listOf(Note(text, createdAtMillis, proposed)) + current).take(MAX_NOTES)
        persist(prefs, updated)
        return updated
    }

    @Synchronized
    internal fun setCompleted(prefs: SharedPreferences, id: String, completed: Boolean): Boolean {
        val current = all(prefs)
        val note = current.firstOrNull { it.id == id } ?: return false
        if (note.completed == completed) return true
        persist(prefs, current.map { if (it.id == id) it.copy(completed = completed, updatedAtMillis = System.currentTimeMillis()) else it })
        return true
    }

    @Synchronized
    internal fun delete(prefs: SharedPreferences, id: String): Boolean {
        val current = all(prefs)
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        persist(prefs, next)
        return true
    }

    @Synchronized
    internal fun all(prefs: SharedPreferences): List<Note> {
        prefs.getString(KEY_VERSIONED_NOTES, null)?.let { raw ->
            check(raw.length <= MAX_SERIALIZED_CHARS) { "备忘数据超出读取上限" }
            val root = JSONObject(raw)
            check(root.optInt("version") == 2) { "备忘数据版本不受支持" }
            val rows = root.getJSONArray("notes")
            check(rows.length() <= MAX_NOTES)
            return (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                Note(row.getString("text"), row.getLong("createdAtMillis"), row.getString("id"),
                    row.optBoolean("completed"), row.optLong("updatedAtMillis", row.getLong("createdAtMillis"))).also {
                    check(it.text.length <= MAX_NOTE_LENGTH && it.id.isNotBlank() && it.id.length <= 150)
                }
            }
        }
        val legacy = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        check(legacy.length <= MAX_SERIALIZED_CHARS) { "备忘数据超出读取上限" }
        val migrated = decodeLegacy(legacy)
        persist(prefs, migrated)
        return migrated
    }

    private fun persist(prefs: SharedPreferences, notes: List<Note>) {
        val rows = JSONArray().apply {
            notes.forEach { note -> put(JSONObject().put("id", note.id).put("text", note.text)
                .put("createdAtMillis", note.createdAtMillis).put("updatedAtMillis", note.updatedAtMillis)
                .put("completed", note.completed)) }
        }
        // Commit the new version before retiring legacy state in the very same preferences transaction.
        check(prefs.edit().putString(KEY_VERSIONED_NOTES, JSONObject().put("version", 2).put("notes", rows).toString())
            .remove(KEY_NOTES).commit()) { "备忘保存失败，请重试" }
    }

    /** Legacy encoder retained for import/migration tests; new writes use the versioned representation. */
    internal fun encode(notes: List<Note>): String = notes.take(MAX_NOTES).joinToString("\u001e") { note ->
        buildString { note.text.forEach { ch -> if (ch == '\\' || ch == '\u001e' || ch == '\u001f') append('\\'); append(ch) } } + "\u001f" + note.createdAtMillis
    }

    private fun decodeLegacy(raw: String): List<Note> {
        if (raw.isEmpty()) return emptyList()
        val rows = mutableListOf<Note>()
        val field = StringBuilder()
        var text: String? = null
        var escaped = false
        fun finish() {
            val body = text ?: error("旧备忘数据缺少分隔符，原数据已保留")
            val at = field.toString().toLongOrNull() ?: error("旧备忘时间格式异常，原数据已保留")
            check(body.isNotEmpty() && body.length <= MAX_NOTE_LENGTH && rows.size < MAX_NOTES)
            var stable = legacyId(body, at)
            if (rows.any { it.id == stable }) stable += "-${rows.size}"
            rows += Note(body, at, stable)
            field.setLength(0)
            text = null
        }
        raw.forEach { ch ->
            when {
                escaped -> { field.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == '\u001f' && text == null -> { text = field.toString(); field.setLength(0) }
                ch == '\u001e' -> finish()
                else -> field.append(ch)
            }
        }
        if (escaped) field.append('\\')
        if (field.isNotEmpty() || text != null) finish()
        return rows
    }

    private fun legacyId(text: String, createdAtMillis: Long): String =
        "note-" + UUID.nameUUIDFromBytes((createdAtMillis.toString() + "\u0000" + text).toByteArray(Charsets.UTF_8))
}
