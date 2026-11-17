package io.github.mangi.eta.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/** Settings and search results share this single, explicit priority order. */
internal enum class SettingsSection(val key: String) {
    MODELS_CONNECTIONS("section_models_connections"),
    ASSISTANT_VOICE("section_assistant_voice"),
    APPEARANCE_DATA("section_appearance_data"),
    TOOLS_EXTENSIONS("section_tools_extensions"),
    PROMPTS_MEMORY("section_prompts_memory"),
    PERMISSIONS_DEVICES("section_permissions_devices"),
    SYSTEM_INTEGRATION("section_system_integration"),
    DIAGNOSTICS("section_diagnostics"),
    ABOUT_DOWNLOADS("section_about_downloads"),
}

/** Returns actual visible groups only, in page order, so filtering leaves no empty list items. */
internal fun visibleSettingsSections(
    query: String,
    searchHaystacks: Map<SettingsSection, String>,
): List<SettingsSection> {
    val keyword = query.trim()
    return SettingsSection.entries.filter { section ->
        keyword.isEmpty() || searchHaystacks[section]?.contains(keyword, ignoreCase = true) == true
    }
}

/** Registers groups by identity; declaration position cannot silently change their priority. */
internal class SettingsSectionContent {
    private val content = mutableMapOf<SettingsSection, @Composable () -> Unit>()

    fun section(section: SettingsSection, body: @Composable () -> Unit) {
        check(content.put(section, body) == null) { "Settings group registered twice: $section" }
    }

    fun contentFor(section: SettingsSection): @Composable () -> Unit =
        checkNotNull(content[section]) { "Settings group is missing: $section" }
}

internal fun LazyListScope.settingsSections(
    visibleSections: List<SettingsSection>,
    content: SettingsSectionContent.() -> Unit,
) {
    val groups = SettingsSectionContent().apply(content)
    visibleSections.forEach { section ->
        val body = groups.contentFor(section)
        item(key = section.key) { body() }
    }
}
