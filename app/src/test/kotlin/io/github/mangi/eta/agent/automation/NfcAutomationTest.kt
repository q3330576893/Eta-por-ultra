package io.github.mangi.eta.agent.automation

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [33])
class NfcAutomationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val dao get() = EtaDatabase.get(context).agentTaskDao()

    @After fun cleanup() = runBlocking {
        listOf("nfc-exact-test", "nfc-other-test", "nfc-empty-test").forEach { dao.remove(it) }
        AgentTaskScheduler.jobFinished()
    }

    @Test fun typedParcelableIntentRoutesAllThreeActionsAndLimitsPayload() {
        NfcTagPayload.dispatchActions.forEach { action ->
            val payload = NfcTagPayload.fromIntent(dispatch(action))!!
            assertEquals("00A1", payload.tagId)
            assertEquals(200, payload.ndefText.length)
        }
        assertNull(NfcTagPayload.fromIntent(Intent("forged.action")))
        assertNull(NfcTagPayload.fromIntent(Intent(NfcAdapter.ACTION_TAG_DISCOVERED)))
    }

    @Test fun mimeTextPlainIsReadAndNonTextRecordsAreIgnored() {
        val mime = NdefRecord.createMime("text/plain", "home".toByteArray())
        val binary = NdefRecord.createMime("application/octet-stream", "secret".toByteArray())
        assertEquals("home", NfcTagPayload.extractNdefText(arrayOf(NdefMessage(mime, binary))))
    }

    @Test fun exactIdentityNormalizesSeparatorsAndEmptyFiltersFailClosed() {
        val normalized = AgentTaskRules.validate(JSONObject("""{"type":"nfc_tapped","filters":{"tagId":"00:a1"}}"""))
        assertEquals("00A1", normalized.getJSONObject("filters").getString("tagId"))
        assertTrue(AgentTaskRules.matches(normalized, "nfc_tapped", JSONObject("""{"tagId":"00A1"}""")))
        assertFalse(AgentTaskRules.matches(normalized, "nfc_tapped", JSONObject("""{"tagId":"00A100"}""")))
        listOf("{}", "{\"ndefText\":\"home\"}", "{\"tagId\":\"\"}").forEach { filters ->
            val broad = JSONObject("{\"type\":\"nfc_tapped\",\"filters\":$filters}")
            assertThrows(IllegalArgumentException::class.java) { AgentTaskRules.validate(broad) }
            assertFalse(AgentTaskRules.matches(broad, "nfc_tapped", JSONObject("""{"tagId":"00A1","ndefText":"home"}""")))
        }
    }

    @Test fun sameCardDebouncesAndIndependentCardsRemainDistinct() {
        val debounce = NfcScanDebouncer(2000)
        assertTrue(debounce.accept("00A1", 1000))
        assertFalse(debounce.accept("00A1", 1001))
        assertTrue(debounce.accept("00B1", 1001))
        assertFalse(debounce.accept("00A1", 2999))
        assertTrue(debounce.accept("00A1", 3000))
    }

    @Test fun activityDispatchAndRecreationStayDisplayOnlyAndPauseDisarmsReader() {
        val controller = Robolectric.buildActivity(TestNfcBindingActivity::class.java, dispatch(NfcAdapter.ACTION_TAG_DISCOVERED)).create().start().resume()
        val activity = controller.get()
        assertEquals("00A1", activity.dispatchPayload?.tagId)
        val armed = NfcBindingActivity::class.java.getDeclaredField("armed").apply { isAccessible = true }.get(activity) as AtomicBoolean
        armed.set(true)
        controller.pause()
        assertFalse(armed.get())
        activity.onTagDiscovered(mockTag())
        assertNull(NfcBindingActivity::class.java.getDeclaredField("scanned").apply { isAccessible = true }.get(activity))
        controller.stop().destroy()
        val recreated = Robolectric.buildActivity(TestNfcBindingActivity::class.java, dispatch(NfcAdapter.ACTION_NDEF_DISCOVERED)).create().start().resume()
        assertEquals("00A1", recreated.get().dispatchPayload?.tagId)
        assertNull(NfcBindingActivity::class.java.getDeclaredField("scanned").apply { isAccessible = true }.get(recreated.get()))
        recreated.pause().stop().destroy()
    }

    @Test fun realQueueRetainsNfcFieldsAndOnlyExactTaskRunsOnce() = runBlocking {
        dao.insertTask(task("nfc-exact-test", "{\"tagId\":\"00A1\"}"))
        dao.insertTask(task("nfc-other-test", "{\"tagId\":\"00B1\"}"))
        dao.insertTask(task("nfc-empty-test", "{}")) // Upgrade-era broad rule.
        val event = NfcTagPayload("00A1", "home").event().put("source", "foreground_reader")
        assertTrue(publishAndWait("scan-1", event))
        val run = dao.runs("nfc-exact-test", 10).single()
        assertEquals("00A1", JSONObject(run.eventJson).getString("tagId"))
        assertEquals("home", JSONObject(run.eventJson).getString("ndefText"))
        assertFalse(publishAndWait("scan-1", event))
        assertEquals(1, dao.runs("nfc-exact-test", 10).size)
        assertTrue(dao.runs("nfc-other-test", 10).isEmpty())
        assertTrue(dao.runs("nfc-empty-test", 10).isEmpty())
    }

    @Test fun untrustedPublishAndLegacyReceiverNeverQueueTasks() = runBlocking {
        dao.insertTask(task("nfc-exact-test", "{\"tagId\":\"00A1\"}"))
        NfcTagReceiver().onReceive(context, dispatch(NfcAdapter.ACTION_TAG_DISCOVERED))
        assertFalse(publishAndWait("spoof", NfcTagPayload("00A1", "home").event()))
        assertTrue(dao.runs("nfc-exact-test", 10).isEmpty())
    }

    private fun publishAndWait(key: String, data: JSONObject): Boolean {
        val latch = CountDownLatch(1)
        var queued = false
        AgentTaskScheduler.publish(context, "nfc_tapped", key, data) { queued = it; latch.countDown() }
        assertTrue("event DB operation timed out", latch.await(10, TimeUnit.SECONDS))
        return queued
    }

    private fun task(id: String, filters: String) = AgentTaskEntity(
        id = id, name = id, prompt = "test", triggerJson = "{\"type\":\"nfc_tapped\",\"filters\":$filters}",
        enabled = true, nextRunAt = null, cooldownSeconds = 60, maxRuns = 1, createdAt = 0, updatedAt = 0,
    )

    private fun dispatch(action: String) = Intent(action).setType("text/plain")
        .putExtra(NfcAdapter.EXTRA_TAG, mockTag())
        .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefMessage(NdefRecord.createTextRecord("en", "x".repeat(300)))))

    private fun mockTag(): Tag {
        val method = Tag::class.java.getDeclaredMethod("createMockTag", ByteArray::class.java, IntArray::class.java, Array<Bundle>::class.java)
        method.isAccessible = true
        return method.invoke(null, byteArrayOf(0, 0xA1.toByte()), intArrayOf(1), arrayOf(Bundle())) as Tag
    }
}

class TestNfcBindingActivity : NfcBindingActivity() {
    override fun loadTasks() = Unit
}
