package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.agent.quickactions.*
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.time.Instant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class QuickTileServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("eta_quick_tiles", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun defaultsAndLocalActionsResolveStrictly() {
        QuickTileService.DEFAULT_INTENTS.forEach { assertNotNull(QuickTileService.resolveIntent(it)) }
        assertTrue(QuickTileService.isTileSafe(DailyIntent.FlashlightAction(true)))
        assertTrue(QuickTileService.isTileSafe(DailyIntent.ViewNotes))
    }
    @Test fun compoundNegatedAndActivityHandoffsAreDenied() {
        listOf("打开手电筒然后查天气", "不要静音", "查询上海天气和北京天气", "随便一句话")
            .forEach { assertNull(QuickTileService.resolveIntent(it)) }
        listOf(DailyIntent.AlarmSetup, DailyIntent.SetAlarm(7, 30), DailyIntent.SetTimer(60),
            DailyIntent.NavigateAction("西湖"), DailyIntent.OpenAppAction("微信"), DailyIntent.AlarmQuery,
            DailyIntent.SystemPanelAction(SystemPanel.WIFI), DailyIntent.CalcFollowUp(CalcFollowOp.ADD, "5"),
            DailyIntent.Weather(chooseLocation = true)).forEach { assertFalse(QuickTileService.isTileSafe(it)) }
    }
    @Test fun strictConfigurationPreservesOrderAndRejectsWholeInvalidList() {
        assertTrue(QuickTileService.configure(context, listOf("还有多少电", "几点了")))
        assertEquals(listOf("还有多少电", "几点了"), QuickTileService.configuredIntents(context))
        assertFalse(QuickTileService.configure(context, listOf("几点了", "打开微信")))
        assertFalse(QuickTileService.configure(context, List(5) { "几点了" }))
        assertFalse(QuickTileService.configure(context, emptyList()))
        assertEquals(listOf("还有多少电", "几点了"), QuickTileService.configuredIntents(context))
    }
    @Test fun terminalResultReloadsAfterIndexAdvancesAndExpiresAtTwentySeconds() {
        val now = 1_000_000L
        assertTrue(QuickTileService.configure(context, listOf("几点了", "还有多少电")))
        context.getSharedPreferences("eta_quick_tiles", Context.MODE_PRIVATE).edit().putInt("index", 1).commit()
        QuickTileService.saveResult(context, "几点了", "时间 12:30", now)
        assertEquals("还有多少电", QuickTileService.configuredIntents(context)[QuickTileService.currentIndex(context)])
        assertEquals("时间 12:30", QuickTileService.savedResult(context, now + 19_999)?.result)
        assertNull(QuickTileService.savedResult(context, now + 20_000))
        assertNull(QuickTileService.savedResult(context, now - 1))
    }
    @Test fun legacyRunningMarkerNeverSurvivesProcessRestart() {
        QuickTileService.saveResult(context, "几点了", "正在查询…", 1000)
        assertNull(QuickTileService.savedResult(context, 1001))
        assertNull(QuickTileExecutionGate().running())
    }
    @Test fun compactLabelsUseActualStructuredFieldsAndNormalizeWhitespace() {
        val request = DailyIntent.TimeNow
        val answer = DailyCommandResult.ClockAnswer(request, "现在时间", "现在是12点30分。", Instant.EPOCH,
            listOf(DailyResultField("时间", "12:30:42")))
        assertEquals("时间 12:30", QuickTileService.resultTileLabel("几点了", answer))
        val battery = DailyCommandResult.ActionDone(DailyIntent.BatteryStatus, "电量", "还有92%电量", listOf(DailyResultField("电量", "92%")), Instant.EPOCH, true)
        assertEquals("电量 92%", QuickTileService.resultTileLabel("还有多少电", battery))
        assertEquals("查询 结果", QuickTileService.resultTileLabel("天气", "查询\n\t 结果"))
    }
    @Test fun processGateRejectsDuplicatesAndStaleCompletions() {
        val gate = QuickTileExecutionGate()
        val ticket = gate.begin("几点了", listOf("几点了"), 0)!!
        assertNull(gate.begin("几点了", listOf("几点了"), 0))
        gate.invalidate()
        assertFalse(gate.isCurrent(ticket))
        assertNull(gate.begin("还有多少电", listOf("还有多少电"), 0))
        gate.finish(ticket)
        val second = gate.begin("还有多少电", listOf("还有多少电"), 0)!!
        gate.finish(ticket)
        assertTrue(gate.isCurrent(second))
        gate.finish(second)
    }
}
