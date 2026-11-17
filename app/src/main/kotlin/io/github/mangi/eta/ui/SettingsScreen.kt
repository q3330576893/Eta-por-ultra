package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import top.yukonga.miuix.kmp.basic.Icon
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.core.DiagnosticsBundle
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.EtaDesign
import io.github.mangi.eta.ui.components.EtaBrandHeroCard
import io.github.mangi.eta.ui.components.etaBrandBrush
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.onboarding.OnboardingKeys
import io.github.mangi.eta.ui.settings.rememberSettingsSearchHaystacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */
/** Eta Ultra 的源码、问题反馈、正式安装包与版本记录统一由本仓库提供。 */
private const val PROJECT_REPO_URL = "https://github.com/q3330576893/Eta-por-ultra"

@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageContent(context = context, onNavigate = onNavigate, onBack = onBack)
}

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var showTestConsent by remember { mutableStateOf(false) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // 关于组：反馈与诊断。
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    // 崩溃记录条数：只给关于页那行入口写摘要；明细都在「诊断与日志」里。
    var crashCount by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
                accessibilityProtectionEnabled =
                    AccessibilityProtectionClient.isEnabled(context)
                val crashes = CrashRecorder.records(context)
                crashCount = crashes.size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId && it.isEnabled }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId && it.isEnabled }
    }
    // Only presentation names are shown; connection credentials and request configuration stay private.
    val providerSummary = selectedProvider?.name
        ?: stringResource(R.string.settings_priority_provider_hint)
    val currentModelSummary = selectedModel?.let { model ->
        stringResource(
            R.string.settings_priority_current_model_provider,
            selectedProvider?.name.orEmpty(),
            model.displayName,
        )
    } ?: stringResource(R.string.settings_priority_current_model_unset)

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(EtaApp.serviceInstance)) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        DropdownItem(text = target.displayName(context))
    }

    // 搜索和默认页面共用 SettingsSection 优先级；标题、别名及当前模型名称使用同一过滤口径。
    // 匹配后只注册可见列表条目，避免空分组占位。
    var settingsQuery by remember { mutableStateOf("") }
    val indexedHaystacks = rememberSettingsSearchHaystacks()
    val searchHaystacks = indexedHaystacks + (
        SettingsSection.MODELS_CONNECTIONS to listOfNotNull(
            indexedHaystacks[SettingsSection.MODELS_CONNECTIONS],
            selectedProvider?.name,
            selectedModel?.displayName,
        ).joinToString("\u0000")
    )
    val visibleSections = visibleSettingsSections(settingsQuery, searchHaystacks)
    val settingsNoHit = settingsQuery.isNotBlank() && visibleSections.isEmpty()

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
    ) {

            item(key = "settings_brand_hero") {
                EtaBrandHeroCard()
            }

            item(key = "settings_search") {
                SettingsSearchBar(
                    query = settingsQuery,
                    onQueryChange = { settingsQuery = it },
                    emptyResult = settingsNoHit,
                )
            }

            settingsSections(visibleSections) {
                section(SettingsSection.APPEARANCE_DATA) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_appearance_data))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.appearance_title),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Palette,
                                    tint = EtaPreferenceColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.AppearanceSettings) },
                        )

                        EtaPreferenceDivider()
                        LanguagePreference(iconTint = EtaPreferenceColors.Blue)

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.data_backup_title),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Description,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.DataBackup) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.data_usage_title),
                            summary = stringResource(R.string.data_usage_summary),
                            startAction = {
                                EtaPreferenceIcon(icon = Icons.Rounded.Inventory2, tint = EtaPreferenceColors.Blue)
                            },
                            onClick = { onNavigate(AppRoute.DataUsage) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.usage_stats_title),
                            summary = stringResource(R.string.usage_stats_summary),
                            startAction = {
                                EtaPreferenceIcon(icon = Icons.Rounded.BarChart, tint = EtaPreferenceColors.Green)
                            },
                            onClick = { onNavigate(AppRoute.TokenUsage) },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.settings_priority_reset_tour),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Refresh,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = {
                                coroutineScope.launch {
                                    SettingsDataStore.updateSettings { current ->
                                        current.copy(
                                            seenTourPacks = current.seenTourPacks - OnboardingKeys.All,
                                        )
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_priority_reset_tour_done),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                }

                section(SettingsSection.MODELS_CONNECTIONS) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_models_connections))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.settings_priority_provider_title),
                            summary = providerSummary,
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Cloud,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.ModelProviders) },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.settings_priority_current_model),
                            summary = currentModelSummary,
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Hub,
                                    tint = EtaPreferenceColors.Green,
                                )
                            },
                            onClick = {
                                onNavigate(
                                    selectedProvider?.id?.let { AppRoute.ModelProviderDetail(it) }
                                        ?: AppRoute.ModelProviders,
                                )
                            },
                        )

                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                            key = Prefs.Keys.AGENT_THINKING_ENABLED,
                            icon = Icons.Rounded.Psychology,
                            iconTint = EtaPreferenceColors.Blue,
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.route_github_account),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Code,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.GitHubAccount) },
                        )
                    }
                }

                section(SettingsSection.ASSISTANT_VOICE) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_assistant_voice))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.speech_settings_title),
                            summary = stringResource(R.string.settings_priority_voice_summary),
                            startAction = {
                                EtaPreferenceIcon(icon = Icons.Rounded.Mic, tint = EtaPreferenceColors.Blue)
                            },
                            onClick = { onNavigate(AppRoute.SpeechSettings) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.lingscreen_entry_title),
                            startAction = {
                                EtaPreferenceIcon(icon = Icons.Rounded.Smartphone, tint = EtaPreferenceColors.Blue)
                            },
                            onClick = { onNavigate(AppRoute.VirtualScreen) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.weather_settings_title),
                            summary = stringResource(R.string.weather_settings_entry_summary),
                            startAction = {
                                EtaPreferenceIcon(icon = Icons.Rounded.Cloud, tint = EtaPreferenceColors.Blue)
                            },
                            onClick = { onNavigate(AppRoute.WeatherSettings) },
                        )
                    }
                }

                section(SettingsSection.TOOLS_EXTENSIONS) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_tools_extensions))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.settings_tools_list),
                            startAction = { EtaPreferenceIcon(Icons.Rounded.Dashboard, tint = EtaPreferenceColors.Green) },
                            onClick = { onNavigate(AppRoute.Tools) },
                        )

                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                            key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                            icon = Icons.Rounded.Language,
                            iconTint = EtaPreferenceColors.Blue,
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.web_tools_settings_title),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.TravelExplore,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.WebToolsSettings) },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.ui_linux_tool_environment_314d22),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Inventory2,
                                    tint = EtaPreferenceColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.automation_title),
                            startAction = { EtaPreferenceIcon(icon = Icons.Rounded.FilterAlt, tint = EtaPreferenceColors.Blue) },
                            onClick = { onNavigate(AppRoute.Tasks) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.route_subagents),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Hub,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Subagents) },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.route_skills),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.ImportContacts,
                                    tint = EtaPreferenceColors.Green,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Skills) },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.route_mcp_servers),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.AccountTree,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.McpServers) },
                        )

                    }
                }

                section(SettingsSection.PROMPTS_MEMORY) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_prompts_memory))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.ui_memory_b55ff5),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                                    tint = EtaPreferenceColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Memory) },
                        )
                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_auto_compaction),
                            key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                            icon = Icons.Rounded.Layers,
                            iconTint = EtaPreferenceColors.Blue,
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.prompt_studio_title),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Psychology,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.PromptStudio) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.sidebar_characters),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.SportsBar,
                                    tint = EtaPreferenceColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Characters) },
                        )
                    }
                }

                section(SettingsSection.PERMISSIONS_DEVICES) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_permissions_devices))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.ui_floating_window_permissions_076b77),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Layers,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            endActions = {
                                Text(
                                    text = stringResource(
                                        if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = if (overlayGranted) {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    } else {
                                        MiuixTheme.colorScheme.error
                                    },
                                )
                            },
                            onClick = {
                                if (!overlayGranted) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                android.net.Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                }
                            },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.AccessibilityNew,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            endActions = {
                                val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                                Text(
                                    text = stringResource(
                                        if (enabled) R.string.status_enabled else R.string.status_disabled,
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = if (enabled) {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    } else {
                                        MiuixTheme.colorScheme.primary
                                    },
                                )
                            },
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                    )
                                }
                            },
                        )
                        if (AccessibilityProtectionClient.isSupported() && (prefs != null || hasConnectedFramework)) {
                            EtaPreferenceDivider()
                            EtaSwitchPreference(
                                title = stringResource(R.string.ui_enforce_accessibility_55e838),
                                checked = accessibilityProtectionEnabled,
                                onCheckedChange = { enabled ->
                                    if (accessibilityProtectionPending) {
                                        return@EtaSwitchPreference
                                    }
                                    accessibilityProtectionPending = true
                                    AccessibilityProtectionClient.setEnabled(
                                        context = context,
                                        enabled = enabled,
                                    ) { result ->
                                        accessibilityProtectionPending = false
                                        accessibilityProtectionEnabled = result.enabled
                                        accessibilityGranted = isAgentAccessibilityEnabled(context)
                                        val failureMessage = when (result.status) {
                                            AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                                            AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                                                context.getString(R.string.accessibility_protection_unavailable)
                                            AccessibilityProtectionClient.ControlStatus.REJECTED ->
                                                context.getString(R.string.accessibility_protection_rejected)
                                        }
                                        if (failureMessage != null) {
                                            Toast.makeText(
                                                context.applicationContext,
                                                failureMessage,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                                startAction = {
                                    EtaPreferenceIcon(
                                        icon = Icons.Rounded.VerifiedUser,
                                        tint = EtaPreferenceColors.Blue,
                                        enabled = prefs != null && !accessibilityProtectionPending,
                                    )
                                },
                                enabled = prefs != null && !accessibilityProtectionPending,
                            )
                        }

                        // 流体云属于「通知可见性」范畴，跟权限放一起：常驻与降级是同一类开关。
                        EtaPreferenceDivider()
                        FluidPinnedPref()

                        EtaPreferenceDivider()
                        FluidDowngradePref()
                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                            key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                            icon = Icons.Rounded.Smartphone,
                            iconTint = EtaPreferenceColors.Green,
                        )

                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                            key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                            icon = Icons.Rounded.Visibility,
                            iconTint = EtaPreferenceColors.Blue,
                        )

                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                            key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                            icon = Icons.Rounded.GppMaybe,
                            iconTint = EtaPreferenceColors.Orange,
                        )

                        EtaPreferenceDivider()
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                            key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                            icon = Icons.Rounded.Terminal,
                            iconTint = EtaPreferenceColors.Green,
                        )

                    }
                }

                section(SettingsSection.SYSTEM_INTEGRATION) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_system_integration))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.capability_enhancements),
                            startAction = { EtaPreferenceIcon(Icons.Rounded.Security, tint = EtaPreferenceColors.Blue) },
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.SupportAgent,
                                    tint = EtaPreferenceColors.Green,
                                )
                            },
                            onClick = openAssistantSettings,
                        )
                        EtaPreferenceDivider()
                        if (prefs != null || hasConnectedFramework) {
                            EtaPreferenceDivider()
                            EtaDropdownPreference(
                                title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                                items = powerAssistantItems,
                                selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                                onSelectedIndexChange = { index ->
                                    val target = powerAssistantTargets.getOrNull(index)
                                        ?: return@EtaDropdownPreference
                                    val targetPrefs = prefs ?: return@EtaDropdownPreference
                                    if (putStringSync(
                                            prefs = targetPrefs,
                                            key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                            value = target.persistedValue,
                                        )
                                    ) {
                                        powerAssistantTarget = target
                                        enhancementHistory.recordCommittedTarget(target)
                                    } else {
                                        Toast.makeText(
                                            context.applicationContext,
                                            context.getString(R.string.settings_write_failed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                startAction = {
                                    EtaPreferenceIcon(
                                        icon = Icons.Rounded.PowerSettingsNew,
                                        tint = EtaPreferenceColors.Yellow,
                                        enabled = prefs != null,
                                    )
                                },
                                enabled = prefs != null,
                            )

                            EtaPreferenceDivider()
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                                key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                                icon = Icons.Rounded.Settings,
                                iconTint = EtaPreferenceColors.Green,
                            )
                            EtaPreferenceDivider()
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                                key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                                icon = Icons.Rounded.Cloud,
                                iconTint = EtaPreferenceColors.Blue,
                            )
                            EtaPreferenceDivider()
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                                summary = stringResource(R.string.ui_vendor_assistant_require_prefix_summary),
                                key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                                icon = Icons.Rounded.FilterAlt,
                                iconTint = EtaPreferenceColors.Blue,
                            )
                        }
                    }
                }

                section(SettingsSection.DIAGNOSTICS) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_diagnostics))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.settings_self_check_title),
                            summary = "一键体检：授权、自动任务依赖、本机数据",
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    tint = EtaPreferenceColors.Green,
                                )
                            },
                            onClick = { onNavigate(AppRoute.SelfCheck) },
                        )

                        EtaPreferenceDivider()
                        if (io.github.mangi.eta.BuildConfig.BETA_BUILD) {
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.settings_test_privacy_title),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { showTestConsent = true },
                        )
                        }

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.settings_diagnostics_title),
                            summary = if (crashCount <= 0) {
                                "诊断包 · 暂无崩溃记录"
                            } else {
                                "诊断包 · 崩溃记录 $crashCount 条"
                            },
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.BugReport,
                                    tint = EtaPreferenceColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.DiagnosticsCenter) },
                        )
                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.diag_patrol_title),
                            summary = stringResource(R.string.diag_patrol_entry_summary),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.BugReport,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.DiagnosticsPatrol) },
                        )
                    }
                }

                section(SettingsSection.ABOUT_DOWNLOADS) {
                    EtaPreferenceGroupTitle(stringResource(R.string.settings_priority_about_downloads))
                    EtaPreferenceGroup {
                        EtaArrowPreference(
                            title = stringResource(R.string.about_group_title),
                            summary = stringResource(R.string.about_group_summary),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Groups,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { openUrl("$PROJECT_REPO_URL/releases/latest") },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.about_email_title),
                            summary = stringResource(R.string.about_email_summary),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Email,
                                    tint = EtaPreferenceColors.Green,
                                )
                            },
                            onClick = { openUrl("$PROJECT_REPO_URL/releases") },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.ui_about_feedback_title),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.BugReport,
                                    tint = EtaPreferenceColors.Orange,
                                )
                            },
                            onClick = { openUrl("$PROJECT_REPO_URL/issues") },
                        )

                        EtaPreferenceDivider()
                        EtaArrowPreference(
                            title = stringResource(R.string.ui_about_github_star_title),
                            summary = stringResource(R.string.ui_about_github_star_hint),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Code,
                                    tint = EtaPreferenceColors.Blue,
                                )
                            },
                            onClick = { openUrl(PROJECT_REPO_URL) },
                        )

                    }
                }
            }
        }

        if (showTestConsent) {
            io.github.mangi.eta.ui.consent.TestConsentSettingsDialog(
                onDismiss = { showTestConsent = false },
            )
        }


}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * 配置来源由调用方按能力边界传入。Hook 开关仍可能因 LSPosed 未连接而禁用；Agent
 * Runtime 开关始终使用 App 本地配置。
 */
