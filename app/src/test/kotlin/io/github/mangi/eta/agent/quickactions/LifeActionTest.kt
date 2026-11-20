package io.github.mangi.eta.agent.quickactions

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 测试：通过 [LifeActionExecutor.LifeDeviceOps] fake 覆盖映射、换算与结果构造，
 * 不要求真实 CameraManager / AudioManager 成功，也不依赖 Robolectric native runtime。
 */
class LifeActionTest {
    private val now = Instant.ofEpochMilli(1_700_000_000_000L)

    @Test fun appAliasMappingIsExplicitAndCameraUsesStandardResolve() {
        assertEquals("com.tencent.mm", LifeActionExecutor.aliasToPackage("微信"))
        assertEquals("com.taobao.taobao", LifeActionExecutor.aliasToPackage("淘宝"))
        assertEquals("com.eg.android.AlipayGphone", LifeActionExecutor.aliasToPackage("支付宝"))
        assertEquals("com.ss.android.ugc.aweme", LifeActionExecutor.aliasToPackage("抖音"))
        assertEquals("com.android.settings", LifeActionExecutor.aliasToPackage("设置"))
        assertNull(LifeActionExecutor.aliasToPackage("相机"))
        assertNull(LifeActionExecutor.aliasToPackage("QQ"))
        assertTrue(LifeActionExecutor.isCameraAlias("相机"))
        assertTrue(LifeActionExecutor.isCameraAlias("照相机"))
        assertFalse(LifeActionExecutor.isCameraAlias("微信"))
        assertEquals("相机", LifeActionExecutor.appDisplayName("照相机"))
    }

    @Test fun volumePercentRoundTripIsBounded() {
        assertEquals(8, LifeActionExecutor.indexFromPercent(50, 0, 15))
        assertEquals(15, LifeActionExecutor.indexFromPercent(100, 0, 15))
        assertEquals(0, LifeActionExecutor.indexFromPercent(0, 0, 15))
        assertEquals(0, LifeActionExecutor.indexFromPercent(-20, 0, 15))
        assertEquals(15, LifeActionExecutor.indexFromPercent(150, 0, 15))
        assertEquals(53, LifeActionExecutor.percentFromIndex(8, 0, 15))
        assertEquals(100, LifeActionExecutor.percentFromIndex(15, 0, 15))
        assertEquals(0, LifeActionExecutor.percentFromIndex(0, 0, 15))
        assertEquals(0, LifeActionExecutor.indexFromPercent(50, 5, 5))
        assertEquals(0, LifeActionExecutor.percentFromIndex(5, 5, 5))
    }

    @Test fun ringerMappingAndModeMatch() {
        assertEquals(0, LifeActionExecutor.audioModeFor(RingerKind.SILENT))
        assertEquals(1, LifeActionExecutor.audioModeFor(RingerKind.VIBRATE))
        assertEquals(2, LifeActionExecutor.audioModeFor(RingerKind.NORMAL))
        assertTrue(LifeActionExecutor.modeMatches(1, RingerKind.VIBRATE))
        assertFalse(LifeActionExecutor.modeMatches(2, RingerKind.SILENT))
        assertEquals("震动", LifeActionExecutor.ringerLabel(RingerKind.VIBRATE))
    }

    @Test fun geoQueryPercentEncodesChineseDestination() {
        assertEquals("geo:0,0?q=%E8%A5%BF%E6%B9%96", LifeActionExecutor.geoQuery("西湖"))
        assertEquals("geo:0,0?q=ABC-123", LifeActionExecutor.geoQuery("ABC-123"))
    }

    @Test fun formatBytesUsesHumanUnits() {
        assertEquals("512 B", LifeActionExecutor.formatBytes(512))
        assertEquals("1.0 KB", LifeActionExecutor.formatBytes(1024))
        assertEquals("1.5 MB", LifeActionExecutor.formatBytes(1572864))
    }

    @Test fun flashlightVerifiedOnlyWhenReadbackMatchesTarget() {
        val ops = FakeOps(torchAvailable = true, torchOn = false)
        val on = LifeActionExecutor.executeFlashlight(ops, DailyIntent.FlashlightAction(true), now)
        assertTrue(on is DailyCommandResult.ActionDone)
        assertTrue(on.verified)
        assertEquals("已打开手电筒。", on.summary)
        assertTrue(ops.torchOn)

        val off = LifeActionExecutor.executeFlashlight(ops, DailyIntent.FlashlightAction(false), now)
        assertTrue(off.verified)
        assertFalse(ops.torchOn)
    }

