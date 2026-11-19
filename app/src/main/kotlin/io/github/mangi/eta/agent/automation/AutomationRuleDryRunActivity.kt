package io.github.mangi.eta.agent.automation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Separate editor/test surface. Only the explicit Save button uses the task mutation API. */
class AutomationRuleDryRunActivity : Activity() {
    private lateinit var ruleInput: EditText
    private lateinit var eventInput: EditText
    private lateinit var resultView: TextView
    private lateinit var saveButton: Button
    private var task: AgentTaskEntity? = null
    private var lastTested: String? = null
    private var validTest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String) = TextView(this).apply { text = value; setPadding(0, 10, 0, 10); column.addView(this) }
        text(getString(R.string.automation_tools_dryrun_title)).textSize = 22f
        text(getString(R.string.automation_tools_dryrun_summary))
        ruleInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            setText(savedInstanceState?.getString("draft") ?: intent.getStringExtra(EXTRA_RULE) ?: "{\"type\":\"daily\",\"time\":\"09:00\",\"timeZone\":\"Asia/Shanghai\"}")
            column.addView(this)
        }
        text(getString(R.string.automation_tools_dryrun_event_hint))
        eventInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(savedInstanceState?.getString("event").orEmpty())
            hint = "{\"type\":\"nfc_tapped\",\"tagId\":\"00A1\",\"ndefText\":\"home\"}"
            column.addView(this)
        }
        Button(this).apply {
            setText(R.string.automation_tools_dryrun_run)
            setOnClickListener { runDryRun() }
            column.addView(this)
        }
        resultView = text("").apply { setTextIsSelectable(true) }
        saveButton = Button(this).apply {
            setText(R.string.automation_tools_dryrun_save)
            isEnabled = false
            setOnClickListener { saveTestedRule() }
            column.addView(this)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        intent.getStringExtra(EXTRA_TASK_ID)?.let { id ->
            val app = applicationContext
            editorWorker.execute {
                val loaded = runCatching { runBlocking { EtaDatabase.get(app).agentTaskDao().task(id) } }.getOrNull()
                ui { task = loaded; saveButton.isEnabled = loaded != null }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", ruleInput.text.toString())
        outState.putString("event", eventInput.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun runDryRun() {
        val draft = ruleInput.text.toString()
        val eventDraft = eventInput.text.toString()
        lastTested = null
        validTest = false
        val app = applicationContext
        val nextRun = task?.nextRunAt
        editorWorker.execute {
            val report = runCatching {
                val snapshot = AutomationRuleStateSampler.sample(app)
                val result = AutomationRuleDryRun.evaluate(JSONObject(draft), snapshot,
                    eventDraft.takeIf(String::isNotBlank)?.let(::JSONObject), nextRun)
                val sampled = snapshot.values.entries.joinToString("\n") { (name, value) ->
                    "$name: ${value.value ?: "unknown"} · ${value.reason}"
                }
                Triple(result.valid, result.explain(), "sampledAt: ${snapshot.sampledAt}\n$sampled")
            }
            ui {
                report.onSuccess { (valid, explanation, snapshot) ->
                    lastTested = draft
                    validTest = valid
                    resultView.text = "$explanation\n$snapshot"
                }.onFailure { resultView.text = "false · validate: ${it.message ?: "JSON 格式错误"}" }
            }
        }
    }

    private fun saveTestedRule() {
        val current = task ?: return
        val draft = ruleInput.text.toString()
        if (!validTest || lastTested != draft) {
            resultView.setText(R.string.automation_tools_dryrun_test_first)
            return
        }
        val app = applicationContext
        saveButton.isEnabled = false
        editorWorker.execute {
            val saved = runCatching { JSONObject(AgentTaskTools(app).execute("tasks_update", JSONObject()
                .put("taskId", current.id).put("expectedUpdatedAt", current.updatedAt).put("trigger", JSONObject(draft)))) }
            val updated = if (saved.getOrNull()?.optBoolean("ok") == true) {
                runBlocking { EtaDatabase.get(app).agentTaskDao().task(current.id) }
            } else null
            ui {
                saveButton.isEnabled = true
                if (updated != null) { task = updated; resultView.setText(R.string.automation_tools_dryrun_saved) }
                else resultView.text = saved.getOrNull()?.optString("message") ?: saved.exceptionOrNull()?.message.orEmpty()
            }
        }
    }

    private fun ui(action: () -> Unit) { runOnUiThread { if (!isFinishing && !isDestroyed) action() } }

    companion object {
        private const val EXTRA_RULE = "automation.dryrun.rule"
        private const val EXTRA_TASK_ID = "automation.dryrun.task_id"
        private val editorWorker = Executors.newSingleThreadExecutor { Thread(it, "eta-rule-test") }
        internal fun intent(context: Context, task: AgentTaskEntity? = null): Intent =
            Intent(context, AutomationRuleDryRunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                task?.let { putExtra(EXTRA_RULE, it.triggerJson); putExtra(EXTRA_TASK_ID, it.id) }
            }
    }
}
