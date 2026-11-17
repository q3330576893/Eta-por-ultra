package io.github.mangi.eta.ui.screens.weather

import android.app.Application
import io.github.mangi.eta.agent.quickactions.WeatherCity
import io.github.mangi.eta.data.repository.WeatherPreferences
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 天气预报设置偏好的持久化与边界：开关、地点往返、清除、更新时间、损坏 JSON 兜底。 */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeatherPreferencesTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences(WeatherPreferences.PREF_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun useCurrentLocationDefaultsFalseAndPersists() {
        assertFalse(WeatherPreferences.useCurrentLocation(context))
        WeatherPreferences.setUseCurrentLocation(context, true)
        assertTrue(WeatherPreferences.useCurrentLocation(context))
        WeatherPreferences.setUseCurrentLocation(context, false)
        assertFalse(WeatherPreferences.useCurrentLocation(context))
    }

    @Test
    fun selectedCityRoundTripsThroughStructuredCodec() {
        val city = WeatherCity(
            id = 123L, name = "芦苇村", countryCode = "CN", country = "中国",
            admin1 = "浙江省", admin2 = "湖州市", latitude = 30.8, longitude = 120.1,
            timezone = "Asia/Shanghai",
        )
        WeatherPreferences.saveSelectedCity(context, city)
        assertEquals(city, WeatherPreferences.selectedCity(context))
        assertEquals("芦苇村", WeatherPreferences.snapshot(context).selectedCity?.name)
        assertEquals(30.8, WeatherPreferences.snapshot(context).selectedCity?.latitude ?: 0.0, 0.0)
    }

    @Test
    fun clearingSelectedCityRemovesItFromSnapshot() {
        val city = WeatherCity(1, "北京", "CN", "中国", "北京市", "北京市", 39.9, 116.4, "Asia/Shanghai")
        WeatherPreferences.saveSelectedCity(context, city)
        assertEquals(city, WeatherPreferences.selectedCity(context))
        WeatherPreferences.saveSelectedCity(context, null)
        assertNull(WeatherPreferences.selectedCity(context))
    }

    @Test
    fun markUpdatedStoresProvidedTimestamp() {
        assertEquals(0L, WeatherPreferences.lastUpdatedMillis(context))
        WeatherPreferences.markUpdated(context, 9_876L)
        assertEquals(9_876L, WeatherPreferences.lastUpdatedMillis(context))
        assertEquals(9_876L, WeatherPreferences.snapshot(context).lastUpdatedMillis)
    }

    @Test
    fun corruptedCityJsonFallsBackToEmptySnapshotWithoutCrash() {
        context.getSharedPreferences(WeatherPreferences.PREF_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString("selected_city", "{not-json").commit()
        val snapshot = WeatherPreferences.snapshot(context)
        assertNull(snapshot.selectedCity)
        assertFalse(snapshot.useCurrentLocation)
    }

    @Test
    fun oversizedCityJsonIsIgnored() {
        context.getSharedPreferences(WeatherPreferences.PREF_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString("selected_city", "x".repeat(5000)).commit()
        assertNull(WeatherPreferences.selectedCity(context))
    }
}
