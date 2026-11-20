package io.github.mangi.eta.agent.automation

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.mangi.eta.agent.quickactions.DailyIntent
import io.github.mangi.eta.agent.quickactions.DailyIntentRouter
import io.github.mangi.eta.agent.quickactions.WeatherWidgetProvider
import io.github.mangi.eta.agent.quickactions.WeatherWidgetSettingsActivity
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RetiredFeaturesTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test fun retiredActivitiesServicesAndReceiversAreNotRegistered() {
        val manager = context.packageManager
        val prefix = "io.github.mangi.eta."
        listOf("agent.notes.MemoInboxActivity", "agent.knowledge.KnowledgeLibraryActivity",
            "agent.knowledge.KnowledgeSourceActivity", "agent.automation.NfcBindingActivity",
            "agent.automation.NfcTagActivity", "agent.automation.AutomationRuleDryRunActivity",
            "agent.quickactions.QuickSurfacesSettingsActivity").forEach { name ->
            assertThrows(name, PackageManager.NameNotFoundException::class.java) {
                manager.getActivityInfo(ComponentName(context.packageName, prefix + name), 0)
            }
        }
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            manager.getServiceInfo(ComponentName(context.packageName, prefix + "agent.quickactions.QuickTileService"), 0)
        }
        listOf("agent.notes.MemoWidgetProvider", "agent.automation.NfcTagReceiver").forEach { name ->
            assertThrows(name, PackageManager.NameNotFoundException::class.java) {
                manager.getReceiverInfo(ComponentName(context.packageName, prefix + name), 0)
            }
        }
    }

    @Test fun weatherWidgetAndItsDedicatedSettingsRemainRegistered() {
        val manager = context.packageManager
        val intent = WeatherWidgetSettingsActivity.intent(context)
        assertEquals(WeatherWidgetSettingsActivity::class.java.name, intent.component?.className)
        assertFalse(manager.getActivityInfo(requireNotNull(intent.component), 0).exported)
        assertEquals(WeatherWidgetProvider::class.java.name,
            manager.getReceiverInfo(ComponentName(context, WeatherWidgetProvider::class.java), 0).name)
        assertTrue(DailyIntentRouter.parse("明天天气") is DailyIntent.Weather)
        assertTrue(DailyIntentRouter.parse("北京天气") is DailyIntent.Weather)
        assertTrue(DailyIntentRouter.parse("更换地点") is DailyIntent.Weather)
    }

    @Test fun localMemoAliasesNoLongerReachTheDeterministicExecutor() {
        listOf("记一下明天开会", "备忘买牛奶", "查看备忘", "我的备忘录").forEach {
            assertNull(it, DailyIntentRouter.parse(it))
        }
    }

    @Test fun retiredNfcRulesCannotBeCreatedMatchedOrPublished() {
        val rule = JSONObject("""{"type":"nfc_tapped","filters":{"tagId":"00A1"}}""")
        assertFalse("nfc_tapped" in AgentTaskRules.eventTypes)
        assertThrows(IllegalArgumentException::class.java) { AgentTaskRules.validate(rule) }
        assertFalse(AgentTaskRules.matches(rule, "nfc_tapped", JSONObject("""{"tagId":"00A1"}""")))
        var queued: Boolean? = null
        AgentTaskScheduler.publish(context, "nfc_tapped", "retired", JSONObject()) { queued = it }
        assertEquals(false, queued)
    }

    @Test fun upgradeAndRestoreRemoveOnlyRetiredNfcTasksAndTheirQueuedRuns() = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val nfc = task("retired", """{"type":"nfc_tapped","filters":{"tagId":"00A1"}}""")
        val daily = task("daily", """{"type":"daily","time":"09:00","timeZone":"Asia/Shanghai"}""")
            .copy(prompt = "normal task mentioning nfc_tapped stays intact")
        val event = task("battery", """{"type":"battery_low"}""")
        listOf(nfc, daily, event).forEach { dao.insertTask(it) }
        val oldRun = AgentTaskRunEntity("retired-run", nfc.id, "nfc_tapped:fixture",
            eventJson = """{"type":"nfc_tapped","tagId":"00A1"}""", queuedAt = 1)
        val normalRun = AgentTaskRunEntity("normal-run", daily.id, "time:fixture", queuedAt = 2)
        dao.insertRun(oldRun)
        dao.insertRun(normalRun)
        assertEquals(1, AgentTaskScheduler.retireRemovedNfcTasks(context))
        assertNull(dao.task(nfc.id))
        assertTrue(dao.runs(nfc.id, 10).isEmpty())
        assertEquals(daily, dao.task(daily.id))
        assertEquals(event, dao.task(event.id))
        assertEquals(normalRun, dao.runs(daily.id, 10).single())
        assertEquals(0, AgentTaskScheduler.retireRemovedNfcTasks(context))
        // A subsequently restored legacy rule is retired again; no one-shot migration marker bypasses it.
        dao.insertTask(nfc)
        assertEquals(1, AgentTaskScheduler.retireRemovedNfcTasks(context))
        assertEquals(setOf(daily.id, event.id), dao.tasks().map { it.id }.toSet())
    }

    @Test fun reconfiguredParentCannotCarryRetiredNfcRunsIntoTheModelQueue() = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val daily = task("reconfigured", """{"type":"daily","time":"09:00","timeZone":"Asia/Shanghai"}""")
        dao.insertTask(daily)
        val nfc = AgentTaskRunEntity("old-nfc", daily.id, "nfc_tapped:fixture",
            eventJson = """{"type":"nfc_tapped","tagId":"00A1","ndefText":"fixture"}""", queuedAt = 1)
        val legacyKeyOnly = nfc.copy(id = "old-key-only", fireKey = "nfc_tapped:legacy", eventJson = "{}")
        val payloadOnly = nfc.copy(id = "old-payload-only", fireKey = "restored:fixture")
        val normal = AgentTaskRunEntity("normal", daily.id, "time:fixture", queuedAt = 2)
        val history = nfc.copy(id = "completed-history", fireKey = "nfc_tapped:completed", status = "completed", finishedAt = 2)
        listOf(nfc, legacyKeyOnly, payloadOnly, normal, history).forEach { assertTrue(dao.insertRun(it) != -1L) }
        assertTrue(AgentTaskScheduler.isRetiredNfcRun(nfc))
        assertTrue(AgentTaskScheduler.isRetiredNfcRun(legacyKeyOnly))
        assertTrue(AgentTaskScheduler.isRetiredNfcRun(payloadOnly))
        assertFalse(AgentTaskScheduler.isRetiredNfcRun(normal))
        assertEquals(0, AgentTaskScheduler.retireRemovedNfcTasks(context))
        assertEquals(daily, dao.task(daily.id))
        assertEquals(normal, dao.nextQueued())
        assertEquals(listOf(normal), dao.activeTaskRuns())
        assertEquals(setOf(normal.id, history.id), dao.runs(daily.id, 10).map { it.id }.toSet())
    }

    @Test fun recoveringRetiredRunningNfcDoesNotConsumeAnOrdinaryTasksRunBudget() = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val protectedTask = task("protected-daily", """{"type":"daily","time":"09:00","timeZone":"Asia/Shanghai"}""")
            .copy(maxRuns = 1, lastStartedAt = 1, lastStatus = "queued")
        val ordinary = task("ordinary-running", """{"type":"battery_low"}""")
        listOf(protectedTask, ordinary).forEach { dao.insertTask(it) }
        val removed = AgentTaskRunEntity("retired-running", protectedTask.id, "nfc_tapped:running", status = "running",
            eventJson = """{"type":"nfc_tapped","tagId":"00A1"}""", queuedAt = 1, startedAt = 2)
        val normal = AgentTaskRunEntity("ordinary-run", ordinary.id, "time:running", status = "running", queuedAt = 1, startedAt = 2)
        listOf(removed, normal).forEach { assertTrue(dao.insertRun(it) != -1L) }
        AgentTaskScheduler.recoverAfterUpgrade(context, now = 3)
        val restored = requireNotNull(dao.task(protectedTask.id))
        assertEquals(0, restored.runCount)
        assertTrue(restored.enabled)
        assertEquals("ready", restored.lastStatus)
        assertNull(restored.lastStartedAt)
        assertTrue(dao.runs(protectedTask.id, 10).isEmpty())
        assertEquals(1, dao.task(ordinary.id)?.runCount)
        assertEquals("interrupted", dao.runs(ordinary.id, 10).single().status)
        assertTrue(dao.activeTaskRuns().isEmpty())
    }

    @Test fun retiredQueuedSummaryRestoresOnlyTheLastOrdinaryHistory() = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        for (withHistory in listOf(false, true)) {
            val id = "summary-$withHistory"
            val current = task(id, """{"type":"battery_low"}""").copy(
                lastStartedAt = 20, lastStatus = "queued", runCount = if (withHistory) 1 else 0,
            )
            dao.insertTask(current)
            if (withHistory) assertTrue(dao.insertRun(AgentTaskRunEntity("history-$id", id, "time:history",
                status = "completed", queuedAt = 10, startedAt = 11, finishedAt = 12)) != -1L)
            assertTrue(dao.insertRun(AgentTaskRunEntity("removed-$id", id, "nfc_tapped:$id",
                eventJson = """{"type":"nfc_tapped"}""", queuedAt = 20)) != -1L)
            AgentTaskScheduler.retireRemovedNfcTasks(context)
            val restored = requireNotNull(dao.task(id))
            assertEquals(current.runCount, restored.runCount)
            assertEquals(current.enabled, restored.enabled)
            assertEquals(if (withHistory) "completed" else "ready", restored.lastStatus)
            assertEquals(if (withHistory) 10L else null, restored.lastStartedAt)
        }
    }

    @Test fun legacyNfcTaskCannotBeManuallyQueuedBeforeUpgradeCleanup() = runBlocking {
        val dao = EtaDatabase.get(context).agentTaskDao()
        val old = task("manual-retired", """{"type":"nfc_tapped","filters":{"tagId":"00A1"}}""")
        dao.insertTask(old)
        assertFalse(AgentTaskScheduler.enqueueManual(context, old))
        assertTrue(dao.runs(old.id, 10).isEmpty())
    }

    private fun task(id: String, rule: String) = AgentTaskEntity(
        id = id, name = id, prompt = "fixture", triggerJson = rule, enabled = true,
        nextRunAt = null, cooldownSeconds = 60, maxRuns = 5, createdAt = 1, updatedAt = 1,
    )
}
