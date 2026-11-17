package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import io.github.mangi.eta.ui.components.EtaDesign
import io.github.mangi.eta.ui.components.rememberDataUrlBitmap
import io.github.mangi.eta.ui.components.EtaTextButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.components.QuickActionResultCard
import io.github.mangi.eta.agent.quickactions.DailyCommandResult
import io.github.mangi.eta.agent.quickactions.QuickActionsExecutor
import io.github.mangi.eta.agent.quickactions.DailyIntent
import io.github.mangi.eta.agent.quickactions.WeatherCity
import kotlinx.coroutines.launch
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class EtaVoicePhase { READY, PROCESSING, ERROR }

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
    val status: EtaVoiceStatus = EtaVoiceStatus.InputRequest,
)

internal sealed interface EtaVoiceStatus {
    data object InputRequest : EtaVoiceStatus
    data object Reasoning : EtaVoiceStatus
    data object Completed : EtaVoiceStatus
    data class RunningTool(val name: String) : EtaVoiceStatus
    data class Failed(val detail: String?) : EtaVoiceStatus
    data object Stopped : EtaVoiceStatus
}

internal data class EtaVoicePanelColors(
    val content: Color,
    val input: Color,
    val focusedInput: Color,
    val inputPrimary: Color,
    val inputSecondary: Color,
    val inputTertiary: Color,
    val tertiary: Color,
    val panelStroke: Color,
    val chipStroke: Color,
)

@Composable
private fun rememberEtaVoicePanelColors(): EtaVoicePanelColors {
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    // 悬浮底色保持高对比（可能盖在任意第三方 App 上），强调色跟随系统主题主色：
    // 切换 Monet 主色或深浅色时，胶囊的聚焦边框与语音 chip 同步变化，不再与全局主题脱节。
    val accent = scheme.primary
    return remember(dark, accent) {
        if (dark) EtaVoicePanelColors(
            Color(0xFA202431), Color(0xFA242A39), accent.copy(alpha = 0.28f),
            Color(0xFFF1F3FF), Color(0xFFB5BED6), Color(0xFF9CA8C4),
            accent, Color(0x25FFFFFF), accent.copy(alpha = 0.32f),
        ) else EtaVoicePanelColors(
            Color(0xFCFFFFFF), Color(0xFCFFFFFF), accent.copy(alpha = 0.14f),
            Color(0xFF202537), Color(0xFF56617A), Color(0xFF65718B),
            accent, Color(0xFFC9D6F2), accent.copy(alpha = 0.22f),
        )
    }
}

