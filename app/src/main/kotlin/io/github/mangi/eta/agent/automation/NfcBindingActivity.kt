package io.github.mangi.eta.agent.automation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Explicit foreground reader. Dispatch extras never cross the execution boundary. */
open class NfcBindingActivity : Activity(), NfcAdapter.ReaderCallback {
    private var adapter: NfcAdapter? = null
    private val resumed = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    private lateinit var notice: TextView
    private lateinit var payloadView: TextView
    private lateinit var trigger: CheckBox
    private lateinit var taskPicker: Spinner
    private var tasks: List<AgentTaskEntity> = emptyList()
    private var scanned: NfcTagPayload? = null
    internal var dispatchPayload: NfcTagPayload? = null
        private set
    protected open val triggerByDefault: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = NfcAdapter.getDefaultAdapter(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gap = (20 * resources.displayMetrics.density).toInt()
            setPadding(gap, gap, gap, gap)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, 10, 0, 10); column.addView(this) }
        fun button(title: Int, action: () -> Unit) = Button(this).apply {
            setText(title); setOnClickListener { action() }; column.addView(this)
        }
        label(getString(R.string.automation_tools_nfc_title)).textSize = 22f
        label(getString(R.string.automation_tools_nfc_scope))
        notice = label("")
        payloadView = label("").apply { setTextIsSelectable(true) }
        trigger = CheckBox(this).apply {
            setText(R.string.automation_tools_nfc_trigger_mode)
            isChecked = triggerByDefault
            column.addView(this)
        }
        button(R.string.automation_tools_nfc_scan) { startReading() }
        button(R.string.automation_tools_nfc_stop) { stopReading() }
        button(R.string.automation_tools_nfc_settings) { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        button(R.string.automation_tools_nfc_copy) {
            scanned?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NFC tagId", it.tagId))
                notice.setText(R.string.automation_tools_nfc_copied)
            }
        }
        label(getString(R.string.automation_tools_nfc_bind_hint))
        taskPicker = Spinner(this).also { column.addView(it) }
        button(R.string.automation_tools_nfc_bind) { bindSelectedTask() }
        setContentView(ScrollView(this).apply { addView(column) })
        inspectDispatch(intent)
        loadTasks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inspectDispatch(intent)
    }

    private fun inspectDispatch(intent: Intent) {
        dispatchPayload = NfcTagPayload.fromIntent(intent)
        // A parcelable and action are forgeable: display only, never publish or bind from extras.
        if (intent.action in NfcTagPayload.dispatchActions) {
            notice.setText(R.string.automation_tools_nfc_dispatch_hint)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.set(true)
        showAvailability()
    }

    override fun onPause() {
        resumed.set(false)
        stopReading()
        super.onPause()
    }

    private fun showAvailability(): Boolean {
        val current = adapter
        when {
            current == null -> notice.setText(R.string.automation_tools_nfc_unsupported)
            !current.isEnabled -> notice.setText(R.string.automation_tools_nfc_disabled)
            else -> return true
        }
        return false
    }

    private fun startReading() {
        if (!resumed.get() || !showAvailability()) return
        armed.set(true)
        runCatching {
            adapter?.enableReaderMode(
                this, this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE,
                null,
            )
        }.onSuccess { notice.setText(R.string.automation_tools_nfc_reading) }
            .onFailure { armed.set(false); notice.setText(R.string.automation_tools_nfc_unavailable) }
    }

    private fun stopReading() {
        armed.set(false)
        runCatching { adapter?.disableReaderMode(this) }
    }

    override fun onTagDiscovered(tag: Tag) {
        if (!resumed.get() || !armed.compareAndSet(true, false)) return
        val shouldTrigger = triggerMode
        // Application-owned serial worker survives Activity pause/destroy until the DB write finishes.
        val app = applicationContext
        NfcOperations.worker.execute {
            val tagId = NfcTagPayload.formatTagId(tag.id)
            if (tagId.isEmpty()) { ui { notice.setText(R.string.automation_tools_nfc_invalid) }; return@execute }
            val ndefText = runCatching {
                Ndef.get(tag)?.let { ndef ->
                    try {
                        ndef.connect()
                        NfcTagPayload.extractNdefText(arrayOf(ndef.ndefMessage ?: return@let ""))
                    } finally { runCatching { ndef.close() } }
                }.orEmpty()
            }.getOrDefault("")
            val payload = NfcTagPayload(tagId, ndefText)
            ui {
                scanned = payload
                payloadView.text = "tagId: ${payload.tagId}\nNDEF: ${payload.ndefText}"
                notice.setText(R.string.automation_tools_nfc_scanned)
            }
            if (shouldTrigger) {
                if (!NfcOperations.debounce.accept(tagId, SystemClock.elapsedRealtime())) {
                    ui { notice.setText(R.string.automation_tools_nfc_debounced) }
                    return@execute
                }
                AgentTaskScheduler.publish(
                    app, NfcTagReceiver.EVENT_TYPE, "${tagId}:${UUID.randomUUID()}",
                    payload.event().put("source", "foreground_reader"),
                ) { queued -> ui {
                    notice.setText(if (queued) R.string.automation_tools_nfc_queued else R.string.automation_tools_nfc_unmatched)
                } }
            }
        }
    }

    // Snapshot the checkbox on the main thread; ReaderCallback runs on the NFC binder thread.
    @Volatile private var triggerMode = false
    override fun onStart() {
        super.onStart()
        triggerMode = trigger.isChecked
        trigger.setOnCheckedChangeListener { _, checked -> triggerMode = checked }
    }

    protected open fun loadTasks() {
        val app = applicationContext
        NfcOperations.worker.execute {
            val loaded = runCatching { runBlocking { EtaDatabase.get(app).agentTaskDao().tasks() } }
            ui {
                loaded.onSuccess { items ->
                    tasks = items
                    taskPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name })
                    if (items.isEmpty()) notice.setText(R.string.automation_tools_nfc_no_tasks)
                }.onFailure { notice.setText(R.string.automation_tools_nfc_unavailable) }
            }
        }
    }

    private fun bindSelectedTask() {
        val payload = scanned ?: run { notice.setText(R.string.automation_tools_nfc_read_first); return }
        val task = tasks.getOrNull(taskPicker.selectedItemPosition) ?: run { notice.setText(R.string.automation_tools_nfc_no_tasks); return }
        val old = runCatching { JSONObject(task.triggerJson) }.getOrElse { JSONObject() }
        val rule = JSONObject().put("type", "nfc_tapped").put("filters", JSONObject().put("tagId", payload.tagId))
        old.optJSONObject("conditions")?.let { rule.put("conditions", it) }
        val app = applicationContext
        NfcOperations.worker.execute {
            val result = JSONObject(AgentTaskTools(app).execute("tasks_update", JSONObject()
                .put("taskId", task.id).put("expectedUpdatedAt", task.updatedAt).put("trigger", rule)))
            ui {
                notice.text = if (result.optBoolean("ok")) getString(R.string.automation_tools_nfc_bound)
                    else result.optString("message", getString(R.string.automation_tools_nfc_unavailable))
                if (result.optBoolean("ok")) loadTasks()
            }
        }
    }

    private fun ui(action: () -> Unit) { runOnUiThread { if (!isFinishing && !isDestroyed) action() } }
}

/** Manifest NFC entry; the user explicitly arms foreground reading before any task executes. */
class NfcTagActivity : NfcBindingActivity() {
    override val triggerByDefault: Boolean = true
}

private object NfcOperations {
    val worker = Executors.newSingleThreadExecutor { Thread(it, "eta-nfc-io") }
    val debounce = NfcScanDebouncer()
}
