package io.github.mangi.eta.agent.quickactions

import java.time.LocalDate
import kotlin.math.roundToInt

/** 基于已返回字段生成短播报，不经过模型，不编造降水概率或警报。 */
internal object WeatherBriefing {
    fun describe(day: DailyWeatherDay): String = day.conditionText?.takeIf(String::isNotBlank)
        ?: DailyWeatherDescriptions.wmo(day.weatherCode)

    fun speak(request: DailyIntent.Weather, snapshot: DailyWeatherSnapshot, days: List<DailyWeatherDay>, today: LocalDate, stale: Boolean): String {
        if (days.isEmpty()) return "暂时没有所问日期的预报。"
        val place = snapshot.regionLabel?.substringBefore('·')?.trim()?.takeIf(String::isNotBlank) ?: snapshot.city.name
        fun label(date: LocalDate): String = when (date) { today -> "今天"; today.plusDays(1) -> "明天"; else -> "${date.monthValue}月${date.dayOfMonth}日" }
        val body = if (request.period == WeatherPeriod.THREE_DAYS) {
            "$place，" + days.take(3).joinToString("；") { "${label(it.date)}${describe(it)}${temperature(it).let { temp -> if (temp.isBlank()) "" else "，$temp" }}" } + "。"
        } else {
            val day = days.first()
            val condition = if (request.rainOnly) when {
                rainy(day) -> "有雨"
                (day.precipitationProbabilityPercent ?: 0) >= 30 -> "有降水可能"
                day.weatherCode < 0 && day.conditionText.isNullOrBlank() -> "降水情况暂缺"
                else -> "暂无降雨"
            } else describe(day)
            val facts = listOfNotNull(temperature(day).takeIf(String::isNotBlank),
                day.precipitationProbabilityPercent?.let { "降水概率百分之$it" })
            "${label(day.date)}$place$condition" + (if (facts.isEmpty()) "" else "，" + facts.joinToString("，")) + "。" + advice(day)
        }
        return if (stale) "这是上次更新的预报。$body" else body
    }

    private fun temperature(day: DailyWeatherDay): String {
        val min = day.minimumCelsius?.takeIf(Double::isFinite)?.roundToInt()
        val max = day.maximumCelsius?.takeIf(Double::isFinite)?.roundToInt()
        fun value(number: Int) = if (number < 0) "零下${-number}" else number.toString()
        return when {
            min != null && max != null -> "气温${value(min)}到${value(max)}度"
            min != null -> "最低${value(min)}度"
            max != null -> "最高${value(max)}度"
            else -> ""
        }
    }
    private fun rainy(day: DailyWeatherDay): Boolean = DailyWeatherDescriptions.hasRain(day.weatherCode) ||
        day.conditionText?.contains("雨") == true || (day.rainMillimeters ?: 0.0) > 0

    private fun advice(day: DailyWeatherDay): String = when {
        day.weatherCode in setOf(95, 96, 99) -> "外出留意雷雨，注意避雷。"
        rainy(day) || (day.precipitationProbabilityPercent ?: 0) >= 40 -> "出门建议带伞。"
        day.weatherCode in setOf(68, 71, 73, 75, 77, 85, 86) -> "注意保暖和路面湿滑。"
        (day.maximumCelsius ?: 20.0) <= 8 -> "外出注意保暖。"
        (day.maximumCelsius ?: 20.0) >= 32 -> "外出注意防暑补水。"
        day.maximumCelsius != null && day.minimumCelsius != null && day.maximumCelsius - day.minimumCelsius >= 10 -> "早晚温差较大，建议准备外套。"
        else -> ""
    }
}
