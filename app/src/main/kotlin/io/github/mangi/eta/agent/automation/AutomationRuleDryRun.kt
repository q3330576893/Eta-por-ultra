package io.github.mangi.eta.agent.automation

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONObject

internal enum class DryRunTruth { TRUE, FALSE, UNKNOWN }
internal data class DryRunValue(val value: Any?, val reason: String)
internal data class DryRunSnapshot(val sampledAt: Long, val values: Map<String, DryRunValue>) {
    fun boolean(name: String): Boolean? = values[name]?.value as? Boolean
}
internal data class DryRunCheck(val name: String, val truth: DryRunTruth, val reason: String)
internal data class DryRunResult(val valid: Boolean, val triggered: DryRunTruth, val checks: List<DryRunCheck>) {
    fun explain(): String = buildString {
        append("triggered: ").append(triggered.name.lowercase()).append('\n')
        checks.forEach { append(it.truth.name.lowercase()).append(" · ").append(it.name).append(": ").append(it.reason).append('\n') }
    }
}

/** Reads state only. A denied/unavailable observation remains unknown instead of becoming false. */
internal object AutomationRuleStateSampler {
    fun sample(context: Context, now: Long = System.currentTimeMillis()): DryRunSnapshot {
        val values = linkedMapOf<String, DryRunValue>()
        fun observe(name: String, block: () -> Any?) {
            values[name] = try {
                val value = block()
                DryRunValue(value, if (value == null) "状态服务未返回数据或授权缺失" else "本次当前状态采样")
            } catch (_: SecurityException) { DryRunValue(null, "权限缺失，状态未知") }
              catch (_: Exception) { DryRunValue(null, "状态服务暂不可用") }
        }
        observe("charging") {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery ->
                if (battery.hasExtra(BatteryManager.EXTRA_PLUGGED)) battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 else null
            }
        }
        observe("unlocked") { context.getSystemService(KeyguardManager::class.java)?.let { !it.isDeviceLocked } }
        observe("interactive") { context.getSystemService(PowerManager::class.java)?.isInteractive }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED) {
            observe("networkConnected") { context.getSystemService(ConnectivityManager::class.java)?.let { it.activeNetwork != null } }
            observe("transport") {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                manager?.activeNetwork?.let(manager::getNetworkCapabilities)?.let { caps ->
                    when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        else -> null
                    }
                }
            }
        } else {
            values["networkConnected"] = DryRunValue(null, "缺少 ACCESS_NETWORK_STATE 权限")
            values["transport"] = DryRunValue(null, "缺少 ACCESS_NETWORK_STATE 权限")
        }
        observe("currentApp") { AgentAccessibilityService.current()?.currentPackageName()?.takeIf { it.isNotBlank() } }
        observe("notificationAccess") { AgentNotificationHistoryService.isEnabled(context) }
        observe("bluetoothAccess") { context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED }
        values["time"] = DryRunValue(Instant.ofEpochMilli(now).toString(), "设备当前时钟")
        return DryRunSnapshot(now, values)
    }
}