/** 流体云常驻：与灵屏解耦，属于全局流体云行为。 */
@Composable
private fun FluidPinnedPref() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = null)
    val current = settings ?: return
    EtaSwitchPreference(
        title = stringResource(R.string.lingscreen_fluid_pinned),
        summary = stringResource(R.string.lingscreen_fluid_pinned_summary),
        checked = current.overlayFluidPinned,
        onCheckedChange = { value ->
            scope.launch {
                SettingsDataStore.updateSettings { it.copy(overlayFluidPinned = value) }
                runCatching {
                    context.startService(
                        Intent(context, io.github.mangi.eta.agent.runtime.AgentRuntimeService::class.java)
                            .setAction(io.github.mangi.eta.agent.runtime.AgentRuntimeService.ACTION_REFRESH_FLUID)
                    )
                }
            }
        },
        startAction = { EtaPreferenceIcon(Icons.Rounded.Cloud, tint = EtaPreferenceColors.Green) },
    )
}

/**
 * 流体云通知降级：把进度卡、锁屏胶囊这类通知退化成普通通知。
 *
 * 原理是这些通知不再申请 promoted ongoing（[android.app.Notification.Builder.setRequestPromotedOngoing]），
 * 系统不给提升，ColorOS 就不会把它放进流体云，只在通知栏按常驻通知显示；
 * 标题、正文、暂停／停止／插入这些按钮都保留。
 */
