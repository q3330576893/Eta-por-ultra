package io.github.mangi.eta.agent.quickactions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.FlashlightController
import java.time.Instant
import java.util.Locale

/**
 * 生活便利 · 本地零模型执行器（P0）。
 *
 * 设计约束：
 * - 只使用本地系统 API，不访问网络、不加载模型；
 * - 每个动作都尽量"读回"真实状态，verified 只反映读回是否命中目标；
 * - 导航属于系统交接（handoff），verified 恒为 false；
 * - 危险动作（拨号/短信/勿扰）不在本执行器范围内。
 *
 * 为便于纯 JVM 测试，具体系统操作抽到 [LifeDeviceOps] 接口；测试注入 fake 即可覆盖
 * 映射 / 换算 / 结果构造，不要求真实 CameraManager 成功。
 */
internal object LifeActionExecutor {

    // ---- 应用别名表：只做明确映射，找不到就明确失败，绝不静默打开别的应用 ----
    internal val APP_ALIASES: Map<String, String> = mapOf(
        "微信" to "com.tencent.mm",
        "淘宝" to "com.taobao.taobao",
        "支付宝" to "com.eg.android.AlipayGphone",
        "抖音" to "com.ss.android.ugc.aweme",
        "设置" to "com.android.settings",
    )

    internal fun aliasToPackage(alias: String): String? = APP_ALIASES[alias]
    internal fun isCameraAlias(alias: String): Boolean = alias == "相机" || alias == "照相机"
    internal fun appDisplayName(alias: String): String = if (alias == "照相机") "相机" else alias

    // ---- 小爱同学式生活便利 · 第二批（P0）固定别名表 / 语义状态 ----
    /** 系统面板固定别名 → Settings action 后缀；只做明确映射，找不到明确失败。 */
    internal val PANEL_ACTIONS: Map<SystemPanel, String> = mapOf(
        SystemPanel.WIFI to "android.settings.WIFI_SETTINGS",
        SystemPanel.BLUETOOTH to "android.settings.BLUETOOTH_SETTINGS",
        SystemPanel.HOTSPOT to "android.settings.TETHER_SETTINGS",
        SystemPanel.NETWORK to "android.settings.WIRELESS_SETTINGS",
    )
    internal fun panelAction(panel: SystemPanel): String? = PANEL_ACTIONS[panel]
    internal fun panelLabel(panel: SystemPanel): String = when (panel) {
        SystemPanel.WIFI -> "WiFi 设置"
        SystemPanel.BLUETOOTH -> "蓝牙设置"
        SystemPanel.HOTSPOT -> "热点设置"
        SystemPanel.NETWORK -> "网络设置"
    }

    /** 语义化播放状态：避免纯 JVM 测试触碰 android.media.session.PlaybackState。 */
    internal enum class PlaybackStatus { PLAYING, PAUSED, STOPPED, NONE, UNAVAILABLE }

    internal data class MediaSessionHandle(val id: String, val lastActiveAt: Long)

    /** 回调 boolean 的命令词，供结果显示使用。 */
    internal fun ringerLabel(kind: RingerKind): String = when (kind) {
        RingerKind.SILENT -> "静音"
        RingerKind.VIBRATE -> "震动"
        RingerKind.NORMAL -> "响铃"
    }

    internal fun audioModeFor(kind: RingerKind): Int = when (kind) {
        RingerKind.SILENT -> AudioManager.RINGER_MODE_SILENT
        RingerKind.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingerKind.NORMAL -> AudioManager.RINGER_MODE_NORMAL
    }

    internal fun modeMatches(actual: Int, kind: RingerKind): Boolean = actual == audioModeFor(kind)

    /** 把百分比换算为 [min,max] 区间内的音量下标。 */
    internal fun indexFromPercent(percent: Int, min: Int, max: Int): Int {
        if (max <= min) return 0
        val clamped = percent.coerceIn(0, 100)
        return min + ((max - min).toDouble() * clamped / 100.0).roundToIntCompat().coerceIn(0, max - min)
    }

    /** 把音量下标换算回 0..100 的百分比，便于读回对比。 */
    internal fun percentFromIndex(index: Int, min: Int, max: Int): Int {
        if (max <= min) return 0
        return ((index - min).toDouble() * 100.0 / (max - min).toDouble()).roundToIntCompat().coerceIn(0, 100)
    }

