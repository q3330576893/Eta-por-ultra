package io.github.mangi.eta.agent.quickactions

import android.content.Context
import android.location.Address
import android.location.Geocoder
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** 使用真实坐标与系统地名服务；地名失败时仍能按坐标预报，不读出口 IP。 */
internal object WeatherLocationResolver {
    private data class Region(val latitude: Double, val longitude: Double, val city: WeatherCity)
    @Volatile private var lastRegion: Region? = null

    suspend fun resolve(context: Context, sample: DeviceLocationProvider.Result.Available): WeatherCity {
        val cached = lastRegion
        if (cached != null && kotlin.math.abs(sample.latitude - cached.latitude) < .0005 &&
            kotlin.math.abs(sample.longitude - cached.longitude) < .0005) {
            return cached.city.copy(latitude = sample.latitude, longitude = sample.longitude, id = idFor(sample))
        }
        val address = if (!Geocoder.isPresent()) null else withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine<Address?> { continuation ->
                try {
                    Geocoder(context.applicationContext, Locale.SIMPLIFIED_CHINESE)
                        .getFromLocation(sample.latitude, sample.longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                            }
                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        })
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        val city = fromAddress(sample, address)
        if (address != null) lastRegion = Region(sample.latitude, sample.longitude, city)
        return city
    }

    internal fun fromAddress(sample: DeviceLocationProvider.Result.Available, address: Address?): WeatherCity {
        val county = address?.subLocality.orEmpty().take(100)
        val locality = address?.locality.orEmpty().take(100)
        return WeatherCity(idFor(sample), county.ifBlank { locality }.ifBlank { "当前位置" },
            address?.countryCode.orEmpty(), address?.countryName.orEmpty().take(100), address?.adminArea.orEmpty().take(100),
            locality, sample.latitude, sample.longitude, "auto", admin3 = county, locationSource = "当前定位")
    }
    private fun idFor(sample: DeviceLocationProvider.Result.Available): Long =
        -kotlin.math.abs((sample.latitude * 100_000).toLong() * 31 + (sample.longitude * 100_000).toLong())
}