/** Wrap-content surface: the host overlay contains only this bottom capsule and optional reply. */
@Composable
internal fun EtaVoicePanel(
    speechLevel: () -> Float,
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    recognitionEnabled: Boolean?,
    entranceComplete: Boolean,
    onEntranceFinished: () -> Unit,
    onContentReady: () -> Unit,
    onInputFocus: () -> Unit,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    inputFocusRequestKey: Int,
    canOpenConversation: Boolean,
    handoffRunning: Boolean,
    exitRequested: Boolean,
    onInputChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit = {},
    onOpenConversation: () -> Unit,
    screenContextAppLabel: String? = null,
    screenContextCapturedAtMillis: Long? = null,
    screenContextEnabled: Boolean = true,
    onScreenContextEnabledChange: (Boolean) -> Unit = {},
    speechPlaybackActive: Boolean = false,
    onCopyReply: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStopPlayback: () -> Unit = {},
    onQuickRetry: () -> Unit = {},
    conversationKey: String = "",
    onApprovalSubmit: ((String) -> Unit)? = null,
    continuousConversation: Boolean = false,
    continuousListening: Boolean = false,
    onContinuousChange: (Boolean) -> Unit = {},
    speechCaption: String? = null,
    voiceDetail: String? = null,
    voiceState: DuplexVoiceState? = null,
    onRefreshContext: () -> Unit = {},
    contextSummary: String? = null,
    contextImage: String? = null,
    suggestions: List<String>? = null,
    quickResult: DailyCommandResult? = null,
    onQuickCityInput: (String) -> Unit = {},
    onQuickTextInput: (String) -> Unit = {},
    onQuickCitySelected: (WeatherCity) -> Unit = {},
    insightsContent: (@Composable () -> Unit)? = null,
    onContentBounds: (android.graphics.Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val ime = WindowInsets.ime
    val navigation = WindowInsets.navigationBars
    val keyboardVisible by remember(density, ime, navigation) {
        derivedStateOf { ime.getBottom(density) > navigation.getBottom(density) }
    }
    val contentVisible = recognitionEnabled != null && (recognitionEnabled || entranceComplete)
    val readyCallback by rememberUpdatedState(onContentReady)
    val entranceCallback by rememberUpdatedState(onEntranceFinished)
    val entryAlpha = remember { Animatable(0f) }
    val exitAlpha by animateFloatAsState(if (exitRequested) 0f else 1f, EtaDesign.Motion.fast(), label = "capsule_exit")
    var detailsExpanded by remember { mutableStateOf(false) }
    val hasMessages = state.messages.isNotEmpty()
    val bottomInsetDp = with(density) { maxOf(ime.getBottom(this), navigation.getBottom(this)).toDp().value }
    val upperLimitDp = (configuration.screenHeightDp - bottomInsetDp - 190f).coerceIn(48f, 480f)
    val maximumReplyDp = minOf((configuration.screenHeightDp * 0.40f).coerceIn(180f, 340f), upperLimitDp)
    val maximumReplyPx = with(density) { maximumReplyDp.dp.toPx() }
    val minimumReplyPx = with(density) { 140.dp.toPx() }.coerceAtMost(maximumReplyPx)
    val listState = rememberLazyListState()
    var keepBottomAnchored by remember { mutableStateOf(true) }
    var settledHeight by remember { mutableFloatStateOf(maximumReplyPx) }
    var dragHeight by remember { mutableStateOf<Float?>(null) }
    var pullBeyondTop by remember { mutableFloatStateOf(0f) }
    var pullBelowBottom by remember { mutableFloatStateOf(0f) }
    val height by animateFloatAsState(
        targetValue = settledHeight.coerceIn(minimumReplyPx, maximumReplyPx),
        animationSpec = EtaDesign.Motion.medium(), label = "capsule_reply_height",
    )
    val currentHeight by rememberUpdatedState(dragHeight ?: height)
    val openCallback by rememberUpdatedState(onOpenConversation)
    val closeCallback by rememberUpdatedState(onClose)
    val canHandoff by rememberUpdatedState(canOpenConversation)
    val handoffBlocked by rememberUpdatedState(handoffRunning)

    LaunchedEffect(exitRequested) {
        if (!exitRequested && !entranceComplete) {
            delay(180)
            entranceCallback()
        }
    }
    LaunchedEffect(contentVisible, exitRequested) {
        if (contentVisible && !exitRequested) {
            entryAlpha.animateTo(1f, EtaDesign.Motion.let { if (recognitionEnabled == false) it.slow() else it.fast() })
            readyCallback()
        }
    }
    LaunchedEffect(maximumReplyPx) { settledHeight = settledHeight.coerceIn(minimumReplyPx, maximumReplyPx) }

    // No background, scrim, full-screen welcome or full-screen pointer target is drawn here.
    Column(
        modifier = modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .testTag("eta_assistant_compact_surface")
            .graphicsLayer { this.alpha = exitAlpha }
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
    ) {
        if (contentVisible) Column(Modifier.graphicsLayer { this.alpha = entryAlpha.value }.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            onContentBounds(android.graphics.Rect(kotlin.math.floor(bounds.left).toInt(), kotlin.math.floor(bounds.top).toInt(),
                kotlin.math.ceil(bounds.right).toInt(), kotlin.math.ceil(bounds.bottom).toInt()))
        }) {
          Column(Modifier.fillMaxWidth().heightIn(max = upperLimitDp.dp).verticalScroll(rememberScrollState())) {
            quickResult?.let { result ->
                QuickActionResultCard(
                    result = result,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()),
                    onCityInput = onQuickCityInput,
                    onQuickTextInput = onQuickTextInput,
                    onCitySelected = onQuickCitySelected,
                    onRetry = onQuickRetry,
                    onRefresh = onQuickRetry,
                )
                Box(Modifier.height(8.dp))
            }
            if (hasMessages && !detailsExpanded) Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(EtaDesign.Radius.LG))
                    .background(colors.content)
                    .border(1.dp, colors.panelStroke, RoundedCornerShape(EtaDesign.Radius.LG)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        voiceStatusLabel(state, speech, speechPlaybackActive),
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.inputSecondary, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CapsuleAction(Icons.Rounded.ContentCopy, stringResource(R.string.capsule_copy_reply), colors, onCopyReply)
                    if (state.phase != EtaVoicePhase.PROCESSING) {
                        CapsuleAction(Icons.Rounded.Refresh, stringResource(R.string.capsule_retry), colors, onRetry)
                    }
                    if (canOpenConversation) CapsuleAction(
                        Icons.Rounded.OpenInFull, stringResource(R.string.voice_open_conversation), colors, onOpenConversation,
                    )
                    CapsuleAction(Icons.Rounded.Close, stringResource(R.string.voice_close_assistant), colors, onClose)
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                        .testTag("eta_assistant_drag_handle")
                        .pointerInput(maximumReplyPx, minimumReplyPx) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragHeight = currentHeight
                                    pullBeyondTop = 0f
                                    pullBelowBottom = 0f
                                },
                                onVerticalDrag = { change, amount ->
                                    if (!handoffBlocked) {
                                        change.consume()
                                        val requested = (dragHeight ?: currentHeight) + pullBeyondTop - pullBelowBottom - amount
                                        dragHeight = requested.coerceIn(minimumReplyPx, maximumReplyPx)
                                        pullBeyondTop = (requested - maximumReplyPx).coerceAtLeast(0f)
                                        pullBelowBottom = (minimumReplyPx - requested).coerceAtLeast(0f)
                                    }
                                },
                                onDragEnd = {
                                    when {
                                        pullBelowBottom >= 92.dp.toPx() -> closeCallback()
                                        canHandoff && pullBeyondTop >= 24.dp.toPx() -> openCallback()
                                        else -> settledHeight = (dragHeight ?: height).coerceIn(minimumReplyPx, maximumReplyPx)
                                    }
                                    dragHeight = null
                                    pullBeyondTop = 0f
                                    pullBelowBottom = 0f
                                },
                                onDragCancel = { dragHeight = null; pullBeyondTop = 0f; pullBelowBottom = 0f },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(colors.tertiary))
                }
                Column(Modifier.fillMaxWidth().height(with(density) { (dragHeight ?: height).toDp() })) {
                    AgentConversationMessages(
                        visibleMessages = state.messages,
                        scrollState = listState,
                        isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                        bottomInset = 8.dp,
                        conversationKey = conversationKey,
                        onSubmit = onApprovalSubmit,
                        keepBottomAnchored = keepBottomAnchored,
                        onBottomAnchorChanged = { keepBottomAnchored = it },
                        assistantOverlay = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    if (insightsContent != null) Box(Modifier.heightIn(max = 120.dp)) { insightsContent() }
                }
            }

            AnimatedVisibility(detailsExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = if (hasMessages) 180.dp else 330.dp)
                        .verticalScroll(rememberScrollState()).clip(RoundedCornerShape(EtaDesign.Radius.XL))
                        .background(colors.content).border(1.dp, colors.panelStroke, RoundedCornerShape(EtaDesign.Radius.XL))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            voiceStatusLabel(state, speech, speechPlaybackActive),
                            Modifier.weight(1f), color = colors.inputPrimary, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        CapsuleAction(Icons.Rounded.Close, stringResource(R.string.voice_close_assistant), colors, onClose)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(EtaDesign.Radius.SM))
                            .clickable(role = Role.Switch) { onScreenContextEnabledChange(!screenContextEnabled) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.voice_context_toggle), Modifier.weight(1f), color = colors.inputPrimary, fontSize = 13.sp)
                        Text(
                            stringResource(if (screenContextEnabled) R.string.capsule_on else R.string.capsule_off),
                            color = EtaAssistantPalette.Blue, fontSize = 13.sp,
                        )
                    }
                    if (screenContextAppLabel != null && screenContextEnabled) Text(
                        stringResource(R.string.voice_context_source, screenContextAppLabel),
                        color = colors.inputSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (screenContextCapturedAtMillis != null && screenContextEnabled) {
                        val timestamp = remember(screenContextCapturedAtMillis) {
                            java.time.Instant.ofEpochMilli(screenContextCapturedAtMillis).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                        }
                        Text(stringResource(R.string.capsule_context_time, timestamp), color = colors.inputSecondary, fontSize = 11.sp)
                        Text(stringResource(R.string.voice_context_notice), color = colors.inputSecondary, fontSize = 11.sp)
                    }
                    contextSummary?.let { Text(it, color = colors.inputSecondary, fontSize = 11.sp) }
                    if (screenContextEnabled && contextImage != null) {
                        rememberDataUrlBitmap(contextImage, maxPx = 640)?.let { bitmap ->
                            Image(bitmap, stringResource(R.string.capsule_context_preview),
                                Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(EtaDesign.Radius.SM)),
                                contentScale = ContentScale.Fit)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.capsule_context_refresh),
                            Modifier.weight(1f).clickable(onClick = onRefreshContext).padding(vertical = 8.dp),
                            color = EtaAssistantPalette.Blue, fontSize = 13.sp)
                        if (recognitionEnabled == true) Text(
                            stringResource(if (continuousConversation) R.string.voice_continuous_on else R.string.voice_continuous_title),
                            Modifier.clip(CircleShape).clickable { onContinuousChange(!continuousConversation) }.padding(8.dp),
                            color = EtaAssistantPalette.Blue, fontSize = 13.sp)
                    }
                    voiceDetail?.let { Text(it, color = colors.inputSecondary, fontSize = 11.sp) }
                    voiceState?.let { voice ->
                        Text(voice.audio.routeDescription + " · " + voice.audio.bargeInReason,
                            color = colors.inputSecondary, fontSize = 11.sp)
                        Text("RMS ${voice.metrics.rms.toInt()} · ${voice.metrics.semanticHint}",
                            color = colors.inputSecondary, fontSize = 11.sp)
                        if (voice.words.isNotEmpty()) {
                            Text(stringResource(R.string.voice_word_times), color = colors.inputSecondary, fontSize = 11.sp)
                            Text(voice.words.takeLast(12).joinToString(" · ") { "${it.word} ${it.startMs}–${it.endMs} ms" },
                                color = colors.inputPrimary, fontSize = 11.sp)
                        }
                        if (voice.timeline.isNotEmpty()) {
                            Text(stringResource(R.string.voice_timeline), color = colors.inputSecondary, fontSize = 11.sp)
                            voice.timeline.takeLast(8).forEach { entry ->
                                val label = stringResource(when (entry.kind) {
                                    VoiceTimelineKind.USER_FINAL -> R.string.voice_timeline_user
                                    VoiceTimelineKind.BARGE_IN -> R.string.voice_timeline_barge_in
                                    VoiceTimelineKind.ASSISTANT_STARTED -> R.string.voice_timeline_speaking
                                    VoiceTimelineKind.ASSISTANT_ENDED -> R.string.voice_timeline_speech_end
                                })
                                Text("${entry.elapsedMs} ms · $label ${entry.text}", color = colors.inputSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                    EtaAssistantSuggestions(
                        onSuggestionClick = { keyboard?.hide(); detailsExpanded = false; onSuggestionClick(it) },
                        colors = colors,
                        suggestions = suggestions ?: listOf("帮我总结当前屏幕内容", "今天有什么安排", "设置5分钟倒计时"),
                    )
                    if (speech.engineLabel.isNotBlank()) Text(
                        text = speech.engineLabel, color = colors.inputSecondary, fontSize = 12.sp,
                    )
                    if (recognitionEnabled == true) EtaSpeechFeedback(speech, onDownloadModel, onOpenSpeechSettings)
                }
            }
          }
            if (!speechCaption.isNullOrBlank()) Text(
                speechCaption, Modifier.fillMaxWidth().heightIn(max = 72.dp)
                    .clip(RoundedCornerShape(EtaDesign.Radius.SM)).background(colors.content).padding(10.dp),
                color = colors.inputPrimary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            if (continuousListening) Row(
                Modifier.padding(start = 12.dp, top = 4.dp).clip(CircleShape).background(colors.content)
                    .clickable { onContinuousChange(false) }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.voice_continuous_stop), color = colors.inputPrimary, fontSize = 12.sp) }
            if (speechPlaybackActive) Row(
                Modifier.padding(start = 12.dp, top = 4.dp).clip(CircleShape)
                    .background(colors.content).clickable(onClick = onStopPlayback).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.capsule_stop_reading), color = colors.inputPrimary, fontSize = 12.sp)
            }
            if (hasMessages || detailsExpanded) Box(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth()) {
                AssistantComposer(
                    state = state, input = input, speech = speech,
                    recognitionEnabled = recognitionEnabled == true,
                    inputFocusRequestKey = inputFocusRequestKey, onInputFocus = onInputFocus,
                    onMicrophone = onMicrophone, onCancelSpeech = onCancelSpeech, onFinishSpeech = onFinishSpeech,
                    onDownloadModel = onDownloadModel, onOpenSpeechSettings = onOpenSpeechSettings,
                    onKeyboard = onKeyboard, keyboardVisible = keyboardVisible, colors = colors,
                    onInputChange = onInputChange,
                    onSubmit = { keyboard?.hide(); onSubmit() }, onStop = onStop,
                    onExpand = { detailsExpanded = !detailsExpanded },
                    statusLabel = voiceStatusLabel(state, speech, speechPlaybackActive),
                    modifier = Modifier.fillMaxWidth(),
                )
                EtaAssistantWave(
                    active = !exitRequested && (speech.active || speechPlaybackActive || state.phase == EtaVoicePhase.PROCESSING),
                    energized = speech.active || speechPlaybackActive,
                    level = { if (speech.active) speechLevel() else 0f },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun CapsuleAction(icon: ImageVector, description: String, colors: EtaVoicePanelColors, action: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(19.dp), tint = colors.inputPrimary)
    }
}

@Composable
private fun voiceStatusLabel(state: EtaVoiceUiState, speech: EtaSpeechState, playback: Boolean): String = when {
    speech.active -> stringResource(when (speech.phase) {
        EtaSpeechPhase.STARTING -> R.string.voice_starting_label
        EtaSpeechPhase.RECOGNIZING -> R.string.voice_recognizing
        else -> R.string.voice_listening
    })
    playback -> stringResource(R.string.voice_speaking_label)
    state.phase == EtaVoicePhase.PROCESSING -> when (val status = state.status) {
        is EtaVoiceStatus.RunningTool -> stringResource(R.string.voice_running_tool, status.name)
        else -> stringResource(R.string.voice_processing)
    }
    state.phase == EtaVoicePhase.ERROR -> stringResource(R.string.voice_failed)
    state.status == EtaVoiceStatus.Completed -> stringResource(R.string.voice_completed)
    state.status == EtaVoiceStatus.Stopped -> stringResource(R.string.action_stop)
    else -> stringResource(R.string.capsule_ready)
}