    /** geo: 导航 URI，目的地按 UTF-8 逐字节做百分号编码，纯函数便于测试。 */
    internal fun geoQuery(destination: String): String {
        val encoded = StringBuilder()
        for (byte in destination.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val safe = value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code ||
                value in '0'.code..'9'.code || value in setOf('-', '_', '.', '~').map { it.code }
            if (safe) encoded.append(value.toChar())
            else encoded.append(String.format(Locale.ROOT, "%%%02X", value))
        }
        return "geo:0,0?q=$encoded"
    }

    internal fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    // ---- 便捷入口：真实 Context ----
    internal fun executeFlashlight(context: Context, request: DailyIntent.FlashlightAction, now: Instant) =
        executeFlashlight(AndroidLifeDeviceOps(context), request, now)

    internal fun executeSetVolume(context: Context, request: DailyIntent.SetVolumeAction, now: Instant) =
        executeSetVolume(AndroidLifeDeviceOps(context), request, now)

    internal fun executeAdjustVolume(context: Context, request: DailyIntent.AdjustVolumeAction, now: Instant) =
        executeAdjustVolume(AndroidLifeDeviceOps(context), request, now)

    internal fun executeRinger(context: Context, request: DailyIntent.SetRingerAction, now: Instant) =
        executeRinger(AndroidLifeDeviceOps(context), request, now)

    internal fun executeOpenApp(context: Context, request: DailyIntent.OpenAppAction, now: Instant) =
        executeOpenApp(AndroidLifeDeviceOps(context), request, now)

    internal fun executeNavigate(context: Context, request: DailyIntent.NavigateAction, now: Instant) =
        executeNavigate(AndroidLifeDeviceOps(context), request, now)

    internal fun executeBattery(context: Context, now: Instant) =
        executeBattery(AndroidLifeDeviceOps(context), now)

    internal fun executeStorage(context: Context, now: Instant) =
        executeStorage(AndroidLifeDeviceOps(context), now)

    internal fun executeMediaPlayback(context: Context, request: DailyIntent.MediaPlaybackAction, now: Instant) =
        executeMediaPlayback(AndroidLifeDeviceOps(context), request, now)

    internal fun executeDnd(context: Context, request: DailyIntent.DndAction, now: Instant) =
        executeDnd(AndroidLifeDeviceOps(context), request, now)

    internal fun executeSystemPanel(context: Context, request: DailyIntent.SystemPanelAction, now: Instant) =
        executeSystemPanel(AndroidLifeDeviceOps(context), request, now)

    internal fun executeAlarmQuery(context: Context, now: Instant) =
        executeAlarmQuery(AndroidLifeDeviceOps(context), now)

    // ---- 可注入纯逻辑：Android 系统交互全部经 ops，测试可用 fake ----

    internal fun executeFlashlight(ops: LifeDeviceOps, request: DailyIntent.FlashlightAction, now: Instant): DailyCommandResult {
        val state = ops.torchState()
        if (!state.available) {
            return DailyCommandResult.Failure(request, "FLASHLIGHT_UNAVAILABLE", "设备没有可用的闪光灯。", now, "手电筒")
        }
        val accepted = ops.setTorch(request.enabled)
        val actual = ops.torchState().enabled
        val verified = accepted && actual == request.enabled
        val action = if (request.enabled) "打开" else "关闭"
        val readback = when (actual) { true -> "已打开"; false -> "已关闭"; null -> "未读回" }
        return DailyCommandResult.ActionDone(
            request = request,
            title = "手电筒",
            summary = if (verified) "已${action}手电筒。" else "已发出${action}手电筒指令，但未能读回确认，请检查设备状态。",
            fields = listOf(
                DailyResultField("目标状态", action),
                DailyResultField("读回状态", readback),
                DailyResultField("闪光灯可用", if (state.available) "是" else "否"),
            ),
            asOf = now,
            verified = verified,
            sourceLabel = "本地设备 · CameraManager",
        )
    }

