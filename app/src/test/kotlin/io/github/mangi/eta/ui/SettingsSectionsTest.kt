package io.github.mangi.eta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionsTest {
    @Test
    fun openingSettingsPrioritizesModelsVoiceAndDataBeforeAdvancedGroups() {
        assertEquals(
            listOf(
                SettingsSection.MODELS_CONNECTIONS,
                SettingsSection.ASSISTANT_VOICE,
                SettingsSection.APPEARANCE_DATA,
                SettingsSection.TOOLS_EXTENSIONS,
                SettingsSection.PROMPTS_MEMORY,
                SettingsSection.PERMISSIONS_DEVICES,
                SettingsSection.SYSTEM_INTEGRATION,
                SettingsSection.DIAGNOSTICS,
                SettingsSection.ABOUT_DOWNLOADS,
            ),
            visibleSettingsSections("", emptyMap()),
        )
        assertEquals(SettingsSection.entries.size, SettingsSection.entries.map { it.key }.toSet().size)
    }

    @Test
    fun filteringKeepsPagePriorityEvenWhenIndexWasDeclaredInReverseOrder() {
        val index = linkedMapOf(
            SettingsSection.ABOUT_DOWNLOADS to "Github releases and source",
            SettingsSection.TOOLS_EXTENSIONS to "MCP connection tools",
            SettingsSection.MODELS_CONNECTIONS to "Model providers and GitHub connection",
        )
        assertEquals(
            listOf(SettingsSection.MODELS_CONNECTIONS, SettingsSection.ABOUT_DOWNLOADS),
            visibleSettingsSections("  GITHUB  ", index),
        )
    }

    @Test
    fun voiceAndMemoryQueriesOnlyRevealTheirOwningGroup() {
        val index = mapOf(
            SettingsSection.ASSISTANT_VOICE to "语音 识别 播报 停顿发送 离线 Vosk",
            SettingsSection.PROMPTS_MEMORY to "记忆 提示词 自动压缩",
            SettingsSection.APPEARANCE_DATA to "语言 外观 备份",
        )
        assertEquals(listOf(SettingsSection.ASSISTANT_VOICE), visibleSettingsSections("停顿发送", index))
        assertEquals(listOf(SettingsSection.ASSISTANT_VOICE), visibleSettingsSections("vosk", index))
        assertEquals(listOf(SettingsSection.PROMPTS_MEMORY), visibleSettingsSections("自动压缩", index))
        assertFalse(visibleSettingsSections("离线", index).contains(SettingsSection.APPEARANCE_DATA))
    }

    @Test
    fun unmatchedQueryProducesNoSectionsInsteadOfEmptyGroupItems() {
        assertTrue(visibleSettingsSections("unmatched", mapOf(SettingsSection.MODELS_CONNECTIONS to "providers")).isEmpty())
        assertTrue(visibleSettingsSections("providers", emptyMap()).isEmpty())
        assertEquals(SettingsSection.entries.toList(), visibleSettingsSections(" \n ", emptyMap()))
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateGroupRegistrationFailsBeforeRendering() {
        SettingsSectionContent().apply {
            section(SettingsSection.MODELS_CONNECTIONS) {}
            section(SettingsSection.MODELS_CONNECTIONS) {}
        }
    }

    @Test(expected = IllegalStateException::class)
    fun missingSectionContentCannotSilentlyDisappear() {
        SettingsSectionContent().contentFor(SettingsSection.MODELS_CONNECTIONS)
    }
}
