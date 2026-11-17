package io.github.mangi.eta.ui.screens.weather

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TravelExplore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.quickactions.OpenMeteoWeatherService
import io.github.mangi.eta.agent.quickactions.WeatherCity
import io.github.mangi.eta.data.repository.WeatherPreferences
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 设置页新增的「天气预报」入口。
 *
 * 这里只负责偏好：常用地点（精确到乡镇的坐标）、是否「询问天气使用当前位置」、以及手动
 * 刷新。天气的联网与展示仍由快捷指令/主代理负责；本页不弹运行时权限，只在权限缺失时给
 * 明确提示和跳转应用详情的深链，避免没有 Activity requestPermission launcher 时崩溃。
 */
@Composable
internal fun WeatherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val initial = remember { WeatherPreferences.snapshot(context) }
    var useCurrentLocation by remember { mutableStateOf(initial.useCurrentLocation) }
    var selectedCity by remember { mutableStateOf(initial.selectedCity) }
    var lastUpdated by remember { mutableStateOf(initial.lastUpdatedMillis) }
    var accessState by remember { mutableStateOf(DeviceLocationProvider.accessState(context)) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<WeatherCity>>(emptyList()) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var currentLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // 从系统设置返回时刷新权限状态，避免停留在旧的「未授予」提示。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessState = DeviceLocationProvider.accessState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val noneLabel = stringResource(R.string.weather_settings_current_city_none)
    val shortLabel = stringResource(R.string.weather_settings_search_too_short)
    val emptyLabel = stringResource(R.string.weather_settings_search_empty)
    val failedLabel = stringResource(R.string.weather_settings_search_failed)
    val coordinateLabel = stringResource(R.string.weather_settings_coordinates)
    val permissionDenied = accessState == DeviceLocationProvider.AccessState.DENIED

    fun runSearch() {
        val keyword = query.trim()
        if (keyword.length < 2) {
            searchMessage = shortLabel
            return
        }
        searchMessage = null
        searching = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { OpenMeteoWeatherService().searchCities(keyword) }
            }
            searching = false
            outcome.onSuccess { cities ->
                searchResults = cities
                searchMessage = if (cities.isEmpty()) emptyLabel else null
            }.onFailure {
                searchResults = emptyList()
                searchMessage = failedLabel
            }
        }
    }

    fun chooseCity(city: WeatherCity) {
        WeatherPreferences.saveSelectedCity(context, city)
        WeatherPreferences.markUpdated(context)
        selectedCity = city
        lastUpdated = WeatherPreferences.lastUpdatedMillis(context)
        showSearch = false
        searchResults = emptyList()
        searchMessage = null
    }

    fun manualRefresh() {
        scope.launch {
            accessState = DeviceLocationProvider.accessState(context)
            val latest = withContext(Dispatchers.IO) {
                runCatching { DeviceLocationProvider.latest(context) }.getOrNull()
            }
            currentLocation = when (latest) {
                is DeviceLocationProvider.Result.Available -> latest.latitude to latest.longitude
                else -> null
            }
            val now = System.currentTimeMillis()
            WeatherPreferences.markUpdated(context, now)
            lastUpdated = now
            Toast.makeText(context, R.string.weather_settings_refresh_done, Toast.LENGTH_SHORT).show()
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.weather_settings_title),
        onBack = onBack,
    ) {
        item(key = "location_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.weather_settings_group_location))
        }

        item(key = "current_city") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = selectedCity?.displayName ?: noneLabel,
                    summary = selectedCity?.let {
                        stringResource(R.string.weather_settings_coordinates, formatCoordinate(it.latitude), formatCoordinate(it.longitude))
                    },
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Cloud, tint = EtaPreferenceColors.Blue)
                    },
                )
                if (lastUpdated > 0L) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(
                        summary = stringResource(R.string.weather_settings_updated_at, formatUpdatedAt(lastUpdated)),
                    )
                }
                currentLocation?.let { loc ->
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(
                        summary = String.format(Locale.ROOT, coordinateLabel, formatCoordinate(loc.first), formatCoordinate(loc.second)),
                    )
                }
            }
        }

        item(key = "change_city") {
            EtaArrowPreference(
                title = stringResource(R.string.weather_settings_change_city),
                summary = stringResource(R.string.weather_settings_change_city_summary),
                startAction = {
                    EtaPreferenceIcon(icon = Icons.Rounded.TravelExplore, tint = EtaPreferenceColors.Orange)
                },
                onClick = { showSearch = !showSearch },
            )
        }

        if (showSearch) {
            item(key = "city_search") {
                EtaPreferenceGroup {
                    EtaPreference {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(
                                value = query,
                                onValueChange = { query = it.take(40) },
                                label = stringResource(R.string.weather_settings_search_hint),
                                singleLine = true,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            EtaTextButton(
                                text = stringResource(if (searching) R.string.weather_settings_searching else R.string.weather_settings_search),
                                onClick = { runSearch() },
                                enabled = !searching,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            searchMessage?.let { message ->
                item(key = "city_search_message") {
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
            }
            searchResults.forEachIndexed { index, city ->
                item(key = "city_result_$index") {
                    EtaPreference(
                        title = city.displayName,
                        summary = stringResource(
                            R.string.weather_settings_coordinates,
                            formatCoordinate(city.latitude),
                            formatCoordinate(city.longitude),
                        ),
                        onClick = { chooseCity(city) },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                }
            }
        }

        item(key = "preference_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.weather_settings_group_preference))
        }

        item(key = "use_current_location") {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.weather_settings_use_current_location),
                    summary = stringResource(R.string.weather_settings_use_current_location_summary),
                    checked = useCurrentLocation,
                    onCheckedChange = { enabled ->
                        useCurrentLocation = enabled
                        WeatherPreferences.setUseCurrentLocation(context, enabled)
                        WeatherPreferences.markUpdated(context)
                        lastUpdated = WeatherPreferences.lastUpdatedMillis(context)
                    },
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Smartphone, tint = EtaPreferenceColors.Green)
                    },
                )
            }
        }

        if (permissionDenied) {
            item(key = "location_permission") {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = stringResource(R.string.weather_settings_location_permission_title),
                        summary = stringResource(R.string.weather_settings_location_permission_summary),
                    )
                    EtaPreferenceDivider(hasLeading = false)
                    EtaTextButton(
                        text = stringResource(R.string.weather_settings_open_app_details),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "actions_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.weather_settings_group_actions))
        }

        item(key = "manual_refresh") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.weather_settings_refresh),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Refresh, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { manualRefresh() },
                )
            }
        }
    }
}

private fun formatCoordinate(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

private fun formatUpdatedAt(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT))
