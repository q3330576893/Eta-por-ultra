package io.github.mangi.eta.agent.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** latest 保持设备工具的缓存读取语义；天气用 current 做有界主动定位，结束立即释放。 */
internal object DeviceLocationProvider {
    private val currentMutex = Mutex()

    suspend fun current(context: Context, timeoutMs: Long = CurrentLocationRequest.TIMEOUT_MS): Result =
        currentMutex.withLock { CurrentLocationRequest.resolve(AndroidCurrentLocationBackend(context.applicationContext), timeoutMs) }

    fun failureMessage(status: String): String = when (status) {
        "permission_required" -> "定位权限尚未授予，请允许使用期间访问位置；大致位置可用，精确位置效果更好。"
        "location_disabled" -> "系统定位已关闭，请打开系统位置服务后重试。"
        "provider_unavailable" -> "系统暂时没有可用的定位提供者，请检查位置服务。"
        "timeout" -> "本次定位超时，请到窗边或户外重试，也可选择常用地点。"
        else -> "暂未取得新位置，请重试或选择常用地点；这不是天气联网错误。"
    }

    enum class AccessState {
        DENIED,
        FOREGROUND_ONLY,
        DISABLED,
        AVAILABLE,
    }

    sealed interface Result {
        data class Available(
            val latitude: Double,
            val longitude: Double,
            val accuracyMeters: Float?,
            val ageMillis: Long,
        ) : Result

        data class Unavailable(val status: String) : Result
    }

    fun accessState(context: Context): AccessState {
        val foregroundGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!foregroundGranted) return AccessState.DENIED
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return AccessState.DISABLED
        if (!manager.isLocationEnabled) return AccessState.DISABLED
        // 后台定位只有在持续订阅或后台触发读取时才需要；读取系统已有的最近位置
        // 属于前台读一次缓存，拿到「使用时允许」就够用，不能再当成拒绝。
        val backgroundGranted = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (backgroundGranted) AccessState.AVAILABLE else AccessState.FOREGROUND_ONLY
    }

    private class AndroidCurrentLocationBackend(private val context: Context) : CurrentLocationBackend {
        private val manager = context.getSystemService(LocationManager::class.java)
        override fun access(): AccessState = accessState(context)
        override fun providers(): List<String> {
            val enabled = runCatching { manager?.getProviders(true).orEmpty() }.getOrDefault(emptyList())
            val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            return listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { it in enabled && (fine || it != LocationManager.GPS_PROVIDER) }
        }
        @SuppressLint("MissingPermission")
        override fun cached(): List<Result.Available> = providers().mapNotNull {
            runCatching { manager?.getLastKnownLocation(it)?.toObservation() }.getOrNull()
        }
        @SuppressLint("MissingPermission")
        override fun request(provider: String, callback: (Result.Available?) -> Unit): () -> Unit {
            val cancellation = CancellationSignal()
            val service = manager ?: throw IllegalStateException("Location service unavailable")
            try {
                service.getCurrentLocation(provider, cancellation, context.mainExecutor) { location ->
                    callback(location?.toObservation())
                }
            } catch (error: Exception) {
                cancellation.cancel()
                throw error
            }
            return { cancellation.cancel() }
        }
    }

    internal fun Location.toObservation(): Result.Available {
        val age = if (elapsedRealtimeNanos > 0L) {
            ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0)
        } else (System.currentTimeMillis() - time).coerceAtLeast(0)
        return Result.Available(latitude, longitude, accuracy.takeIf { hasAccuracy() }, age)
    }

    @SuppressLint("MissingPermission")
    fun latest(context: Context): Result {
        return when (accessState(context)) {
            AccessState.DENIED -> Result.Unavailable("permission_required")
            AccessState.DISABLED -> Result.Unavailable("location_disabled")
            // FOREGROUND_ONLY 也能读最近位置，只是不能后台持续读取。
            AccessState.FOREGROUND_ONLY, AccessState.AVAILABLE -> {
                val manager = context.getSystemService(LocationManager::class.java)
                    ?: return Result.Unavailable("unavailable")
                val providers = runCatching { manager.getProviders(true) }
                    .getOrElse { return Result.Unavailable("unavailable") }
                val location = providers
                    .asSequence()
                    .mapNotNull { provider ->
                        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    }
                    .maxByOrNull { it.elapsedRealtimeNanos }
                    ?: return Result.Unavailable("unavailable")
                val ageMillis = if (location.elapsedRealtimeNanos > 0L) {
                    ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
                        .coerceAtLeast(0L)
                } else {
                    (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
                }
                Result.Available(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                    ageMillis = ageMillis,
                )
            }
        }
    }
}
