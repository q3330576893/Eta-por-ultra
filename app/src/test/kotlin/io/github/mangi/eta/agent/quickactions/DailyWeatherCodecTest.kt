package io.github.mangi.eta.agent.quickactions

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyWeatherCodecTest {
    @Test fun structuredGeocodingPreservesAmbiguousChineseCities() {
        val json = JSONObject("""
            {"results":[
              {"id":1,"name":"北京","latitude":39.9,"longitude":116.4,"country_code":"CN","country":"中国","admin1":"北京市","admin2":"北京市","timezone":"Asia/Shanghai"},
              {"id":2,"name":"北京","latitude":30.7,"longitude":108.6,"country_code":"CN","country":"中国","admin1":"重庆市","admin2":"重庆市","timezone":"Asia/Shanghai"}
            ]}
        """.trimIndent())
        val cities = DailyWeatherCodec.cities(json)
        assertEquals(2, cities.size)
        assertTrue(cities[0].displayName.contains("北京市"))
        assertTrue(cities[1].displayName.contains("重庆市"))
    }

    @Test fun weatherSnapshotRoundTripsWithoutLosingStructuredFields() {
        val city = WeatherCity(1, "北京", "CN", "中国", "北京市", "北京市", 39.9, 116.4, "Asia/Shanghai")
        val snapshot = DailyWeatherSnapshot(
            city = city,
            fetchedAt = Instant.ofEpochMilli(1234),
            observationAt = Instant.ofEpochMilli(1200),
            currentTemperatureCelsius = 21.5,
            currentWeatherCode = 1,
            currentPrecipitationMillimeters = 0.0,
            days = listOf(DailyWeatherDay(java.time.LocalDate.of(2026, 11, 16), 61, 22.0, 10.0, 3.2, 2.8, 60)),
        )
        val result = DailyWeatherCodec.snapshot(DailyWeatherCodec.encodeSnapshot(snapshot))
        assertEquals(snapshot.city, result.city)
        assertEquals(snapshot.currentTemperatureCelsius, result.currentTemperatureCelsius)
        assertEquals(snapshot.days, result.days)
    }
}
