package io.github.mangi.eta.agent.quickactions

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.screens.weather.WeatherSettingsScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/** Dedicated, explicit settings destination; safe for widget taps without a MainActivity route. */
internal class QuickSurfacesSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val initial = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial)
                AgentAppTheme(appearance = appearance, applyInterfaceScale = true) {
                    var weather by rememberSaveable { mutableStateOf(intent.getBooleanExtra(EXTRA_WEATHER, false)) }
                    if (weather) WeatherSettingsScreen(onBack = { finish() })
                    else {
                        val commands = remember { mutableStateListOf(*QuickTileService.configuredIntents(this@QuickSurfacesSettingsActivity).toTypedArray()) }
                        var message by rememberSaveable { mutableStateOf("") }
                        var addPending by remember { mutableStateOf(false) }
                        MiuixScaffoldPage(title = stringResource(R.string.quick_surface_title), onBack = { finish() }) {
                            item(key = "intro") { EtaPreferenceGroup {
                                EtaPreference(title = stringResource(R.string.quick_surface_order_title), summary = stringResource(R.string.quick_surface_help))
                            } }
                            commands.forEachIndexed { index, command -> item(key = "command-$index") { EtaPreferenceGroup {
                                EtaPreference {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextField(value = command, onValueChange = { commands[index] = it.take(120) },
                                            label = getString(R.string.quick_surface_command, index + 1), singleLine = true,
                                            modifier = Modifier.fillMaxWidth())
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            EtaTextButton(stringResource(R.string.quick_surface_up), enabled = index > 0, onClick = {
                                                val previous = commands[index - 1]; commands[index - 1] = commands[index]; commands[index] = previous
                                            })
                                            EtaTextButton(stringResource(R.string.quick_surface_down), enabled = index < commands.lastIndex, onClick = {
                                                val next = commands[index + 1]; commands[index + 1] = commands[index]; commands[index] = next
                                            })
                                            EtaTextButton(stringResource(R.string.quick_surface_remove), enabled = commands.size > 1, onClick = { commands.removeAt(index) })
                                        }
                                    }
                                }
                            } } }
                            item(key = "actions") { EtaPreferenceGroup {
                                EtaTextButton(stringResource(R.string.quick_surface_add_command), enabled = commands.size < QuickTileService.MAX_TILES,
                                    onClick = { commands.add("") }, modifier = Modifier.fillMaxWidth())
                                EtaTextButton(stringResource(R.string.quick_surface_save), onClick = {
                                    message = getString(if (QuickTileService.configure(this@QuickSurfacesSettingsActivity, commands.toList()))
                                        R.string.quick_surface_saved else R.string.quick_surface_validation)
                                }, modifier = Modifier.fillMaxWidth())
                                EtaTextButton(stringResource(R.string.quick_surface_add_qs), enabled = !addPending, onClick = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        addPending = true
                                        try {
                                            getSystemService(StatusBarManager::class.java).requestAddTileService(
                                                ComponentName(this@QuickSurfacesSettingsActivity, QuickTileService::class.java),
                                                getString(R.string.quick_surface_title), Icon.createWithResource(this@QuickSurfacesSettingsActivity, R.mipmap.ic_launcher),
                                                mainExecutor,
                                            ) { code ->
                                                addPending = false
                                                message = getString(when (code) {
                                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.quick_surface_qs_added
                                                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.quick_surface_qs_added
                                                    else -> R.string.quick_surface_qs_manual
                                                })
                                            }
                                        } catch (_: Exception) { addPending = false; message = getString(R.string.quick_surface_qs_manual) }
                                    } else message = getString(R.string.quick_surface_qs_manual)
                                }, modifier = Modifier.fillMaxWidth())
                                EtaPreference(title = stringResource(R.string.quick_surface_weather_settings), onClick = { weather = true })
                            } }
                            if (message.isNotBlank()) item(key = "message") { Text(message, modifier = Modifier.padding(24.dp)) }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        WeatherWidgetProvider.updateAll(this)
        super.onStop()
    }

    companion object {
        private const val EXTRA_WEATHER = "eta.quick_surfaces.WEATHER"
        /** Main/settings integration: startActivity(QuickSurfacesSettingsActivity.intent(context)). */
        fun intent(context: Context, weather: Boolean = false): Intent =
            Intent(context, QuickSurfacesSettingsActivity::class.java).putExtra(EXTRA_WEATHER, weather)
                .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
