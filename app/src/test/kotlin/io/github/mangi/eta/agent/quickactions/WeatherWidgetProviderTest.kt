package io.github.mangi.eta.agent.quickactions

import android.content.Context
import io.github.mangi.eta.data.repository.WeatherPreferences
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class WeatherWidgetProviderTest {
    private val now = Instant.parse("2026-11-19T04:00:00Z")
    private val context get() = RuntimeEnvironment.getApplication()
    private fun snapshot(at: Instant = now) = UapiWeatherService.decode(JSONObject("""{"adcode":"360723","city":"赣州","district":"大余县","forecast":[{"date":"2026-11-19","weather_day":"晴","temp_min":12,"temp_max":24}]}"""), at)
    @Before fun reset() {
        listOf("eta_daily_commands", WeatherPreferences.PREF_NAME).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }
    @Test fun cachedFirstMarksOldDataStaleAndKeepsRealUpdateTime() {
        val old = snapshot(now.minusSeconds(3600))
        val model = WeatherWidgetProvider.cachedModel(false, old.city, old, now)
        assertTrue(model.stale)
        assertTrue(model.forecast!!.contains("12°–24°"))
        assertEquals(old.fetchedAt, model.updatedAt)
        assertTrue(model.place!!.contains("大余县"))
    }
    @Test fun yesterdayAndWrongLocationsAreNeverShownAsToday() {
        val current = snapshot()
        assertNull(WeatherWidgetProvider.cachedModel(false, current.city, current, now.plusSeconds(86400)).forecast)
        val other = current.city.copy(adcode = "110101")
        assertNull(WeatherWidgetProvider.cachedModel(false, other, current, now).forecast)
        assertNull(WeatherWidgetProvider.cachedModel(true, null, current, now).forecast)
    }
    @Test fun missingLocationDefaultAndPassiveRenderingDoNotExecuteWeather() = runBlocking {
        var executions = 0
        val answer = WeatherWidgetProvider.refresh(context) { executions++; error("unexpected request") }
        assertTrue(answer is DailyCommandResult.NeedsCity)
        WeatherWidgetProvider.updateAll(context)
        assertEquals(0, executions)
    }
    @Test fun explicitRefreshUsesExistingExecutorOnlyWhenPreferenceHasLocation() = runBlocking {
        WeatherPreferences.saveSelectedCity(context, snapshot().city)
        var executions = 0
        val expected = DailyCommandResult.NeedsCity(DailyIntent.Weather(), now)
        assertEquals(expected, WeatherWidgetProvider.refresh(context) { executions++; expected })
        assertEquals(1, executions)
        WeatherPreferences.setUseCurrentLocation(context, true)
        WeatherPreferences.saveSelectedCity(context, null)
        WeatherWidgetProvider.refresh(context) { executions++; expected }
        assertEquals(2, executions)
    }
    @Test fun cacheOnlyGetterIsBoundedAndReusesRepositoryEncoding() {
        val store = DailyCommandRepository(context)
        val value = snapshot()
        store.saveWeather(value)
        assertEquals(value, store.lastCachedWeather())
        context.getSharedPreferences("eta_daily_commands", Context.MODE_PRIVATE).edit().putString("weather_cache", "x".repeat(32769)).commit()
        assertNull(store.lastCachedWeather())
    }
}
