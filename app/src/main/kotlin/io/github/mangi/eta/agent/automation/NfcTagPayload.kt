package io.github.mangi.eta.agent.automation

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Parcelable
import org.json.JSONObject

/** Values from dispatch are display-only. Only a foreground ReaderCallback may publish a scan. */
internal data class NfcTagPayload(val tagId: String, val ndefText: String) {
    fun event(): JSONObject = JSONObject().put("tagId", tagId).put("ndefText", ndefText.take(MAX_FIELD))

    companion object {
        const val MAX_FIELD = 200
        val dispatchActions = setOf(
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
        )

        fun normalizeTagId(raw: String): String? {
            val value = raw.trim().replace(Regex("[:\\-\\s]"), "").uppercase(java.util.Locale.ROOT)
            return value.takeIf { it.length in 2..MAX_FIELD && it.length % 2 == 0 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
        }

        fun formatTagId(bytes: ByteArray?): String {
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_FIELD / 2) return ""
            val hex = "0123456789ABCDEF"
            return buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 255
                    append(hex[value shr 4]); append(hex[value and 15])
                }
            }
        }

        @Suppress("DEPRECATION")
        fun fromIntent(intent: Intent): NfcTagPayload? {
            if (intent.action !in dispatchActions) return null
            return runCatching {
                val tag = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                } else intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                val tagId = formatTagId(tag?.id)
                if (tagId.isEmpty()) return null
                val messages = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                } else intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                NfcTagPayload(tagId, extractNdefText(messages))
            }.getOrNull()
        }

        fun extractNdefText(messages: Array<out Parcelable?>?): String = buildString {
            messages.orEmpty().filterIsInstance<NdefMessage>().forEach { message ->
                message.records.forEach { record ->
                    if (length < MAX_FIELD) {
                        val text = when {
                            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> decodeTextRecord(record)
                            record.tnf == NdefRecord.TNF_MIME_MEDIA && String(record.type, Charsets.US_ASCII).equals("text/plain", true) ->
                                String(record.payload.take(1600).toByteArray(), Charsets.UTF_8)
                            else -> ""
                        }
                        if (text.isNotBlank()) {
                            if (isNotEmpty()) append(' ')
                            append(text.take((MAX_FIELD - length).coerceAtLeast(0)))
                        }
                    }
                }
            }
        }.take(MAX_FIELD)

        fun decodeTextRecord(record: NdefRecord): String {
            val payload = record.payload
            if (payload.isEmpty()) return ""
            val start = 1 + (payload[0].toInt() and 63)
            if (start > payload.size) return ""
            val charset = if ((payload[0].toInt() and 128) == 0) Charsets.UTF_8 else Charsets.UTF_16
            return runCatching { String(payload.copyOfRange(start, minOf(payload.size, start + 1600)), charset).take(MAX_FIELD) }.getOrDefault("")
        }
    }
}

/** Process-local monotonic debounce shared by all NFC activities; distinct cards remain independent. */
internal class NfcScanDebouncer(private val windowMs: Long = 2000) {
    private val seen = LinkedHashMap<String, Long>()
    @Synchronized fun accept(tagId: String, now: Long): Boolean {
        val previous = seen[tagId]
        if (previous != null && now >= previous && now - previous < windowMs) return false
        seen[tagId] = now
        while (seen.size > 100) seen.remove(seen.keys.first())
        return true
    }
}
