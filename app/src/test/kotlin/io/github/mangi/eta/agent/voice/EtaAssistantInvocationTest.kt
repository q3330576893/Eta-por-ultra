package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowVoiceInteractionService

@RunWith(EtaRobolectricTestRunner::class)
@Config(
    sdk = [36],
    application = Application::class,
    shadows = [EtaAssistantInvocationTest.RecordingVoiceInteractionService::class],
)
class EtaAssistantInvocationTest {
    @Test
    fun onReadyRegistersServiceWithoutStartingSessionOrActivity() = withService { service, shadow ->
        assertFalse(EtaVoiceInteractionService.requestSession())
        service.onReady()
        assertEquals(0, shadow.sessionFlags.size)
        assertNull(shadow.lastSessionBundle)
        assertNoStartedActivity()
    }

    @Test
    fun keyguardInvocationShowsSessionWithAssistAndScreenshotWithoutActivity() = withService { service, shadow ->
        service.onReady()
        service.onLaunchVoiceAssistFromKeyguard()
        assertSession(shadow, refresh = false)
        assertNoStartedActivity()
    }

    @Test
    fun requestSessionUsesReadyServiceWithoutActivityFallback() = withService { service, shadow ->
        assertFalse(EtaVoiceInteractionService.requestSession())
        service.onReady()
        assertTrue(EtaVoiceInteractionService.requestSession())
        assertSession(shadow, refresh = false)
        assertNoStartedActivity()
    }

    @Test
    fun refreshContextBroadcastShowsRefreshSessionExactlyOnceAfterRepeatedReady() = withService { service, shadow ->
        service.onReady()
        service.onReady()
        EtaVoiceInteractionService.refreshContext(application)
        shadowOf(Looper.getMainLooper()).idle()
        assertSession(shadow, refresh = true)
        assertNoStartedActivity()
    }

    @Test
    fun shutdownClearsActiveServiceAndUnregistersRefreshReceiver() = withService { service, shadow ->
        service.onReady()
        assertTrue(EtaVoiceInteractionService.requestSession())
        val lastSession = shadow.lastSessionBundle
        service.onShutdown()
        service.onShutdown()
        assertFalse(EtaVoiceInteractionService.requestSession())
        EtaVoiceInteractionService.refreshContext(application)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadow.sessionFlags.size)
        assertEquals(lastSession, shadow.lastSessionBundle)
        assertNoStartedActivity()
    }

    @Test
    fun shutdownOfOlderServiceKeepsLatestReadyServiceActive() = withService { older, olderShadow ->
        older.onReady()
        withService { current, currentShadow ->
            current.onReady()
            older.onShutdown()
            assertTrue(EtaVoiceInteractionService.requestSession())
            assertEquals(0, olderShadow.sessionFlags.size)
            assertSession(currentShadow, refresh = false)
            assertNoStartedActivity()
        }
    }

    @Test
    fun installedManifestDeclaresNeitherAssistActivityBridgeNorEtaVoiceAssistActivity() {
        val packageManager = application.packageManager
        @Suppress("DEPRECATION")
        val declaredActivities = packageManager.getPackageInfo(
            application.packageName,
            PackageManager.GET_ACTIVITIES,
        ).activities.orEmpty()
        assertTrue("The installed manifest must include application activities", declaredActivities.isNotEmpty())
        assertFalse(declaredActivities.any { it.name.substringAfterLast('.') == "EtaVoiceAssistActivity" })
        @Suppress("DEPRECATION")
        val assistActivities = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_ASSIST).setPackage(application.packageName),
            PackageManager.MATCH_ALL,
        )
        assertTrue("ACTION_ASSIST must reach the voice session without an app Activity bridge", assistActivities.isEmpty())
        assertNoStartedActivity()
    }

    private val application: Application
        get() = RuntimeEnvironment.getApplication()

    private fun assertNoStartedActivity() {
        assertNull("Assistant invocation must keep the current foreground Activity", shadowOf(application).nextStartedActivity)
    }

    private fun assertSession(shadow: RecordingVoiceInteractionService, refresh: Boolean) {
        assertEquals(
            listOf(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT),
            shadow.sessionFlags,
        )
        val arguments = shadow.lastSessionBundle
        assertNotNull(arguments)
        requireNotNull(arguments)
        assertTrue(arguments.containsKey(EtaVoiceInteractionService.EXTRA_REFRESH_CONTEXT))
        assertEquals(refresh, arguments.getBoolean(EtaVoiceInteractionService.EXTRA_REFRESH_CONTEXT))
    }

    private fun withService(block: (EtaVoiceInteractionService, RecordingVoiceInteractionService) -> Unit) {
        val controller = Robolectric.buildService(EtaVoiceInteractionService::class.java).create()
        val service = controller.get()
        try {
            block(service, Shadow.extract(service))
        } finally {
            service.onShutdown()
            controller.destroy()
        }
    }

    /** Preserve Robolectric's recorded Bundle while also observing the exact framework flags. */
    @Implements(VoiceInteractionService::class)
    class RecordingVoiceInteractionService : ShadowVoiceInteractionService() {
        val sessionFlags = mutableListOf<Int>()

        @Implementation
        public override fun showSession(args: Bundle, flags: Int) {
            sessionFlags += flags
            super.showSession(args, flags)
        }
    }
}
