package io.github.mangi.eta.agent.quickactions

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Pure JSON projection, shared by the network adapter and bounded private cache. */
internal object DailyWeatherCodec {
    fun cities(json: JSONObject): List<WeatherCity> {
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), 20)).mapNotNull { index ->
            val row = results.optJSONObject(index) ?: return@mapNotNull null
            val lat = row.numberOrNull("latitude") ?: return@mapNotNull null
            val lon = row.numberOrNull("longitude") ?: return@mapNotNull null
            val name = row.optString("name").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            WeatherCity(row.optLong("id"), name.take(100), row.optString("country_code").take(4),
                row.optString("country").take(100), row.optString("admin1").take(100),
                row.optString("admin2").take(100), lat, lon,
                row.optString("timezone").takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: "UTC")
        }.distinctBy { "${it.id}:${it.latitude}:${it.longitude}" }
    }

    fun city(json: JSONObject): WeatherCity? = cities(JSONObject().put("results", JSONArray().put(json))).singleOrNull()

    fun encodeCity(city: WeatherCity): JSONObject = JSONObject()
        .put("id", city.id).put("name", city.name).put("country_code", city.countryCode)
        .put("country", city.country).put("admin1", city.admin1).put("admin2", city.admin2)
        .put("latitude", city.latitude).put("longitude", city.longitude).put("timezone", city.timezone)

    fun forecast(json: JSONObject, city: WeatherCity, fetchedAt: Instant): DailyWeatherSnapshot {
        require(!json.optBoolean("error")) { "Weather service returned an error" }
        val zone = runCatching { ZoneId.of(json.optString("timezone")) }.getOrElse { ZoneId.of(city.timezone) }
        val current = json.optJSONObject("current")
        val observation = current?.optString("time")?.let { time ->
            runCatching { LocalDateTime.parse(time).atZone(zone).toInstant() }.getOrNull()
        }
        val daily = requireNotNull(json.optJSONObject("daily")) { "Weather response has no daily forecast" }
        val dates = requireNotNull(daily.optJSONArray("time"))
        val days = (0 until minOf(dates.length(), 7)).mapNotNull { index ->
            val date = runCatching { LocalDate.parse(dates.getString(index)) }.getOrNull() ?: return@mapNotNull null
            val code = daily.optJSONArray("weather_code").numberAt(index)?.toInt() ?: return@mapNotNull null
            DailyWeatherDay(date, code,
                daily.optJSONArray("temperature_2m_max").numberAt(index),
                daily.optJSONArray("temperature_2m_min").numberAt(index),
                daily.optJSONArray("precipitation_sum").numberAt(index),
                daily.optJSONArray("rain_sum").numberAt(index),
                daily.optJSONArray("precipitation_probability_max").numberAt(index)?.toInt()?.takeIf { it in 0..100 })
        }
        require(days.isNotEmpty()) { "Weather response has no usable forecast" }
        return DailyWeatherSnapshot(city, fetchedAt, observation,
            current?.numberOrNull("temperature_2m"), current?.numberOrNull("weather_code")?.toInt(),
            current?.numberOrNull("precipitation"), days)
    }

    fun encodeSnapshot(snapshot: DailyWeatherSnapshot): JSONObject = JSONObject()
        .put("city", encodeCity(snapshot.city))
        .put("fetched_at", snapshot.fetchedAt.toEpochMilli())
        .put("observation_at", snapshot.observationAt?.toEpochMilli() ?: JSONObject.NULL)
        .put("current_temperature", snapshot.currentTemperatureCelsius ?: JSONObject.NULL)
        .put("current_code", snapshot.currentWeatherCode ?: JSONObject.NULL)
        .put("current_precipitation", snapshot.currentPrecipitationMillimeters ?: JSONObject.NULL)
        .put("days", JSONArray().also { rows -> snapshot.days.forEach { day ->
            rows.put(JSONObject().put("date", day.date.toString()).put("code", day.weatherCode)
                .put("maximum", day.maximumCelsius ?: JSONObject.NULL)
                .put("minimum", day.minimumCelsius ?: JSONObject.NULL)
                .put("precipitation", day.precipitationMillimeters ?: JSONObject.NULL)
                .put("rain", day.rainMillimeters ?: JSONObject.NULL)
                .put("probability", day.precipitationProbabilityPercent ?: JSONObject.NULL))
        } })

    fun snapshot(json: JSONObject): DailyWeatherSnapshot {
        val city = requireNotNull(city(json.getJSONObject("city")))
        val rows = json.getJSONArray("days")
        return DailyWeatherSnapshot(city, Instant.ofEpochMilli(json.getLong("fetched_at")),
            json.numberOrNull("observation_at")?.toLong()?.let(Instant::ofEpochMilli),
            json.numberOrNull("current_temperature"), json.numberOrNull("current_code")?.toInt(),
            json.numberOrNull("current_precipitation"), (0 until minOf(rows.length(), 7)).map { index ->
                val day = rows.getJSONObject(index)
                DailyWeatherDay(LocalDate.parse(day.getString("date")), day.getInt("code"),
                    day.numberOrNull("maximum"), day.numberOrNull("minimum"), day.numberOrNull("precipitation"),
                    day.numberOrNull("rain"), day.numberOrNull("probability")?.toInt())
            })
    }

    private fun JSONObject.numberOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf(Double::isFinite)

    private fun JSONArray?.numberAt(index: Int): Double? =
        if (this == null || isNull(index)) null else optDouble(index, Double.NaN).takeIf(Double::isFinite)
}

internal object DailyWeatherDescriptions {
    fun wmo(code: Int): String = when (code) {
        0 -> "晴"; 1 -> "晴间多云"; 2 -> "多云"; 3 -> "阴"
        45, 48 -> "雾"; 51 -> "小毛毛雨"; 53 -> "毛毛雨"; 55 -> "大毛毛雨"
        56, 57 -> "冻毛毛雨"; 61 -> "小雨"; 63 -> "中雨"; 65 -> "大雨"
        66, 67 -> "冻雨"; 71 -> "小雪"; 73 -> "中雪"; 75 -> "大雪"; 77 -> "雪粒"
        80 -> "小阵雨"; 81 -> "阵雨"; 82 -> "强阵雨"; 85, 86 -> "阵雪"
        95 -> "雷雨"; 96, 99 -> "雷雨伴冰雹"; else -> "天气状况代码 $code"
    }

    fun hasRain(code: Int): Boolean = code in setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
}
