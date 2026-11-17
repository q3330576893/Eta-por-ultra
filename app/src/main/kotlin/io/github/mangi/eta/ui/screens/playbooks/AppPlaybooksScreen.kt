package io.github.mangi.eta.ui.screens.playbooks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.planning.AppPlaybook
import io.github.mangi.eta.agent.planning.AppPlaybookStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Standalone management page. Parent owns its Settings/AppRoute navigation entry. */
@Composable
internal fun AppPlaybooksScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember { AppPlaybookStore.forContext(context) }
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<AppPlaybook>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AppPlaybook?>(null) }
    val errorLabel = stringResource(R.string.app_playbooks_error)
    fun load() { revision++ }
    LaunchedEffect(revision, editing) {
        if (!editing) try {
            cards = withContext(Dispatchers.IO) { store.list() }
        } catch (_: Exception) { notice = errorLabel }
    }
    val importedLabel = stringResource(R.string.app_playbooks_imported)
    val exportedLabel = stringResource(R.string.app_playbooks_exported)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(AppPlaybookStore.MAX_USER_IMPORT_BYTES + 1) }
                        ?: error("导入文件读取失败")
                    require(bytes.size <= AppPlaybookStore.MAX_USER_IMPORT_BYTES)
                    val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                    store.importFromUser(raw).size
                }
                notice = importedLabel.replace("%1\$d", imported.toString())
                load()
            } catch (_: Exception) { notice = errorLabel }
            finally { busy = false }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val content = store.exportJson().toByteArray(Charsets.UTF_8)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content) }
                        ?: error("导出文件写入失败")
                }
                notice = exportedLabel
            } catch (_: Exception) { notice = errorLabel }
            finally { busy = false }
        }
    }
    if (editing) {
        AppPlaybookEditor(store, selected, onBack = { editing = false }, onChanged = { editing = false; load() })
        return
    }
    MiuixScaffoldPage(title = stringResource(R.string.app_playbooks_title), onBack = onBack) {
        item(key = "playbooks-notice") {
            Text(
                text = stringResource(R.string.app_playbooks_reference_notice),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        item(key = "playbooks-actions") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaTextButton(text = stringResource(R.string.app_playbooks_new), enabled = !busy, onClick = { selected = null; editing = true }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EtaTextButton(text = stringResource(R.string.app_playbooks_import), enabled = !busy, onClick = { importer.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.weight(1f))
                    EtaTextButton(text = stringResource(R.string.app_playbooks_export), enabled = !busy && cards.isNotEmpty(), onClick = { exporter.launch("eta-app-playbooks.json") }, modifier = Modifier.weight(1f))
                }
                EtaTextButton(text = stringResource(R.string.app_playbooks_retry), enabled = !busy, onClick = { load() })
            }
        }
        notice?.let { message -> item(key = "playbooks-message") { Text(message, modifier = Modifier.padding(24.dp)) } }
        if (cards.isEmpty()) item(key = "playbooks-empty") {
            Text(stringResource(R.string.app_playbooks_empty), modifier = Modifier.padding(28.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        items(cards, key = { it.packageName + ":" + it.id }) { card ->
            val status = stringResource(if (card.verified) R.string.app_playbooks_verified_label else R.string.app_playbooks_unverified_label)
            val disabled = if (!card.enabled) " · " + stringResource(R.string.app_playbooks_disabled_label) else ""
            val version = stringResource(
                R.string.app_playbooks_version_label,
                card.appVersionName ?: stringResource(R.string.app_playbooks_version_any),
                card.appVersionCode?.toString() ?: stringResource(R.string.app_playbooks_version_any),
            )
            val effect = when {
                card.lastSuccessAt == null && card.lastFailureAt == null -> stringResource(R.string.app_playbooks_effect_unknown)
                (card.lastSuccessAt ?: Long.MIN_VALUE) >= (card.lastFailureAt ?: Long.MIN_VALUE) -> stringResource(R.string.app_playbooks_effect_success)
                else -> stringResource(R.string.app_playbooks_effect_failure)
            }
            val source = card.sourceRunId?.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.app_playbooks_source_run, it.take(16))
            } ?: stringResource(R.string.app_playbooks_source_none)
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = card.title,
                    summary = card.packageName + " · " + status + disabled + "\n" +
                        version + " · " + stringResource(R.string.app_playbooks_use_count, card.useCount) + " · " + effect +
                        "\n" + source,
                    onClick = { selected = card; editing = true },
                )
            }
        }
    }
}

