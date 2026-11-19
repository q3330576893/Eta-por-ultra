package io.github.mangi.eta.ui.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.model.FanoutBranchStatusUi
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ConversationModelComparisonPanel(state: AgentAppState) {
    val chat = state.homeState
    val owner = state.homeConversationId
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { kotlinx.coroutines.delay(1400); copied = null } }
    var warnings by remember(owner) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(owner, chat.samplingOverride, chat.modelId) { warnings = state.samplingWarnings() }
    val blockedReason = when {
        chat.isStreaming || chat.isFanoutRunning || chat.isCompacting -> R.string.conversation_models_wait
        chat.messageEdit != null || chat.roleplay != null -> R.string.conversation_models_plain_chat
        chat.pendingFileReferences.isNotEmpty() || chat.pendingMessageRefs.isNotEmpty() -> R.string.conversation_models_attachments
        chat.compareModelIds.isEmpty() -> R.string.conversation_models_choose
        chat.input.isBlank() -> R.string.conversation_models_enter_prompt
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        if (warnings.isNotEmpty()) Text(stringResource(R.string.conversation_models_unsupported, warnings.joinToString()))
        Text(stringResource(R.string.conversation_models_compare))
        Text(stringResource(R.string.conversation_models_cost))
        state.modelPickerState.providerGroups.forEach { group ->
            group.models.forEach { model ->
                val key = "${model.providerId}:${model.id}"
                val checked = key in chat.compareModelIds
                Row {
                    Text("${group.providerName} · ${model.displayName}", modifier = Modifier.weight(1f))
                    EtaSwitch(checked = checked, onCheckedChange = {
                        if (state.homeConversationId == owner && !chat.isFanoutRunning && (checked || chat.compareModelIds.size < 3)) {
                            state.setCompareModels(if (checked) chat.compareModelIds - key else chat.compareModelIds + key)
                        }
                    })
                }
            }
        }
        blockedReason?.let { Text(stringResource(it)) }
        EtaTextButton(text = stringResource(R.string.conversation_models_start), enabled = blockedReason == null,
            onClick = { if (state.homeConversationId == owner) state.startComparison() })
        if (chat.isFanoutRunning) {
            EtaTextButton(text = stringResource(R.string.conversation_models_cancel),
                onClick = { if (state.homeConversationId == owner) state.cancelComparison() })
        }
        if (chat.fanoutColumns.size > 1) Text(stringResource(R.string.conversation_models_swipe_compare))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            chat.fanoutColumns.forEach { branch ->
                Column(Modifier.width(280.dp)
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${branch.modelName} · ${stringResource(when (branch.status) {
                        FanoutBranchStatusUi.Pending -> R.string.conversation_models_pending
                        FanoutBranchStatusUi.Running -> R.string.conversation_models_running
                        FanoutBranchStatusUi.Succeeded -> R.string.conversation_models_succeeded
                        FanoutBranchStatusUi.Failed -> R.string.conversation_models_failed
                        FanoutBranchStatusUi.Skipped -> R.string.conversation_models_skipped
                    })}")
                    branch.warnings.takeIf { it.isNotEmpty() }?.let {
                        Text(stringResource(R.string.conversation_models_unsupported, it.joinToString()))
                    }
                    branch.errorText?.let { Text(it) }
                    SelectionContainer { Text(branch.answerPreview.take(20_000)) }
                    if (branch.answerPreview.length > 20_000) Text(stringResource(R.string.conversation_models_preview_limit))
                    EtaTextButton(text = stringResource(if (copied == branch.id) R.string.copy_copied else R.string.ui_copy_4edd1d),
                        enabled = branch.answerPreview.isNotBlank(), onClick = {
                            @Suppress("DEPRECATION")
                            clipboard.setText(AnnotatedString(branch.answerPreview))
                            copied = branch.id
                        })
                    if (branch.status == FanoutBranchStatusUi.Running || branch.status == FanoutBranchStatusUi.Pending) {
                        EtaTextButton(text = stringResource(R.string.conversation_models_cancel),
                            onClick = { if (state.homeConversationId == owner) state.cancelComparison(branch.id) })
                    }
                    if (!chat.isFanoutRunning && branch.status == FanoutBranchStatusUi.Succeeded) {
                        val adoptable = state.canAdoptComparison(owner)
                        EtaTextButton(text = stringResource(R.string.conversation_models_adopt), enabled = adoptable,
                            onClick = { if (state.homeConversationId == owner && state.canAdoptComparison(owner)) state.adoptComparison(branch.id) })
                        if (!adoptable) Text(stringResource(R.string.conversation_models_history_changed))
                    }
                }
            }
        }
    }
}
