package io.github.mangi.eta.agent.device

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** 单次请求的可测试边界：每个提供者返回一个可取消句柄，所有退出路径都会释放它。 */
internal interface CurrentLocationBackend {
    fun access(): DeviceLocationProvider.AccessState
    fun providers(): List<String>
    fun cached(): List<DeviceLocationProvider.Result.Available>
    fun request(provider: String, callback: (DeviceLocationProvider.Result.Available?) -> Unit): () -> Unit
}

internal object CurrentLocationRequest {
    const val MAX_AGE_MS = 30_000L
    const val TIMEOUT_MS = 20_000L

    fun usable(location: DeviceLocationProvider.Result.Available, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 &&
            location.ageMillis in 0..maxAgeMs &&
            (location.accuracyMeters == null || (location.accuracyMeters.isFinite() && location.accuracyMeters >= 0f))

    suspend fun resolve(
        backend: CurrentLocationBackend,
        timeoutMs: Long = TIMEOUT_MS,
        maxAgeMs: Long = MAX_AGE_MS,
    ): DeviceLocationProvider.Result {
        when (backend.access()) {
            DeviceLocationProvider.AccessState.DENIED -> return DeviceLocationProvider.Result.Unavailable("permission_required")
            DeviceLocationProvider.AccessState.DISABLED -> return DeviceLocationProvider.Result.Unavailable("location_disabled")
            else -> Unit
        }
        // 很新的系统采样可复用；历史位置绝不伪装成本次定位。
        val fresh = backend.cached().filter { usable(it, maxAgeMs) }
            .minWithOrNull(compareBy<DeviceLocationProvider.Result.Available> { it.accuracyMeters ?: Float.MAX_VALUE }.thenBy { it.ageMillis })
        if (fresh != null && (fresh.accuracyMeters ?: Float.MAX_VALUE) <= 100f) return fresh
        val providers = backend.providers().distinct().take(3)
        if (providers.isEmpty()) return fresh ?: DeviceLocationProvider.Result.Unavailable("provider_unavailable")
        val results = Channel<Pair<String, DeviceLocationProvider.Result.Available?>>(Channel.UNLIMITED)
        val releases = mutableListOf<() -> Unit>()
        var best = fresh
        try {
            for (provider in providers) {
                try {
                    releases += backend.request(provider) { results.trySend(provider to it) }
                } catch (_: SecurityException) {
                    return DeviceLocationProvider.Result.Unavailable("permission_required")
                } catch (_: Exception) {
                    results.trySend(provider to null)
                }
            }
            val completed = withTimeoutOrNull(timeoutMs.coerceIn(1, 60_000)) {
                val received = mutableSetOf<String>()
                while (received.size < providers.size) {
                    val (provider, location) = results.receive()
                    if (!received.add(provider)) continue
                    if (location != null && usable(location, maxAgeMs)) {
                        val previous = best
                        if (previous == null || (location.accuracyMeters ?: Float.MAX_VALUE) < (previous.accuracyMeters ?: Float.MAX_VALUE) ||
                            (location.accuracyMeters == previous.accuracyMeters && location.ageMillis < previous.ageMillis)) best = location
                        if ((location.accuracyMeters ?: Float.MAX_VALUE) <= 100f) return@withTimeoutOrNull true
                    }
                }
                true
            }
            return best ?: DeviceLocationProvider.Result.Unavailable(if (completed == null) "timeout" else "unavailable")
        } finally {
            releases.forEach { cancel -> runCatching { cancel() } }
            results.close()
        }
    }
}
