package io.github.mangi.eta.agent.quickactions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.screens.weather.WeatherSettingsScreen
import kotlinx.coroutines.launch

/** Weather widgets keep a dedicated settings destination independent of retired command tiles. */
internal class WeatherWidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val initial = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial)
                AgentAppTheme(appearance = appearance, applyInterfaceScale = true) {
                    WeatherSettingsScreen(onBack = { finish() })
                }
            }
        }
    }

    override fun onStop() {
        WeatherWidgetProvider.updateAll(this)
        super.onStop()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, WeatherWidgetSettingsActivity::class.java).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