@Composable
private fun FluidDowngradePref() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = null)
    val current = settings ?: return
    EtaSwitchPreference(
        title = stringResource(R.string.lingscreen_fluid_downgrade),
        summary = stringResource(R.string.lingscreen_fluid_downgrade_summary),
        checked = current.overlayFluidDowngrade,
        onCheckedChange = { value ->
            // 先同步进程内策略，避免刷新时通知仍按旧值构建。
            io.github.mangi.eta.agent.runtime.FluidNotificationPolicy.set(value)
            scope.launch {
                SettingsDataStore.updateSettings { it.copy(overlayFluidDowngrade = value) }
                runCatching {
                    context.startService(
                        Intent(context, io.github.mangi.eta.agent.runtime.AgentRuntimeService::class.java)
                            .setAction(io.github.mangi.eta.agent.runtime.AgentRuntimeService.ACTION_REFRESH_FLUID)
                    )
                }
            }
        },
        startAction = { EtaPreferenceIcon(Icons.Rounded.Notifications, tint = EtaPreferenceColors.Orange) },
    )
}

@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color,
    /** 与本项互斥的开关：对方开启时本项置灰；本项开启时先把对方关掉。 */
    exclusiveKey: String? = null,
) {
    val writable = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    val exclusiveDefault = exclusiveKey?.let { Prefs.Keys.BOOLEAN_DEFAULTS[it] ?: true } ?: false
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    var exclusiveChecked by remember(prefs, exclusiveKey) {
        mutableStateOf(
            exclusiveKey != null && (prefs?.getBoolean(exclusiveKey, exclusiveDefault) ?: false),
        )
    }
    DisposableEffect(prefs, key, exclusiveKey) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
            if (exclusiveKey != null && changedKey == exclusiveKey) {
                exclusiveChecked = changedPrefs.getBoolean(exclusiveKey, exclusiveDefault)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = writable && !exclusiveChecked
    EtaSwitchPreference(
        title = title,
        summary = if (exclusiveChecked) {
            context.getString(R.string.ui_vendor_assistant_exclusive_summary)
        } else {
            summary
        },
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@EtaSwitchPreference
            // 互斥：开启本项前先关掉对方，避免两边同时接管同一条 query。
            if (value && exclusiveKey != null && !putBooleanSync(targetPrefs, exclusiveKey, false)) {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return@EtaSwitchPreference
            }
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                if (exclusiveKey != null && value) {
                    exclusiveChecked = false
                }
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    Prefs.putBooleanForUi(prefs, key, value)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "Eta"
    }

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}


/** 设置页顶部搜索框：占位提示 + 清除按钮 + 无结果提示。聚焦时渐变描边光晕。 */
@Composable
private fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    emptyResult: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = EtaDesign.Motion.medium(),
        label = "search_focus_glow",
    )
    val glowAlpha = EtaDesign.Brand.GlowAlpha * focusProgress
    val borderBrush = if (focused) {
        etaBrandBrush()
    } else {
        androidx.compose.ui.graphics.SolidColor(
            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(EtaDesign.Radius.MD))
                .border(
                    width = if (focused) 1.5.dp else 1.dp,
                    brush = borderBrush,
                    shape = RoundedCornerShape(EtaDesign.Radius.MD),
                )
                .then(
                    if (focused) {
                        Modifier.drawBehind {
                            drawRoundRect(
                                brush = etaBrandBrush(),
                                topLeft = androidx.compose.ui.geometry.Offset(-3f, -3f),
                                size = androidx.compose.ui.geometry.Size(size.width + 6f, size.height + 6f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f),
                                alpha = glowAlpha,
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = EtaDesign.Space.MD, vertical = EtaDesign.Space.SM + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = if (focused) EtaDesign.Brand.Indigo else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(EtaDesign.Size.IconSm),
            )
            Spacer(Modifier.width(EtaDesign.Space.SM))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_search_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(EtaDesign.Brand.Indigo),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(EtaDesign.Space.SM))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f))
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (emptyResult) {
            Text(
                text = stringResource(R.string.settings_search_empty),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}
