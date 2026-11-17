package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.agent.quickactions.DailyWeatherCodec
import io.github.mangi.eta.agent.quickactions.WeatherCity
import org.json.JSONObject

/**
 * 设置页「天气预报」入口使用的轻量偏好。
 *
 * 只保存三类数据：是否「询问天气时使用当前定位」、用户显式选择的常用地点、以及最近一次
 * 地点/定位更新的时间戳。地点序列化复用 [DailyWeatherCodec]，与快捷指令的常用城市保持
 * 完全一致的结构，避免两套 JSON 格式互相打架。所有写入都发生在 App 本地 SharedPreferences，
 * 不接触 Hook / LSPosed 配置。
 */
internal data class WeatherPreferencesSnapshot(
    val useCurrentLocation: Boolean,
    val selectedCity: WeatherCity?,
    val lastUpdatedMillis: Long,
)

internal object WeatherPreferences {
    internal const val PREF_NAME = "eta_weather_settings"
    private const val KEY_USE_CURRENT_LOCATION = "use_current_location"
    private const val KEY_SELECTED_CITY = "selected_city"
    private const val KEY_LAST_UPDATED = "last_updated"
    private const val MAX_CITY_JSON_LENGTH = 4096

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun snapshot(context: Context): WeatherPreferencesSnapshot = snapshot(prefs(context))

    internal fun snapshot(prefs: SharedPreferences): WeatherPreferencesSnapshot {
        val rawCity = prefs.getString(KEY_SELECTED_CITY, null)
        val city = rawCity?.takeIf { it.length <= MAX_CITY_JSON_LENGTH }
            ?.let { runCatching { DailyWeatherCodec.city(JSONObject(it)) }.getOrNull() }
        return WeatherPreferencesSnapshot(
            useCurrentLocation = prefs.getBoolean(KEY_USE_CURRENT_LOCATION, false),
            selectedCity = city,
            lastUpdatedMillis = prefs.getLong(KEY_LAST_UPDATED, 0L),
        )
    }

    fun useCurrentLocation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_CURRENT_LOCATION, false)

    fun setUseCurrentLocation(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_CURRENT_LOCATION, enabled).apply()
    }

    fun selectedCity(context: Context): WeatherCity? = snapshot(context).selectedCity

    /** 传 null 表示清除已保存地点（例如切回「仅用当前位置」时）。 */
    fun saveSelectedCity(context: Context, city: WeatherCity?) {
        prefs(context).edit().apply {
            if (city == null) {
                remove(KEY_SELECTED_CITY)
            } else {
                putString(KEY_SELECTED_CITY, DailyWeatherCodec.encodeCity(city).toString())
            }
            apply()
        }
    }

    fun lastUpdatedMillis(context: Context): Long = prefs(context).getLong(KEY_LAST_UPDATED, 0L)

    fun markUpdated(context: Context, atMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_UPDATED, atMillis).apply()
    }
}
