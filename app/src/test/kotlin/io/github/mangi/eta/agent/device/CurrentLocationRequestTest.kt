package io.github.mangi.eta.agent.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CurrentLocationRequestTest {
    @Test fun rejectsStaleAndInvalidLocation() {
        assertTrue(!CurrentLocationRequest.usable(DeviceLocationProvider.Result.Available(25.0, 114.0, 30f, 30_001)))
        assertTrue(!CurrentLocationRequest.usable(DeviceLocationProvider.Result.Available(91.0, 114.0, 30f, 0)))
        assertTrue(CurrentLocationRequest.usable(DeviceLocationProvider.Result.Available(25.0, 114.0, 30f, 1_000)))
    }

    @Test fun timeoutCancelsEveryProviderRequest() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val backend = object : CurrentLocationBackend {
            override fun access() = DeviceLocationProvider.AccessState.FOREGROUND_ONLY
            override fun providers() = listOf("network")
            override fun cached() = emptyList<DeviceLocationProvider.Result.Available>()
            override fun request(provider: String, callback: (DeviceLocationProvider.Result.Available?) -> Unit): () -> Unit = { cancelled.set(true) }
        }
        val result = CurrentLocationRequest.resolve(backend, timeoutMs = 5)
        assertEquals(DeviceLocationProvider.Result.Unavailable("timeout"), result)
        assertTrue(cancelled.get())
    }
}
