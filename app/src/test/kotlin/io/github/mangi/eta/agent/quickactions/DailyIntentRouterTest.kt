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
}
