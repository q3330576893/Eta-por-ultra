package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentName
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import io.github.mangi.eta.core.AssistantContextFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaAssistantScreenContextTest {
    /** 打开一次显式采集，返回本次许可；每次唤醒默认关闭。 */
    private fun explicitPermit(): String {
        EtaAssistantScreenContexts.setEnabled(true)
        return requireNotNull(EtaAssistantScreenContexts.requestRefresh())
    }

    @Test
    fun replacingEntryClosesOldContextAndOldOwnerCannotClearNewEntry() = runBlocking {
        val old = requireNotNull(EtaAssistantScreenContexts.begin(0, listOf(ComponentName("old.app", "OldActivity")), explicitPermit()))
        val current = requireNotNull(EtaAssistantScreenContexts.begin(0, listOf(ComponentName("current.app", "CurrentActivity")), explicitPermit()))
        try {
            EtaAssistantScreenContexts.release(old.id)
            assertSame(current, EtaAssistantScreenContexts.find(current.id))
            assertNull(EtaAssistantScreenContexts.find(old.id))
            assertEquals("", old.snapshot().text)
            assertTrue(current.snapshot().text.contains("current.app"))
            assertFalse(current.snapshot().text.contains("old.app"))
        } finally {
            EtaAssistantScreenContexts.release(current.id)
            EtaAssistantScreenContexts.setEnabled(false)
        }
    }

    /** 没有任何显式许可时，系统即使带着 showFlags 也不能建立采集上下文。 */
    @Test
    fun beginWithoutExplicitPermitGrantsNothing() {
        EtaAssistantScreenContexts.setEnabled(false)
        assertNull(EtaAssistantScreenContexts.begin(0, listOf(ComponentName("peek.app", "Activity"))))
        EtaAssistantScreenContexts.setEnabled(true)
        // 已启用但未申请许可，同样不建立采集。
        assertNull(EtaAssistantScreenContexts.begin(0, listOf(ComponentName("peek.app", "Activity"))))
        EtaAssistantScreenContexts.setEnabled(false)
    }

    @Test
    fun disabledContextReturnsEmptySnapshotAndStillExposesIdentity() = runBlocking {
        val capture = EtaAssistantScreenContext(
            0,
            listOf(ComponentName("current.app", "CurrentActivity")),
            captureRequested = true,
        )
        try {
            assertEquals("current.app", capture.foregroundAppLabel)
            assertTrue(capture.capturedAt > 0L)
            assertEquals(EtaAssistantScreenContext.Snapshot(), capture.snapshot(includeContext = false))
            assertTrue(capture.snapshot().text.contains("current.app"))
        } finally { capture.close() }
    }

    @Test
    fun deniedScreenshotStillProvidesApplicationIdentity() = runBlocking {
        val capture = EtaAssistantScreenContext(
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
            listOf(ComponentName("current.app", "CurrentActivity")),
            captureRequested = true,
        )
        try {
            capture.acceptScreenshot(null)
            val snapshot = capture.snapshot()
            assertNull(snapshot.image)
            assertTrue(snapshot.text.contains("current.app"))
            assertTrue(snapshot.text.contains("未提供可用截图"))
        } finally { capture.close() }
    }

    @Test
    fun closedEntryRejectsLateScreenshotAndReleasesContext() = runBlocking {
        val capture = EtaAssistantScreenContext(0, listOf(ComponentName("closed.app", "Activity")))
        capture.close()
        capture.acceptScreenshot(null)
        assertEquals(EtaAssistantScreenContext.Snapshot(), capture.snapshot())
    }

    /** 默认唤醒不申请应用内容与截图；只有显式采集才带上对应 flag。 */
    @Test
    fun ordinaryInvocationRequestsNoContext() {
        assertEquals(0, AssistantContextFlags.forSdk(34))
        assertEquals(0, AssistantContextFlags.forSdk(37))
    }

    @Test
    fun explicitCaptureRequestsBothChannelsAndAndroid17ScreenContent() {
        val older = AssistantContextFlags.forSdk(34, captureRequested = true)
        assertTrue(older and VoiceInteractionSession.SHOW_WITH_ASSIST != 0)
        assertTrue(older and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0)
        assertEquals(0, older and VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT)
        assertTrue(
            AssistantContextFlags.forSdk(37, captureRequested = true) and
                VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT != 0,
        )
    }

    @Test
    fun passwordNodesAreExcludedWithoutExcludingOrdinaryTextFields() {
        assertTrue(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }
}
