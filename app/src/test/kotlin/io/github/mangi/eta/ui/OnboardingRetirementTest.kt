package io.github.mangi.eta.ui

import android.app.Application
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.settings.SETTINGS_SEARCH_INDEX
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OnboardingRetirementTest {
    @Test
    fun oldSettingsRestoreIgnoresTourStateAndPreservesActivePreferences() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val restored = json.decodeFromString<Settings>(
            """{
                "selectedProviderId":"fixture-provider",
                "selectedModelId":"fixture-model",
                "memoryEnabled":false,
                "autoBackupEnabled":true,
                "appearance":{"themeMode":"DARK","blurEnabled":false},
                "onboardingEnabled":true,
                "seenTourPacks":{"welcome":1,"gestures":2}
            }""",
        )

        assertEquals("fixture-provider", restored.selectedProviderId)
        assertEquals("fixture-model", restored.selectedModelId)
        assertFalse(restored.memoryEnabled)
        assertTrue(restored.autoBackupEnabled)
        assertEquals(AppearanceThemeMode.DARK, restored.appearance.themeMode)
        assertFalse(restored.appearance.blurEnabled)
        val exported = json.encodeToString(restored)
        assertFalse(exported.contains("onboardingEnabled"))
        assertFalse(exported.contains("seenTourPacks"))
        assertEquals(restored, json.decodeFromString<Settings>(exported))
    }

    @Test
    fun tutorialSearchIsEmptyWhileAppearanceAndBackupRemainDiscoverable() {
        val resources = RuntimeEnvironment.getApplication().resources
        val index = SETTINGS_SEARCH_INDEX.associate { entry ->
            entry.section to buildString {
                append(entry.keywords)
                entry.labels.forEach { append(' ').append(resources.getString(it)) }
                entry.labelsRaw.forEach { append(' ').append(it) }
            }.lowercase()
        }

        listOf("新手", "教程", "onboarding", "tutorial", "重播引导").forEach { query ->
            assertTrue("Retired search entry: $query", visibleSettingsSections(query, index).isEmpty())
        }
        assertEquals(listOf(SettingsSection.APPEARANCE_DATA), visibleSettingsSections("外观", index))
        assertEquals(listOf(SettingsSection.APPEARANCE_DATA), visibleSettingsSections("自动备份", index))
    }

    @Test
    fun removedTutorialHostsAndGestureOverlaysAreAbsentFromTheRuntime() {
        listOf(
            "ui.onboarding.OnboardingHostKt",
            "ui.onboarding.GestureCoachKt",
            "ui.onboarding.GestureCoachScript",
            "ui.onboarding.OnboardingKeys",
            "ui.onboarding.SpotlightTourKt",
            "ui.onboarding.TourAnchorRegistryKt",
            "ui.onboarding.TourAnchors",
            "ui.onboarding.TourModelKt",
            "ui.components.PaneHintBarKt",
        ).forEach { suffix ->
            assertThrows(suffix, ClassNotFoundException::class.java) {
                Class.forName("io.github.mangi.eta.$suffix", false, javaClass.classLoader)
            }
        }
    }
}