    internal fun executeSetVolume(ops: LifeDeviceOps, request: DailyIntent.SetVolumeAction, now: Instant): DailyCommandResult {
        val range = ops.volumeRange()
        if (range == null || range.first >= range.last) {
            return DailyCommandResult.Failure(request, "AUDIO_UNAVAILABLE", "设备音频服务暂不可用。", now, "本地音频")
        }
        val target = indexFromPercent(request.percent, range.first, range.last)
        val accepted = ops.setVolumeIndex(target)
        val actual = ops.volumeIndex()
        val actualPercent = percentFromIndex(actual, range.first, range.last)
        val verified = accepted && actual == target
        return DailyCommandResult.ActionDone(
            request = request,
            title = "音量设置",
            summary = if (verified) "音量已设为 $actualPercent%。" else "已发出音量设置指令，但读回为 $actual%，请手动核对。",
            fields = listOf(
                DailyResultField("目标音量", "${request.percent}%"),
                DailyResultField("读回音量", "$actualPercent%"),
                DailyResultField("音量档位", "$actual / ${range.last}"),
            ),
            asOf = now,
            verified = verified,
            sourceLabel = "本地设备 · AudioManager",
        )
    }

    internal fun executeAdjustVolume(ops: LifeDeviceOps, request: DailyIntent.AdjustVolumeAction, now: Instant): DailyCommandResult {
        val range = ops.volumeRange()
        if (range == null || range.first >= range.last) {
            return DailyCommandResult.Failure(request, "AUDIO_UNAVAILABLE", "设备音频服务暂不可用。", now, "本地音频")
        }
        val before = ops.volumeIndex()
        val target = (if (request.up) before + 1 else before - 1).coerceIn(range.first, range.last)
        val accepted = ops.setVolumeIndex(target)
        val actual = ops.volumeIndex()
        val verified = accepted && actual == target
        val direction = if (request.up) "调大" else "调小"
        return DailyCommandResult.ActionDone(
            request = request,
            title = "音量调节",
            summary = if (verified) "音量已${direction}，当前 ${percentFromIndex(actual, range.first, range.last)}%。"
                      else "已发出${direction}指令，但读回未确认，请手动核对。",
            fields = listOf(
                DailyResultField("调整", direction),
                DailyResultField("当前音量", "${percentFromIndex(actual, range.first, range.last)}%"),
                DailyResultField("音量档位", "$actual / ${range.last}"),
            ),
            asOf = now,
            verified = verified,
            sourceLabel = "本地设备 · AudioManager",
        )
    }

