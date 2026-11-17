package io.github.mangi.eta.agent.quickactions

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyQuickActionTest {
    private val now = Instant.parse("2026-11-16T06:20:00Z")
    private val city = WeatherCity(1, "北京", "CN", "中国", "北京市", "北京市", 39.9, 116.4, "Asia/Shanghai")

    @Test fun noCityWithoutSavedCityReturnsNeedsCity() = runBlocking {
        val service = FakeWeather(city)
        val result = QuickActionsExecutor.executeWeather(DailyIntent.Weather(), null, true, FakeStore(), service, now)
        assertTrue(result is DailyCommandResult.NeedsCity)
        assertEquals(0, service.calls)
    }

    @Test fun selectedCityIsRememberedAndWeatherUsesStructuredForecast() = runBlocking {
        val store = FakeStore()
        val service = FakeWeather(city)
        val result = QuickActionsExecutor.executeWeather(DailyIntent.Weather("北京"), city, true, store, service, now)
        assertTrue(result is DailyCommandResult.WeatherAnswer)
        assertEquals(city, store.saved)
        assertEquals(1, service.calls)
    }

    @Test fun freshSavedWeatherAvoidsAnyNetworkCall() = runBlocking {
        val store = FakeStore().apply { saved = city; cached = forecast(city, now.minusSeconds(60)) }
        val service = FakeWeather(city)
        val result = QuickActionsExecutor.executeWeather(DailyIntent.Weather(), null, true, store, service, now)
        assertTrue(result is DailyCommandResult.WeatherAnswer && result.cached && !result.stale)
        assertEquals(0, service.calls)
    }

    private class FakeStore : DailyCommandStore {
        var saved: WeatherCity? = null
        var cached: DailyWeatherSnapshot? = null
        override fun preferredCity() = saved
        override fun savePreferredCity(city: WeatherCity) { saved = city }
        override fun cachedWeather(city: WeatherCity) = cached
        override fun saveWeather(snapshot: DailyWeatherSnapshot) { cached = snapshot }
    }

    private class FakeWeather(private val city: WeatherCity) : DailyWeatherService {
        var calls = 0
        override suspend fun searchCities(query: String): List<WeatherCity> { calls++; return listOf(city) }
        override suspend fun forecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot {
            calls++
            return DailyQuickActionTest.forecast(city, now)
        }
    }

    companion object {
        private fun forecast(city: WeatherCity, now: Instant) = DailyWeatherSnapshot(
            city, now, now, 21.0, 1, 0.0,
            listOf(
                DailyWeatherDay(LocalDate.of(2026, 11, 16), 1, 24.0, 12.0, 0.0, 0.0, 5),
                DailyWeatherDay(LocalDate.of(2026, 11, 17), 61, 20.0, 10.0, 2.0, 1.5, 55),
                DailyWeatherDay(LocalDate.of(2026, 11, 18), 3, 18.0, 8.0, 0.0, 0.0, 0),
            ),
        )
    }
}
