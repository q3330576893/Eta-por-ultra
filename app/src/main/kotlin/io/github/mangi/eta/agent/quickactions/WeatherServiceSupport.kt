package io.github.mangi.eta.agent.quickactions

import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import org.json.JSONObject

internal class WeatherServiceException(
    val code: String,
    val userMessage: String,
    val retryAfterSeconds: Long? = null,
) : IOException(userMessage)

internal object WeatherErrors {
    fun code(error: Throwable): String = when (error) {
        is WeatherServiceException -> error.code
        is InterruptedIOException -> "WEATHER_TIMEOUT"
        is UnknownHostException -> "WEATHER_DNS"
        is SSLException -> "WEATHER_TLS"
        is IOException -> "WEATHER_NETWORK"
        else -> "WEATHER_RESPONSE_INVALID"
    }

    fun describe(error: Throwable): String = when (error) {
        is WeatherServiceException -> error.userMessage
        is InterruptedIOException -> "天气服务响应超时，请稍后重试。"
        is UnknownHostException -> "天气服务域名解析失败，请检查网络或 DNS。"
        is SSLException -> "天气服务安全连接失败，请检查系统时间和网络。"
        is IOException -> "天气服务连接失败，请检查网络后重试。"
        else -> "天气服务返回的数据格式异常，请稍后重试。"
    }
}

/** 地点索引仅用于选点，预报只认已核对的经纬度。 */
internal object WeatherPlaces {
    const val MAX_QUERY_LENGTH = 80
    private val hanAddress = Regex("^[\\p{IsHan} ,，·]+$")
    private val separators = Regex("[\\s,，·]+")
    private val suffix = Regex("(?:特别行政区|自治区|自治州|自治县|街道|省|市|县|区|镇|乡|村)$")
    private val segment = Regex(".+?(?:特别行政区|自治区|自治州|自治县|街道|省|市|县|区|镇|乡|村)")

    fun key(value: String): String = value.trim().replace(separators, "").replace(suffix, "").lowercase(Locale.ROOT)

    private fun level(value: String): Int = when (suffix.find(value)?.value) {
        "省", "自治区", "特别行政区" -> 1
        "市", "自治州" -> 2
        "县", "区", "自治县" -> 3
        "镇", "乡", "街道" -> 4
        "村" -> 5
        else -> 0
    }

    private fun parts(query: String): List<String> {
        if (!hanAddress.matches(query)) return listOf(query)
        val delimited = query.split(separators).filter(String::isNotBlank)
        if (delimited.size > 1) {
            return if (delimited.map(::level).all { it > 0 }) delimited.sortedBy(::level) else delimited
        }
        val compact = delimited.singleOrNull() ?: return listOf(query)
        val segments = segment.findAll(compact).map { it.value }.toList()
        return if (segments.joinToString("") == compact && segments.zipWithNext().all { (a, b) -> level(a) < level(b) }) {
            segments
        } else listOf(compact)
    }

    fun leafQuery(query: String): String = parts(query).last()
    fun detailed(query: String): Boolean = parts(query).size > 1 || level(query) >= 3
    fun fullQuery(query: String): String = parts(query).joinToString(",")

    fun select(query: String, candidates: List<WeatherCity>): List<WeatherCity> {
        val parts = parts(query)
        val leaf = key(parts.last())
        val parents = parts.dropLast(1)
        val matches = candidates.filter { city ->
            if (detailed(query) && key(city.name) != leaf) return@filter false
            val hierarchy = listOf(city.country, city.admin1, city.admin2, city.admin3, city.admin4)
            val confirmed = parents.filter { part -> hierarchy.any { it.isNotBlank() && key(it) == key(part) } }
            parents.all { parent ->
                val rank = level(parent)
                parent in confirmed || (
                    // Photon 有时省略地级市。只在省和县都明确命中时接受这一处缺项，展示服务原始层级。
                    rank == 2 && city.admin2.isBlank() && confirmed.any { level(it) == 1 } && confirmed.any { level(it) == 3 }
                )
            }
        }
        val exact = matches.filter { key(it.name) == leaf }
        return (exact.ifEmpty { matches }).distinctBy { Triple(it.id, it.latitude, it.longitude) }.take(8)
    }

    fun photon(json: JSONObject): List<WeatherCity> {
        require(json.optString("type") == "FeatureCollection")
        val rows = requireNotNull(json.optJSONArray("features"))
        return (0 until minOf(rows.length(), 20)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val properties = row.optJSONObject("properties") ?: return@mapNotNull null
            val geometry = row.optJSONObject("geometry") ?: return@mapNotNull null
            if (geometry.optString("type") != "Point") return@mapNotNull null
            val coords = geometry.optJSONArray("coordinates") ?: return@mapNotNull null
            val lat = coords.optDouble(1, Double.NaN)
            val lon = coords.optDouble(0, Double.NaN)
            if (!validCoordinates(lat, lon)) return@mapNotNull null
            val name = properties.text("name").ifBlank { return@mapNotNull null }
            val kind = when (properties.optString("osm_type")) { "N" -> 1; "W" -> 2; "R" -> 3; else -> return@mapNotNull null }
            val osmId = properties.optLong("osm_id", 0)
            if (osmId !in 1..((Long.MAX_VALUE - 3) / 4)) return@mapNotNull null
            WeatherCity(-(osmId * 4 + kind), name, properties.text("countrycode").take(4), properties.text("country"),
                properties.text("state"), properties.text("city"), lat, lon, "auto",
                properties.text("county"), properties.text("district").ifBlank { properties.text("locality") }, "OpenStreetMap / Photon")
        }
    }

    fun validCoordinates(lat: Double, lon: Double): Boolean = lat in -90.0..90.0 && lon in -180.0..180.0

    fun manual(latitude: String, longitude: String): WeatherCity? {
        val lat = latitude.trim().toDoubleOrNull() ?: return null
        val lon = longitude.trim().toDoubleOrNull() ?: return null
        if (!validCoordinates(lat, lon)) return null
        return WeatherCity(Long.MIN_VALUE, "手动坐标", "", "", "", "", lat, lon, "auto", locationSource = "手动坐标")
    }

    private fun JSONObject.text(key: String) = optString(key).trim().take(100)
}
