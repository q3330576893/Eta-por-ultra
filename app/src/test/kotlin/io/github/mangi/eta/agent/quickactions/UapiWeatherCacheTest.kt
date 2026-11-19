package io.github.mangi.eta.agent.quickactions

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UapiWeatherCacheTest {
    private val now = Instant.parse("2026-11-19T04:00:00Z")
    private fun response(code: String = "360723") = JSONObject("""{"adcode":"$code","city":"赣州市","district":"大余县","forecast":[{"date":"2026-11-19","weather_day":"晴","temp_min":12,"temp_max":24}]}""")
    private fun city(code: String = "360723") = WeatherCity(code.toLong(), "大余县", "CN", "中国", "江西省", "赣州市", null, null, "Asia/Shanghai", locationSource = "UAPI", adcode = code)

    @Test fun sameKeySingleFlightAndHotCacheDoNotWaitForAnotherNetworkKey() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = UapiWeatherService(clock = Clock.fixed(now, ZoneOffset.UTC), transport = { _, value ->
            calls.incrementAndGet()
            if (value == "110101") { entered.complete(Unit); release.await() }
            response(value)
        })
        service.forecast(city(), now)
        val slow = async { service.forecast(city("110101"), now) }
        entered.await()
        val follower = async(start = CoroutineStart.UNDISPATCHED) { service.forecast(city("110101"), now) }
        withTimeout(1000) { assertEquals("360723", service.forecast(city(), now).city.adcode) }
        release.complete(Unit)
        assertEquals(slow.await(), follower.await())
        assertEquals(2, calls.get())
    }
    @Test fun refreshUpdatesOnlyLocationAliasesAndKeepsUnrelatedHotCache() = runBlocking {
        var calls = 0
        val service = UapiWeatherService(clock = Clock.fixed(now, ZoneOffset.UTC), transport = { _, value -> calls++; response(if (value == "大余县") "360723" else value) })
        service.searchCities("大余县")
        service.forecast(city("110101"), now)
        service.refreshForecast(city(), now)
        service.searchCities("大余县")
        service.forecast(city("110101"), now)
        assertEquals(3, calls)
    }
    @Test fun failedRefreshRetainsCacheAndCancellationDoesNotPoisonSingleFlight() = runBlocking {
        var failRequests = false
        var cancel = false
        val service = UapiWeatherService(clock = Clock.fixed(now, ZoneOffset.UTC), transport = { _, value ->
            if (cancel) throw CancellationException("cancelled")
            if (failRequests) throw IOException("offline")
            response(value)
        })
        val first = service.forecast(city(), now)
        failRequests = true
        try { service.refreshForecast(city(), now); fail("expected failure") } catch (_: IOException) { }
        assertEquals(first, service.forecast(city(), now))
        failRequests = false; cancel = true
        try { service.refreshForecast(city(), now); fail("expected cancellation") } catch (_: CancellationException) { }
        cancel = false
        assertEquals(first, service.refreshForecast(city(), now))
    }
    @Test fun rateLimitCooldownStillAllowsHotReadsAndNeverFabricatesCoordinates() = runBlocking {
        var calls = 0
        val service = UapiWeatherService(clock = Clock.fixed(now, ZoneOffset.UTC), transport = { _, value ->
            calls++
            if (value == "110101") throw WeatherServiceException("WEATHER_RATE_LIMITED", "wait", 60)
            response(value)
        })
        service.forecast(city(), now)
        repeat(2) { try { service.forecast(city("110101"), now); fail("expected 429") } catch (error: WeatherServiceException) { assertEquals("WEATHER_RATE_LIMITED", error.code) } }
        val cached = service.forecast(city(), now)
        assertNull(cached.city.latitude)
        assertEquals(2, calls)
    }
    @Test fun timestampIsResponseCompletionAndGpsCoordinatesRemainReal() = runBlocking {
        var instant = now
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = instant
        }
        val service = UapiWeatherService(clock = clock, transport = { _, value -> instant = now.plusSeconds(3); response(value) })
        val gps = city().copy(latitude = 25.395, longitude = 114.357, locationSource = "当前定位")
        val result = service.forecast(gps, now)
        assertEquals(now.plusSeconds(3), result.fetchedAt)
        assertEquals(gps.latitude, result.city.latitude)
        assertEquals(gps.longitude, result.city.longitude)
        assertEquals("当前定位", result.city.locationSource)
    }
}
