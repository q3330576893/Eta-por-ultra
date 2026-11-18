package io.github.mangi.eta.agent.quickactions

import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

internal interface DailyWeatherService {
    suspend fun searchCities(query: String): List<WeatherCity>
    suspend fun forecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot
    suspend fun refreshForecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot = forecast(city, now)
}

/** 坐标预报与地点搜索分离。Photon 只接收用户提交的地名，不接收设备当前位置。 */
internal class OpenMeteoWeatherService(
    private val client: OkHttpClient = sharedClient,
) : DailyWeatherService {
    private val search = if (client === sharedClient) sharedSearch else SearchState()

    override suspend fun searchCities(query: String): List<WeatherCity> {
        val name = query.trim()
        require(name.length in 2..WeatherPlaces.MAX_QUERY_LENGTH && name.none(Char::isISOControl))
        val key = name.lowercase(Locale.ROOT)
        return search.mutex.withLock {
            val cached = search.entries[key]
            if (cached != null && monotonicMillis() - cached.savedAt <= if (cached.results.isEmpty()) 60_000 else 900_000) {
                return@withLock cached.results
            }
            val sources: List<suspend () -> List<WeatherCity>> = if (WeatherPlaces.detailed(name)) {
                listOf({ photon(name) }, { geoNames(name) })
            } else listOf({ geoNames(name) }, { photon(name) })
            var failure: Exception? = null
            for (source in sources) {
                try {
                    val matches = WeatherPlaces.select(name, source())
                    if (matches.isNotEmpty()) {
                        search.entries[key] = CachedSearch(monotonicMillis(), matches)
                        return@withLock matches
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // 任意提供者不可用且另一方无结果时，保留故障而非错误地称地点不存在。
                    if (failure == null || error is WeatherServiceException && error.code == "WEATHER_RATE_LIMITED") failure = error
                }
            }
            failure?.let { throw it }
            emptyList<WeatherCity>().also { search.entries[key] = CachedSearch(monotonicMillis(), it) }
        }
    }

    private suspend fun geoNames(name: String): List<WeatherCity> {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", WeatherPlaces.leafQuery(name)).addQueryParameter("language", "zh")
            .addQueryParameter("count", "20").addQueryParameter("format", "json").build()
        val data = json(Request.Builder().url(url).build(), "地名服务")
        require(!data.optBoolean("error"))
        return DailyWeatherCodec.cities(data)
    }

    private suspend fun photon(name: String): List<WeatherCity> {
        val remainingMs = search.photonBackoffUntil - monotonicMillis()
        if (remainingMs > 0) throw rateLimited("乡镇搜索服务", (remainingMs + 999) / 1000)
        // 串行去重且只在明确查询时请求。输入过程中没有任何网络调用。
        delay((search.lastPhotonRequest + 1_200 - monotonicMillis()).coerceIn(0, 1_200))
        search.lastPhotonRequest = monotonicMillis()
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", WeatherPlaces.fullQuery(name)).addQueryParameter("limit", "15").build()
        return try {
            WeatherPlaces.photon(json(Request.Builder().url(url).build(), "乡镇搜索服务"))
        } catch (error: WeatherServiceException) {
            if (error.code == "WEATHER_RATE_LIMITED") {
                search.photonBackoffUntil = monotonicMillis() + (error.retryAfterSeconds ?: 60) * 1_000
            }
            throw error
        }
    }

    override suspend fun forecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot {
        require(city.hasCoordinates) { "Coordinate forecast requires observed coordinates" }
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", city.latitude.toString())
            .addQueryParameter("longitude", city.longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,precipitation")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,rain_sum,precipitation_probability_max")
            .addQueryParameter("timezone", city.timezone).addQueryParameter("forecast_days", "3")
            .addQueryParameter("temperature_unit", "celsius").build()
        val request = Request.Builder().url(url).build()
        val body = try {
            json(request, "天气预报服务")
        } catch (error: IOException) {
            // 弱网短暂断开或服务端 5xx 可再试一次。限流、拒绝、证书问题与超时不重复压测。
            if (WeatherErrors.code(error) !in setOf("WEATHER_NETWORK", "WEATHER_SERVER")) throw error
            delay(300)
            json(request, "天气预报服务")
        }
        return DailyWeatherCodec.forecast(body, city, now)
    }

    private suspend fun json(request: Request, service: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().header("Accept", "application/json")
            .header("User-Agent", "Eta-Ultra-Weather (+https://github.com/q3330576893/Eta-por-ultra)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw httpFailure(it, service)
                        val source = it.body.source()
                        if (source.request(262_145)) throw WeatherServiceException("WEATHER_RESPONSE_INVALID", "$service 返回的数据超出大小限制。")
                        JSONObject(source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun httpFailure(response: Response, service: String): WeatherServiceException = when (response.code) {
        429 -> rateLimited(service, retryAfter(response.header("Retry-After")))
        403 -> WeatherServiceException("WEATHER_ACCESS_DENIED", "$service 拒绝了请求（HTTP 403），请稍后重试；如需选点也可直接输入坐标。")
        in 500..599 -> WeatherServiceException("WEATHER_SERVER", "$service 暂时异常（HTTP ${response.code}），请稍后重试。")
        else -> WeatherServiceException("WEATHER_HTTP", "$service 请求失败（HTTP ${response.code}）。")
    }

    private data class CachedSearch(val savedAt: Long, val results: List<WeatherCity>)

    private class SearchState {
        val mutex = Mutex()
        var lastPhotonRequest = Long.MIN_VALUE / 2
        var photonBackoffUntil = Long.MIN_VALUE / 2
        val entries = object : LinkedHashMap<String, CachedSearch>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSearch>?): Boolean = size > 32
        }
    }

    private companion object {
        val sharedSearch = SearchState()
        val sharedClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

        fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

        fun retryAfter(raw: String?): Long = (raw?.toLongOrNull() ?: raw?.let {
            runCatching {
                ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - Instant.now().epochSecond
            }.getOrNull()
        } ?: 60).coerceIn(1, 300)

        fun rateLimited(service: String, seconds: Long) = WeatherServiceException(
            "WEATHER_RATE_LIMITED", "$service 达到请求频率上限，请 $seconds 秒后再试。", seconds,
        )
    }
}
