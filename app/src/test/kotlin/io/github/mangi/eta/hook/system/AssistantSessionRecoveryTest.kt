package io.github.mangi.eta.hook.system

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AssistantSessionRecoveryTest {
    @Test
    fun backgroundRepairPrecedesSingleSuccessfulSessionAndDuplicateWakeAddsNoWork() {
        val recovery = AssistantSessionRecovery()
        val handler = Handler(Looper.getMainLooper())
        val events = mutableListOf<String>()
        val schedule = {
            recovery.schedule(
                reconfigure = { handler.post { events += "reconfigure" }; Unit },
                postDelayed = { task, delay -> handler.postDelayed(task, delay) },
                isCurrent = { true },
                showSession = { events += "showSession"; true },
                onShown = { events += "shown" },
            )
        }

        assertTrue(schedule())
        assertTrue(schedule())
        assertTrue(recovery.isPending)
        assertTrue(events.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("reconfigure"), events)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(listOf("reconfigure", "showSession", "shown"), events)
        assertFalse(recovery.isPending)
    }

    @Test
    fun transientVoiceFailureRetriesUntilFirstSuccessAndStops() {
        val recovery = AssistantSessionRecovery()
        val handler = Handler(Looper.getMainLooper())
        var repairs = 0
        var attempts = 0
        var sessions = 0
        recovery.schedule(
            reconfigure = { repairs++ },
            postDelayed = { task, delay -> handler.postDelayed(task, delay) },
            isCurrent = { true },
            showSession = { ++attempts == 2 },
            onShown = { sessions++ },
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(1, repairs)
        assertEquals(2, attempts)
        assertEquals(1, sessions)
        assertFalse(recovery.isPending)
    }

    @Test
    fun permanentVoiceFailureHasFiniteAttemptsAndReleasesPendingState() {
        val recovery = AssistantSessionRecovery()
        val handler = Handler(Looper.getMainLooper())
        val delays = mutableListOf<Long>()
        var repairs = 0
        var attempts = 0
        var sessions = 0
        recovery.schedule(
            reconfigure = { repairs++ },
            postDelayed = { task, delay ->
                delays += delay
                handler.postDelayed(task, delay)
            },
            isCurrent = { true },
            showSession = { attempts++; false },
            onShown = { sessions++ },
        )

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(1, repairs)
        assertEquals(listOf(250L, 750L, 2_000L, 5_000L, 10_000L), delays)
        assertEquals(5, attempts)
        assertEquals(0, sessions)
        assertFalse(recovery.isPending)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(5, attempts)
    }

    @Test
    fun changedTargetCancelsQueuedSessionRatherThanShowingAnotherAssistant() {
        val recovery = AssistantSessionRecovery()
        val handler = Handler(Looper.getMainLooper())
        var current = true
        var attempts = 0
        recovery.schedule(
            reconfigure = {},
            postDelayed = { task, delay -> handler.postDelayed(task, delay) },
            isCurrent = { current },
            showSession = { attempts++; true },
            onShown = {},
        )
        current = false

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(0, attempts)
        assertFalse(recovery.isPending)
    }

    @Test
    fun rejectedHandlerPostAllowsFutureWakeToTryAgain() {
        val recovery = AssistantSessionRecovery()
        assertFalse(recovery.schedule(
            reconfigure = {},
            postDelayed = { _, _ -> false },
            isCurrent = { true },
            showSession = { true },
            onShown = {},
        ))
        assertFalse(recovery.isPending)
    }
}
