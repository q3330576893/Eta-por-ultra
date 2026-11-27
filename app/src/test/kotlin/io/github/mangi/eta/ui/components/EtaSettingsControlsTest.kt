package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtaSettingsControlsTest {
    @Test
    fun glassSwitchExposesSwitchRoleStateAndExactlyOneCallbackPerAccessibleClick() {
        val checked = mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        SettingsComposeTestHost(
            appearance = appearance(),
            content = {
                EtaSettingsGlassPage {
                    Box(Modifier.padding(24.dp)) {
                        EtaSwitch(
                            checked = checked.value,
                            onCheckedChange = { changes += it; checked.value = it },
                        )
                    }
                }
            },
        ).use { host ->
            val before = host.singleToggle()
            assertEquals(Role.Switch, before.config.getOrNull(SemanticsProperties.Role))
            assertEquals(ToggleableState.Off, before.config.getOrNull(SemanticsProperties.ToggleableState))
            assertFalse(before.config.contains(SemanticsProperties.Disabled))
            assertTrue("Switch must preserve its 44dp touch target", before.boundsInRoot.height >= host.dp(44f) - 1f)
            host.performSemanticsClick(before)
            assertEquals(listOf(true), changes)
            assertEquals(ToggleableState.On, host.singleToggle().config.getOrNull(SemanticsProperties.ToggleableState))
            host.performSemanticsClick(host.singleToggle())
            assertEquals(listOf(true, false), changes)
            assertEquals(ToggleableState.Off, host.singleToggle().config.getOrNull(SemanticsProperties.ToggleableState))
        }
    }

    @Test
    fun disabledGlassSwitchKeepsItsStateAndIgnoresPhysicalTaps() {
        var callbackCount = 0
        SettingsComposeTestHost(
            appearance = appearance(dark = true),
            content = {
                EtaSettingsGlassPage {
                    Box(Modifier.padding(24.dp)) {
                        EtaSwitch(checked = true, enabled = false, onCheckedChange = { callbackCount++ })
                    }
                }
            },
        ).use { host ->
            val toggle = host.singleToggle()
            assertEquals(Role.Switch, toggle.config.getOrNull(SemanticsProperties.Role))
            assertEquals(ToggleableState.On, toggle.config.getOrNull(SemanticsProperties.ToggleableState))
            assertTrue("Assistive technology must identify the switch as disabled", toggle.config.contains(SemanticsProperties.Disabled))
            host.tap(toggle.boundsInRoot)
            assertEquals(0, callbackCount)
            assertEquals(ToggleableState.On, host.singleToggle().config.getOrNull(SemanticsProperties.ToggleableState))
            host.captureIfRequested("settings_switch_disabled_dark")
        }
    }

    @Test
    fun switchPreferenceHasOneAccessibleToggleAndRowAndThumbEachToggleOnlyOnce() {
        val checked = mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        SettingsComposeTestHost(
            appearance = appearance(),
            content = {
                EtaSettingsGlassPage {
                    EtaSwitchPreference(
                        title = "Use glass effects",
                        summary = "A readable background remains available when blur is disabled.",
                        checked = checked.value,
                        onCheckedChange = { changes += it; checked.value = it },
                    )
                }
            },
        ).use { host ->
            val row = host.singleToggle()
            assertEquals(Role.Switch, row.config.getOrNull(SemanticsProperties.Role))
            val labels = host.nodes(merged = true).single { node ->
                node.config.getOrNull(SemanticsProperties.ToggleableState) != null
            }.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertTrue("Merged toggle semantics must include its visible label", "Use glass effects" in labels)
            host.performSemanticsClick(row)
            assertEquals(listOf(true), changes)
            assertEquals(ToggleableState.On, host.singleToggle().config.getOrNull(SemanticsProperties.ToggleableState))
            val bounds = host.singleToggle().boundsInRoot
            val centerX = bounds.right - host.dp(42f)
            val centerY = bounds.center.y
            host.tap(Rect(centerX - 1f, centerY - 1f, centerX + 1f, centerY + 1f))
            assertEquals("Tapping the thumb must not also invoke its parent row", listOf(true, false), changes)
            assertEquals(ToggleableState.Off, host.singleToggle().config.getOrNull(SemanticsProperties.ToggleableState))
        }
    }

    @Test
    fun longTextAtTwoHundredPercentFontScaleWrapsWithoutClippingOrCoveringTheSwitchInEitherDirection() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            var regularHeight = 0f
            for (fontScale in listOf(1f, 2f)) {
                SettingsComposeTestHost(
                    appearance = appearance(),
                    fontScale = fontScale,
                    layoutDirection = direction,
                    content = {
                        EtaSettingsGlassPage {
                            EtaPreference(
                                title = LongTitle,
                                summary = LongSummary,
                                modifier = Modifier.testTag("large_preference"),
                                startAction = { Box(Modifier.size(EtaPreferenceDefaults.IconSize)) },
                                endActions = {
                                    EtaSwitch(
                                        checked = true,
                                        onCheckedChange = {},
                                        modifier = Modifier.testTag("large_preference_switch"),
                                    )
                                },
                            )
                        }
                    },
                ).use { host ->
                    val row = host.nodeWithTag("large_preference").boundsInRoot
                    val control = host.nodeWithTag("large_preference_switch").boundsInRoot
                    val title = host.nodeWithText(LongTitle)
                    val summary = host.nodeWithText(LongSummary)
                    assertTrue("Preference must fit a 320dp viewport: $row", row.left >= -1f && row.right <= host.dp(320f) + 1f)
                    assertContained(row, title.boundsInRoot)
                    assertContained(row, summary.boundsInRoot)
                    assertContained(row, control)
                    assertTrue("Summary must follow the complete wrapped title", summary.boundsInRoot.top >= title.boundsInRoot.bottom - 1f)
                    for (text in listOf(title, summary)) {
                        assertFalse("Text and trailing switch must not overlap at fontScale=$fontScale in $direction", text.boundsInRoot.overlaps(control))
                        assertTextIsNotTruncated(text)
                    }
                    assertTrue("Large text must not shrink the switch target", control.height >= host.dp(44f) - 1f)
                    if (fontScale == 1f) {
                        regularHeight = row.height
                    } else {
                        assertTrue("Preference must grow vertically rather than clipping enlarged text", row.height > regularHeight)
                        val suffix = if (direction == LayoutDirection.Ltr) "ltr" else "rtl"
                        host.captureIfRequested("settings_font_200_$suffix")
                    }
                }
            }
        }
    }

    @Test
    fun glassSwitchThumbMirrorsItsRenderedPositionInRtlForBothStates() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val checked = mutableStateOf(true)
            SettingsComposeTestHost(
                appearance = appearance(),
                layoutDirection = direction,
                content = {
                    EtaSettingsGlassPage {
                        Box(Modifier.padding(24.dp)) {
                            EtaSwitch(checked = checked.value, onCheckedChange = { checked.value = it })
                        }
                    }
                },
            ).use { host ->
                for (state in listOf(true, false)) {
                    checked.value = state
                    val bitmap = host.captureBitmap()
                    try {
                        val bounds = host.singleToggle().boundsInRoot
                        val y = bounds.center.y.toInt()
                        val left = bitmap.getPixel((bounds.left + host.dp(7f)).toInt(), y)
                        val right = bitmap.getPixel((bounds.right - host.dp(7f)).toInt(), y)
                        fun isThumb(pixel: Int): Boolean =
                            android.graphics.Color.red(pixel) >= 235 &&
                                android.graphics.Color.green(pixel) >= 235 &&
                                android.graphics.Color.blue(pixel) >= 235
                        val thumbOnRight = state == (direction == LayoutDirection.Ltr)
                        assertEquals("Left thumb pixel in $direction checked=$state", !thumbOnRight, isThumb(left))
                        assertEquals("Right thumb pixel in $direction checked=$state", thumbOnRight, isThumb(right))
                    } finally {
                        bitmap.recycle()
                    }
                }
                host.captureIfRequested(if (direction == LayoutDirection.Rtl) "settings_switch_rtl" else "settings_switch_ltr")
            }
        }
    }

    private fun appearance(dark: Boolean = false) = AppearanceSettings(
        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
        blurEnabled = false,
    )

    private fun SettingsComposeTestHost.singleToggle(): SemanticsNode {
        val toggles = nodes().filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
        assertEquals("Exactly one accessible toggle must represent the control", 1, toggles.size)
        return toggles.single()
    }

    private fun SettingsComposeTestHost.nodeWithTag(tag: String): SemanticsNode = nodes().single {
        it.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    private fun SettingsComposeTestHost.nodeWithText(text: String): SemanticsNode = nodes().single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun assertContained(parent: Rect, child: Rect) {
        assertTrue("Content must have a non-empty measured area: $child", child.width > 0f && child.height > 0f)
        assertTrue("Content must stay inside its preference: parent=$parent, child=$child", child.left >= parent.left - 1f && child.top >= parent.top - 1f && child.right <= parent.right + 1f && child.bottom <= parent.bottom + 1f)
    }

    private fun assertTextIsNotTruncated(node: SemanticsNode) {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("Text must expose its actual layout result", node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
        assertTrue(results.isNotEmpty())
        assertFalse("All enlarged text must remain visible", results.last().hasVisualOverflow)
    }

    private companion object {
        const val LongTitle = "Allow the assistant to use this capability"
        const val LongSummary = "When enabled, the assistant can use this capability for the current conversation. You can turn it off at any time in settings."
    }
}