@Composable
private fun AppPlaybookEditor(store: AppPlaybookStore, original: AppPlaybook?, onBack: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var packageName by remember(original) { mutableStateOf(original?.packageName.orEmpty()) }
    var id by remember(original) { mutableStateOf(original?.id.orEmpty()) }
    var title by remember(original) { mutableStateOf(original?.title.orEmpty()) }
    var steps by remember(original) { mutableStateOf(original?.steps?.joinToString("\n").orEmpty()) }
    var completionCheck by remember(original) { mutableStateOf(original?.completionCheck.orEmpty()) }
    var appVersionName by remember(original) { mutableStateOf(original?.appVersionName.orEmpty()) }
    var appVersionCode by remember(original) { mutableStateOf(original?.appVersionCode?.toString().orEmpty()) }
    var enabled by remember(original) { mutableStateOf(original?.enabled ?: true) }
    var verified by remember(original) { mutableStateOf(original?.verified ?: false) }
    var busy by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val errorLabel = stringResource(R.string.app_playbooks_error)
    fun mutate(block: () -> Unit) {
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block() }; onChanged() }
            catch (_: Exception) { notice = errorLabel }
            finally { busy = false }
        }
    }
    MiuixScaffoldPage(title = stringResource(R.string.app_playbooks_title), onBack = onBack) {
        item(key = "editor-fields") {
            EtaPreferenceGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value = packageName, onValueChange = { packageName = it.take(200); verified = false }, label = stringResource(R.string.app_playbooks_package), enabled = !busy && original == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = id, onValueChange = { id = it.take(64); verified = false }, label = stringResource(R.string.app_playbooks_id), enabled = !busy && original == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = title, onValueChange = { title = it.take(120); verified = false }, label = stringResource(R.string.app_playbooks_name), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = steps, onValueChange = { steps = it.take(10_020); verified = false }, label = stringResource(R.string.app_playbooks_steps), enabled = !busy, singleLine = false, minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth())
                    TextField(value = completionCheck, onValueChange = { completionCheck = it.take(500); verified = false }, label = stringResource(R.string.app_playbooks_check), enabled = !busy, singleLine = false, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    TextField(value = appVersionName, onValueChange = { appVersionName = it.take(120); verified = false }, label = stringResource(R.string.app_playbooks_version_name), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = appVersionCode, onValueChange = { appVersionCode = it.take(20); verified = false }, label = stringResource(R.string.app_playbooks_version_code), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    original?.sourceRunId?.takeIf { it.isNotBlank() }?.let { source ->
                        Text(stringResource(R.string.app_playbooks_source_run, source.take(32)), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                EtaSwitchPreference(title = stringResource(R.string.app_playbooks_enabled), checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                EtaPreferenceDivider()
                EtaSwitchPreference(title = stringResource(R.string.app_playbooks_verified), summary = stringResource(R.string.app_playbooks_verified_summary), checked = verified, onCheckedChange = { verified = it }, enabled = !busy)
            }
        }
        notice?.let { message -> item(key = "editor-message") { Text(message, modifier = Modifier.padding(24.dp)) } }
        item(key = "editor-actions") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaTextButton(text = stringResource(R.string.app_playbooks_save), enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    mutate {
                        val versionCode = appVersionCode.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
                            ?: if (appVersionCode.trim().isEmpty()) null else throw IllegalArgumentException("invalid version code")
                        store.saveFromUser(packageName.trim(), id.trim(), title, steps.lines().filter(String::isNotBlank), completionCheck,
                            original?.revision ?: 0L, enabled, verified,
                            appVersionName = appVersionName,
                            appVersionCode = versionCode,
                            sourceRunId = original?.sourceRunId)
                    }
                })
                if (original != null) {
                    if (confirmingDelete) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EtaTextButton(text = stringResource(R.string.app_playbooks_delete_confirm), enabled = !busy, onClick = { mutate { store.delete(original.packageName, original.id, original.revision) } }, modifier = Modifier.weight(1f))
                        EtaTextButton(text = stringResource(R.string.app_playbooks_cancel), enabled = !busy, onClick = { confirmingDelete = false }, modifier = Modifier.weight(1f))
                    } else EtaTextButton(text = stringResource(R.string.app_playbooks_delete), enabled = !busy, onClick = { confirmingDelete = true }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
