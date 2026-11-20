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
    data object AlarmSetup : DailyIntent
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
        val chooseLocation: Boolean = false,
    ) : DailyIntent

    // ---- 生活便利 · 本地零模型（P0）----
    /** 手电筒开关；enabled=true 打开，false 关闭。 */
    data class FlashlightAction(val enabled: Boolean) : DailyIntent

    /** 把媒体音量设为具体百分比（0..100）。 */
    data class SetVolumeAction(val percent: Int) : DailyIntent

    /** 按一格步长调大/调小媒体音量；up=true 调大。 */
    data class AdjustVolumeAction(val up: Boolean) : DailyIntent

    /** 响铃模式：静音 / 震动 / 响铃。 */
    data class SetRingerAction(val mode: RingerKind) : DailyIntent

    /** 打开本地应用；alias 为规范别名（见 LifeActionExecutor 的别名表）。 */
    data class OpenAppAction(val alias: String) : DailyIntent

    /** 交给地图/浏览器进行地理导航，属于 handoff，verified=false。 */
    data class NavigateAction(val destination: String) : DailyIntent

    /** 读取电池电量百分比，本地读回 verified=true。 */
    data object BatteryStatus : DailyIntent

    /** 读取内部存储使用情况，本地读回 verified=true。 */
    data object StorageStatus : DailyIntent

    // ---- 小爱同学式生活便利 · 第二批（P0）----
    /** 媒体播放控制；通过 MediaSessionManager + MediaController.transportControls 执行。 */
    data class MediaPlaybackAction(val command: MediaPlaybackCommand) : DailyIntent

    /** 勿扰开关；CANCEL 与 DISABLE 都落到 INTERRUPTION_FILTER_ALL，仅文案不同。 */
    data class DndAction(val kind: DndKind) : DailyIntent

    /** 打开系统设置面板；固定别名表，handoff verified=false。 */
    data class SystemPanelAction(val panel: SystemPanel) : DailyIntent

    /** 查看/打开系统闹钟列表；ACTION_SHOW_ALARMS handoff verified=false。 */
    data object AlarmQuery : DailyIntent

    /** 基于上次计算结果的追算；operand 为数字文本，复用 SimpleCalculator。 */
    data class CalcFollowUp(val op: CalcFollowOp, val operand: String) : DailyIntent
}

internal enum class WeatherPeriod { TODAY, TOMORROW, THREE_DAYS }

/** 与 Android AudioManager 的 RINGER_MODE_* 一一对应。 */
internal enum class RingerKind { SILENT, VIBRATE, NORMAL }

internal enum class MediaPlaybackCommand { PAUSE, RESUME, NEXT, PREVIOUS }

internal enum class DndKind { ENABLE, DISABLE, CANCEL }

internal enum class SystemPanel { WIFI, BLUETOOTH, HOTSPOT, NETWORK }

internal enum class CalcFollowOp { ADD, SUBTRACT }

internal data class WeatherCity(
    val id: Long,
    val name: String,
    val countryCode: String,
    val country: String,
    val admin1: String,
    val admin2: String,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String,
    val admin3: String = "",
    val admin4: String = "",
    val locationSource: String = "GeoNames",
    val adcode: String? = null,
) {
    val hasCoordinates: Boolean get() = latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    val displayName: String get() = listOf(name, admin4, admin3, admin2, admin1, country)
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
        override val request: DailyIntent,
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

    data class NeedsAlarmTime(
        override val request: DailyIntent = DailyIntent.AlarmSetup,
        override val asOf: Instant,
        override val summary: String = "想设几点的闹钟？请输入时间，例如明天早上7点、每天7点半或工作日08:30。",
    ) : DailyCommandResult {
        override val title = "设置闹钟"
        override val sourceLabel = "本地快捷指令"
        override val verified = false
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
        override val sourceLabel = forecast.provider
        override val sourceUrl = forecast.providerUrl
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
        override val title = "确认天气地点"
        override val sourceLabel = cities.map { it.locationSource }.distinct().joinToString(" · ")
        override val sourceUrl = if (cities.any { it.locationSource.contains("OpenStreetMap") }) "https://www.openstreetmap.org/copyright" else "https://open-meteo.com/en/docs/geocoding-api"
        override val verified = false
    }

    /**
     * 生活便利动作的通用结果。verified 只反映本地读回是否真的命中目标状态：
     * 手电筒/音量/响铃/电量/存储读回成功才为 true；导航属于系统交接，恒为 false。
     */
    data class ActionDone(
        override val request: DailyIntent,
        override val title: String,
        override val summary: String,
        override val fields: List<DailyResultField>,
        override val asOf: Instant,
        override val verified: Boolean,
        override val sourceLabel: String = "本地设备",
    ) : DailyCommandResult

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
    val conditionText: String? = null,
)

internal data class DailyWeatherSnapshot(
    val city: WeatherCity,
    val fetchedAt: Instant,
    val observationAt: Instant?,
    val currentTemperatureCelsius: Double?,
    val currentWeatherCode: Int?,
    val currentPrecipitationMillimeters: Double?,
    val days: List<DailyWeatherDay>,
    val provider: String = "Open-Meteo",
    val providerUrl: String = "https://open-meteo.com/",
    val currentConditionText: String? = null,
    val regionLabel: String? = null,
)