/** Pure evaluator: deliberately has no scheduler, task publisher, runtime or model dependency. */
internal object AutomationRuleDryRun {
    fun evaluate(raw: JSONObject, snapshot: DryRunSnapshot, observedEvent: JSONObject? = null, nextRunAt: Long? = null): DryRunResult {
        val rule = try { AgentTaskRules.validate(raw) } catch (error: Exception) {
            return DryRunResult(false, DryRunTruth.FALSE, listOf(DryRunCheck("validate", DryRunTruth.FALSE, error.message ?: "规则格式错误或尚未支持")))
        }
        val checks = mutableListOf(DryRunCheck("validate", DryRunTruth.TRUE, "使用现有 AgentTaskRules.validate 校验"))
        val type = rule.getString("type")
        var event: JSONObject? = observedEvent?.let { JSONObject(it.toString()) }
        fun predicate(name: String, expected: Boolean) {
            val value = snapshot.boolean(name)
            checks += DryRunCheck("trigger.$type", truth(value?.let { it == expected }),
                if (value == null) snapshot.values[name]?.reason ?: "当前状态未知" else "$name=$value，要求 $expected；仅评估当前快照，未观察事件边沿")
            if (value != null) event = JSONObject().put("type", type)
        }
        if (event == null) {
            when (type) {
                "charging_connected" -> predicate("charging", true)
                "charging_disconnected" -> predicate("charging", false)
                "device_unlocked" -> predicate("unlocked", true)
                "screen_on" -> predicate("interactive", true)
                "screen_off" -> predicate("interactive", false)
                "network_connected", "network_disconnected" -> {
                    predicate("networkConnected", type == "network_connected")
                    snapshot.values["transport"]?.value?.let { event?.put("transport", it) }
                }
                "app_foreground" -> {
                    val current = snapshot.values["currentApp"]
                    if (current?.value is String) event = JSONObject().put("type", type).put("packageName", current.value)
                    else checks += DryRunCheck("trigger.currentApp", DryRunTruth.UNKNOWN, current?.reason ?: "无障碍服务未连接，前台应用未知")
                }
                "once", "daily", "interval" -> {
                    val due = when (type) {
                        "once" -> OffsetDateTime.parse(rule.getString("at")).toInstant().toEpochMilli().let { snapshot.sampledAt >= it }
                        "interval" -> nextRunAt?.let { snapshot.sampledAt >= it }
                        else -> {
                            val local = Instant.ofEpochMilli(snapshot.sampledAt).atZone(ZoneId.of(rule.getString("timeZone")))
                            val days = rule.optJSONArray("weekdays")
                            local.toLocalTime().withSecond(0).withNano(0) == LocalTime.parse(rule.getString("time")) &&
                                (days == null || (0 until days.length()).any { days.getInt(it) == local.dayOfWeek.value })
                        }
                    }
                    checks += DryRunCheck("trigger.time", truth(due), if (due == null) "interval 尚无已保存 nextRunAt，首次锚点未知" else "按采样时间与规则/已保存调度点比较；执行仍受系统调度和任务次数限制")
                    event = JSONObject().put("type", type)
                }
                else -> checks += DryRunCheck("trigger.$type", DryRunTruth.UNKNOWN, when (type) {
                    "nfc_tapped" -> "需要前台硬件扫描；试跑页面不会读取标签或发布 NFC 事件，可输入模拟事件检查过滤条件"
                    "notification_posted", "notification_removed" -> if (snapshot.boolean("notificationAccess") == false) "通知读取授权缺失；通知事件未知" else "当前快照没有通知到达/移除事件"
                    "bluetooth_connected", "bluetooth_disconnected" -> if (snapshot.boolean("bluetoothAccess") == false) "蓝牙连接权限缺失；蓝牙事件未知" else "当前快照没有蓝牙连接事件"
                    else -> "此触发器已支持；当前快照缺少相应事件，可输入模拟事件只读检查"
                })
            }
        } else checks += DryRunCheck("event", DryRunTruth.TRUE, "使用显式输入的模拟事件；事件只参与本次计算")

        if (event != null) {
            val candidate = requireNotNull(event)
            val eventType = candidate.optString("type")
            val matches = AgentTaskRules.matches(rule, eventType, candidate)
            val filters = rule.optJSONObject("filters")
            val missingFilters = filters?.keys()?.asSequence()?.filter { name ->
                !candidate.has(when (name) { "titleContains" -> "title"; "textContains" -> "text"; else -> name })
            }?.toSet().orEmpty()
            val knownRule = JSONObject(rule.toString())
            val knownFilters = JSONObject(filters?.toString() ?: "{}")
            missingFilters.forEach { knownFilters.remove(it) }
            knownRule.put("filters", knownFilters)
            val knownMismatch = eventType != type || (missingFilters.isEmpty() && !matches) ||
                (type != "nfc_tapped" && !AgentTaskRules.matches(knownRule, eventType, candidate))
            val matchTruth = when { knownMismatch -> DryRunTruth.FALSE; missingFilters.isNotEmpty() -> DryRunTruth.UNKNOWN; else -> truth(matches) }
            checks += DryRunCheck("matches", matchTruth, when (matchTruth) {
                DryRunTruth.TRUE -> "事件类型与全部过滤条件匹配"
                DryRunTruth.FALSE -> "事件类型或已知过滤条件不匹配（AND）"
                DryRunTruth.UNKNOWN -> "样本缺少过滤字段：${missingFilters.joinToString()}"
            })
            filters?.keys()?.forEach { name ->
                val single = JSONObject(rule.toString())
                val selected = JSONObject().put(name, filters.get(name))
                // NFC identity remains mandatory when explaining any additional filter.
                if (type == "nfc_tapped") selected.put("tagId", filters.getString("tagId"))
                single.put("filters", selected)
                val fieldKnown = candidate.has(when (name) { "titleContains" -> "title"; "textContains" -> "text"; else -> name }) &&
                    (type != "nfc_tapped" || candidate.has("tagId"))
                val result = if (fieldKnown) truth(AgentTaskRules.matches(single, eventType, candidate)) else DryRunTruth.UNKNOWN
                checks += DryRunCheck("filters.$name", result, if (fieldKnown) "要求 ${filters.get(name)}；使用现有 matches 比较" else "样本事件缺少 $name 字段")
            }
        }
        val states = snapshot.values.mapNotNull { (name, value) -> (value.value as? Boolean)?.let { name to it } }.toMap()
        val conditions = rule.optJSONObject("conditions")
        conditions?.keys()?.forEach { name ->
            val expected = conditions.getBoolean(name)
            val actual = snapshot.boolean(name)
            val one = JSONObject(rule.toString()).put("conditions", JSONObject().put(name, expected))
            checks += DryRunCheck("conditions.$name", if (actual == null) DryRunTruth.UNKNOWN else truth(AgentTaskRules.conditionsMatch(one, states)),
                if (actual == null) snapshot.values[name]?.reason ?: "权限缺失或状态未知" else "当前 $actual，要求 $expected（AND）")
        }
        if (conditions != null && conditions.keys().asSequence().all { states.containsKey(it) }) {
            checks += DryRunCheck("conditionsMatch", truth(AgentTaskRules.conditionsMatch(rule, states)), "使用现有 conditionsMatch 对全部条件执行 AND")
        }
        val verdict = when {
            checks.any { it.truth == DryRunTruth.FALSE } -> DryRunTruth.FALSE
            checks.any { it.truth == DryRunTruth.UNKNOWN } -> DryRunTruth.UNKNOWN
            else -> DryRunTruth.TRUE
        }
        return DryRunResult(true, verdict, checks)
    }

    private fun truth(value: Boolean?): DryRunTruth = when (value) { true -> DryRunTruth.TRUE; false -> DryRunTruth.FALSE; null -> DryRunTruth.UNKNOWN }
}