    @Test fun flashlightUnavailableReturnsFailure() {
        val ops = FakeOps(torchAvailable = false)
        val result = LifeActionExecutor.executeFlashlight(ops, DailyIntent.FlashlightAction(true), now)
        assertTrue(result is DailyCommandResult.Failure)
        assertEquals("FLASHLIGHT_UNAVAILABLE", (result as DailyCommandResult.Failure).code)
    }

    @Test fun flashlightUnconfirmedReadbackIsNotVerified() {
        val ops = FakeOps(torchAvailable = true, torchOn = false, torchCommandAccepted = false)
        val result = LifeActionExecutor.executeFlashlight(ops, DailyIntent.FlashlightAction(true), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertFalse(result.verified)
    }

    @Test fun setVolumeReadbackDrivesVerified() {
        val ops = FakeOps(volume = 3)
        val result = LifeActionExecutor.executeSetVolume(ops, DailyIntent.SetVolumeAction(50), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertTrue(result.verified)
        assertEquals(8, ops.volume)
        assertTrue(result.summary.contains("%"))
    }

    @Test fun adjustVolumeClampsAtRangeEnd() {
        val ops = FakeOps(volume = 15)
        val result = LifeActionExecutor.executeAdjustVolume(ops, DailyIntent.AdjustVolumeAction(true), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertTrue(result.verified)
        assertEquals(15, ops.volume)
    }

    @Test fun ringerModeVerifiedOnlyWhenReadbackMatches() {
        val ops = FakeOps(ringer = 2)
        val result = LifeActionExecutor.executeRinger(ops, DailyIntent.SetRingerAction(RingerKind.SILENT), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertTrue(result.verified)
        assertEquals(0, ops.ringer)
    }

    @Test fun openAppUnknownAliasFailsExplicitlyWithoutOpeningAnything() {
        val ops = FakeOps()
        val result = LifeActionExecutor.executeOpenApp(ops, DailyIntent.OpenAppAction("不存在的App"), now)
        assertTrue(result is DailyCommandResult.Failure)
        assertEquals("APP_NOT_FOUND", (result as DailyCommandResult.Failure).code)
        assertNull(ops.launchedPackage)
    }

    @Test fun openAppKnownAliasLaunchesResolvedPackage() {
        val ops = FakeOps()
        val result = LifeActionExecutor.executeOpenApp(ops, DailyIntent.OpenAppAction("微信"), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertTrue(result.verified)
        assertEquals("com.tencent.mm", ops.launchedPackage)
    }

    @Test fun navigationIsHandoffAndNeverVerified() {
        val ops = FakeOps()
        val result = LifeActionExecutor.executeNavigate(ops, DailyIntent.NavigateAction("西湖"), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertFalse(result.verified)
        assertEquals("geo:0,0?q=%E8%A5%BF%E6%B9%96", ops.navigationUri)
    }

    @Test fun batteryAndStorageAreReadBackVerified() {
        val battery = LifeActionExecutor.executeBattery(FakeOps(battery = 78), now)
        assertTrue(battery is DailyCommandResult.ActionDone)
        assertTrue(battery.verified)
        assertEquals("当前电量 78%。", battery.summary)

        val storage = LifeActionExecutor.executeStorage(
            FakeOps(storage = LifeActionExecutor.StorageStats(1024L * 1024 * 1024, 250L * 1024 * 1024)), now)
        assertTrue(storage is DailyCommandResult.ActionDone)
        assertTrue(storage.verified)
        assertTrue(storage.summary.contains("1.0 GB"))
        assertTrue(storage.summary.contains("250.0 MB"))
    }

    // ---- 小爱同学式生活便利 · 第二批（P0）----

    @Test fun panelActionMappingIsExplicit() {
        assertEquals("android.settings.WIFI_SETTINGS", LifeActionExecutor.panelAction(SystemPanel.WIFI))
        assertEquals("android.settings.BLUETOOTH_SETTINGS", LifeActionExecutor.panelAction(SystemPanel.BLUETOOTH))
        assertEquals("android.settings.TETHER_SETTINGS", LifeActionExecutor.panelAction(SystemPanel.HOTSPOT))
        assertEquals("android.settings.WIRELESS_SETTINGS", LifeActionExecutor.panelAction(SystemPanel.NETWORK))
        assertEquals("WiFi 设置", LifeActionExecutor.panelLabel(SystemPanel.WIFI))
    }

    @Test fun mediaWithoutNotificationAccessFailsWithRequiredCode() {
        val ops = FakeOps(mediaAccess = false)
        val result = LifeActionExecutor.executeMediaPlayback(
            ops, DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.PAUSE), now)
        assertTrue(result is DailyCommandResult.Failure)
        assertEquals("MEDIA_ACCESS_REQUIRED", (result as DailyCommandResult.Failure).code)
        assertTrue(result.summary.contains("通知使用权"))
    }

    @Test fun mediaUsesMostRecentlyActiveSessionAndVerifiesReadback() {
        val ops = FakeOps(
            sessions = listOf(
                LifeActionExecutor.MediaSessionHandle("old", 100L),
                LifeActionExecutor.MediaSessionHandle("recent", 999L),
            ),
            statusForSession = mapOf("recent" to LifeActionExecutor.PlaybackStatus.PAUSED),
        )
        val result = LifeActionExecutor.executeMediaPlayback(
            ops, DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.PAUSE), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertTrue(result.verified)
        assertEquals("recent", ops.mediaCommandLog.single().second)
    }

    @Test fun mediaUnconfirmedReadbackIsNotVerified() {
        val ops = FakeOps(
            sessions = listOf(LifeActionExecutor.MediaSessionHandle("s", 1L)),
            statusForSession = mapOf("s" to LifeActionExecutor.PlaybackStatus.PLAYING),
        )
        val result = LifeActionExecutor.executeMediaPlayback(
            ops, DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.PAUSE), now)
        assertTrue(result is DailyCommandResult.ActionDone)
        assertFalse(result.verified)
    }

    @Test fun mediaWithoutActiveSessionFailsExplicitly() {
        val result = LifeActionExecutor.executeMediaPlayback(
            FakeOps(), DailyIntent.MediaPlaybackAction(MediaPlaybackCommand.NEXT), now)
        assertTrue(result is DailyCommandResult.Failure)
        assertEquals("NO_MEDIA_SESSION", (result as DailyCommandResult.Failure).code)
    }

    @Test fun dndWithoutPolicyAccessFailsWithRequiredCode() {
        val result = LifeActionExecutor.executeDnd(
            FakeOps(dndAccess = false), DailyIntent.DndAction(DndKind.ENABLE), now)
        assertTrue(result is DailyCommandResult.Failure)
        assertEquals("DND_PERMISSION_REQUIRED", (result as DailyCommandResult.Failure).code)
        assertTrue(result.summary.contains("勿扰权限"))
    }

    @Test fun dndEnableAndCancelDriveVerifiedFromReadback() {
        val on = LifeActionExecutor.executeDnd(FakeOps(), DailyIntent.DndAction(DndKind.ENABLE), now)
        assertTrue(on is DailyCommandResult.ActionDone)
        assertTrue(on.verified)
        assertEquals("勿扰开启", on.fields.first { it.label == "读回状态" }.value)
        val cancel = LifeActionExecutor.executeDnd(
            FakeOps(initialDnd = true), DailyIntent.DndAction(DndKind.CANCEL), now)
        assertTrue(cancel is DailyCommandResult.ActionDone)
        assertTrue(cancel.verified)
    }

    @Test fun systemPanelsAndAlarmQueryAreHandoffAndNeverVerified() {
        val panel = LifeActionExecutor.executeSystemPanel(
            FakeOps(), DailyIntent.SystemPanelAction(SystemPanel.WIFI), now)
        assertTrue(panel is DailyCommandResult.ActionDone)
        assertFalse(panel.verified)
        assertEquals("android.settings.WIFI_SETTINGS", (FakeOps().let { ops ->
            LifeActionExecutor.executeSystemPanel(ops, DailyIntent.SystemPanelAction(SystemPanel.WIFI), now)
            ops.openedPanel
        }))
        val alarm = LifeActionExecutor.executeAlarmQuery(FakeOps(), now)
        assertTrue(alarm is DailyCommandResult.ActionDone)
        assertFalse(alarm.verified)
        assertEquals("系统时钟", alarm.sourceLabel)
    }

    @Test fun calcFollowUpReusesLastResultAndFailsWithoutHistory() {
        CalculationHistory.clear()
        val noHistory = QuickActionsExecutor.executeCalcFollowUp(
            DailyIntent.CalcFollowUp(CalcFollowOp.ADD, "5"), now)
        assertTrue(noHistory is DailyCommandResult.Failure)
        assertEquals("NO_CALCULATION_HISTORY", (noHistory as DailyCommandResult.Failure).code)

        QuickActionsExecutor.executeCalculation(DailyIntent.Calculate("10+2"), now)
        val add = QuickActionsExecutor.executeCalcFollowUp(
            DailyIntent.CalcFollowUp(CalcFollowOp.ADD, "5"), now)
        assertTrue(add is DailyCommandResult.CalculationAnswer)
        assertEquals("17", add.fields.first { it.label == "结果" }.value)
        assertTrue(add.verified)

        val subtract = QuickActionsExecutor.executeCalcFollowUp(
            DailyIntent.CalcFollowUp(CalcFollowOp.SUBTRACT, "3"), now)
        assertTrue(subtract is DailyCommandResult.CalculationAnswer)
        assertEquals("14", subtract.fields.first { it.label == "结果" }.value)
        CalculationHistory.clear()
    }

    private class FakeOps(
        torchAvailable: Boolean = true,
        torchOn: Boolean = false,
        private val torchCommandAccepted: Boolean = true,
        volume: Int = 0,
        ringer: Int = 2,
        private val battery: Int? = 50,
        private val storage: LifeActionExecutor.StorageStats? = LifeActionExecutor.StorageStats(1000L, 500L),
        private val mediaAccess: Boolean = true,
        private val sessions: List<LifeActionExecutor.MediaSessionHandle> = emptyList(),
        private val statusForSession: Map<String, LifeActionExecutor.PlaybackStatus> = emptyMap(),
        private val mediaCommandAccepted: Boolean = true,
        private val dndAccess: Boolean = true,
        private val dndCommandAccepted: Boolean = true,
        private val initialDnd: Boolean = false,
        private val panelOpened: Boolean = true,
    ) : LifeActionExecutor.LifeDeviceOps {
        var torchOn: Boolean = torchOn
            private set
        private val hasTorch = torchAvailable
        var volume: Int = volume
            private set
        var ringer: Int = ringer
            private set
        var launchedPackage: String? = null
            private set
        var navigationUri: String? = null
            private set
        var mediaCommandLog: MutableList<Pair<MediaPlaybackCommand, String>> = mutableListOf()
            private set
        var dndOn: Boolean? = initialDnd
            private set
        var openedPanel: String? = null
            private set
        var alarmsOpened: Boolean = false
            private set

        override fun torchState() = LifeActionExecutor.TorchState(hasTorch, torchOn)
        override fun setTorch(enabled: Boolean): Boolean {
            if (torchCommandAccepted) torchOn = enabled
            return torchCommandAccepted
        }
        override fun volumeRange(): IntRange = 0..15
        override fun volumeIndex() = volume
        override fun setVolumeIndex(index: Int): Boolean { volume = index; return true }
        override fun ringerMode() = ringer
        override fun setRingerMode(mode: Int): Boolean { ringer = mode; return true }
        override fun resolveApp(alias: String) = LifeActionExecutor.aliasToPackage(alias)
        override fun launch(packageName: String): Boolean { launchedPackage = packageName; return true }
        override fun openNavigation(uri: String): Boolean { navigationUri = uri; return true }
        override fun batteryPercent() = battery
        override fun storageStats() = storage

        // ---- 第二批（P0）----
        override fun mediaAccessGranted() = mediaAccess
        override fun activeMediaSessions() = sessions
        override fun mediaCommand(sessionId: String, command: MediaPlaybackCommand): Boolean {
            mediaCommandLog += command to sessionId
            return mediaCommandAccepted
        }
        override fun playbackStatus(sessionId: String) = statusForSession[sessionId] ?: LifeActionExecutor.PlaybackStatus.NONE
        override fun notificationPolicyAccessGranted() = dndAccess
        override fun setDndEnabled(enabled: Boolean): Boolean { dndOn = enabled; return dndCommandAccepted }
        override fun dndEnabled(): Boolean? = dndOn
        override fun openSystemPanel(action: String): Boolean { openedPanel = action; return panelOpened }
        override fun openAlarms(): Boolean { alarmsOpened = true; return alarmsOpened }
    }
}
