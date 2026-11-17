package io.github.mangi.eta.agent.voice

import android.app.Application
import android.graphics.Rect
import android.graphics.Region
import android.view.View
import android.view.ViewTreeObserver
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantTouchableRegionTest {
    @Test
    fun realInternalInsetsListenerUsesRegionModeAndOnlyMeasuredContent() = withRegion { view, helper ->
        helper.update(Rect(24, 600, 376, 760))
        assertTrue(helper.attach())
        assertTrue(hasListener(view.viewTreeObserver))
        val insets = dispatchInsets(view.viewTreeObserver)
        assertEquals(3, insets.mode)
        assertEquals(Rect(24, 600, 376, 760), insets.region.bounds)
        assertTrue(insets.region.contains(24, 600))
        assertTrue(insets.region.contains(375, 759))
        assertFalse(insets.region.contains(23, 600))
        assertFalse(insets.region.contains(376, 600))
        assertFalse(insets.region.contains(24, 599))
        assertFalse(insets.region.contains(24, 760))
        assertFalse("Desktop above the capsule stays outside its input region", insets.region.contains(200, 200))
    }

    @Test
    fun updatedContentReplacesOldRegionAndExcludesImeBlankSpace() = withRegion { view, helper ->
        assertTrue(helper.attach())
        helper.update(Rect(24, 600, 376, 760))
        assertTrue(dispatchInsets(view.viewTreeObserver).region.contains(200, 700))
        // The overlay supplies boundsInRoot after IME moves the content upward.
        helper.update(Rect(24, 300, 376, 460))
        val insets = dispatchInsets(view.viewTreeObserver)
        assertEquals(3, insets.mode)
        assertEquals(Rect(24, 300, 376, 460), insets.region.bounds)
        assertTrue(insets.region.contains(200, 400))
        assertFalse("Blank space below content and above IME stays touch-through", insets.region.contains(200, 500))
        assertFalse("Previous capsule bounds must be removed", insets.region.contains(200, 700))
        assertFalse("IME area is outside the overlay content", insets.region.contains(200, 850))
    }

    @Test
    fun updateCopiesCallerRectBeforeDispatch() = withRegion { view, helper ->
        val content = Rect(24, 600, 376, 760)
        helper.update(content)
        content.set(0, 0, 400, 900)
        assertTrue(helper.attach())
        assertEquals(Rect(24, 600, 376, 760), dispatchInsets(view.viewTreeObserver).region.bounds)
    }

    @Test
    fun emptyContentClearsPreviouslyTouchableRegion() = withRegion { view, helper ->
        helper.update(Rect(24, 600, 376, 760))
        assertTrue(helper.attach())
        assertFalse(dispatchInsets(view.viewTreeObserver).region.isEmpty)
        helper.update(Rect())
        val insets = dispatchInsets(view.viewTreeObserver)
        assertEquals(3, insets.mode)
        assertTrue(insets.region.isEmpty)
    }

    @Test
    fun repeatedAttachIsIdempotentAndSingleCloseRemovesListener() {
        val view = View(RuntimeEnvironment.getApplication())
        var exemptions = 0
        val helper = AssistantTouchableRegion(view, exempt = { exemptions++ })
        try {
            assertFalse(hasListener(view.viewTreeObserver))
            helper.update(Rect(24, 600, 376, 760))
            assertTrue(helper.attach())
            assertTrue(helper.attach())
            assertEquals(1, exemptions)
            assertTrue(hasListener(view.viewTreeObserver))
            assertEquals(3, dispatchInsets(view.viewTreeObserver).mode)
            helper.close()
            assertFalse("Closing once must remove every helper listener", hasListener(view.viewTreeObserver))
            helper.close()
            val insets = dispatchInsets(view.viewTreeObserver)
            assertEquals(0, insets.mode)
            assertTrue(insets.region.isEmpty)
            assertTrue(helper.attach())
            assertEquals(2, exemptions)
            assertEquals(3, dispatchInsets(view.viewTreeObserver).mode)
        } finally {
            helper.close()
        }
        assertFalse(hasListener(view.viewTreeObserver))
    }

    private fun withRegion(block: (View, AssistantTouchableRegion) -> Unit) {
        val view = View(RuntimeEnvironment.getApplication())
        AssistantTouchableRegion(view, exempt = {}).use { block(view, it) }
        assertFalse(hasListener(view.viewTreeObserver))
    }

    private fun hasListener(observer: ViewTreeObserver): Boolean =
        ViewTreeObserver::class.java.getDeclaredMethod("hasComputeInternalInsetsListeners").apply {
            isAccessible = true
        }.invoke(observer) as Boolean

    /** Exercise android-all's actual hidden constructor, dispatcher, proxy callback and Region. */
    private fun dispatchInsets(observer: ViewTreeObserver): InsetsSnapshot {
        val type = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
        val info = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnComputeInternalInsets", type).apply {
            isAccessible = true
        }.invoke(observer, info)
        val mode = type.getDeclaredField("mTouchableInsets").apply { isAccessible = true }.getInt(info)
        val region = type.getField("touchableRegion").get(info) as Region
        return InsetsSnapshot(mode, Region(region))
    }

    private data class InsetsSnapshot(val mode: Int, val region: Region)
}
