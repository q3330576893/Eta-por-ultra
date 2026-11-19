package io.github.mangi.eta.agent.quickactions

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.mangi.eta.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One click executes one strict command. Results stay in the expanded QS panel for 20 seconds. */
internal class QuickTileService : TileService() {
    private val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listener?.cancel()
        listener = listeningScope.launch {
            changes.collectLatest {
                refreshTile()
                val remaining = savedResult(applicationContext)?.expiresAt?.minus(System.currentTimeMillis()) ?: 0L
                if (remaining > 0L) {
                    delay(remaining)
                    refreshTile()
                }
            }
        }
    }

    override fun onStopListening() {
        listener?.cancel()
        listener = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext
        if (gate.running() != null) { refreshTile(); return }
        if (isLocked) {
            saveResult(app, "", getString(R.string.quick_surface_locked))
            changed(app)
            refreshTile()
            return
        }
        val commands = configuredIntents(app)
        val index = if (commands.isEmpty()) 0 else currentIndex(app) % commands.size
        val raw = commands.getOrNull(index).orEmpty()
        val request = resolveIntent(raw)
        if (request == null) {
            saveResult(app, raw, getString(R.string.quick_surface_invalid))
            changed(app)
            refreshTile()
            return
        }
        val ticket = gate.begin(raw, commands, index) ?: return
        clearSavedResult(app)
        changed(app)
        refreshTile()
        // Process-owned work survives TileService unbinding; only terminal results reach disk.
        executionScope.launch {
            try {
                val answer = withTimeout(12_000) {
                    withContext(Dispatchers.IO) { QuickActionsExecutor.execute(app, request) }
                }
                complete(app, ticket, resultTileLabel(raw, answer))
            } catch (_: TimeoutCancellationException) {
                complete(app, ticket, app.getString(R.string.quick_surface_failed))
            } catch (cancelled: CancellationException) {
                // A cancellation is never converted into a successful command or index advance.
                gate.finish(ticket)
                changed(app)
                throw cancelled
            } catch (_: Exception) {
                complete(app, ticket, app.getString(R.string.quick_surface_failed))
            }
        }
    }

    private fun refreshTile() {
        val app = applicationContext
        val commands = configuredIntents(app)
        val index = if (commands.isEmpty()) 0 else currentIndex(app) % commands.size
        val next = commands.getOrNull(index).orEmpty()
        val running = gate.running()
        val saved = savedResult(app)
        val label = when {
            running != null -> getString(R.string.quick_surface_running)
            saved != null -> saved.result
            else -> next.ifEmpty { getString(R.string.quick_surface_title) }
        }
        val nextCommand = if (running != null && commands == running.commands) {
            commands.getOrNull((running.index + 1) % commands.size).orEmpty()
        } else next
        val subtitle = when {
            nextCommand.isEmpty() -> getString(R.string.quick_surface_empty)
            resolveIntent(nextCommand) == null -> getString(R.string.quick_surface_invalid)
            else -> getString(R.string.quick_surface_next, nextCommand)
        }
        qsTile?.let {
            it.label = label.take(MAX_LABEL)
            it.subtitle = subtitle.take(MAX_SUBTITLE)
            it.state = if (running != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onDestroy() {
        listeningScope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val MAX_TILES = 4
        internal const val MAX_LABEL = 60
        internal const val MAX_SUBTITLE = 90
        internal const val MAX_RESULT_CHARS = 400
        internal const val RESULT_HOLD_MILLIS = 20_000L
        private const val PREFS = "eta_quick_tiles"
        private const val KEY_COUNT = "count"
        private const val KEY_INDEX = "index"
        private const val KEY_PREFIX = "intent_"
        private const val KEY_RESULT_COMMAND = "result_command"
        private const val KEY_RESULT_TEXT = "result_text"
        private const val KEY_RESULT_EXPIRES_AT = "result_expires_at"
        private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        internal val gate = QuickTileExecutionGate()
        private val changes = MutableStateFlow(0L)
        internal val DEFAULT_INTENTS = listOf("几点了", "今天天气", "还有多少电", "查看备忘")

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        internal fun resolveIntent(raw: String): DailyIntent? = raw.trim()
            .takeIf { it.isNotEmpty() && it.length <= 120 }
            ?.let(DailyIntentRouter::parse)?.takeIf(::isTileSafe)

        /** Positive allowlist: any future DailyIntent defaults to denied, including Activity handoffs. */
        internal fun isTileSafe(intent: DailyIntent): Boolean = when (intent) {
            DailyIntent.TimeNow, DailyIntent.DateToday, DailyIntent.TodayAgenda,
            DailyIntent.BatteryStatus, DailyIntent.StorageStatus, DailyIntent.ViewNotes,
            is DailyIntent.Calculate, is DailyIntent.FlashlightAction,
            is DailyIntent.SetVolumeAction, is DailyIntent.AdjustVolumeAction,
            is DailyIntent.SetRingerAction, is DailyIntent.MediaPlaybackAction,
            is DailyIntent.DndAction, is DailyIntent.NoteAction -> true
            is DailyIntent.Weather -> !intent.chooseLocation
            else -> false
        }

        internal fun configuredIntents(context: Context): List<String> {
            val store = prefs(context)
            if (!store.contains(KEY_COUNT)) return DEFAULT_INTENTS
            return (0 until store.getInt(KEY_COUNT, 0).coerceIn(0, MAX_TILES))
                .mapNotNull { store.getString(KEY_PREFIX + it, null) }.distinct()
        }

        /** All-or-nothing validation keeps order and never silently drops a malformed command. */
        internal fun configure(context: Context, intents: List<String>): Boolean {
            val clean = intents.map(String::trim)
            if (clean.size !in 1..MAX_TILES || clean.distinct().size != clean.size || clean.any { resolveIntent(it) == null }) return false
            synchronized(gate) {
                gate.invalidate()
                prefs(context).edit().apply {
                    putInt(KEY_COUNT, clean.size)
                    putInt(KEY_INDEX, 0)
                    repeat(MAX_TILES) { remove(KEY_PREFIX + it) }
                    clean.forEachIndexed { index, command -> putString(KEY_PREFIX + index, command) }
                    remove(KEY_RESULT_COMMAND); remove(KEY_RESULT_TEXT); remove(KEY_RESULT_EXPIRES_AT)
                }.apply()
            }
            changed(context)
            return true
        }

        private fun complete(context: Context, ticket: QuickTileExecutionGate.Ticket, result: String) {
            synchronized(gate) {
                if (gate.isCurrent(ticket) && configuredIntents(context) == ticket.commands) {
                    prefs(context).edit().putInt(KEY_INDEX, (ticket.index + 1) % ticket.commands.size).apply()
                    saveResult(context, ticket.command, result)
                }
                gate.finish(ticket)
            }
            changed(context)
            WeatherWidgetProvider.updateAll(context)
        }

        internal fun changed(context: Context) {
            changes.value += 1
            TileService.requestListeningState(context, ComponentName(context, QuickTileService::class.java))
        }

        internal data class SavedResult(val command: String, val result: String, val expiresAt: Long)

        internal fun saveResult(context: Context, command: String, result: String, now: Long = System.currentTimeMillis()) {
            prefs(context).edit().putString(KEY_RESULT_COMMAND, command.take(120))
                .putString(KEY_RESULT_TEXT, normalize(result).take(MAX_RESULT_CHARS))
                .putLong(KEY_RESULT_EXPIRES_AT, now + RESULT_HOLD_MILLIS).apply()
        }

        internal fun savedResult(context: Context, now: Long = System.currentTimeMillis()): SavedResult? {
            val store = prefs(context)
            val command = store.getString(KEY_RESULT_COMMAND, null) ?: return null
            val result = store.getString(KEY_RESULT_TEXT, null) ?: return null
            val expiresAt = store.getLong(KEY_RESULT_EXPIRES_AT, 0)
            // Discard legacy half-written running markers and implausible timestamps after clock changes.
            return if (expiresAt > now && expiresAt <= now + RESULT_HOLD_MILLIS && result.isNotBlank() &&
                result !in setOf("正在查询…", "正在执行…")) SavedResult(command, result, expiresAt) else null
        }

        internal fun clearSavedResult(context: Context) {
            prefs(context).edit().remove(KEY_RESULT_COMMAND).remove(KEY_RESULT_TEXT).remove(KEY_RESULT_EXPIRES_AT).apply()
        }

        internal fun resultTileLabel(command: String, result: DailyCommandResult): String {
            val value = when (result.request) {
                DailyIntent.TimeNow -> result.fields.firstOrNull { it.label == "时间" }?.value?.take(5)?.let { "时间 $it" }
                DailyIntent.BatteryStatus -> result.fields.firstOrNull { it.label == "电量" }?.value?.let { "电量 $it" }
                else -> null
            }
            return normalize(value ?: result.summary).take(MAX_LABEL)
        }

        internal fun resultTileLabel(command: String, result: String): String = normalize(result).take(MAX_LABEL)
        private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
        internal fun currentIndex(context: Context): Int = prefs(context).getInt(KEY_INDEX, 0).coerceAtLeast(0)
    }
}

/** Process-wide identity guard; configuration invalidation suppresses late callbacks but keeps work exclusive. */
internal class QuickTileExecutionGate {
    internal data class Ticket(val id: Long, val generation: Long, val command: String, val commands: List<String>, val index: Int)
    private var sequence = 0L
    private var generation = 0L
    private var active: Ticket? = null
    @Synchronized fun begin(command: String, commands: List<String>, index: Int): Ticket? {
        if (active != null) return null
        return Ticket(++sequence, generation, command, commands.toList(), index).also { active = it }
    }
    @Synchronized fun running(): Ticket? = active?.takeIf { it.generation == generation }
    @Synchronized fun isCurrent(ticket: Ticket): Boolean = active == ticket && ticket.generation == generation
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun finish(ticket: Ticket) { if (active == ticket) active = null }
}
