package io.github.mangi.eta.agent.quickactions

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Direct entry point: no model configuration, credential lookup or tool-loop startup. */
internal object QuickActionsExecutor {
    suspend fun execute(
        context: Context,
        request: DailyIntent,
        selectedCity: WeatherCity? = null,
        rememberCity: Boolean = true,
        forceRefresh: Boolean = false,
    ): DailyCommandResult = execute(context, request, selectedCity, rememberCity,
        DailyCommandRepository(context), OpenMeteoWeatherService(), Clock.systemDefaultZone(), forceRefresh)

    internal suspend fun execute(
        context: Context,
        request: DailyIntent,
        selectedCity: WeatherCity?,
        rememberCity: Boolean,
        store: DailyCommandStore,
        weather: DailyWeatherService,
        clock: Clock,
        forceRefresh: Boolean = false,
    ): DailyCommandResult {
        val now = clock.instant()
        val local = ZonedDateTime.now(clock)
        return when (request) {
            DailyIntent.TimeNow -> DailyCommandResult.ClockAnswer(request, "现在时间",
                "现在是 ${local.hour}点${local.minute.toString().padStart(2, '0')}分。", now,
                listOf(DailyResultField("时间", local.format(DateTimeFormatter.ofPattern("HH:mm:ss"))),
                    DailyResultField("时区", local.zone.id)))
            DailyIntent.DateToday -> DailyCommandResult.ClockAnswer(request, "今天日期",
                "今天是 ${local.year}年${local.monthValue}月${local.dayOfMonth}日，${weekLabel(local.dayOfWeek)}。", now,
                listOf(DailyResultField("日期", local.toLocalDate().toString()), DailyResultField("星期", weekLabel(local.dayOfWeek))))
            is DailyIntent.Calculate -> executeCalculation(request, now)
            DailyIntent.TodayAgenda -> executeTodayAgenda(context, clock)
            is DailyIntent.SetAlarm -> handoffAlarm(context, request, now)
            is DailyIntent.SetTimer -> handoffTimer(context, request, now)
            is DailyIntent.Weather -> executeWeather(request, selectedCity, rememberCity, store, weather, now, forceRefresh)
        }
    }

    internal fun executeCalculation(request: DailyIntent.Calculate, now: Instant): DailyCommandResult =
        when (val calculation = SimpleCalculator.evaluate(request.expression)) {
            is SimpleCalculator.Result.Answer -> DailyCommandResult.CalculationAnswer(
                request, "计算结果是 ${calculation.value}。", now,
                listOf(DailyResultField("计算式", request.expression), DailyResultField("结果", calculation.value)),
            )
            is SimpleCalculator.Result.Failure -> DailyCommandResult.Failure(
                request, calculation.code, calculation.message, now, "本地计算",
            )
        }

