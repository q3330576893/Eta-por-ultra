package io.github.mangi.eta.agent.quickactions

import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知栏快捷 tile：一次下拉即触发本地零模型意图。
 *
 * 安全约束（与方向三 · 建议 3.1 一致）：
 * - 每个可配置串必须先经过 [DailyIntentRouter.parse] 严格整串匹配；复合句/否定句返回 null，直接拒绝并回退会话路径，绝不执行。
 * - 再加一层 [isTileSafe] 白名单：需要二次交互或系统 Activity handoff 的意图不进 tile，防误触。
 * - 最多 [MAX_TILES] 个可配置槽位，逐次点击轮转；配置存私有 SharedPreferences。
 * - 锁屏（isLocked）时不执行，只提示解锁后使用。
 */
internal class QuickTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            toast(MSG_LOCKED)
            return
        }
        val intents = configuredIntents(applicationContext)
        if (intents.isEmpty()) {
            toast(MSG_NOT_CONFIGURED)
            refreshTile()
            return
        }
        val index = currentIndex(applicationContext) % intents.size
        val raw = intents[index]
        val intent = resolveIntent(raw)
        if (intent == null) {
            // 严格整串匹配失败（复合句/未授权串）：不执行任何动作，保持会话路径交由用户说出。
            toast(MSG_UNSAFE)
            advance(applicationContext, intents.size)
            refreshTile()
            return
        }
        updateTile(label = raw, subtitle = SUBTITLE_RUNNING, active = true)
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { QuickActionsExecutor.execute(applicationContext, intent) }
                    .getOrNull()?.summary?.take(MAX_SUBTITLE) ?: MSG_EXEC_FAILED
            }
            // scope 为 Main.immediate，到这里已回到主线程，可直接更新 UI 与 tile。
            toast(summary)
            advance(applicationContext, intents.size)
            refreshTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        val intents = configuredIntents(applicationContext)
        val index = if (intents.isEmpty()) 0 else currentIndex(applicationContext) % intents.size
        val raw = intents.getOrNull(index).orEmpty()
        val safe = raw.isNotEmpty() && resolveIntent(raw) != null
        val subtitle = when {
            intents.isEmpty() -> MSG_NOT_CONFIGURED
            !safe -> MSG_UNSAFE
            else -> "$SUBTITLE_READY · ${index + 1}/${intents.size}"
        }
        updateTile(label = raw.ifEmpty { DEFAULT_LABEL }, subtitle = subtitle, active = safe)
    }

    private fun updateTile(label: String, subtitle: String, active: Boolean) {
        qsTile?.let { tile ->
            tile.label = label.take(MAX_LABEL)
            tile.subtitle = subtitle.take(MAX_SUBTITLE)
            tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
            tile.updateTile()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        internal const val MAX_TILES = 4
        private const val PREFS = "eta_quick_tiles"
        private const val KEY_COUNT = "count"
        private const val KEY_INDEX = "index"
        private const val KEY_PREFIX = "intent_"
        private const val MAX_LABEL = 60
        private const val MAX_SUBTITLE = 90
        private const val DEFAULT_LABEL = "快捷指令"

        // UI 文案：先用中文常量，避免与父级 strings.xml 改动冲突；后续可提取见冻结报告。
        private const val MSG_LOCKED = "请先解锁屏幕，再使用快捷指令"
        private const val MSG_NOT_CONFIGURED = "还没有配置快捷指令"
        private const val MSG_UNSAFE = "这不是单一指令，已跳过；请直接说出或输入"
        private const val MSG_EXEC_FAILED = "指令执行失败，请重试"
        private const val SUBTITLE_READY = "点按执行"
        private const val SUBTITLE_RUNNING = "正在执行…"

        /** 默认四条只读、零风险的严格整串意图；均可被 [DailyIntentRouter.parse] 精确识别。 */
        internal val DEFAULT_INTENTS = listOf("几点了", "今天天气", "还有多少电", "查看备忘")

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** 严格整串匹配 + tile 安全白名单；复合句或需要二次交互的串一律返回 null。 */
        internal fun resolveIntent(raw: String): DailyIntent? {
            val value = raw.trim()
            if (value.isEmpty() || value.length > 120) return null
            return DailyIntentRouter.parse(value)?.takeIf(::isTileSafe)
        }

        /**
         * tile 只允许本地、单一、无需二次交互的意图。
         * 需要补问时间（AlarmSetup）、系统 Activity handoff（闹钟/倒计时/导航/打开应用）
         * 与依赖上次结果（CalcFollowUp）的意图都不进 tile，防止一次误触引发非预期动作。
         */
        internal fun isTileSafe(intent: DailyIntent): Boolean = when (intent) {
            DailyIntent.AlarmSetup,
            is DailyIntent.SetAlarm,
            is DailyIntent.SetTimer,
            is DailyIntent.NavigateAction,
            is DailyIntent.OpenAppAction,
            is DailyIntent.CalcFollowUp -> false

            is DailyIntent.Weather -> !intent.chooseLocation
            else -> true
        }

        internal fun configuredIntents(context: Context): List<String> {
            val store = prefs(context)
            val count = store.getInt(KEY_COUNT, 0).coerceIn(0, MAX_TILES)
            if (count == 0) return DEFAULT_INTENTS
            return (0 until count).mapNotNull { store.getString(KEY_PREFIX + it, null) }.distinct()
        }

        /** 供后续设置入口调用；过滤掉未严格匹配的串，最多 [MAX_TILES] 条。 */
        internal fun configure(context: Context, intents: List<String>) {
            val safe = intents.map(String::trim).filter { resolveIntent(it) != null }.distinct().take(MAX_TILES)
            if (safe.isEmpty()) return
            prefs(context).edit().apply {
                putInt(KEY_COUNT, safe.size)
                putInt(KEY_INDEX, 0)
                safe.forEachIndexed { index, value -> putString(KEY_PREFIX + index, value) }
            }.apply()
        }

        internal fun currentIndex(context: Context): Int =
            prefs(context).getInt(KEY_INDEX, 0).coerceAtLeast(0)

        private fun advance(context: Context, size: Int) {
            prefs(context).edit().putInt(KEY_INDEX, (currentIndex(context) + 1) % size).apply()
        }
    }
}
