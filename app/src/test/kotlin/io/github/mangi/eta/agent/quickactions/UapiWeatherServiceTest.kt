package io.github.mangi.eta.agent.quickactions

import java.time.Instant
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UAPI 单请求解析：地点、预报与来源都来自同一次响应，坐标不被伪造。 */
class UapiWeatherServiceTest {
    private val fetchedAt = Instant.parse("2026-11-18T03:00:00Z")

    @Test
    fun oneResponseYieldsRegionAndDailyForecast() {
        val snapshot = UapiWeatherService.decode(JSONObject(RESPONSE), fetchedAt)
        assertEquals("大余县", snapshot.city.name)
        assertEquals("江西省", snapshot.city.admin1)
        assertEquals("赣州市", snapshot.city.admin2)
        assertEquals("360723", snapshot.city.adcode)
        // 行政编码查询没有坐标，必须保持未知，不能写成 0,0 冒充定位结果。
        assertNull(snapshot.city.latitude)
        assertNull(snapshot.city.longitude)
        assertFalse(snapshot.city.hasCoordinates)
        assertEquals("UAPI", snapshot.provider)
        assertEquals(2, snapshot.days.size)
        assertEquals("多云", snapshot.days[0].conditionText)
        assertEquals(24.0, snapshot.days[0].maximumCelsius!!, 0.001)
        assertEquals(12.0, snapshot.days[0].minimumCelsius!!, 0.001)
        assertEquals("晴", snapshot.currentConditionText)
        assertEquals(18.0, snapshot.currentTemperatureCelsius!!, 0.001)
        assertEquals(LocalDate.of(2026, 11, 18), snapshot.days[0].date)
    }

    @Test
    fun missingAdcodeIsRejectedInsteadOfGuessing() {
        val broken = JSONObject(RESPONSE).put("adcode", "")
        val error = runCatching { UapiWeatherService.decode(broken, fetchedAt) }.exceptionOrNull()
        assertTrue(error is WeatherServiceException)
        assertEquals("WEATHER_RESPONSE_INVALID", (error as WeatherServiceException).code)
    }

    @Test
    fun missingForecastIsReportedAsMissingNotAsTodayWeather() {
        val broken = JSONObject(RESPONSE).put("forecast", org.json.JSONArray())
        val error = runCatching { UapiWeatherService.decode(broken, fetchedAt) }.exceptionOrNull()
        assertEquals("WEATHER_FORECAST_MISSING", (error as WeatherServiceException).code)
    }

    @Test
    fun unknownConditionDoesNotInventRainOrClearSky() {
        // 未知天气词映射为 -1：既不能声称晴天，也不能声称下雨。
        assertEquals(-1, WeatherWmoCodec.chinese("浮尘转霾"))
        assertEquals(0, WeatherWmoCodec.chinese("晴"))
        assertEquals(61, WeatherWmoCodec.chinese("小雨"))
        assertEquals(95, WeatherWmoCodec.chinese("雷阵雨"))
        assertFalse(DailyWeatherDescriptions.hasRain(-1))
    }

    private companion object {
        val RESPONSE = """
            {
              "province": "江西省",
              "city": "赣州市",
              "district": "大余县",
              "adcode": "360723",
              "weather": "晴",
              "temperature": 18,
              "report_time": "2026-11-18 11:00:00",
              "forecast": [
                {"date": "2026-11-18", "weather_day": "多云", "temp_max": 24, "temp_min": 12},
                {"date": "2026-11-19", "weather_day": "小雨", "temp_max": 20, "temp_min": 11}
              ]
            }
        """.trimIndent()
    }
}
