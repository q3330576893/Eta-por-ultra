package io.github.mangi.eta.agent.automation

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class NfcTagReceiverTest {

    @Test
    fun tagIdIsUpperCaseHexAndLengthLimited() {
        assertEquals("00A1B2FF", NfcTagReceiver.formatTagId(byteArrayOf(0x00, -0x5F, -0x4E, -0x01)))
        assertEquals("", NfcTagReceiver.formatTagId(null))
        val long = ByteArray(300) { 0xAB.toByte() }
        // Reject overlong IDs rather than truncating two distinct cards into one identity.
        assertEquals("", NfcTagReceiver.formatTagId(long))
    }

    @Test
    fun ndefTextRecordDecodesUtf8AndUtf16() {
        val utf8 = NdefRecord.createTextRecord("zh", "回家")
        assertEquals("回家", NfcTagReceiver.decodeTextRecord(utf8))
        // 手工构造 UTF-16 状态位（bit7=1）+ 语言码长度
        val body = "home".toByteArray(Charsets.UTF_16BE)
        val payload = ByteArray(1 + 2 + body.size).also {
            it[0] = (0x80 or 2).toByte() // UTF-16, "en" 长度 2
            it[1] = 'e'.code.toByte(); it[2] = 'n'.code.toByte()
            System.arraycopy(body, 0, it, 3, body.size)
        }
        val utf16 = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, null, payload)
        assertEquals("home", NfcTagReceiver.decodeTextRecord(utf16))
    }

    @Test
    fun extractNdefTextIgnoresNonTextRecordsAndJoinsMultiple() {
        val text1 = NdefRecord.createTextRecord("zh", "客厅")
        val text2 = NdefRecord.createTextRecord("en", "home")
        val uri = NdefRecord.createUri("https://example.com")
        val messages = arrayOf(NdefMessage(text1, uri), NdefMessage(text2))
        assertEquals("客厅 home", NfcTagReceiver.extractNdefText(messages))
        assertEquals("", NfcTagReceiver.extractNdefText(null))
    }

    @Test
    fun nfcTappedRuleAcceptsTagIdAndNdefTextFilters() {
        val rule = AgentTaskRules.validate(
            JSONObject().put("type", "nfc_tapped").put(
                "filters",
                JSONObject().put("tagId", "00A1B2FF").put("ndefText", "home")
            )
        )
        val event = JSONObject().put("tagId", "00A1B2FF").put("ndefText", "Welcome HOME")
        assertTrue(AgentTaskRules.matches(rule, "nfc_tapped", event))
        // tagId 精确匹配：多一个字节即失配
        assertFalse(
            AgentTaskRules.matches(
                rule, "nfc_tapped",
                JSONObject().put("tagId", "00A1B2FFAA").put("ndefText", "home")
            )
        )
        // ndefText 为包含匹配，但 tagId 仍要精确
        assertFalse(
            AgentTaskRules.matches(
                rule, "nfc_tapped",
                JSONObject().put("tagId", "00A1B2FF").put("ndefText", "office")
            )
        )
    }

    @Test
    fun nfcTappedRejectsOverlongOrUnsupportedFilters() {
        val overlong = JSONObject().put("type", "nfc_tapped").put(
            "filters", JSONObject().put("tagId", "A".repeat(NfcTagReceiver.MAX_FIELD + 1))
        )
        val unsupported = JSONObject().put("type", "nfc_tapped").put(
            "filters", JSONObject().put("packageName", "com.example")
        )
        assertThrows(Exception::class.java) { AgentTaskRules.validate(overlong) }
        assertThrows(Exception::class.java) { AgentTaskRules.validate(unsupported) }
    }
}
