package io.github.mangi.eta.agent.voice

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AssistantScreenContextProjection
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Session 与浮窗同进程共享一次唤醒的快照，关闭或替换后不再接纳迟到结果。 */
internal class EtaAssistantScreenContext(
    showFlags: Int,
    foregroundApps: List<ComponentName>,
    private val captureRequested: Boolean = false,
) {
    private val foregroundApps = foregroundApps.takeIf { captureRequested }.orEmpty()
    private val assistRequested = captureRequested && showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0
    private val screenshotRequested = captureRequested && showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0
    val id: String = UUID.randomUUID().toString()
    val capturedAt = System.currentTimeMillis()
    @Volatile var foregroundPackageName: String? = this.foregroundApps.firstOrNull()?.packageName
        private set
    @Volatile var foregroundAppLabel: String? = foregroundPackageName
        private set
    private val startedAt = SystemClock.elapsedRealtime()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = CompletableDeferred<Unit>()
    private val activities = mutableMapOf<Int, Pair<Boolean, String>>()
    private val receivedActivities = mutableSetOf<Int>()
    private var expectedActivities = 1
    private var pendingActivities = 0
    private var assistComplete = !assistRequested
    private var screenshotComplete = !screenshotRequested
    private var screenshotReceived = false
    private var image: AgentModelClient.ModelImage? = null
    private var closed = false

    init { completeIfReady() }

    fun acceptAssist(context: Context, state: VoiceInteractionSession.AssistState) {
        synchronized(this) {
            if (closed || !assistRequested || state.index in receivedActivities) return
            if (receivedActivities.size >= MAX_ACTIVITIES && !state.isFocused) return
            receivedActivities += state.index
            expectedActivities = maxOf(expectedActivities, state.count.coerceIn(1, MAX_ACTIVITIES))
            pendingActivities++
        }
        scope.launch {
            val component = state.assistStructure?.activityComponent
            val label = component?.let { activity -> runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(activity.packageName, 0)).toString()
            }.getOrDefault(activity.packageName) }
            val text = try {
                EtaAssistContentReader.read(context, state)
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("助理应用上下文解析失败: type=${exception.javaClass.simpleName}")
                "应用内容读取失败"
            }
            synchronized(this@EtaAssistantScreenContext) {
                if (closed) return@synchronized
                activities[state.index] = state.isFocused to text
                if (state.isFocused && component != null) {
                    foregroundPackageName = component.packageName
                    foregroundAppLabel = label ?: component.packageName
                }
                pendingActivities--
                assistComplete = pendingActivities == 0 && receivedActivities.size >= expectedActivities
                completeIfReady()
            }
        }
    }

    fun acceptScreenshot(bitmap: Bitmap?) {
        synchronized(this) {
            if (closed || !screenshotRequested || screenshotReceived) {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                return
            }
            screenshotReceived = true
        }
        val job = scope.launch {
            val capturedImage = try {
                bitmap?.let { AgentImageCodec.fromScreenContextBitmap(it, source = "assistant_screen_context") }
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("助理截图编码失败: type=${exception.javaClass.simpleName}")
                null
            }
            synchronized(this@EtaAssistantScreenContext) {
                if (closed) return@synchronized
                image = capturedImage
                screenshotComplete = true
                completeIfReady()
            }
        }
        // 即使任务在执行前取消，也要释放系统回调交付的 Bitmap。
        job.invokeOnCompletion { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }

    suspend fun snapshot(includeContext: Boolean = true): Snapshot {
        if (!includeContext || !captureRequested) return Snapshot()
        val remaining = (WAIT_MILLIS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
        if (remaining > 0) withTimeoutOrNull(remaining) { ready.await() }
        return synchronized(this) {
            if (closed) return@synchronized Snapshot()
            val text = buildString {
                appendLine("采集时间（Unix 毫秒）：$capturedAt；这是用户手动采集的画面，不代表操作后的实时状态。")
                if (foregroundApps.isNotEmpty()) {
                    appendLine("前台应用：" + foregroundApps.take(MAX_ACTIVITIES)
                        .joinToString { it.flattenToShortString().take(512) })
                }
                appendLine(if (image != null) "本次已附屏幕截图。" else "系统未提供可用截图。")
                if (activities.isEmpty()) appendLine("系统尚未提供应用内容。")
                activities.entries.sortedWith(compareByDescending<Map.Entry<Int, Pair<Boolean, String>>> { it.value.first }
                    .thenBy { it.key }).take(MAX_ACTIVITIES).forEach { appendLine(it.value.second) }
            }
            Snapshot(AssistantScreenContextProjection.bound(text), image)
        }
    }

    fun close() {
        synchronized(this) {
            closed = true
            image = null
            activities.clear()
            ready.complete(Unit)
        }
        scope.cancel()
    }

    private fun completeIfReady() {
        if (assistComplete && screenshotComplete) ready.complete(Unit)
    }

    data class Snapshot(val text: String = "", val image: AgentModelClient.ModelImage? = null)

    companion object {
        private const val MAX_ACTIVITIES = 4
        private const val WAIT_MILLIS = 2_000L
    }
}

/** 最多保留当前一次唤醒，Intent 只传 ID，不传图片或界面树。 */
internal object EtaAssistantScreenContexts {
    private var current: EtaAssistantScreenContext? = null
    private var enabled = false
    private var refreshPermit: String? = null
    private var refreshPermitAt = 0L

    /** Turning off also revokes pending requests and closes encoders before any late callback. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            refreshPermit = null
            current?.close()
            current = null
        }
    }

    @Synchronized
    fun requestRefresh(): String? {
        if (!enabled) return null
        current?.close()
        current = null
        return UUID.randomUUID().toString().also {
            refreshPermit = it
            refreshPermitAt = SystemClock.elapsedRealtime()
        }
    }

    @Synchronized
    fun isRefreshPending(permit: String?): Boolean =
        enabled && permit != null && refreshPermit == permit &&
            SystemClock.elapsedRealtime() - refreshPermitAt in 0..REFRESH_PERMIT_MILLIS

    @Synchronized
    fun cancelRefresh(permit: String?) {
        if (refreshPermit == permit) refreshPermit = null
    }

    /** A capture accepts a single explicit request; replayed extras and external show flags grant nothing. */
    @Synchronized
    fun begin(
        flags: Int,
        foregroundApps: List<ComponentName>,
        permit: String? = null,
    ): EtaAssistantScreenContext? {
        if (!isRefreshPending(permit)) return null
        refreshPermit = null
        current?.close()
        return EtaAssistantScreenContext(flags, foregroundApps, captureRequested = true).also { current = it }
    }

    @Synchronized
    fun find(id: String?): EtaAssistantScreenContext? = current?.takeIf { enabled && it.id == id }

    @Synchronized
    fun release(id: String?) {
        if (current?.id != id) return
        current?.close()
        current = null
    }

    private const val REFRESH_PERMIT_MILLIS = 5_000L
}