    internal suspend fun executeTodayAgenda(context: Context, clock: Clock): DailyCommandResult {
        val request = DailyIntent.TodayAgenda
        val now = clock.instant()
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return DailyCommandResult.Failure(request, "CALENDAR_PERMISSION_REQUIRED",
                "读取今日日程需要已授予的日历读取权限（READ_CALENDAR），请在应用权限设置中开启后重试。", now, "设备日历")
        }
        val date = now.atZone(clock.zone).toLocalDate()
        val start = date.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        return try {
            coroutineScope {
                suspendCancellableCoroutine<DailyCommandResult> { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    launch(Dispatchers.IO) {
                        try {
                            val answer = readTodayAgenda(context, now, clock.zone, start, end, cancellation)
                            if (continuation.isActive) continuation.resume(answer)
                        } catch (cancelled: CancellationException) {
                            continuation.cancel(cancelled)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            DailyCommandResult.Failure(request, "CALENDAR_PERMISSION_REQUIRED",
                "日历读取权限已变化，请在应用权限设置中开启 READ_CALENDAR 后重试。", now, "设备日历")
        } catch (_: Exception) {
            DailyCommandResult.Failure(request, "CALENDAR_UNAVAILABLE", "设备日历暂时读取失败，请稍后重试。", now, "设备日历")
        }
    }

    private fun readTodayAgenda(
        context: Context,
        now: Instant,
        zone: ZoneId,
        start: Long,
        end: Long,
        cancellation: CancellationSignal,
    ): DailyCommandResult {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.appendQueryParameter("limit", "20").build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        val selection = "${CalendarContract.Instances.BEGIN} < ? AND ${CalendarContract.Instances.END} > ?"
        val cursor = context.contentResolver.query(uri, projection, selection, arrayOf(end.toString(), start.toString()),
            "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.END} ASC", cancellation)
            ?: return DailyCommandResult.Failure(DailyIntent.TodayAgenda, "CALENDAR_UNAVAILABLE",
                "设备日历暂时读取失败，请稍后重试。", now, "设备日历")
        val fields = mutableListOf(DailyResultField("日期", now.atZone(zone).toLocalDate().toString()),
            DailyResultField("时区", zone.id))
        var count = 0
        cursor.use {
            val titleIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            for (row in 0 until 20) {
                cancellation.throwIfCanceled()
                if (!it.moveToNext()) break
                val begin = it.getLong(beginIndex)
                val finish = it.getLong(endIndex)
                // Enforce the half-open local-day window even for a provider with loose filtering.
                if (begin >= end || finish <= start || finish <= begin) continue
                val title = it.getString(titleIndex).orEmpty().take(80)
                    .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().ifBlank { "未命名日程" }
                val time = if (it.getInt(allDayIndex) != 0) "全天" else {
                    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
                    val from = if (begin < start) "00:00" else Instant.ofEpochMilli(begin).atZone(zone).format(formatter)
                    val until = if (finish >= end) "24:00" else Instant.ofEpochMilli(finish).atZone(zone).format(formatter)
                    "$from–$until"
                }
                fields += DailyResultField(time, title)
                count++
            }
        }
        cancellation.throwIfCanceled()
        val summary = when (count) {
            0 -> "今天的设备日历中暂无日程。"
            20 -> "今天的设备日历中有以下日程，最多显示 20 项。"
            else -> "今天的设备日历中有 $count 项日程。"
        }
        return DailyCommandResult.CalendarAnswer(DailyIntent.TodayAgenda, summary, now, fields)
    }

    private suspend fun handoffAlarm(context: Context, request: DailyIntent.SetAlarm, now: Instant): DailyCommandResult {
        if (request.hour !in 0..23 || request.minute !in 0..59 || request.label.length > 80) {
            return DailyCommandResult.Failure(request, "INVALID_ALARM", "闹钟时间需要在 00:00 至 23:59 之间。", now)
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, request.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, request.label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (request.repeatDays.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS,
            ArrayList(request.repeatDays.sortedBy { it.value }.map { if (it == DayOfWeek.SUNDAY) 1 else it.value + 1 }))
        return try {
            withContext(Dispatchers.Main.immediate) { context.startActivity(intent) }
            val date = request.date?.let { "${it.monthValue}月${it.dayOfMonth}日 " }.orEmpty()
            val time = "%02d:%02d".format(Locale.ROOT, request.hour, request.minute)
            val fields = mutableListOf(DailyResultField("时间", date + time))
            if (request.repeatDays.isNotEmpty()) fields += DailyResultField("重复", request.repeatDays.sortedBy { it.value }.joinToString("、", transform = ::weekLabel))
            fields += DailyResultField("标签", request.label)
            if (request.date != null) fields += DailyResultField("日期核对", "系统标准闹钟接口仅传递时分，请在时钟页面确认 ${request.date}。")
            DailyCommandResult.AlarmHandoff(request, "已交给系统时钟设置，请在时钟页面确认闹钟。", now, fields)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            DailyCommandResult.Failure(request, "CLOCK_ALARM_UNAVAILABLE", "系统时钟暂时无法接收闹钟设置，请打开时钟应用核对。", now, "系统时钟")
        }
    }

    private suspend fun handoffTimer(context: Context, request: DailyIntent.SetTimer, now: Instant): DailyCommandResult {
        if (request.durationSeconds !in 1..86_400) {
            return DailyCommandResult.Failure(request, "INVALID_TIMER", "倒计时时长需要在 1 秒至 24 小时之间。", now)
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, request.durationSeconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, request.label.take(80)).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            withContext(Dispatchers.Main.immediate) { context.startActivity(intent) }
            DailyCommandResult.TimerHandoff(request, "已交给系统时钟设置，请在时钟页面确认倒计时。", now,
                listOf(DailyResultField("时长", durationLabel(request.durationSeconds))))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            DailyCommandResult.Failure(request, "CLOCK_TIMER_UNAVAILABLE", "系统时钟暂时无法接收倒计时，请打开时钟应用核对。", now, "系统时钟")
        }
    }

    internal suspend fun executeWeather(
        request: DailyIntent.Weather,
        selectedCity: WeatherCity?,
        rememberCity: Boolean,
        store: DailyCommandStore,
        weather: DailyWeatherService,
        now: Instant,
        forceRefresh: Boolean = false,
    ): DailyCommandResult = withContext(Dispatchers.IO) {
        val saved = store.preferredCity()
        val requestedCity = request.cityQuery
        var city = selectedCity ?: saved?.takeIf { savedCity ->
            requestedCity.isNullOrBlank() || normalizeCity(requestedCity) == normalizeCity(savedCity.name)
        }
        if (city == null && request.cityQuery.isNullOrBlank()) return@withContext DailyCommandResult.NeedsCity(request, now)
        var cached: DailyWeatherSnapshot? = city?.let(store::cachedWeather)
        try {
            val result = withTimeoutOrNull(5000L) {
                if (city == null) {
                    val query = request.cityQuery!!.trim()
                    if (query.length !in 2..40) return@withTimeoutOrNull DailyCommandResult.NeedsCity(request, now, "请输入 2 至 40 个字符的城市名。")
                    val cities = weather.searchCities(query)
                    if (cities.isEmpty()) return@withTimeoutOrNull DailyCommandResult.NeedsCity(request, now, "没有找到这个城市，请补充省份或重新输入城市名。")
                    if (cities.size > 1) return@withTimeoutOrNull DailyCommandResult.CityChoices(request, cities, now)
                    city = cities.single()
                }
                val resolved = requireNotNull(city)
                if (rememberCity && (selectedCity != null || request.cityQuery != null)) store.savePreferredCity(resolved)
                cached = store.cachedWeather(resolved)
                val fresh = cached?.takeIf { !forceRefresh && DailyCommandRepository.isFresh(it, now) }
                if (fresh != null) return@withTimeoutOrNull weatherAnswer(request, fresh, cached = true, stale = false, now)
                val forecast = weather.forecast(resolved, now)
                store.saveWeather(forecast)
                weatherAnswer(request, forecast, cached = false, stale = false, now)
            }
            result ?: staleOrFailure(request, cached, now, "天气服务响应超时，请稍后重试。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            staleOrFailure(request, cached, now, "天气服务暂时连接失败，请稍后重试。")
        }
    }

    private fun staleOrFailure(request: DailyIntent.Weather, cached: DailyWeatherSnapshot?, now: Instant, message: String): DailyCommandResult {
        val usable = cached?.takeIf { DailyCommandRepository.usableStale(it, now) }
        return if (usable != null && selectedDays(request, usable, now).isNotEmpty())
            weatherAnswer(request, usable, cached = true, stale = true, now)
        else DailyCommandResult.Failure(request, "WEATHER_UNAVAILABLE", message, now, "Open-Meteo")
    }

    private fun weatherAnswer(request: DailyIntent.Weather, snapshot: DailyWeatherSnapshot, cached: Boolean, stale: Boolean, now: Instant): DailyCommandResult {
        val selected = selectedDays(request, snapshot, now)
        if (selected.isEmpty()) return DailyCommandResult.Failure(request, "WEATHER_DATE_UNAVAILABLE", "缓存中缺少目标日期，请稍后联网重试。", now, "Open-Meteo")
        val zone = runCatching { ZoneId.of(snapshot.city.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val today = now.atZone(zone).toLocalDate()
        val rows = selected.map { day ->
            val title = when (day.date) { today -> "今天"; today.plusDays(1) -> "明天"; else -> "${day.date.monthValue}月${day.date.dayOfMonth}日" }
            val range = listOfNotNull(day.minimumCelsius?.let(::celsius), day.maximumCelsius?.let(::celsius)).joinToString("～")
            val rain = day.precipitationProbabilityPercent?.let { "，降水概率 $it%" }.orEmpty()
            DailyResultField(title, "${DailyWeatherDescriptions.wmo(day.weatherCode)}${if (range.isNotBlank()) "，$range" else ""}$rain")
        }.toMutableList()
        if (request.period == WeatherPeriod.TODAY) snapshot.currentTemperatureCelsius?.let {
            rows.add(0, DailyResultField("当前", "${celsius(it)}，${snapshot.currentWeatherCode?.let(DailyWeatherDescriptions::wmo) ?: "实时预报"}"))
        }
        selected.firstOrNull()?.let { day ->
            day.precipitationMillimeters?.let { rows += DailyResultField("日降水量", "${decimal(it)} mm") }
            day.rainMillimeters?.let { rows += DailyResultField("日降雨量", "${decimal(it)} mm") }
        }
        val dataTime = snapshot.observationAt ?: snapshot.fetchedAt
        rows += DailyResultField("数据时间", dataTime.atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)))
        rows += DailyResultField("更新", if (stale) "联网失败，显示已过期缓存" else if (cached) "15 分钟内缓存" else "本次联网获取")
        val summary = if (request.rainOnly) {
            val day = selected.first()
            val status = when {
                DailyWeatherDescriptions.hasRain(day.weatherCode) || (day.rainMillimeters ?: 0.0) > 0 -> "预报有雨"
                (day.precipitationProbabilityPercent ?: 0) >= 30 -> "有降水可能"
                else -> "预报暂无明显降雨"
            }
            val dayLabel = if (request.period == WeatherPeriod.TOMORROW) "明天" else "今天"
            "${snapshot.city.name}$dayLabel，$status${day.precipitationProbabilityPercent?.let { "，降水概率 $it%" }.orEmpty()}。"
        } else "${snapshot.city.name}：${rows.take(if (request.period == WeatherPeriod.THREE_DAYS) 3 else 1).joinToString("；") { it.label + " " + it.value }}。"
        return DailyCommandResult.WeatherAnswer(request, snapshot.city, snapshot, cached, stale,
            (if (stale) "联网失败，以下为缓存预报。" else "") + summary, rows)
    }

    private fun selectedDays(request: DailyIntent.Weather, snapshot: DailyWeatherSnapshot, now: Instant): List<DailyWeatherDay> {
        val zone = runCatching { ZoneId.of(snapshot.city.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val today = now.atZone(zone).toLocalDate()
        return snapshot.days.filter { when (request.period) {
            WeatherPeriod.TODAY -> it.date == today
            WeatherPeriod.TOMORROW -> it.date == today.plusDays(1)
            WeatherPeriod.THREE_DAYS -> !it.date.isBefore(today) && it.date.isBefore(today.plusDays(3))
        } }.sortedBy { it.date }.take(3)
    }

    private fun weekLabel(day: DayOfWeek): String = "星期" + when (day) {
        DayOfWeek.MONDAY -> "一"; DayOfWeek.TUESDAY -> "二"; DayOfWeek.WEDNESDAY -> "三"; DayOfWeek.THURSDAY -> "四"
        DayOfWeek.FRIDAY -> "五"; DayOfWeek.SATURDAY -> "六"; DayOfWeek.SUNDAY -> "日"
    }
    private fun normalizeCity(raw: String): String = raw.trim().removeSuffix("市").removeSuffix("县").removeSuffix("区")
    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
    private fun celsius(value: Double): String = decimal(value) + "℃"
    internal fun durationLabel(seconds: Int): String = buildString {
        if (seconds / 3600 > 0) append("${seconds / 3600}小时")
        if (seconds / 60 % 60 > 0) append("${seconds / 60 % 60}分钟")
        if (seconds % 60 > 0) append("${seconds % 60}秒")
    }
}
