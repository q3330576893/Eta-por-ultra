package io.github.mangi.eta.agent.quickactions

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Only explicit, single-purpose commands reach the deterministic executor. */
internal sealed interface DailyIntent {
    data object TimeNow : DailyIntent
    data object DateToday : DailyIntent
    data class Calculate(val expression: String) : DailyIntent
    data object TodayAgenda : DailyIntent
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String = "闹钟",
        val repeatDays: Set<DayOfWeek> = emptySet(),
        val date: LocalDate? = null,
    ) : DailyIntent
    data class SetTimer(val durationSeconds: Int, val label: String = "倒计时") : DailyIntent
    data class Weather(
        val cityQuery: String? = null,
        val period: WeatherPeriod = WeatherPeriod.TODAY,
        val rainOnly: Boolean = false,
    ) : DailyIntent
}

internal enum class WeatherPeriod { TODAY, TOMORROW, THREE_DAYS }

internal data class WeatherCity(
    val id: Long,
    val name: String,
    val countryCode: String,
    val country: String,
    val admin1: String,
    val admin2: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
) {
    val displayName: String get() = listOf(name, admin2, admin1, country)
        .filter(String::isNotBlank).distinct().joinToString(" · ")
}

internal data class DailyResultField(val label: String, val value: String)

/** API-computed observations; a system clock handoff is explicitly unverified. */
internal sealed interface DailyCommandResult {
    val request: DailyIntent
    val title: String
    val summary: String
    val sourceLabel: String
    val sourceUrl: String? get() = null
    val asOf: Instant
    val fields: List<DailyResultField> get() = emptyList()
    val verified: Boolean

    data class ClockAnswer(
        override val request: DailyIntent,
        override val title: String,
        override val summary: String,
        override val asOf: Instant,
        override val fields: List<DailyResultField>,
    ) : DailyCommandResult {
        override val sourceLabel = "设备时间"
        override val verified = true
    }

    data class CalculationAnswer(
        override val request: DailyIntent.Calculate,
        override val summary: String,
        override val asOf: Instant,
        override val fields: List<DailyResultField> = emptyList(),
    ) : DailyCommandResult {
        override val title = "计算结果"
        override val sourceLabel = "本地计算"
        override val verified = true
    }

    data class CalendarAnswer(
        override val request: DailyIntent.TodayAgenda,
        override val summary: String,
        override val asOf: Instant,
        override val fields: List<DailyResultField> = emptyList(),
    ) : DailyCommandResult {
        override val title = "今日日程"
        override val sourceLabel = "设备日历"
        override val verified = true
    }

    data class AlarmHandoff(
        override val request: DailyIntent.SetAlarm,
        override val summary: String,
        override val asOf: Instant,
        override val fields: List<DailyResultField>,
    ) : DailyCommandResult {
        override val title = "闹钟"
        override val sourceLabel = "系统时钟"
        override val verified = false
    }

    data class TimerHandoff(
        override val request: DailyIntent.SetTimer,
        override val summary: String,
        override val asOf: Instant,
        override val fields: List<DailyResultField>,
    ) : DailyCommandResult {
        override val title = "倒计时"
        override val sourceLabel = "系统时钟"
        override val verified = false
    }

    data class WeatherAnswer(
        override val request: DailyIntent.Weather,
        val city: WeatherCity,
        val forecast: DailyWeatherSnapshot,
        val cached: Boolean,
        val stale: Boolean,
        override val summary: String,
        override val fields: List<DailyResultField>,
    ) : DailyCommandResult {
        override val title = city.name + "天气"
        override val sourceLabel = "Open-Meteo · GeoNames"
        override val sourceUrl = "https://open-meteo.com/"
        override val asOf: Instant = forecast.fetchedAt
        override val verified = true
    }

    data class NeedsCity(
        override val request: DailyIntent.Weather,
        override val asOf: Instant,
        override val summary: String = "想查询哪个城市？请输入城市名，选定后可作为下次天气查询的常用城市。",
    ) : DailyCommandResult {
        override val title = "请选择天气城市"
        override val sourceLabel = "城市设置"
        override val verified = false
    }

    data class CityChoices(
        override val request: DailyIntent.Weather,
        val cities: List<WeatherCity>,
        override val asOf: Instant,
        override val summary: String = "找到多个同名地点，请选择要查询的城市。",
    ) : DailyCommandResult {
        override val title = "确认天气城市"
        override val sourceLabel = "Open-Meteo · GeoNames"
        override val sourceUrl = "https://open-meteo.com/en/docs/geocoding-api"
        override val verified = false
    }

    data class Failure(
        override val request: DailyIntent,
        val code: String,
        override val summary: String,
        override val asOf: Instant,
        override val sourceLabel: String = "本地快捷指令",
    ) : DailyCommandResult {
        override val title = "快捷指令提示"
        override val verified = false
    }
}

internal data class DailyWeatherDay(
    val date: LocalDate,
    val weatherCode: Int,
    val maximumCelsius: Double?,
    val minimumCelsius: Double?,
    val precipitationMillimeters: Double?,
    val rainMillimeters: Double?,
    val precipitationProbabilityPercent: Int?,
)

internal data class DailyWeatherSnapshot(
    val city: WeatherCity,
    val fetchedAt: Instant,
    val observationAt: Instant?,
    val currentTemperatureCelsius: Double?,
    val currentWeatherCode: Int?,
    val currentPrecipitationMillimeters: Double?,
    val days: List<DailyWeatherDay>,
)
