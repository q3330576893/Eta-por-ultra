package io.github.mangi.eta.agent.quickactions

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播报文案：直取要点、先地点后天气再温度，最后给一句可执行建议。 */
class WeatherBriefingTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val city = WeatherCity(360723, "大余县", "CN", "中国", "江西省", "赣州市", null, null, "Asia/Shanghai",
        locationSource = "UAPI", adcode = "360723")
    private val snapshot = DailyWeatherSnapshot(city, Instant.parse("2026-11-18T03:00:00Z"), null, 18.0, 2, null,
        emptyList(), provider = "UAPI", regionLabel = "大余县 · 江西省 · 赣州市")

    private fun day(date: LocalDate, code: Int, max: Double?, min: Double?, text: String? = null, rain: Double? = null) =
        DailyWeatherDay(date, code, max, min, null, rain, null, text)

    @Test
    fun todayBriefingLeadsWithPlaceConditionAndTemperature() {
        val text = WeatherBriefing.speak(DailyIntent.Weather(), snapshot,
            listOf(day(today, 61, 24.0, 12.0, "小雨")), today, stale = false)
        assertEquals("今天大余县小雨，气温12到24度。出门建议带伞。", text)
    }

    @Test
    fun tomorrowUsesTomorrowLabel() {
        val text = WeatherBriefing.speak(DailyIntent.Weather(period = WeatherPeriod.TOMORROW), snapshot,
            listOf(day(today.plusDays(1), 0, 26.0, 15.0, "晴")), today, stale = false)
        assertTrue(text.startsWith("明天大余县晴"))
        // 晴天不给带伞建议，也不编造降水概率。
        assertFalse(text.contains("伞"))
        assertFalse(text.contains("降水概率"))
    }

    @Test
    fun negativeTemperaturesAreSpokenAsMinusNotSymbol() {
        val text = WeatherBriefing.speak(DailyIntent.Weather(), snapshot,
            listOf(day(today, 71, -3.0, -11.0, "小雪")), today, stale = false)
        assertTrue(text.contains("气温零下11到零下3度"))
        assertFalse(text.contains("-"))
    }

    @Test
    fun missingTemperatureOmitsItInsteadOfInventingZero() {
        val text = WeatherBriefing.speak(DailyIntent.Weather(), snapshot,
            listOf(day(today, 3, null, null, "阴")), today, stale = false)
        assertEquals("今天大余县阴。", text)
    }

    @Test
    fun staleForecastIsLabelledBeforeContent() {
        val text = WeatherBriefing.speak(DailyIntent.Weather(), snapshot,
            listOf(day(today, 2, 22.0, 14.0, "多云")), today, stale = true)
        assertTrue(text.startsWith("这是上次更新的预报。"))
    }

    @Test
    fun rainOnlyQuestionSaysNoRainOnlyWhenDataSupportsIt() {
        val dry = WeatherBriefing.speak(DailyIntent.Weather(rainOnly = true), snapshot,
            listOf(day(today, 0, 25.0, 15.0, "晴")), today, stale = false)
        assertTrue(dry.contains("暂无降雨"))
        val wet = WeatherBriefing.speak(DailyIntent.Weather(rainOnly = true), snapshot,
            listOf(day(today, 63, 20.0, 14.0, "中雨")), today, stale = false)
        assertTrue(wet.contains("有雨"))
    }

    @Test
    fun threeDayBriefingJoinsDaysWithoutRepeatingAdvice() {
        val text = WeatherBriefing.speak(DailyIntent.Weather(period = WeatherPeriod.THREE_DAYS), snapshot,
            listOf(day(today, 0, 25.0, 15.0, "晴"), day(today.plusDays(1), 61, 20.0, 13.0, "小雨"),
                day(today.plusDays(2), 3, 18.0, 10.0, "阴")), today, stale = false)
        assertTrue(text.contains("今天晴"))
        assertTrue(text.contains("明天小雨"))
        assertTrue(text.contains("11月20日阴"))
        assertFalse(text.contains("带伞"))
    }
}
