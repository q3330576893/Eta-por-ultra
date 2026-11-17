package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechAudioLeaseTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun recordingAlwaysStopsOnScreenOffEvenWithPlaybackContinuationPolicy() {
        var stopped = false
        SpeechAudioLease(context, interruptOnScreenOff = false) { stopped = true }.use {
            it.acquire(playback = false)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(stopped)
        }
    }

    @Test fun preparingPlaybackContinuesOnlyWhenPolicyAllowsAndStillStopsOnNoisy() {
        for (interrupts in listOf(false, true)) {
            var stopped = 0
            SpeechAudioLease(context, interruptOnScreenOff = interrupts) { stopped++ }.use {
                it.acquire(playback = false, recording = false)
                context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(if (interrupts) 1 else 0, stopped)
                context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(if (interrupts) 2 else 1, stopped)
            }
        }
    }

    @Test fun releasedLeaseIgnoresScreenOffAndNoisyEvents() {
        var stopped = 0
        val lease = SpeechAudioLease(context) { stopped++ }
        lease.acquire(playback = false)
        lease.close()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stopped)
    }
}
