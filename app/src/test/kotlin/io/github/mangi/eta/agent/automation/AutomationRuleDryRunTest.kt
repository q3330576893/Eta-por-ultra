package io.github.mangi.eta.agent.automation

import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationRuleDryRunTest {
    private val now = Instant.parse("2026-11-19T01:00:00Z").toEpochMilli()
    private fun snapshot(vararg entries: Pair<String, Any?>) = DryRunSnapshot(now,
        entries.associate { (name, value) -> name to DryRunValue(value, if (value == null) "permission unavailable" else "sample") })

    @Test fun implicitAndUsesExistingConditionsMatcherAndReportsEachBoolean() {
        val rule = JSONObject("""{"type":"charging_connected","conditions":{"charging":true,"unlocked":true,"networkConnected":false}}""")
        val state = snapshot("charging" to true, "unlocked" to true, "networkConnected" to false)
        val result = AutomationRuleDryRun.evaluate(rule, state)
        assertTrue(result.valid)
        assertEquals(DryRunTruth.TRUE, result.triggered)
        assertEquals(3, result.checks.count { it.name.startsWith("conditions.") && it.truth == DryRunTruth.TRUE })
        assertTrue(AgentTaskRules.conditionsMatch(AgentTaskRules.validate(rule), mapOf("charging" to true, "unlocked" to true, "networkConnected" to false)))
    }

    @Test fun oneFalseConditionProducesFalseWithSpecificReason() {
        val rule = JSONObject("""{"type":"charging_connected","conditions":{"charging":true,"unlocked":true}}""")
        val result = AutomationRuleDryRun.evaluate(rule, snapshot("charging" to true, "unlocked" to false))
        assertEquals(DryRunTruth.FALSE, result.triggered)
        val failed = result.checks.single { it.name == "conditions.unlocked" }
        assertEquals(DryRunTruth.FALSE, failed.truth)
        assertTrue(failed.reason.contains("false"))
        assertTrue(failed.reason.contains("true"))
    }

    @Test fun permissionUnknownIsPreservedEvenWhenExpectedFalse() {
        val rule = JSONObject("""{"type":"charging_connected","conditions":{"networkConnected":false}}""")
        val result = AutomationRuleDryRun.evaluate(rule, snapshot("charging" to true, "networkConnected" to null))
        assertEquals(DryRunTruth.UNKNOWN, result.triggered)
        val condition = result.checks.single { it.name == "conditions.networkConnected" }
        assertEquals(DryRunTruth.UNKNOWN, condition.truth)
        assertTrue(condition.reason.contains("permission"))
    }

    @Test fun knownFalseDominatesOtherUnknownInAnd() {
        val rule = JSONObject("""{"type":"charging_connected","conditions":{"unlocked":true,"networkConnected":true}}""")
        val result = AutomationRuleDryRun.evaluate(rule, snapshot("charging" to true, "unlocked" to false, "networkConnected" to null))
        assertEquals(DryRunTruth.FALSE, result.triggered)
        assertTrue(result.checks.any { it.truth == DryRunTruth.UNKNOWN })
    }

    @Test fun currentAppRequiresAnObservedPackageAndExactFilter() {
        val rule = JSONObject("""{"type":"app_foreground","filters":{"packageName":"com.example"}}""")
        assertEquals(DryRunTruth.TRUE, AutomationRuleDryRun.evaluate(rule, snapshot("currentApp" to "com.example")).triggered)
        assertEquals(DryRunTruth.FALSE, AutomationRuleDryRun.evaluate(rule, snapshot("currentApp" to "com.example.other")).triggered)
        assertEquals(DryRunTruth.UNKNOWN, AutomationRuleDryRun.evaluate(rule, snapshot("currentApp" to null)).triggered)
    }

    @Test fun chargingUnlockNetworkAndTimeUseCurrentSnapshot() {
        assertEquals(DryRunTruth.TRUE, AutomationRuleDryRun.evaluate(JSONObject("""{"type":"device_unlocked"}"""), snapshot("unlocked" to true)).triggered)
        assertEquals(DryRunTruth.FALSE, AutomationRuleDryRun.evaluate(JSONObject("""{"type":"network_connected"}"""), snapshot("networkConnected" to false)).triggered)
        assertEquals(DryRunTruth.UNKNOWN, AutomationRuleDryRun.evaluate(JSONObject("""{"type":"network_connected"}"""), snapshot("networkConnected" to null)).triggered)
        val daily = JSONObject("""{"type":"daily","time":"09:00","timeZone":"Asia/Shanghai","weekdays":[4]}""")
        assertEquals(DryRunTruth.TRUE, AutomationRuleDryRun.evaluate(daily, snapshot()).triggered)
        assertEquals(DryRunTruth.FALSE, AutomationRuleDryRun.evaluate(JSONObject(daily.toString()).put("time", "10:00"), snapshot()).triggered)
        val interval = JSONObject("""{"type":"interval","seconds":900}""")
        assertEquals(DryRunTruth.UNKNOWN, AutomationRuleDryRun.evaluate(interval, snapshot()).triggered)
        assertEquals(DryRunTruth.FALSE, AutomationRuleDryRun.evaluate(interval, snapshot(), nextRunAt = now + 1).triggered)
    }

    @Test fun networkTransportMissingIsUnknownRatherThanPretendingMismatch() {
        val rule = JSONObject("""{"type":"network_connected","filters":{"transport":"wifi"}}""")
        val result = AutomationRuleDryRun.evaluate(rule, snapshot("networkConnected" to true, "transport" to null))
        assertEquals(DryRunTruth.UNKNOWN, result.triggered)
    }

    @Test fun unsupportedTypeAndAndSyntaxUseLegacyValidationErrors() {
        val unsupported = AutomationRuleDryRun.evaluate(JSONObject("""{"type":"location_enter"}"""), snapshot())
        assertFalse(unsupported.valid)
        assertTrue(unsupported.checks.single().reason.isNotBlank())
        val unsupportedAnd = AutomationRuleDryRun.evaluate(JSONObject("""{"type":"device_unlocked","conditions":{"and":[{"unlocked":true}]}}"""), snapshot())
        assertFalse(unsupportedAnd.valid)
    }

    @Test fun explicitNfcSampleUsesExactMatchesWithoutPublishingAndLeavesInputUntouched() {
        val rule = JSONObject("""{"type":"nfc_tapped","filters":{"tagId":"00:a1","ndefText":"home"}}""")
        val before = rule.toString()
        val result = AutomationRuleDryRun.evaluate(rule, snapshot(), JSONObject("""{"type":"nfc_tapped","tagId":"00A1","ndefText":"HOME"}"""))
        assertEquals(DryRunTruth.TRUE, result.triggered)
        assertEquals(before, rule.toString())
        assertEquals(DryRunTruth.UNKNOWN, AutomationRuleDryRun.evaluate(rule, snapshot()).triggered)
    }

    @Test fun existingTriggerCatalogRemainsAcceptedByValidation() {
        assertTrue(AgentTaskRules.eventTypes.size >= 17)
        AgentTaskRules.eventTypes.forEach { type ->
            val raw = JSONObject().put("type", type)
            if (type == "nfc_tapped") raw.put("filters", JSONObject().put("tagId", "00A1"))
            assertTrue(AutomationRuleDryRun.evaluate(raw, snapshot()).valid)
        }
    }

    @Test fun dryRunDoesNotPublishRunModelOrMutateTasks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = EtaDatabase.get(context).agentTaskDao()
        val task = AgentTaskEntity(id = "dryrun-side-effect-test", name = "test", prompt = "model must remain idle",
            triggerJson = "{\"type\":\"device_unlocked\"}", enabled = true, nextRunAt = null,
            cooldownSeconds = 60, maxRuns = 1, createdAt = 0, updatedAt = 0)
        dao.insertTask(task)
        try {
            val before = dao.task(task.id)
            repeat(3) {
                AutomationRuleDryRun.evaluate(JSONObject(task.triggerJson), AutomationRuleStateSampler.sample(context))
            }
            assertEquals(before, dao.task(task.id))
            assertTrue(dao.runs(task.id, 10).isEmpty())
            val destination = AutomationRuleDryRunActivity.intent(context, task)
            assertEquals(AutomationRuleDryRunActivity::class.java.name, destination.component?.className)
        } finally { dao.remove(task.id) }
    }
}
