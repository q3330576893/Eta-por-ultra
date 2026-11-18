package io.github.mangi.eta.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Parcelable
import org.json.JSONObject

/**
 * NFC 触碰触发任务规则。
 *
 * - 解析 NDEF text 记录与 tagId，经 [AgentTaskScheduler.publish] 投递 `nfc_tapped` 事件。
 * - tagId 精确匹配、长度受限（[MAX_FIELD] = 200，与 AgentTaskRules 过滤值上限一致）。
 * - 无 NFC 设备：Manifest 用 uses-feature required=false，运行时 [hasNfc] 为 false 时静默返回，不报错。
 * - 不宣称锁屏常驻后台读取；仅在系统前台分发 NDEF/TAG_DISCOVERED 广播时工作。
 */
internal class NfcTagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED && action != NfcAdapter.ACTION_TAG_DISCOVERED) return
        if (!hasNfc(context)) return
        val tag = runCatching { intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) }.getOrNull() ?: return
        val tagId = formatTagId(tag.id).take(MAX_FIELD)
        val ndefText = extractNdefText(intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES))
            .take(MAX_FIELD)
        if (tagId.isEmpty() && ndefText.isEmpty()) return
        val data = JSONObject().put("tagId", tagId).put("ndefText", ndefText)
        AgentTaskScheduler.publish(context.applicationContext, EVENT_TYPE, tagId.ifEmpty { ndefText }, data)
    }

    companion object {
        internal const val EVENT_TYPE = "nfc_tapped"
        internal const val MAX_FIELD = 200

        internal fun hasNfc(context: Context): Boolean =
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)

        /** 大写入 hex，大小写/前导零稳定，供规则精确匹配。 */
        internal fun formatTagId(bytes: ByteArray?): String {
            if (bytes == null) return ""
            val hex = "0123456789ABCDEF"
            val out = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                out.append(hex[value shr 4]).append(hex[value and 0x0F])
            }
            return out.toString()
        }

        /** 只取 well-known RTD_TEXT 记录，拼接可见文本；其它类型不进入过滤字段。 */
        internal fun extractNdefText(messages: Array<out Parcelable?>?): String {
            if (messages.isNullOrEmpty()) return ""
            return messages.mapNotNull { it as? NdefMessage }
                .flatMap { it.records.toList() }
                .filter { record ->
                    record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                        record.type.contentEquals(NdefRecord.RTD_TEXT)
                }
                .map(::decodeTextRecord)
                .filter(String::isNotBlank)
                .joinToString(" ")
        }

        /** NDEF text record = 1 字节状态位（bit7 编码、bit0-5 语言码长度）+ 语言码 + 正文。 */
        internal fun decodeTextRecord(record: NdefRecord): String {
            val payload = record.payload
            if (payload.size < 2) return ""
            val utf8 = (payload[0].toInt() and 0x80) == 0
            val languageLength = payload[0].toInt() and 0x3F
            val start = 1 + languageLength
            if (start > payload.size) return ""
            val body = payload.copyOfRange(start, payload.size)
            return runCatching {
                if (utf8) String(body, Charsets.UTF_8) else String(body, Charsets.UTF_16BE)
            }.getOrDefault("")
        }
    }
}
