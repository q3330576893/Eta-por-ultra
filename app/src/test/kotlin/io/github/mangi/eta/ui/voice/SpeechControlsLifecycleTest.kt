package io.github.mangi.eta.ui.voice

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechControlsLifecycleTest {
    @Test fun recordingAndPreviewStopWhenOwnerStopsWithScreenOff() {
        val power = RuntimeEnvironment.getApplication().getSystemService(PowerManager::class.java)
        shadowOf(power).setIsInteractive(false)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        var stopped = 0
        val observer = speechStopObserver { stopped++ }
        owner.lifecycle.addObserver(observer)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(0, stopped)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(1, stopped)
        owner.lifecycle.removeObserver(observer)
        shadowOf(power).setIsInteractive(true)
    }
}
