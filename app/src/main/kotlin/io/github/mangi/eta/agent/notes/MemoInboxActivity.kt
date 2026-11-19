package io.github.mangi.eta.agent.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.quickactions.NoteStore
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.ShareIntake
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

/** Preview shared text before explicitly saving it locally or sending it to the existing chat draft. */
internal class MemoInboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val initial = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial)
                AgentAppTheme(appearance = appearance, applyInterfaceScale = true) {
                    val scope = rememberCoroutineScope()
                    val generation by NoteStore.changes.collectAsState()
                    var notes by remember { mutableStateOf(emptyList<NoteStore.Note>()) }
                    var text by rememberSaveable { mutableStateOf(intent.getStringExtra(EXTRA_TEXT).orEmpty()) }
                    var shareId by rememberSaveable { mutableStateOf(intent.getStringExtra(EXTRA_SHARE_ID).orEmpty()) }
                    var busy by remember { mutableStateOf(false) }
                    var loading by remember { mutableStateOf(true) }
                    var showCompleted by rememberSaveable { mutableStateOf(true) }
                    var notice by rememberSaveable { mutableStateOf("") }
                    LaunchedEffect(generation) {
                        try { notes = withContext(Dispatchers.IO) { NoteStore.all(applicationContext) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { notice = getString(R.string.memo_load_failed) }
                        finally { loading = false }
                    }
                    fun update(action: () -> Unit, onSuccess: () -> Unit = {}) {
                        if (busy) return
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { action() }
                                notes = withContext(Dispatchers.IO) { NoteStore.all(applicationContext) }
                                onSuccess()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = getString(R.string.memo_save_failed) }
                            finally { busy = false }
                        }
                    }
                    MiuixScaffoldPage(title = stringResource(if (shareId.isBlank()) R.string.memo_title else R.string.memo_preview_title), onBack = { finish() }) {
                        item(key = "memo-compose") { EtaPreferenceGroup {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.memo_private_help))
                                BasicTextField(value = text, onValueChange = { text = it }, enabled = !busy,
                                    textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 240.dp),
                                    decorationBox = { inner ->
                                        if (text.isEmpty()) Text(stringResource(R.string.memo_editor_hint), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        inner()
                                    })
                                Text("${text.length} / ${NoteStore.MAX_NOTE_LENGTH}", style = MiuixTheme.textStyles.footnote1)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    EtaTextButton(stringResource(R.string.memo_save), enabled = !busy && text.isNotBlank() && text.length <= NoteStore.MAX_NOTE_LENGTH,
                                        onClick = {
                                            val captured = text
                                            val key = shareId.ifBlank { "memo-${UUID.randomUUID()}" }
                                            update({ NoteStore.addOnce(applicationContext, captured, System.currentTimeMillis(), key) }) {
                                                notice = getString(R.string.memo_saved)
                                                text = ""
                                                shareId = ""
                                                intent.removeExtra(EXTRA_TEXT)
                                                intent.removeExtra(EXTRA_SHARE_ID)
                                            }
                                        })
                                    if (shareId.isNotBlank()) EtaTextButton(stringResource(R.string.memo_send_chat), enabled = !busy && text.isNotBlank(), onClick = {
                                        ShareIntake.offer(text, emptyList())
                                        startActivity(Intent(this@MemoInboxActivity, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                        finish()
                                    })
                                }
                                if (notice.isNotBlank()) Text(notice, style = MiuixTheme.textStyles.footnote1)
                                if (loading || busy) Text(stringResource(R.string.memo_working), style = MiuixTheme.textStyles.footnote1)
                            }
                        } }
                        item(key = "memo-filter") { EtaPreferenceGroup {
                            EtaTextButton(stringResource(if (showCompleted) R.string.memo_hide_completed else R.string.memo_show_completed),
                                onClick = { showCompleted = !showCompleted })
                        } }
                        val visible = notes.filter { showCompleted || !it.completed }
                            .sortedWith(compareBy<NoteStore.Note> { it.completed }.thenByDescending { it.createdAtMillis })
                        if (!loading && visible.isEmpty()) item(key = "memo-empty") { Text(stringResource(R.string.memo_empty), modifier = Modifier.padding(24.dp)) }
                        visible.forEach { note -> item(key = note.id) { EtaPreferenceGroup {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Checkbox(state = if (note.completed) ToggleableState.On else ToggleableState.Off,
                                        onClick = { update({ NoteStore.setCompleted(applicationContext, note.id, !note.completed) }) })
                                    Text(note.text, modifier = Modifier.weight(1f).padding(start = 10.dp),
                                        textDecoration = if (note.completed) TextDecoration.LineThrough else null)
                                }
                                EtaTextButton(stringResource(R.string.memo_delete), enabled = !busy, onClick = {
                                    android.app.AlertDialog.Builder(this@MemoInboxActivity)
                                        .setMessage(R.string.memo_delete_confirm)
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .setPositiveButton(android.R.string.ok) { _, _ -> update({ NoteStore.delete(applicationContext, note.id) }) }.show()
                                })
                            }
                        } } }
                    }
                }
            }
        }
    }
    companion object {
        private const val EXTRA_TEXT = "eta.memo.PREVIEW"
        private const val EXTRA_SHARE_ID = "eta.memo.SHARE_ID"
        fun intent(context: Context, text: String? = null): Intent = Intent(context, MemoInboxActivity::class.java).apply {
            text?.let { putExtra(EXTRA_TEXT, it); putExtra(EXTRA_SHARE_ID, "share-${UUID.randomUUID()}") }
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
