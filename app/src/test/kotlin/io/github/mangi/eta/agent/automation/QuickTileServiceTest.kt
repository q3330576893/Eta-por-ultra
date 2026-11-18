package io.github.mangi.eta.agent.automation

import io.github.mangi.eta.agent.quickactions.DailyIntent
import io.github.mangi.eta.agent.quickactions.QuickTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class QuickTileServiceTest {

    @Test
    fun defaultIntentsAllResolveStrictly() {
        assertTrue(QuickTileService.DEFAULT_INTENTS.isNotEmpty())
        QuickTileService.DEFAULT_INTENTS.forEach { raw ->
            assertTrue("'$raw' 应严格匹配", QuickTileService.resolveIntent(raw) != null)
        }
    }

    @Test
    fun compoundAndNegatedSentencesAreRejected() {
        val unsafe = listOf(
            "打开手电筒然后查天气",
            "设个闹钟并且查天气",
            "不要静音",
            "10点提醒我开会然后打开微信",
            "查询上海天气和北京天气",
            "随便一句没有匹配的话"
        )
        unsafe.forEach { assertNull("'$it' 必须被拒绝", QuickTileService.resolveIntent(it)) }
    }

    @Test
    fun handoffIntentsAreNotTileSafe() {
        // 这些都被 DailyIntentRouter 严格识别，但属于需要二次交互/system handoff，不进 tile。
        assertFalse(QuickTileService.isTileSafe(DailyIntent.AlarmSetup))
        assertFalse(QuickTileService.isTileSafe(DailyIntent.SetAlarm(7, 30)))
        assertFalse(QuickTileService.isTileSafe(DailyIntent.SetTimer(60)))
        assertFalse(QuickTileService.isTileSafe(DailyIntent.NavigateAction("西湖")))
        assertFalse(QuickTileService.isTileSafe(DailyIntent.OpenAppAction("微信")))
        assertFalse(QuickTileService.isTileSafe(DailyIntent.CalcFollowUp(
            io.github.mangi.eta.agent.quickactions.CalcFollowOp.ADD, "5"
        )))
    }

    @Test
    fun weatherChooseLocationIsNotTileSafeButPlainWeatherIs() {
        assertFalse(QuickTileService.isTileSafe(DailyIntent.Weather(chooseLocation = true)))
        assertTrue(QuickTileService.isTileSafe(DailyIntent.Weather()))
    }

    @Test
    fun safeIntentsRemainTileEligible() {
        assertTrue(QuickTileService.isTileSafe(DailyIntent.TimeNow))
        assertTrue(QuickTileService.isTileSafe(DailyIntent.BatteryStatus))
        assertTrue(QuickTileService.isTileSafe(DailyIntent.FlashlightAction(true)))
        assertTrue(QuickTileService.isTileSafe(DailyIntent.ViewNotes))
    }

    @Test
    fun maxTilesIsThreeToFour() {
        assertTrue(QuickTileService.MAX_TILES in 3..4)
        assertEquals(4, QuickTileService.MAX_TILES)
    }
}
