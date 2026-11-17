package io.github.mangi.eta.agent.quickactions

import android.content.Context
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

internal interface DailyCommandStore {
    fun preferredCity(): WeatherCity?
    fun savePreferredCity(city: WeatherCity)
    fun cachedWeather(city: WeatherCity): DailyWeatherSnapshot?
    fun saveWeather(snapshot: DailyWeatherSnapshot)
}

/** Only explicitly selected city coordinates and structured weather are stored. */
internal class DailyCommandRepository(context: Context) : DailyCommandStore {
    private val preferences = context.applicationContext.getSharedPreferences("eta_daily_commands", Context.MODE_PRIVATE)
    override fun preferredCity(): WeatherCity? = preferences.getString("weather_city", null)
        ?.takeIf { it.length <= 4096 }?.let { runCatching { DailyWeatherCodec.city(JSONObject(it)) }.getOrNull() }

    override fun savePreferredCity(city: WeatherCity) {
        preferences.edit().putString("weather_city", DailyWeatherCodec.encodeCity(city).toString()).apply()
    }

    override fun cachedWeather(city: WeatherCity): DailyWeatherSnapshot? = preferences.getString("weather_cache", null)
        ?.takeIf { it.length <= 32768 }?.let { raw -> runCatching { DailyWeatherCodec.snapshot(JSONObject(raw)) }.getOrNull() }
        ?.takeIf { sameCity(it.city, city) }

    override fun saveWeather(snapshot: DailyWeatherSnapshot) {
        preferences.edit().putString("weather_cache", DailyWeatherCodec.encodeSnapshot(snapshot).toString()).apply()
    }

    companion object {
        val CACHE_TTL: Duration = Duration.ofMinutes(15)
        val MAX_STALE_AGE: Duration = Duration.ofHours(24)
        fun sameCity(left: WeatherCity, right: WeatherCity): Boolean =
            left.id == right.id && kotlin.math.abs(left.latitude - right.latitude) < 0.0001 &&
                kotlin.math.abs(left.longitude - right.longitude) < 0.0001
        fun isFresh(snapshot: DailyWeatherSnapshot, now: Instant): Boolean {
            val age = Duration.between(snapshot.fetchedAt, now)
            return !age.isNegative && age < CACHE_TTL
        }
        fun usableStale(snapshot: DailyWeatherSnapshot, now: Instant): Boolean {
            val age = Duration.between(snapshot.fetchedAt, now)
            return !age.isNegative && age <= MAX_STALE_AGE
        }
    }
}
