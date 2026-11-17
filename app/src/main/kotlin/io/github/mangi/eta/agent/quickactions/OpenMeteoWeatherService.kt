package io.github.mangi.eta.agent.quickactions

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
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
}

/** Official structured APIs, no user API key, IP geolocation, model or web page. */
internal class OpenMeteoWeatherService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build(),
) : DailyWeatherService {
    override suspend fun searchCities(query: String): List<WeatherCity> {
        require(query.trim().length in 2..40)
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim()).addQueryParameter("language", "zh")
            .addQueryParameter("count", "10").addQueryParameter("format", "json").build()
        val cities = DailyWeatherCodec.cities(json(Request.Builder().url(url).build()))
        val cleaned = cityKey(query)
        val exact = cities.filter { cityKey(it.name) == cleaned }
        return (if (exact.isNotEmpty()) exact else cities).take(8)
    }

    override suspend fun forecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", city.latitude.toString())
            .addQueryParameter("longitude", city.longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,precipitation")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,rain_sum,precipitation_probability_max")
            .addQueryParameter("timezone", city.timezone).addQueryParameter("forecast_days", "3")
            .addQueryParameter("temperature_unit", "celsius").build()
        return DailyWeatherCodec.forecast(json(Request.Builder().url(url).build()), city, now)
    }

    private suspend fun json(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().header("Accept", "application/json")
            .header("User-Agent", "Eta-Ultra-Daily-Weather (+https://github.com/q3330576893/Eta-por-ultra)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw IOException("Weather HTTP ${it.code}")
                        val source = it.body.source()
                        if (source.request(262145L)) throw IOException("Weather response exceeds limit")
                        JSONObject(source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun cityKey(value: String): String = value.trim().removeSuffix("市").removeSuffix("县").removeSuffix("区")
}
