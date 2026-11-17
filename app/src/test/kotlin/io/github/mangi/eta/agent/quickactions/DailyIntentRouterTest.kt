package io.github.mangi.eta.agent.quickactions

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyIntentRouterTest {
    private val now = ZonedDateTime.of(2026, 11, 16, 14, 20, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test fun alarmArabicAndChineseTimesParse() {
        assertEquals(DailyIntent.SetAlarm(7, 30, "起床", emptySet(), LocalDate.of(2026, 11, 17)),
            DailyIntentRouter.parse("明天早上7点半叫我起床", now))
        assertEquals(DailyIntent.SetAlarm(6, 30), DailyIntentRouter.parse("设个6:30闹钟", now))
        assertEquals(DailyIntent.SetAlarm(23, 5), DailyIntentRouter.parse("晚上十一点零五分闹钟", now))
    }

    @Test fun timerBoundsAndChineseUnitsParse() {
        assertEquals(DailyIntent.SetTimer(300), DailyIntentRouter.parse("5分钟倒计时", now))
        assertEquals(DailyIntent.SetTimer(5400), DailyIntentRouter.parse("一小时半计时", now))
        assertEquals(DailyIntent.SetTimer(86400), DailyIntentRouter.parse("24小时倒计时", now))
        assertNull(DailyIntentRouter.parse("0秒倒计时", now))
        assertNull(DailyIntentRouter.parse("24小时1秒倒计时", now))
    }

    @Test fun weatherPeriodsAndRainParseWithoutGuessingCity() {
        assertEquals(DailyIntent.Weather(null), DailyIntentRouter.parse("今天天气怎么样", now))
        assertEquals(DailyIntent.Weather("北京", WeatherPeriod.TOMORROW, true),
            DailyIntentRouter.parse("明天北京有雨吗", now))
        assertEquals(DailyIntent.Weather("杭州", WeatherPeriod.THREE_DAYS),
            DailyIntentRouter.parse("未来三天杭州天气", now))
    }

    @Test fun unsafeNegativeConditionalAndCompoundCommandsRemainUnmatched() {
        listOf(
            "不要给我定闹钟", "如果明天下雨就定闹钟", "查天气再设闹钟", "查天气和设闹钟",
            "明天早上7点半叫我起床，然后查北京天气", "请帮我设置闹钟或者计时器",
        ).forEach { assertNull(it, DailyIntentRouter.parse(it, now)) }
    }

    @Test fun explicitCalculationCommandsUseOnlyTheWholeExpression() {
        val examples = listOf(
            "计算 12乘3" to "12*3",
            "算一下(2+3)*4" to "(2+3)*4",
            "1+2等于多少" to "1+2",
            "请帮我计算 0.1加0.2" to "0.1+0.2",
            "算一下12除以4" to "12/4",
            "计算5.5取余2" to "5.5%2",
            "计算（２＋３）×４" to "(2+3)*4",
            "计算1 + 2" to "1 + 2",
            "计算1 2" to "1 2",
            "计算1+" to "1+",
            "计算1/0" to "1/0",
            "计算" + "+".repeat(128) + "1" to "+".repeat(128) + "1",
            "计算" + "1".repeat(257) to "1".repeat(257),
        )
        examples.forEach { (text, expression) ->
            assertEquals(text, DailyIntent.Calculate(expression), DailyIntentRouter.parse(text, now))
        }
    }

    @Test fun contextualConditionalAndCompoundCalculationsStayOnTheConversationPath() {
        listOf(
            "如果需要就计算1+2", "假如1+2等于多少", "不要计算1+2", "计算1+2然后设闹钟",
            "计算1+2并且查天气", "上面那句1+2等于多少", "他说计算1+2", "请解释计算1+2",
            "帮我写一个计算1+2的函数", "计算1+2和3+4", "计算1+2，查天气", "计算1+2\n查天气",
            "计算Math.sqrt(4)", "计算1e10", "计算Runtime.getRuntime()", "1+2", "计算",
        ).forEach { assertNull(it, DailyIntentRouter.parse(it, now)) }
    }

    @Test fun todayAgendaGrammarIsReadOnlyAndSinglePurpose() {
        listOf("今日日程", "今天有什么安排", "查看今天日历", "请帮我查看今天日历").forEach {
            assertEquals(it, DailyIntent.TodayAgenda, DailyIntentRouter.parse(it, now))
        }
        listOf(
            "创建今日日程", "添加今天日历", "帮我安排今天会议", "今天下午三点开会",
            "如果有空查看今天日历", "不要查看今天日历", "查看今天日历然后添加会议",
            "今天有什么安排并且查天气", "明天有什么安排", "查看所有日程", "上文说今日日程",
        ).forEach { assertNull(it, DailyIntentRouter.parse(it, now)) }
    }

    @Test fun clockAndDateQuestionsAreDeterministic() {
        assertEquals(DailyIntent.TimeNow, DailyIntentRouter.parse("现在几点了", now))
        assertEquals(DailyIntent.DateToday, DailyIntentRouter.parse("今天星期几", now))
        assertTrue(DailyIntentRouter.parse("明天是什么日期", now) == null)
    }

    @Test fun flashlightCommandsParseOnlyWholePhrase() {
        assertEquals(DailyIntent.FlashlightAction(true), DailyIntentRouter.parse("打开手电筒", now))
        assertEquals(DailyIntent.FlashlightAction(true), DailyIntentRouter.parse("翻光手电筒", now))
        assertEquals(DailyIntent.FlashlightAction(true), DailyIntentRouter.parse("请帮我开启手电", now))
        assertEquals(DailyIntent.FlashlightAction(false), DailyIntentRouter.parse("关手电筒", now))
        assertEquals(DailyIntent.FlashlightAction(false), DailyIntentRouter.parse("关掉闪光灯", now))
    }

    @Test fun volumeCommandsParsePercentAndStep() {
        assertEquals(DailyIntent.SetVolumeAction(50), DailyIntentRouter.parse("音量调到50%", now))
        assertEquals(DailyIntent.SetVolumeAction(30), DailyIntentRouter.parse("把媒体音量设为30", now))
        assertEquals(DailyIntent.AdjustVolumeAction(false), DailyIntentRouter.parse("声音小一点", now))
        assertEquals(DailyIntent.AdjustVolumeAction(true), DailyIntentRouter.parse("音量调大", now))
        assertNull(DailyIntentRouter.parse("音量调到150", now))
        assertNull(DailyIntentRouter.parse("音量调到abc", now))
    }

    @Test fun ringerOpenAppNavigateAndStatusCommandsParse() {
        assertEquals(DailyIntent.SetRingerAction(RingerKind.SILENT), DailyIntentRouter.parse("静音", now))
        assertEquals(DailyIntent.SetRingerAction(RingerKind.VIBRATE), DailyIntentRouter.parse("调成震动模式", now))
        assertEquals(DailyIntent.SetRingerAction(RingerKind.NORMAL), DailyIntentRouter.parse("恢复响铃", now))
        assertEquals(DailyIntent.OpenAppAction("微信"), DailyIntentRouter.parse("打开微信", now))
        assertEquals(DailyIntent.OpenAppAction("淘宝"), DailyIntentRouter.parse("启动淘宝App", now))
        assertEquals(DailyIntent.NavigateAction("西湖"), DailyIntentRouter.parse("导航去西湖", now))
        assertEquals(DailyIntent.NavigateAction("天安门"), DailyIntentRouter.parse("带我去天安门", now))
        assertEquals(DailyIntent.BatteryStatus, DailyIntentRouter.parse("还有多少电", now))
        assertEquals(DailyIntent.StorageStatus, DailyIntentRouter.parse("存储空间", now))
    }

    @Test fun lifeActionNegativeConditionalAndCompoundCommandsRemainUnmatched() {
        listOf(
            "不要打开手电筒", "别静音", "如果电量低就打开手电筒", "关闭手电筒然后打开微信",
            "导航去西湖并且打开淘宝", "把音量调到50%再静音", "打开微信或者支付宝",
            "帮我打开手电筒吗", "打开手电筒，静音", "查询明天天气和电量", "调大音量再导航去西湖",
            "打开手电筒太亮了", "能否静音", "不是响铃", "请勿打开相机",
        ).forEach { assertNull(it, DailyIntentRouter.parse(it, now)) }
    }

    // ---- 小爱同学式生活便利 · 第二批（P0）----

    @Test fun mediaPlaybackCommandsParseOnlyWholePhrase() {
        assertEquals(DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.PAUSE), DailyIntentRouter.parse("暂停播放", now))
        assertEquals(DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.PAUSE), DailyIntentRouter.parse("暂停", now))
        assertEquals(DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.RESUME), DailyIntentRouter.parse("继续播放", now))
        assertEquals(DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.RESUME), DailyIntentRouter.parse("播放", now))
        assertEquals(DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.NEXT), DailyIntentRouter.parse("下一首", now))
        assertEquals(DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.NEXT), DailyIntentRouter.parse("切歌", now))
        assertEquals(DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.PREVIOUS), DailyIntentRouter.parse("上一曲", now))
    }

    @Test fun dndWholePhrasesBypassUnsafeButCompoundsDoNot() {
        // 含「取消/关闭」的整串白名单必须在 unsafe 前置拦截之前放行。
        assertEquals(DailyIntent.DndAction(DndKind.ENABLE), DailyIntentRouter.parse("开启勿扰", now))
        assertEquals(DailyIntent.DndAction(DndKind.DISABLE), DailyIntentRouter.parse("关闭勿扰", now))
        assertEquals(DailyIntent.DndAction(DndKind.DISABLE), DailyIntentRouter.parse("关掉勿扰模式", now))
        assertEquals(DailyIntent.DndAction(DndKind.CANCEL), DailyIntentRouter.parse("取消勿扰", now))
        assertEquals(DailyIntent.DndAction(DndKind.ENABLE), DailyIntentRouter.parse("请帮我开启勿扰模式", now))
        listOf(
            "如果手机没电就开启勿扰", "开启勿扰然后打开微信", "不要开启勿扰", "关闭勿扰并且静音",
            "取消勿扰模式再看看", "帮我关闭勿扰吗", "开启勿扰模式下",
        ).forEach { assertNull(it, DailyIntentRouter.parse(it, now)) }
    }

    @Test fun systemPanelsAlarmAndNotesParseByFixedAliases() {
        mapOf(
            "打开WiFi设置" to SystemPanel.WIFI,
            "打开wifi设置" to SystemPanel.WIFI,
            "打开蓝牙设置" to SystemPanel.BLUETOOTH,
            "打开热点设置" to SystemPanel.HOTSPOT,
            "打开网络设置" to SystemPanel.NETWORK,
        ).forEach { (text, panel) ->
            assertEquals(text, DailyIntent.SystemPanelAction(panel), DailyIntentRouter.parse(text, now))
        }
        assertEquals(DailyIntent.AlarmQuery, DailyIntentRouter.parse("查看闹钟", now))
        assertEquals(DailyIntent.AlarmQuery, DailyIntentRouter.parse("我定了几个闹钟", now))
        assertEquals(DailyIntent.AlarmQuery, DailyIntentRouter.parse("打开闹钟", now))
        assertEquals(DailyIntent.NoteAction("明天开会"), DailyIntentRouter.parse("记一下明天开会", now))
        assertEquals(DailyIntent.NoteAction("买牛奶"), DailyIntentRouter.parse("备忘买牛奶", now))
        assertEquals(DailyIntent.ViewNotes, DailyIntentRouter.parse("查看备忘", now))
        assertNull(DailyIntentRouter.parse("打开未知设置", now))
        assertNull(DailyIntentRouter.parse("记住", now))
    }

    @Test fun calcFollowUpParsesWholeNumberAndRejectsCompounds() {
        assertEquals(DailyIntent.CalcFollowUp(CalcFollowOp.ADD, "5"), DailyIntentRouter.parse("刚才那个计算再加5", now))
        assertEquals(DailyIntent.CalcFollowUp(CalcFollowOp.SUBTRACT, "3"), DailyIntentRouter.parse("刚才那个计算再减3", now))
        assertEquals(DailyIntent.CalcFollowUp(CalcFollowOp.ADD, "十"), DailyIntentRouter.parse("刚才那个计算再加上十", now))
        listOf(
            "刚才那个计算再加5然后静音", "如果刚才算完了再加5", "不要再加5",
            "刚才那个计算再加abc", "刚才那个计算再加5再减3", "上次计算加5",
        ).forEach { assertNull(it, DailyIntentRouter.parse(it, now)) }
    }
}
