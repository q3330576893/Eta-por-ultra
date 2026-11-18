package io.github.mangi.eta.agent.quickactions

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.provider.AlarmClock
import android.provider.CalendarContract
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.data.repository.WeatherPreferences
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
        DailyCommandRepository(context), ComposedWeatherService(), Clock.systemDefaultZone(), forceRefresh)

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
            DailyIntent.AlarmSetup -> DailyCommandResult.NeedsAlarmTime(asOf = now)
            is DailyIntent.SetAlarm -> handoffAlarm(context, request, now)
            is DailyIntent.SetTimer -> handoffTimer(context, request, now)
            is DailyIntent.Weather -> executeWeatherEntry(context, request, selectedCity, rememberCity, store, weather, now, forceRefresh)
            is DailyIntent.FlashlightAction -> LifeActionExecutor.executeFlashlight(context, request, now)
            is DailyIntent.SetVolumeAction -> LifeActionExecutor.executeSetVolume(context, request, now)
            is DailyIntent.AdjustVolumeAction -> LifeActionExecutor.executeAdjustVolume(context, request, now)
            is DailyIntent.SetRingerAction -> LifeActionExecutor.executeRinger(context, request, now)
            is DailyIntent.OpenAppAction -> LifeActionExecutor.executeOpenApp(context, request, now)
            is DailyIntent.NavigateAction -> LifeActionExecutor.executeNavigate(context, request, now)
            DailyIntent.BatteryStatus -> LifeActionExecutor.executeBattery(context, now)
            DailyIntent.StorageStatus -> LifeActionExecutor.executeStorage(context, now)
            is DailyIntent.MediaPlaybackAction -> LifeActionExecutor.executeMediaPlayback(context, request, now)
            is DailyIntent.DndAction -> LifeActionExecutor.executeDnd(context, request, now)
            is DailyIntent.SystemPanelAction -> LifeActionExecutor.executeSystemPanel(context, request, now)
            DailyIntent.AlarmQuery -> LifeActionExecutor.executeAlarmQuery(context, now)
            is DailyIntent.NoteAction -> executeNote(context, request, now)
            DailyIntent.ViewNotes -> executeViewNotes(context, now)
            is DailyIntent.CalcFollowUp -> executeCalcFollowUp(request, now)
        }
    }

    /**
     * 天气快捷指令入口。仅在「询问天气使用当前位置」偏好开启、且用户没有任何显式地点输入时，
     * 取得新鲜坐标后解析行政区，优先国内预报；其余情况原样委托给
     * [executeWeather]，保证地点搜索/常用城市逻辑只有一处实现（主代理并行维护该函数）。
     */
    private suspend fun executeWeatherEntry(
        context: Context,
        request: DailyIntent.Weather,
        selectedCity: WeatherCity?,
        rememberCity: Boolean,
        store: DailyCommandStore,
        weather: DailyWeatherService,
        now: Instant,
        forceRefresh: Boolean,
    ): DailyCommandResult {
        val useCurrentLocation = selectedCity == null && request.cityQuery.isNullOrBlank() &&
            !request.chooseLocation && WeatherPreferences.useCurrentLocation(context)
        if (!useCurrentLocation) {
            return executeWeather(request, selectedCity, rememberCity, store, weather, now, forceRefresh)
        }
        val current = withContext(Dispatchers.IO) { DeviceLocationProvider.current(context) }
        val city = when (current) {
            is DeviceLocationProvider.Result.Available -> WeatherLocationResolver.resolve(context, current)
            is DeviceLocationProvider.Result.Unavailable -> null
        }
        // 当前位置只是临时坐标，不写回常用城市，避免覆盖用户显式选择的地点。
        return if (city != null) {
            executeWeather(request, city, rememberCity = false, store, weather, now, forceRefresh)
        } else {
            val status = (current as? DeviceLocationProvider.Result.Unavailable)?.status.orEmpty()
            DailyCommandResult.Failure(
                request, "LOCATION_${status.uppercase(Locale.ROOT)}",
                DeviceLocationProvider.failureMessage(status), now, "设备定位",
            )
        }
    }

    private fun currentLocationHash(latitude: Double, longitude: Double): Long =
        -kotlin.math.abs((latitude * 100_000).toLong() * 31L + (longitude * 100_000).toLong())

    internal fun executeCalculation(request: DailyIntent.Calculate, now: Instant): DailyCommandResult =
        when (val calculation = SimpleCalculator.evaluate(request.expression)) {
            is SimpleCalculator.Result.Answer -> {
                CalculationHistory.push(calculation.value)
                DailyCommandResult.CalculationAnswer(
                    request, "计算结果是 ${calculation.value}。", now,
                    listOf(DailyResultField("计算式", request.expression), DailyResultField("结果", calculation.value)),
                )
            }
            is SimpleCalculator.Result.Failure -> DailyCommandResult.Failure(
                request, calculation.code, calculation.message, now, "本地计算",
            )
        }

    /** 记事：正文原样存入本地 NoteStore，verified=true（本地读回命中）。 */
    internal fun executeNote(
        context: android.content.Context,
        request: DailyIntent.NoteAction,
        now: Instant,
    ): DailyCommandResult {
        val notes = NoteStore.add(context, request.text, now.toEpochMilli())
        val saved = notes.firstOrNull()
        return DailyCommandResult.ActionDone(
            request = request,
            title = "备忘",
            summary = "已记下：${saved?.text ?: request.text}",
            fields = listOf(
                DailyResultField("内容", saved?.text ?: request.text),
                DailyResultField("已存条数", "${notes.size} / ${NoteStore.MAX_NOTES}"),
            ),
            asOf = now,
            verified = saved?.text == request.text.take(NoteStore.MAX_NOTE_LENGTH),
            sourceLabel = "本地备忘",
        )
    }

    /** 查看备忘：返回本地结果卡 fields，verified=true。 */
    internal fun executeViewNotes(
        context: android.content.Context,
        now: Instant,
    ): DailyCommandResult {
        val notes = NoteStore.all(context)
        if (notes.isEmpty()) {
            return DailyCommandResult.ActionDone(
                request = DailyIntent.ViewNotes,
                title = "备忘列表",
                summary = "还没有备忘。说「记一下……」即可添加。",
                fields = listOf(DailyResultField("条数", "0")),
                asOf = now,
                verified = true,
                sourceLabel = "本地备忘",
            )
        }
        val fields = buildList {
            add(DailyResultField("条数", "${notes.size}"))
            notes.take(20).forEachIndexed { index, note ->
                add(DailyResultField("第 ${index + 1} 条", note.text))
            }
            if (notes.size > 20) add(DailyResultField("提示", "仅显示最近 20 条，共 ${notes.size} 条。"))
        }
        return DailyCommandResult.ActionDone(
            request = DailyIntent.ViewNotes,
            title = "备忘列表",
            summary = "共有 ${notes.size} 条备忘。",
            fields = fields,
            asOf = now,
            verified = true,
            sourceLabel = "本地备忘",
        )
    }

    /** 追算：复用上次计算结果 + SimpleCalculator，无历史明确失败。 */
    internal fun executeCalcFollowUp(request: DailyIntent.CalcFollowUp, now: Instant): DailyCommandResult {
        val expression = CalculationHistory.expressionFor(request.op, request.operand)
            ?: return DailyCommandResult.Failure(
                request, "NO_CALCULATION_HISTORY", "没有可继续计算的上次结果，请先说一个计算式。", now, "本地计算")
        return when (val calculation = SimpleCalculator.evaluate(expression)) {
            is SimpleCalculator.Result.Answer -> {
                CalculationHistory.push(calculation.value)
                DailyCommandResult.CalculationAnswer(
                    request, "计算结果是 ${calculation.value}。", now,
                    listOf(DailyResultField("计算式", expression), DailyResultField("结果", calculation.value)),
                )
            }
            is SimpleCalculator.Result.Failure -> DailyCommandResult.Failure(
                request, calculation.code, calculation.message, now, "本地计算",
            )
        }
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
        var city = selectedCity ?: saved?.takeIf { savedCity -> !request.chooseLocation && (
            requestedCity.isNullOrBlank() || normalizeCity(requestedCity) == normalizeCity(savedCity.name))
        }
        if (city == null && request.cityQuery.isNullOrBlank()) return@withContext DailyCommandResult.NeedsCity(request, now)
        var cached: DailyWeatherSnapshot? = city?.let(store::cachedWeather)
        try {
            val result = withTimeoutOrNull(12_000L) {
                if (city == null) {
                    val query = request.cityQuery!!.trim()
                    if (query.length !in 2..WeatherPlaces.MAX_QUERY_LENGTH) return@withTimeoutOrNull DailyCommandResult.NeedsCity(request, now, "请输入 2 至 ${WeatherPlaces.MAX_QUERY_LENGTH} 个字符的地点名，可包含省、市、区县、乡镇。")
                    val cities = weather.searchCities(query)
                    if (cities.isEmpty()) return@withTimeoutOrNull DailyCommandResult.NeedsCity(request, now, "地点库暂未找到该地点。请补充省、市、区县后重试，或输入附近已收录地点；不会自动用市级地点冒充乡镇。")
                    if (cities.size > 1 || request.chooseLocation) return@withTimeoutOrNull DailyCommandResult.CityChoices(request, cities, now)
                    city = cities.single()
                }
                val resolved = requireNotNull(city)
                if (rememberCity && selectedCity != null) store.savePreferredCity(resolved)
                cached = store.cachedWeather(resolved)
                val fresh = cached?.takeIf { !forceRefresh && DailyCommandRepository.isFresh(it, now) && selectedDays(request, it, now).isNotEmpty() }
                if (fresh != null) return@withTimeoutOrNull weatherAnswer(request, fresh, cached = true, stale = false, now)
                val forecast = if (forceRefresh) weather.refreshForecast(resolved, now) else weather.forecast(resolved, now)
                store.saveWeather(forecast)
                weatherAnswer(request, forecast, cached = false, stale = false, now)
            }
            result ?: staleOrFailure(request, cached, now, "天气服务响应超时，请稍后重试。", "WEATHER_TIMEOUT")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            staleOrFailure(request, cached, now, WeatherErrors.describe(error), WeatherErrors.code(error))
        }
    }

    private fun staleOrFailure(request: DailyIntent.Weather, cached: DailyWeatherSnapshot?, now: Instant, message: String, code: String = "WEATHER_UNAVAILABLE"): DailyCommandResult {
        val usable = cached?.takeIf { DailyCommandRepository.usableStale(it, now) }
        return if (usable != null && selectedDays(request, usable, now).isNotEmpty())
            weatherAnswer(request, usable, cached = true, stale = true, now)
        else DailyCommandResult.Failure(request, code, message, now, "天气服务")
    }

    private fun weatherAnswer(request: DailyIntent.Weather, snapshot: DailyWeatherSnapshot, cached: Boolean, stale: Boolean, now: Instant): DailyCommandResult {
        val selected = selectedDays(request, snapshot, now)
        if (selected.isEmpty()) return DailyCommandResult.Failure(request, "WEATHER_DATE_UNAVAILABLE", "预报中缺少目标日期，请稍后重试。", now, snapshot.provider)
        val zone = runCatching { ZoneId.of(snapshot.city.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val today = now.atZone(zone).toLocalDate()
        val rows = selected.map { day ->
            val title = when (day.date) { today -> "今天"; today.plusDays(1) -> "明天"; else -> "${day.date.monthValue}月${day.date.dayOfMonth}日" }
            val range = listOfNotNull(day.minimumCelsius?.let(::celsius), day.maximumCelsius?.let(::celsius)).joinToString("～")
            val rain = day.precipitationProbabilityPercent?.let { "，降水概率 $it%" }.orEmpty()
            DailyResultField(title, "${WeatherBriefing.describe(day)}${if (range.isNotBlank()) "，$range" else ""}$rain")
        }.toMutableList()
        if (request.period == WeatherPeriod.TODAY) snapshot.currentTemperatureCelsius?.let {
            rows.add(0, DailyResultField("当前", "${celsius(it)}，${snapshot.currentConditionText ?: snapshot.currentWeatherCode?.let(DailyWeatherDescriptions::wmo) ?: "实时预报"}"))
        }
        selected.firstOrNull()?.let { day ->
            day.precipitationMillimeters?.let { rows += DailyResultField("日降水量", "${decimal(it)} mm") }
            day.rainMillimeters?.let { rows += DailyResultField("日降雨量", "${decimal(it)} mm") }
        }
        rows += DailyResultField("预报地点", snapshot.city.displayName)
        if (snapshot.city.hasCoordinates) rows += DailyResultField("坐标", "${snapshot.city.latitude}，${snapshot.city.longitude}")
        snapshot.regionLabel?.let { rows += DailyResultField("预报覆盖", it) }
        rows += DailyResultField("精度说明", if (snapshot.provider == "UAPI") "行政区域预报，乡镇查询可能返回所属区县天气。" else "按坐标匹配预报网格。")
        val dataTime = snapshot.observationAt ?: snapshot.fetchedAt
        rows += DailyResultField("数据时间", dataTime.atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)))
        rows += DailyResultField("更新", if (stale) "联网失败，显示已过期缓存" else if (cached) "15 分钟内缓存" else "本次联网获取")
        val summary = WeatherBriefing.speak(request, snapshot, selected, today, stale)
        return DailyCommandResult.WeatherAnswer(request, snapshot.city, snapshot, cached, stale, summary, rows)
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
