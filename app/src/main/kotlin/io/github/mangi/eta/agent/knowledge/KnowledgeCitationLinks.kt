package io.github.mangi.eta.agent.knowledge

import android.content.Context
import android.content.Intent
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Internal-only references. They are opened with an explicit component, never ACTION_VIEW. */
internal data class KnowledgeCitationReference(
    val collectionId: String,
    val documentId: String,
    val revision: String,
    val startLine: Int,
    val endLine: Int,
)

internal object KnowledgeCitationLinks {
    const val SCHEME = "eta-kb"
    const val MAX_URI_CHARS = 2048
    const val MAX_LINE = KnowledgeLibraryStore.MAX_DOCUMENT_BYTES + 1
    private val collectionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val documentPattern = Regex("[0-9a-f]{64}")
    private val linePattern = Regex("[1-9][0-9]{0,5}")
    private val keys = setOf("collection_id", "document_id", "revision", "start_line", "end_line")
    private const val HEX = "0123456789ABCDEF"

    fun reference(collectionId: String, documentId: String, revision: String, startLine: Int, endLine: Int): KnowledgeCitationReference? {
        if (!collectionPattern.matches(collectionId) || !documentPattern.matches(documentId) || revision.isBlank() ||
            revision.length > 256 || revision.any { it.isISOControl() || it.isWhitespace() } ||
            !Charsets.UTF_8.newEncoder().canEncode(revision) || encode(revision).length > 1536 ||
            startLine !in 1..MAX_LINE || endLine !in startLine..MAX_LINE) return null
        return KnowledgeCitationReference(collectionId, documentId, revision, startLine, endLine)
    }

    fun build(ref: KnowledgeCitationReference): String {
        require(reference(ref.collectionId, ref.documentId, ref.revision, ref.startLine, ref.endLine) != null)
        return "$SCHEME://source?collection_id=${encode(ref.collectionId)}&document_id=${encode(ref.documentId)}" +
            "&revision=${encode(ref.revision)}&start_line=${ref.startLine}&end_line=${ref.endLine}"
    }

    fun parse(raw: String): KnowledgeCitationReference? {
        if (raw.length > MAX_URI_CHARS || raw.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(raw)
            if (!uri.scheme.equals(SCHEME, true) || uri.isOpaque || uri.rawAuthority != "source" ||
                !uri.rawPath.isNullOrEmpty() || uri.rawFragment != null) return null
            val query = uri.rawQuery ?: return null
            val values = linkedMapOf<String, String>()
            val pairs = query.split('&')
            if (pairs.size != keys.size) return null
            for (pair in pairs) {
                val separator = pair.indexOf('=')
                if (separator < 1) return null
                val key = pair.substring(0, separator)
                if (key !in keys || key in values) return null
                values[key] = decode(pair.substring(separator + 1)) ?: return null
            }
            val start = values.getValue("start_line")
            val end = values.getValue("end_line")
            if (!linePattern.matches(start) || !linePattern.matches(end)) return null
            reference(values.getValue("collection_id"), values.getValue("document_id"), values.getValue("revision"),
                start.toIntOrNull() ?: return null, end.toIntOrNull() ?: return null)
        } catch (_: Exception) { null }
    }

    fun recognizes(raw: String): Boolean = raw.substringBefore(':', "").trim().equals(SCHEME, true)

    fun markdown(filename: String, reference: KnowledgeCitationReference): String {
        val label = filename.take(255).map { if (it.isISOControl()) ' ' else it }.joinToString("").ifBlank { "source" }
        // Code-span labels preserve punctuation literally without allowing filename Markdown to create another link.
        val longestFence = Regex("`+").findAll(label).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(longestFence + 1)
        return "[$fence $label $fence:${reference.startLine}–${reference.endLine}](<${build(reference)}>)"
    }

    fun fromIntent(intent: Intent): KnowledgeCitationReference? = try {
        reference(intent.getStringExtra("collection_id").orEmpty(), intent.getStringExtra("document_id").orEmpty(),
            intent.getStringExtra("revision").orEmpty(), intent.getIntExtra("start_line", -1), intent.getIntExtra("end_line", -1))
    } catch (_: Exception) { null }

    fun intent(context: Context, reference: KnowledgeCitationReference): Intent = KnowledgeSourceActivity.intent(context,
        reference.collectionId, reference.documentId, reference.revision, reference.startLine, reference.endLine)

    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val value = byte.toInt() and 255
            if (unreserved(value)) append(value.toChar()) else {
                append('%'); append(HEX[value ushr 4]); append(HEX[value and 15])
            }
        }
    }

    private fun decode(value: String): String? {
        val bytes = ByteArrayOutputStream(value.length)
        var position = 0
        while (position < value.length) {
            val char = value[position]
            if (char == '%') {
                if (position + 2 >= value.length) return null
                val high = value[position + 1].digitToIntOrNull(16) ?: return null
                val low = value[position + 2].digitToIntOrNull(16) ?: return null
                bytes.write((high shl 4) or low)
                position += 3
            } else {
                if (!unreserved(char.code)) return null
                bytes.write(char.code)
                position++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: Exception) { null }
    }
    private fun unreserved(value: Int): Boolean = value in 65..90 || value in 97..122 || value in 48..57 || value.toChar() in "-._~"
}
