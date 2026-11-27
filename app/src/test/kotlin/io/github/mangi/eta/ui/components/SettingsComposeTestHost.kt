package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.ui.app.AgentAppTheme
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * Uses the same manually driven frame clock as EtaVoicePanelTouchTest. No ui-test dependency,
 * real display, persistent settings, or application services are needed.
 */
internal class SettingsComposeTestHost(
    appearance: AppearanceSettings = AppearanceSettings(),
    fontScale: Float = 1f,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    private val activity = controller.get()
    private val frameClock = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val view = ComposeView(activity)

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        view.setParentCompositionContext(recomposer)
        view.setContent {
            AgentAppTheme(appearance, applyInterfaceScale = false) {
                CompositionLocalProvider(
                    LocalSquircleEnabled provides false,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    content()
                }
            }
        }
        activity.setContentView(view)
        controller.windowFocusChanged(true)
        advance()
    }

    fun advance(frames: Int = 4) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
            root().measureAndLayoutForTest()
        }
    }

    fun nodes(merged: Boolean = false): List<SemanticsNode> {
        val owner = root().semanticsOwner
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            result += node
            node.children.forEach(::visit)
        }
        visit(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        return result
    }

    fun dp(value: Float): Float = value * root().density.density

    fun performSemanticsClick(node: SemanticsNode) {
        assertTrue("The accessible action must handle the click", node.config[SemanticsActions.OnClick].action?.invoke() == true)
        advance(20)
    }

    fun tap(bounds: Rect) {
        assertTrue("Tap target must be laid out: $bounds", bounds.width > 0f && bounds.height > 0f)
        val target = root().view
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, bounds.center.x, bounds.center.y, 0,
            )
            try {
                target.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            advance(1)
        }
        advance(20)
    }

    /** Caller owns the native-rendered bitmap and must recycle it after assertions. */
    fun captureBitmap(): Bitmap {
        advance(40)
        val target = root().view
        check(target.width > 0 && target.height > 0)
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also {
            target.draw(Canvas(it))
        }
    }

    /** Optional native-rendered PNG evidence, without committing a machine-specific golden. */
    fun captureIfRequested(name: String) {
        val outputDirectory = System.getenv("ETA_SETTINGS_SCREENSHOT_DIR")?.takeIf(String::isNotBlank) ?: return
        require(name.matches(Regex("[a-z0-9_-]+")))
        val output = File(outputDirectory)
        check(output.isDirectory || output.mkdirs()) { "Could not create screenshot directory" }
        val bitmap = captureBitmap()
        try {
            File(output, "$name.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        view.disposeComposition()
        recomposer.cancel()
        scope.cancel()
        controller.pause().stop().destroy()
    }

    private fun root(): ViewRootForTest = findRoot(activity.window.decorView)
        ?: error("Compose root has not attached")

    private fun findRoot(candidate: View): ViewRootForTest? {
        if (candidate is ViewRootForTest) return candidate
        if (candidate is ViewGroup) {
            for (index in 0 until candidate.childCount) {
                findRoot(candidate.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