    internal fun executeRinger(ops: LifeDeviceOps, request: DailyIntent.SetRingerAction, now: Instant): DailyCommandResult {
        val target = audioModeFor(request.mode)
        val accepted = ops.setRingerMode(target)
        val actual = ops.ringerMode()
        val verified = accepted && modeMatches(actual, request.mode)
        val label = ringerLabel(request.mode)
        val readback = when (actual) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "震动"
            AudioManager.RINGER_MODE_NORMAL -> "响铃"
            else -> "未识别"
        }
        return DailyCommandResult.ActionDone(
            request = request,
            title = "响铃模式",
            summary = if (verified) "已切换为${label}模式。" else "已发出${label}指令，但读回为$readback，请手动核对。",
            fields = listOf(
                DailyResultField("目标模式", label),
                DailyResultField("读回模式", readback),
            ),
            asOf = now,
            verified = verified,
            sourceLabel = "本地设备 · AudioManager",
        )
    }

    internal fun executeOpenApp(ops: LifeDeviceOps, request: DailyIntent.OpenAppAction, now: Instant): DailyCommandResult {
        val name = appDisplayName(request.alias)
        val packageName = ops.resolveApp(request.alias)
        if (packageName == null) {
            return DailyCommandResult.Failure(request, "APP_NOT_FOUND", "未找到“$name”，设备未安装或无法解析该应用。", now, "本地应用")
        }
        val launched = ops.launch(packageName)
        return if (launched) {
            DailyCommandResult.ActionDone(
                request = request,
                title = "打开应用",
                summary = "已尝试打开$name。",
                fields = listOf(DailyResultField("应用", name), DailyResultField("包名", packageName)),
                asOf = now,
                verified = true,
                sourceLabel = "本地应用",
            )
        } else {
            DailyCommandResult.Failure(request, "APP_LAUNCH_FAILED", "找到$name（$packageName），但系统未成功启动它。", now, "本地应用")
        }
    }

    internal fun executeNavigate(ops: LifeDeviceOps, request: DailyIntent.NavigateAction, now: Instant): DailyCommandResult {
        val uri = geoQuery(request.destination)
        val handedOff = ops.openNavigation(uri)
        return if (handedOff) {
            DailyCommandResult.ActionDone(
                request = request,
                title = "导航",
                summary = "已把前往${request.destination}的导航交给地图应用，请在地图中确认路线。",
                fields = listOf(
                    DailyResultField("目的地", request.destination),
                    DailyResultField("方式", "系统地图 / 浏览器（geo: intent）"),
                ),
                asOf = now,
                verified = false, // handoff：交接成功不等于路线已确认
                sourceLabel = "系统交接",
            )
        } else {
            DailyCommandResult.Failure(request, "NAVIGATION_UNAVAILABLE", "未能找到可处理导航的应用。", now, "系统交接")
        }
    }

    internal fun executeBattery(ops: LifeDeviceOps, now: Instant): DailyCommandResult {
        val percent = ops.batteryPercent()
        return if (percent != null && percent in 0..100) {
            DailyCommandResult.ActionDone(
                request = DailyIntent.BatteryStatus,
                title = "电池电量",
                summary = "当前电量 $percent%。",
                fields = listOf(DailyResultField("电量", "$percent%")),
                asOf = now,
                verified = true,
                sourceLabel = "本地设备 · BatteryManager",
            )
        } else {
            DailyCommandResult.Failure(DailyIntent.BatteryStatus, "BATTERY_UNAVAILABLE", "暂时无法读取电池电量。", now, "本地设备")
        }
    }

    internal fun executeStorage(ops: LifeDeviceOps, now: Instant): DailyCommandResult {
        val stats = ops.storageStats()
        return if (stats != null && stats.totalBytes > 0) {
            val used = (stats.totalBytes - stats.availableBytes).coerceAtLeast(0)
            DailyCommandResult.ActionDone(
                request = DailyIntent.StorageStatus,
                title = "内部存储",
                summary = "内部存储共 ${formatBytes(stats.totalBytes)}，可用 ${formatBytes(stats.availableBytes)}。",
                fields = listOf(
                    DailyResultField("总容量", formatBytes(stats.totalBytes)),
                    DailyResultField("可用", formatBytes(stats.availableBytes)),
                    DailyResultField("已用", formatBytes(used)),
                ),
                asOf = now,
                verified = true,
                sourceLabel = "本地设备 · StatFs",
            )
        } else {
            DailyCommandResult.Failure(DailyIntent.StorageStatus, "STORAGE_UNAVAILABLE", "暂时无法读取内部存储信息。", now, "本地设备")
        }
    }

    /** 播放控制：无通知监听权明确失败；多 session 取最近活跃一个；verified 读回播放状态。 */
    internal fun executeMediaPlayback(
        ops: LifeDeviceOps,
        request: DailyIntent.MediaPlaybackAction,
        now: Instant,
    ): DailyCommandResult {
        if (!ops.mediaAccessGranted()) {
            return DailyCommandResult.Failure(
                request, "MEDIA_ACCESS_REQUIRED",
                "控制播放需要「通知使用权（媒体控制）」，请在 设置 → 通知 → 通知使用权 中为本应用开启后重试。",
                now, "媒体会话",
            )
        }
        val session = ops.activeMediaSessions()
            .filter { it.id.isNotBlank() }
            .maxByOrNull { it.lastActiveAt }
            ?: return DailyCommandResult.Failure(
                request, "NO_MEDIA_SESSION", "当前没有正在播放的媒体会话，请先开始播放音乐。", now, "媒体会话")
        val accepted = ops.mediaCommand(session.id, request.command)
        val status = ops.playbackStatus(session.id)
        val targetStatus = when (request.command) {
            MediaPlaybackCommand.PAUSE -> PlaybackStatus.PAUSED
            MediaPlaybackCommand.RESUME -> PlaybackStatus.PLAYING
            MediaPlaybackCommand.NEXT, MediaPlaybackCommand.PREVIOUS -> null // 切歌不改播放/暂停语义
        }
        val verified = accepted && (targetStatus == null || status == targetStatus)
        val action = when (request.command) {
            MediaPlaybackCommand.PAUSE -> "暂停"
            MediaPlaybackCommand.RESUME -> "继续播放"
            MediaPlaybackCommand.NEXT -> "下一首"
            MediaPlaybackCommand.PREVIOUS -> "上一首"
        }
        val readback = when (status) {
            PlaybackStatus.PLAYING -> "播放中"
            PlaybackStatus.PAUSED -> "已暂停"
            PlaybackStatus.STOPPED -> "已停止"
            PlaybackStatus.NONE -> "无活动会话"
            PlaybackStatus.UNAVAILABLE -> "未读回"
        }
        return DailyCommandResult.ActionDone(
            request = request,
            title = "播放控制",
            summary = if (verified) "已$action。" else "已发出${action}指令，但读回为$readback，请手动核对。",
            fields = listOf(
                DailyResultField("操作", action),
                DailyResultField("媒体会话", session.id.take(80)),
                DailyResultField("读回状态", readback),
            ),
            asOf = now,
            verified = verified,
            sourceLabel = "媒体会话 · MediaSessionManager",
        )
    }

    /** 勿扰开关：需 ACCESS_NOTIFICATION_POLICY，未授权明确失败并带深链文案。 */
    internal fun executeDnd(ops: LifeDeviceOps, request: DailyIntent.DndAction, now: Instant): DailyCommandResult {
        if (!ops.notificationPolicyAccessGranted()) {
            return DailyCommandResult.Failure(
                request, "DND_PERMISSION_REQUIRED",
                "修改勿扰需要「勿扰权限」，请在 设置 → 声音与振动 → 勿扰模式 → 允许访问 中为本应用开启后重试。",
                now, "勿扰设置",
            )
        }
        val enable = request.kind == DndKind.ENABLE
        val accepted = ops.setDndEnabled(enable)
        val actual = ops.dndEnabled()
        val verified = accepted && actual == enable
        val label = when (request.kind) {
            DndKind.ENABLE -> "开启"
            DndKind.DISABLE -> "关闭"
            DndKind.CANCEL -> "取消"
        }
        return DailyCommandResult.ActionDone(
            request = request,
            title = "勿扰模式",
            summary = if (verified) "已${label}勿扰。" else "已发出${label}勿扰指令，但读回未确认，请手动核对。",
            fields = listOf(
                DailyResultField("操作", "${label}勿扰"),
                DailyResultField("读回状态", if (actual == null) "未读回" else if (actual) "勿扰开启" else "勿扰关闭"),
            ),
            asOf = now,
            verified = verified,
            sourceLabel = "勿扰设置",
        )
    }

    /** 系统面板：交接给系统设置 Activity，恒为 handoff（verified=false）。 */
    internal fun executeSystemPanel(
        ops: LifeDeviceOps,
        request: DailyIntent.SystemPanelAction,
        now: Instant,
    ): DailyCommandResult {
        val action = panelAction(request.panel)
        if (action == null) {
            return DailyCommandResult.Failure(request, "PANEL_UNSUPPORTED", "暂不支持该系统设置面板。", now, "系统交接")
        }
        return if (ops.openSystemPanel(action)) {
            DailyCommandResult.ActionDone(
                request = request,
                title = panelLabel(request.panel),
                summary = "已打开${panelLabel(request.panel)}，请在系统设置中继续操作。",
                fields = listOf(DailyResultField("设置项", panelLabel(request.panel)), DailyResultField("方式", "系统设置 Activity")),
                asOf = now,
                verified = false, // handoff：面板已拉起不等于设置已生效
                sourceLabel = "系统交接",
            )
        } else {
            DailyCommandResult.Failure(request, "PANEL_UNAVAILABLE", "系统未接受打开${panelLabel(request.panel)}的请求。", now, "系统交接")
        }
    }

    /** 查看闹钟：ACTION_SHOW_ALARMS 交接，恒为 handoff（verified=false）。 */
    internal fun executeAlarmQuery(ops: LifeDeviceOps, now: Instant): DailyCommandResult =
        if (ops.openAlarms()) {
            DailyCommandResult.ActionDone(
                request = DailyIntent.AlarmQuery,
                title = "闹钟",
                summary = "已打开系统闹钟列表，请在时钟应用中查看已设置的闹钟。",
                fields = listOf(DailyResultField("方式", "系统时钟 · ACTION_SHOW_ALARMS")),
                asOf = now,
                verified = false, // handoff：无法读回具体闹钟条数
                sourceLabel = "系统时钟",
            )
        } else {
            DailyCommandResult.Failure(DailyIntent.AlarmQuery, "ALARM_LIST_UNAVAILABLE", "未能打开系统闹钟列表。", now, "系统时钟")
        }

    private fun Double.roundToIntCompat(): Int = kotlin.math.round(this).toInt()

    // ---- 设备操作抽象 ----
    internal data class TorchState(val available: Boolean, val enabled: Boolean?)
    internal data class StorageStats(val totalBytes: Long, val availableBytes: Long)

    internal interface LifeDeviceOps {
        fun torchState(): TorchState
        fun setTorch(enabled: Boolean): Boolean
        fun volumeRange(): IntRange?
        fun volumeIndex(): Int
        fun setVolumeIndex(index: Int): Boolean
        fun ringerMode(): Int
        fun setRingerMode(mode: Int): Boolean
        /** 返回可启动的真实包名；相机按标准 resolve；找不到返回 null。 */
        fun resolveApp(alias: String): String?
        fun launch(packageName: String): Boolean
        fun openNavigation(uri: String): Boolean
        fun batteryPercent(): Int?
        fun storageStats(): StorageStats?

        // ---- 第二批（P0）系统交互 ----
        fun mediaAccessGranted(): Boolean
        fun activeMediaSessions(): List<MediaSessionHandle>
        fun mediaCommand(sessionId: String, command: MediaPlaybackCommand): Boolean
        fun playbackStatus(sessionId: String): PlaybackStatus
        fun notificationPolicyAccessGranted(): Boolean
        fun setDndEnabled(enabled: Boolean): Boolean
        fun dndEnabled(): Boolean?
        fun openSystemPanel(action: String): Boolean
        fun openAlarms(): Boolean
    }

    /** 真实 Android 实现。 */
    internal class AndroidLifeDeviceOps(private val context: Context) : LifeDeviceOps {
        private val appContext = context.applicationContext

        override fun torchState(): LifeActionExecutor.TorchState {
            val result = FlashlightController.get(appContext).state()
            val available = result.optBoolean("available", false)
            val value = result.opt("enabled")
            return LifeActionExecutor.TorchState(available, if (value is Boolean) value else null)
        }

        override fun setTorch(enabled: Boolean): Boolean {
            val result = FlashlightController.get(appContext)
                .set(enabled, DeviceToolContract.appUserId(appContext))
            return result.optBoolean("verified", false)
        }

        private fun audio(): AudioManager? = appContext.getSystemService(AudioManager::class.java)

        override fun volumeRange(): IntRange? {
            val am = audio() ?: return null
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= min) return null
            return min..max
        }

        override fun volumeIndex(): Int = audio()?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

        override fun setVolumeIndex(index: Int): Boolean = try {
            audio()?.let {
                @Suppress("DEPRECATION")
                it.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
                true
            } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }

        override fun ringerMode(): Int = audio()?.ringerMode ?: -1

        override fun setRingerMode(mode: Int): Boolean = try {
            audio()?.let { it.ringerMode = mode; true } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }

        override fun resolveApp(alias: String): String? = try {
            if (isCameraAlias(alias)) {
                appContext.packageManager
                    .resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)
                    ?.activityInfo?.packageName
            } else {
                val pkg = aliasToPackage(alias) ?: return null
                appContext.packageManager.getLaunchIntentForPackage(pkg)?.let { pkg }
            }
        } catch (_: Exception) {
            null
        }

        override fun launch(packageName: String): Boolean = try {
            val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }

        override fun openNavigation(uri: String): Boolean = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }

        override fun batteryPercent(): Int? = try {
            val manager = appContext.getSystemService(BatteryManager::class.java) ?: return null
            val value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (value in 0..100) value else null
        } catch (_: Exception) {
            null
        }

        override fun storageStats(): LifeActionExecutor.StorageStats? = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            LifeActionExecutor.StorageStats(stat.totalBytes, stat.availableBytes)
        } catch (_: Exception) {
            null
        }

        // ---- 第二批（P0）真实系统交互 ----
        private fun mediaManager(): android.media.session.MediaSessionManager? =
            appContext.getSystemService(android.media.session.MediaSessionManager::class.java)

        private fun notificationManager(): android.app.NotificationManager? =
            appContext.getSystemService(android.app.NotificationManager::class.java)

        private fun listenerComponent() =
            android.content.ComponentName(appContext, io.github.mangi.eta.agent.device.AgentNotificationHistoryService::class.java)

        /** MediaSession.Token 无公开 id；用其 hashCode 作为会话内稳定键，足以区分/匹配当前活动会话。 */
        private fun sessionKey(token: android.media.session.MediaSession.Token?): String? =
            token?.let { it.hashCode().toString() }

        override fun mediaAccessGranted(): Boolean = try {
            notificationManager()?.isNotificationListenerAccessGranted(listenerComponent()) ?: false
        } catch (_: Exception) {
            false
        }

        override fun activeMediaSessions(): List<LifeActionExecutor.MediaSessionHandle> = try {
            val sessions = mediaManager()?.getActiveSessions(listenerComponent()).orEmpty()
            sessions.mapNotNull { controller ->
                val id = sessionKey(controller.sessionToken) ?: return@mapNotNull null
                val state = controller.playbackState
                val activeAt = state?.lastPositionUpdateTime ?: 0L
                LifeActionExecutor.MediaSessionHandle(id, activeAt)
            }
        } catch (_: Exception) {
            emptyList()
        }

        override fun mediaCommand(sessionId: String, command: MediaPlaybackCommand): Boolean = try {
            val controller = mediaManager()?.getActiveSessions(listenerComponent())
                ?.firstOrNull { sessionKey(it.sessionToken) == sessionId }
                ?: return false
            val controls = controller.transportControls ?: return false
            when (command) {
                MediaPlaybackCommand.PAUSE -> controls.pause()
                MediaPlaybackCommand.RESUME -> controls.play()
                MediaPlaybackCommand.NEXT -> controls.skipToNext()
                MediaPlaybackCommand.PREVIOUS -> controls.skipToPrevious()
            }
            true
        } catch (_: Exception) {
            false
        }

        override fun playbackStatus(sessionId: String): LifeActionExecutor.PlaybackStatus = try {
            val controller = mediaManager()?.getActiveSessions(listenerComponent())
                ?.firstOrNull { sessionKey(it.sessionToken) == sessionId }
                ?: return LifeActionExecutor.PlaybackStatus.NONE
            when (controller.playbackState?.state) {
                android.media.session.PlaybackState.STATE_PLAYING -> LifeActionExecutor.PlaybackStatus.PLAYING
                android.media.session.PlaybackState.STATE_PAUSED -> LifeActionExecutor.PlaybackStatus.PAUSED
                android.media.session.PlaybackState.STATE_STOPPED -> LifeActionExecutor.PlaybackStatus.STOPPED
                android.media.session.PlaybackState.STATE_NONE, null -> LifeActionExecutor.PlaybackStatus.NONE
                else -> LifeActionExecutor.PlaybackStatus.STOPPED
            }
        } catch (_: Exception) {
            LifeActionExecutor.PlaybackStatus.UNAVAILABLE
        }

        override fun notificationPolicyAccessGranted(): Boolean = try {
            notificationManager()?.isNotificationPolicyAccessGranted ?: false
        } catch (_: Exception) {
            false
        }

        override fun setDndEnabled(enabled: Boolean): Boolean = try {
            val manager = notificationManager() ?: return false
            manager.setInterruptionFilter(
                if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            true
        } catch (_: Exception) {
            false
        }

        override fun dndEnabled(): Boolean? = try {
            val manager = notificationManager() ?: return null
            when (manager.currentInterruptionFilter) {
                android.app.NotificationManager.INTERRUPTION_FILTER_NONE,
                android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS,
                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY -> true
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        override fun openSystemPanel(action: String): Boolean = try {
            appContext.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }

        override fun openAlarms(): Boolean = try {
            appContext.startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}
