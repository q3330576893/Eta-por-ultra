package io.github.mangi.eta.agent.quickactions

import java.time.Instant
import kotlinx.coroutines.CancellationException

/** 国内地区优先 UAPI；真实坐标有行政区名称时走 UAPI，否则保持坐标预报。绝不以 IP 偷换当前位置。 */
internal class ComposedWeatherService(
    private val uapi: DailyWeatherService = UapiWeatherService(),
    private val openMeteo: DailyWeatherService = OpenMeteoWeatherService(),
) : DailyWeatherService {
    override suspend fun searchCities(query: String): List<WeatherCity> {
        var failure: Exception? = null
        try {
            val direct = uapi.searchCities(query)
            if (direct.isNotEmpty()) return direct
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        try {
            val fallback = openMeteo.searchCities(query)
            if (fallback.isNotEmpty()) return fallback
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw failure ?: error
        }
        failure?.let { throw it }
        return emptyList()
    }

    override suspend fun forecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot = load(city, now, false)
    override suspend fun refreshForecast(city: WeatherCity, now: Instant): DailyWeatherSnapshot = load(city, now, true)

    private suspend fun load(city: WeatherCity, now: Instant, refresh: Boolean): DailyWeatherSnapshot {
        val canResolveRegion = city.adcode != null || city.countryCode.equals("CN", true) &&
            city.locationSource != "手动坐标" && city.name != "当前位置" && city.name.isNotBlank()
        if (canResolveRegion) {
            try {
                return if (refresh) uapi.refreshForecast(city, now) else uapi.forecast(city, now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!city.hasCoordinates) throw failure
            }
        }
        if (!city.hasCoordinates) throw WeatherServiceException("WEATHER_LOCATION_MISSING", "请先选择天气地点。")
        return if (refresh) openMeteo.refreshForecast(city, now) else openMeteo.forecast(city, now)
    }
}
