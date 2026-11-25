package io.github.mangi.eta.ui.screens.weather

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.quickactions.ComposedWeatherService
import io.github.mangi.eta.agent.quickactions.DailyCommandResult
import io.github.mangi.eta.agent.quickactions.DailyIntent
import io.github.mangi.eta.agent.quickactions.QuickActionsExecutor
import io.github.mangi.eta.agent.quickactions.WeatherCity
import io.github.mangi.eta.agent.quickactions.WeatherErrors
import io.github.mangi.eta.agent.quickactions.WeatherPeriod
import io.github.mangi.eta.agent.quickactions.WeatherPlaces
import io.github.mangi.eta.data.repository.WeatherPreferences
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSettingsTheme
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.QuickActionResultCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import java.util.Locale

/** 查询与聊天共用服务、地点和缓存，选择地点后直接显示真实预报。 */
@Composable
internal fun WeatherSettingsScreen(onBack: () -> Unit) = EtaSettingsTheme {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val service = remember { ComposedWeatherService() }
    var selected by remember { mutableStateOf(WeatherPreferences.selectedCity(context)) }
    var useLocation by remember { mutableStateOf(WeatherPreferences.useCurrentLocation(context)) }
    var access by remember { mutableStateOf(DeviceLocationProvider.accessState(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(emptyList<WeatherCity>()) }
    var result by remember { mutableStateOf<DailyCommandResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<Job?>(null) }

    fun refresh(city: WeatherCity? = if (useLocation) null else selected, force: Boolean = true) {
        work?.cancel()
        loading = true
        message = if (city == null && useLocation) "正在定位并查询天气…" else "正在查询天气…"
        result = null
        work = scope.launch {
            try {
                val answer = QuickActionsExecutor.execute(context, DailyIntent.Weather(period = WeatherPeriod.THREE_DAYS),
                    selectedCity = city, rememberCity = false, forceRefresh = force)
                result = answer
                message = if (answer is DailyCommandResult.WeatherAnswer) null else answer.summary
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = WeatherErrors.describe(error)
            } finally { loading = false }
        }
    }
    fun choose(city: WeatherCity) {
        WeatherPreferences.saveSelectedCity(context, city)
        WeatherPreferences.setUseCurrentLocation(context, false)
        WeatherPreferences.markUpdated(context)
        useLocation = false
        selected = city
        choices = emptyList()
        refresh(city, force = false)
    }
    fun search() {
        if (query.trim().length !in 2..WeatherPlaces.MAX_QUERY_LENGTH) { message = "请输入 2 至 80 个字符的地点。"; return }
        val name = query.trim()
        work?.cancel()
        loading = true
        choices = emptyList()
        result = null
        message = "正在搜索地点…"
        work = scope.launch {
            try {
                choices = withTimeoutOrNull(8_000) { service.searchCities(name) }
                    ?: throw io.github.mangi.eta.agent.quickactions.WeatherServiceException("WEATHER_TIMEOUT", "地点查询超时，请重试。")
                message = if (choices.isEmpty()) "没有找到该地点，请补充省、市或区县。" else "请核对预报覆盖区域后选择。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = WeatherErrors.describe(error) }
            finally { loading = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = DeviceLocationProvider.accessState(context)
        if (access != DeviceLocationProvider.AccessState.DENIED) refresh(null)
        else message = DeviceLocationProvider.failureMessage("permission_required")
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = DeviceLocationProvider.accessState(context)
                selected = WeatherPreferences.selectedCity(context)
                useLocation = WeatherPreferences.useCurrentLocation(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    MiuixScaffoldPage(title = "天气预报", onBack = onBack) {
        item(key = "mode") {
            EtaPreferenceGroup {
                EtaPreference(title = if (useLocation) "当前位置" else selected?.displayName ?: "先选择常用地点",
                    summary = selected?.takeUnless { useLocation }?.let {
                        if (it.hasCoordinates) String.format(Locale.ROOT, "坐标 %.5f，%.5f", it.latitude, it.longitude)
                        else "行政区 ${it.adcode.orEmpty()} · 按区县提供预报"
                    } ?: "普通查询优先国内天气源，重复查询复用短时缓存。")
                EtaSwitchPreference(title = "询问天气时使用当前位置", checked = useLocation,
                    summary = "使用真实定位匹配地区；首次定位和弱网可能比常用地点耗时更长。",
                    onCheckedChange = { enabled ->
                        work?.cancel(); loading = false; result = null; message = null
                        useLocation = enabled
                        WeatherPreferences.setUseCurrentLocation(context, enabled)
                        if (enabled) {
                            access = DeviceLocationProvider.accessState(context)
                            if (access == DeviceLocationProvider.AccessState.DENIED) permission.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            else refresh(null)
                        }
                    })
                EtaTextButton(text = if (loading) "取消查询" else "刷新天气", modifier = Modifier.fillMaxWidth(),
                    onClick = { if (loading) { work?.cancel(); loading = false; message = "查询已取消" } else refresh() })
            }
        }
        if (useLocation && access in setOf(DeviceLocationProvider.AccessState.DENIED, DeviceLocationProvider.AccessState.DISABLED)) {
            item(key = "location_access") {
                EtaPreferenceGroup {
                    EtaPreference(title = "定位需要设置", summary = DeviceLocationProvider.failureMessage(
                        if (access == DeviceLocationProvider.AccessState.DENIED) "permission_required" else "location_disabled"))
                    EtaTextButton("打开系统设置", onClick = {
                        val intent = if (access == DeviceLocationProvider.AccessState.DISABLED) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }
                    })
                }
            }
        }
        message?.let { text -> item(key = "notice") { Text(text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) } }
        result?.let { answer -> item(key = "forecast") {
            QuickActionResultCard(answer, modifier = Modifier.padding(horizontal = 16.dp),
                onRetry = { refresh() }, onRefresh = { refresh() }, onCityInput = { query = it; search() }, onCitySelected = ::choose)
        } }
        item(key = "search") {
            EtaPreferenceGroup {
                EtaPreference {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("更换常用地点")
                        TextField(query, onValueChange = { query = it.take(80) }, label = "输入城市或区县名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                        EtaTextButton("搜索地点", onClick = ::search, enabled = !loading, modifier = Modifier.fillMaxWidth())
                        Text("乡镇查询可能返回所属区县天气，搜索结果会显示实际覆盖地区。")
                    }
                }
            }
        }
        choices.forEach { city -> item(key = "place-${city.id}-${city.displayName}") {
            EtaPreferenceGroup {
                EtaPreference(title = city.displayName, summary = if (city.hasCoordinates) "${city.locationSource} · ${city.latitude}，${city.longitude}"
                    else "${city.locationSource} · 行政区 ${city.adcode.orEmpty()}", onClick = { choose(city) })
            }
        } }
        item(key = "coordinate_toggle") { EtaPreferenceGroup {
            EtaTextButton(if (manual) "收起坐标输入" else "手动输入精确坐标", onClick = { manual = !manual })
            if (manual) EtaPreference {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(latitude, onValueChange = { latitude = it.take(20) }, label = "纬度（WGS84）", singleLine = true)
                    TextField(longitude, onValueChange = { longitude = it.take(20) }, label = "经度（WGS84）", singleLine = true)
                    EtaTextButton("保存并查询", onClick = {
                        val point = WeatherPlaces.manual(latitude, longitude)
                        if (point == null) message = "坐标范围应为纬度 -90～90，经度 -180～180。" else choose(point)
                    })
                }
            }
        } }
    }
}
