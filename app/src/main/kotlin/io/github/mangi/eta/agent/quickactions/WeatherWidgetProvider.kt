package io.github.mangi.eta.agent.quickactions

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.WeatherPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Passive entry points read disk only. Only the explicit immutable refresh PendingIntent runs weather. */
internal class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        observe(context)
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        observe(context)
        if (intent.action != ACTION_REFRESH) return
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        if (manager.getAppWidgetIds(ComponentName(app, WeatherWidgetProvider::class.java)).isEmpty()) return
        if (!refreshLock.tryLock()) return
        val pending = goAsync()
        scope.launch {
            try {
                updateAll(app, app.getString(R.string.weather_widget_refreshing))
                val answer = withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) {
                    refresh(app) { QuickActionsExecutor.execute(app, DailyIntent.Weather(), rememberCity = false, forceRefresh = true) }
                }
                updateAll(app, when (answer) {
                    is DailyCommandResult.WeatherAnswer -> null
                    null -> app.getString(R.string.weather_widget_timeout)
                    else -> answer.summary.take(120)
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateAll(app, app.getString(R.string.weather_widget_failed))
            } finally {
                refreshLock.unlock()
                pending.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_REFRESH = "io.github.mangi.eta.action.WEATHER_WIDGET_REFRESH"
        internal const val REFRESH_TIMEOUT_MILLIS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val refreshLock = Mutex()
        private var observed = false
        private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        private var observedPreferences: List<SharedPreferences> = emptyList()

        /** Register once per process, called by real widget/Activity/tile entry points. */
        @Synchronized fun observe(context: Context) {
            if (observed) return
            val app = context.applicationContext
            listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in setOf("weather_cache", "selected_city", "use_current_location")) updateAll(app)
            }
            observedPreferences = listOf("eta_daily_commands", WeatherPreferences.PREF_NAME)
                .map { app.getSharedPreferences(it, Context.MODE_PRIVATE) }
            observedPreferences.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
            observed = true
        }

        /** Safe cache-change hook for repository callers; performs zero weather/location requests. */
        fun updateAll(context: Context, notice: String? = null) {
            observe(context)
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, WeatherWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val repository = DailyCommandRepository(app)
            val preferences = WeatherPreferences.snapshot(app)
            val model = cachedModel(preferences.useCurrentLocation, repository.preferredCity(), repository.lastCachedWeather(), Instant.now())
            ids.forEach { id ->
                val views = RemoteViews(app.packageName, R.layout.widget_weather)
                views.setTextViewText(R.id.weather_widget_place, model.place ?: app.getString(R.string.weather_widget_choose_place))
                views.setTextViewText(R.id.weather_widget_forecast, model.forecast ?: app.getString(R.string.weather_widget_empty))
                val updated = model.updatedAt?.let {
                    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(it)
                }
                views.setTextViewText(R.id.weather_widget_updated, when {
                    notice != null -> notice
                    updated == null -> app.getString(R.string.weather_widget_cached_only)
                    model.stale -> app.getString(R.string.weather_widget_stale, updated)
                    else -> app.getString(R.string.weather_widget_updated, updated)
                })
                val settings = PendingIntent.getActivity(app, id, QuickSurfacesSettingsActivity.intent(app, weather = true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val refresh = PendingIntent.getBroadcast(app, id,
                    Intent(app, WeatherWidgetProvider::class.java).setAction(ACTION_REFRESH).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.weather_widget_card, settings)
                views.setOnClickPendingIntent(R.id.weather_widget_refresh, refresh)
                manager.updateAppWidget(id, views)
            }
        }

        internal data class CachedModel(val place: String?, val forecast: String?, val updatedAt: Instant?, val stale: Boolean)

        /** Pure presentation logic; yesterday's rows are never presented as today's forecast. */
        internal fun cachedModel(useLocation: Boolean, selected: WeatherCity?, snapshot: DailyWeatherSnapshot?, now: Instant): CachedModel {
            val usable = snapshot?.takeIf {
                if (useLocation) it.city.locationSource == "当前定位" && it.city.hasCoordinates
                else selected != null && DailyCommandRepository.sameCity(it.city, selected)
            }
            val place = if (useLocation) usable?.city?.displayName else selected?.displayName
            if (usable == null) return CachedModel(place, null, null, true)
            val zone = runCatching { ZoneId.of(usable.city.timezone) }.getOrDefault(ZoneId.systemDefault())
            val today = now.atZone(zone).toLocalDate()
            val day = usable.days.firstOrNull { it.date == today }
            val temperatures = day?.let { listOfNotNull(it.minimumCelsius?.takeIf(Double::isFinite)?.roundToInt()?.let { n -> "$n°" },
                it.maximumCelsius?.takeIf(Double::isFinite)?.roundToInt()?.let { n -> "$n°" }).joinToString("–") }.orEmpty()
            val forecast = day?.let { listOf(WeatherBriefing.describe(it), temperatures).filter(String::isNotBlank).joinToString("  ") }
            return CachedModel(place, forecast, usable.fetchedAt, day == null || !DailyCommandRepository.isFresh(usable, now))
        }

        /** Injected executor makes the explicit network boundary testable. Missing place returns locally. */
        internal suspend fun refresh(context: Context, execute: suspend () -> DailyCommandResult): DailyCommandResult {
            if (!WeatherPreferences.useCurrentLocation(context) && DailyCommandRepository(context).preferredCity() == null) {
                return DailyCommandResult.NeedsCity(DailyIntent.Weather(), Instant.now())
            }
            return execute()
        }
    }
}
