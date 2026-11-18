package io.github.mangi.eta.agent.quickactions

import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** 国内地名/行政区查询。一次请求同时获取位置和预报；不发送未支持的经纬度参数或使用 IP 猜当前位置。 */
internal class UapiWeatherService(
    private val client: OkHttpClient = sharedClient,
    private val clock: Clock = Clock.systemUTC(),
) : DailyWeatherService {
    private val cache = if (client === sharedClient) sharedCache else ForecastCache()

    override suspend fun searchCities(query: String): List<WeatherCity> {
        val name = query.trim()
        require(name.length in 2..WeatherPlaces.MAX_QUERY_LENGTH && name.none(Char::isISOControl))
        return listOf(fetch("city", name).city)
    }

    override suspend fun forecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot {
        val result = if (city.adcode?.matches(Regex("[0-9]{6}")) == true) fetch("adcode", city.adcode)
        else {
            require(city.locationSource != "UAPI") { "UAPI location requires adcode" }
            val query = listOf(city.country.takeUnless { it == "中国" }, city.admin1, city.admin2, city.admin3, city.name)
                .filterNotNull().filter(String::isNotBlank).distinct().joinToString(" ")
            fetch("city", query)
        }
        // 保存调用者原始 GPS/Photon/手动坐标，UAPI 只提供对应行政区域预报；不写假 0,0。
        return result.copy(city = if (city.hasCoordinates) city.copy(adcode = result.city.adcode, timezone = result.city.timezone) else result.city)
    }

    override suspend fun refreshForecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot {
        cache.lock.withLock { cache.values.clear() }
        return forecast(city, now)
    }

    private suspend fun fetch(parameter: String, value: String): DailyWeatherSnapshot = cache.lock.withLock {
        val key = "$parameter:$value"
        val now = clock.instant()
        cache.values[key]?.takeIf { fresh(it, now) }?.let { return@withLock it }
        val delayLeft = Duration.between(now, cache.retryAt).seconds
        if (delayLeft > 0) throw WeatherServiceException("WEATHER_RATE_LIMITED", "天气服务请求过于频繁，请 $delayLeft 秒后重试。", delayLeft)
        val url = "https://uapis.cn/api/v1/misc/weather".toHttpUrl().newBuilder()
            .addQueryParameter(parameter, value).addQueryParameter("forecast", "true").addQueryParameter("lang", "zh").build()
        val parsed = try {
            decode(json(Request.Builder().url(url).build()), clock.instant())
        } catch (error: WeatherServiceException) {
            if (error.code == "WEATHER_RATE_LIMITED") cache.retryAt = clock.instant().plusSeconds(error.retryAfterSeconds ?: 60)
            throw error
        }
        if (parameter == "adcode" && parsed.city.adcode != value) {
            throw WeatherServiceException("WEATHER_LOCATION_MISMATCH", "天气服务返回地点与所选行政区不一致，请重新选择。")
        }
        cache.values[key] = parsed
        parsed.city.adcode?.let { cache.values["adcode:$it"] = parsed }
        parsed
    }

    private suspend fun json(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().header("Accept", "application/json")
            .header("User-Agent", "Eta-Ultra-Weather (+https://github.com/q3330576893/Eta-por-ultra)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val data = response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw when (it.code) {
                            429 -> WeatherServiceException("WEATHER_RATE_LIMITED", "天气服务请求过于频繁，请稍后重试。", (it.header("Retry-After")?.toLongOrNull() ?: 60).coerceIn(1, 300))
                            401, 402, 403 -> WeatherServiceException("WEATHER_ACCESS_DENIED", "天气服务权限或额度暂时不可用（HTTP ${it.code}）。")
                            else -> WeatherServiceException("WEATHER_HTTP", "天气服务请求失败（HTTP ${it.code}）。")
                        }
                        val source = it.body.source()
                        if (source.request(262_145)) throw WeatherServiceException("WEATHER_RESPONSE_INVALID", "天气响应超出大小限制。")
                        JSONObject(source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resume(data)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private class ForecastCache {
        val lock = Mutex()
        var retryAt: Instant = Instant.EPOCH
        val values = object : LinkedHashMap<String, DailyWeatherSnapshot>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DailyWeatherSnapshot>?): Boolean = size > 32
        }
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        private val sharedCache = ForecastCache()
        private val zone = ZoneId.of("Asia/Shanghai")

        private fun fresh(snapshot: DailyWeatherSnapshot, now: Instant): Boolean =
            !snapshot.fetchedAt.isAfter(now) && Duration.between(snapshot.fetchedAt, now) < Duration.ofMinutes(10) &&
                snapshot.days.any { it.date == now.atZone(zone).toLocalDate() }

        internal fun decode(json: JSONObject, fetchedAt: Instant): DailyWeatherSnapshot {
            val code = json.optString("adcode").takeIf { it.matches(Regex("[0-9]{6}")) }
                ?: throw WeatherServiceException("WEATHER_RESPONSE_INVALID", "天气服务未返回有效行政区。")
            fun text(key: String): String = if (json.isNull(key)) "" else json.optString(key).trim().take(100)
            fun number(row: JSONObject, key: String): Double? = if (row.isNull(key)) null else row.optDouble(key).takeIf { it.isFinite() && it in -100.0..70.0 }
            val province = text("province")
            val cityName = text("city")
            val district = text("district")
            val name = district.ifBlank { cityName }
            if (name.isBlank()) throw WeatherServiceException("WEATHER_RESPONSE_INVALID", "天气服务未返回地点名称。")
            val city = WeatherCity(code.toLong(), name, "CN", "中国", province, cityName, null, null, zone.id,
                locationSource = "UAPI", adcode = code)
            val observed = runCatching { LocalDateTime.parse(text("report_time").replace(' ', 'T')).atZone(zone).toInstant() }.getOrNull()
            val rows = json.optJSONArray("forecast")
            val days = if (rows == null) emptyList() else (0 until minOf(rows.length(), 7)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse(row.optString("date")) }.getOrNull() ?: return@mapNotNull null
                val condition = row.optString("weather_day").trim().take(80).takeUnless { it.isBlank() || it == "null" }
                DailyWeatherDay(date, WeatherWmoCodec.chinese(condition.orEmpty()), number(row, "temp_max"), number(row, "temp_min"),
                    null, null, null, condition)
            }
            if (days.isEmpty()) throw WeatherServiceException("WEATHER_FORECAST_MISSING", "天气服务暂未提供每日预报，请稍后重试。")
            val condition = text("weather").takeIf(String::isNotBlank)
            return DailyWeatherSnapshot(city, fetchedAt, observed, number(json, "temperature"),
                condition?.let(WeatherWmoCodec::chinese), null, days.distinctBy { it.date }.sortedBy { it.date },
                provider = "UAPI", providerUrl = "https://uapis.cn/docs/api-reference/get-misc-weather",
                currentConditionText = condition, regionLabel = city.displayName)
        }
    }
}

internal object WeatherWmoCodec {
    fun chinese(text: String): Int = when {
        text.contains("冰雹") -> 96
        text.contains("雷") -> 95
        text.contains("雨夹雪") -> 68
        text.contains("暴雪") || text.contains("大雪") -> 75
        text.contains("中雪") -> 73
        text.contains("雪") -> 71
        text.contains("暴雨") || text.contains("大雨") -> 65
        text.contains("中雨") -> 63
        text.contains("阵雨") -> 80
        text.contains("小雨") || text.contains("毛毛") -> 61
        text.contains("雾") -> 45
        text.contains("霾") || text.contains("沙尘") -> -1
        text.contains("阴") -> 3
        text.contains("多云") -> 2
        text.contains("晴") -> 0
        else -> -1
    }
}
