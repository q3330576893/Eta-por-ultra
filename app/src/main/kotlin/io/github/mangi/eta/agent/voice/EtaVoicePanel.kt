package io.github.mangi.eta.agent.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

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
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) {
        if (dark) {
            EtaVoicePanelColors(
                content = Color(0xF01B2030),
                input = Color(0xF02B3144),
                focusedInput = Color(0xFF363E55),
                inputPrimary = Color(0xFFF1F3FF),
                inputSecondary = Color(0xFFB5BED6),
                inputTertiary = Color(0xFF9CA8C4),
                tertiary = Color(0xFF707F9F),
                panelStroke = Color(0x1FFFFFFF),
                chipStroke = Color(0x29B7C8FF),
            )
        } else {
            EtaVoicePanelColors(
                content = Color(0xEDF6F7FF),
                input = Color(0xF5FFFFFF),
                focusedInput = Color.White,
                inputPrimary = Color(0xFF202537),
                inputSecondary = Color(0xFF56617A),
                inputTertiary = Color(0xFF65718B),
                tertiary = Color(0xFF9AA7C4),
                panelStroke = Color(0xA6FFFFFF),
                chipStroke = Color(0x244285F4),
            )
        }
    }
}

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
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val darkBackground = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val entryProgress = remember { Animatable(0f) }
    val entryAlpha = remember { Animatable(0f) }
    val scrimProgress = remember { Animatable(0f) }
    val contentVisible = recognitionEnabled != null && (recognitionEnabled || entranceComplete)
    val contentReady by rememberUpdatedState(onContentReady)
    val textEntryOffsetPx = with(density) { if (recognitionEnabled == false) 16.dp.toPx() else 0f }
    val exitAlpha by animateFloatAsState(
        targetValue = if (exitRequested) 0f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "assistant_exit",
    )

    LaunchedEffect(Unit) {
        delay(180)
        scrimProgress.animateTo(1f, tween(280, easing = LinearOutSlowInEasing))
    }

    LaunchedEffect(contentVisible) {
        if (!contentVisible) return@LaunchedEffect
        coroutineScope {
            launch {
                entryAlpha.animateTo(1f, tween(
                    durationMillis = if (recognitionEnabled == false) 360 else 220,
                    easing = FastOutSlowInEasing,
                ))
            }
            launch {
                entryProgress.animateTo(1f,
                    if (recognitionEnabled == false) folmeSpring(damping = 1f, response = 0.50f)
                    else tween(220, easing = LinearOutSlowInEasing))
            }
        }
        // 焦点会主动唤起 IME；必须等显现和位移都结束，不能在仍透明时交出输入焦点。
        contentReady()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha },
    ) {
        EtaAssistantBackdrop(
            dark = darkBackground,
            progress = { scrimProgress.value },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        EtaAssistantEdgeGlow(
            active = (speech.active || !entranceComplete) && !exitRequested,
            onEntranceFinished = onEntranceFinished,
        )
        EtaAssistantWave(
            active = !exitRequested && (
                speech.active || speechPlaybackActive || state.phase == EtaVoicePhase.PROCESSING || state.messages.isEmpty()
            ),
            level = { if (speech.active) speechLevel() else 0f },
            energized = speech.active || speechPlaybackActive,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        )

        AssistantHeader(
            state = state,
            speech = speech,
            speechPlaybackActive = speechPlaybackActive,
            colors = colors,
            screenContextAppLabel = screenContextAppLabel,
            screenContextCapturedAtMillis = screenContextCapturedAtMillis,
            screenContextEnabled = screenContextEnabled,
            canOpenConversation = canOpenConversation,
            onOpenConversation = onOpenConversation,
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        if (contentVisible) BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryAlpha.value
                    translationY = (1f - entryProgress.value) * textEntryOffsetPx
                },
        ) {
            val imeInsets = WindowInsets.ime
            val navigationInsets = WindowInsets.navigationBars
            val keyboardVisible by remember(density, imeInsets, navigationInsets) {
                derivedStateOf { imeInsets.getBottom(density) > navigationInsets.getBottom(density) }
            }
            val navigationBottom = WindowInsets.navigationBars.getBottom(density)
            val statusTop = WindowInsets.statusBars.getTop(density)
            val imeOverlap = if (state.messages.isEmpty()) 0
                else (imeInsets.getBottom(density) - navigationBottom).coerceAtLeast(0)
            val maxContentHeightPx = with(density) {
                (maxHeight - 88.dp).toPx() - statusTop - navigationBottom
            }.coerceAtLeast(with(density) { 220.dp.toPx() })
            AssistantPanel(
                state = state,
                input = input,
                speech = speech,
                recognitionEnabled = recognitionEnabled == true,
                inputFocusRequestKey = inputFocusRequestKey,
                onInputFocus = onInputFocus,
                onMicrophone = onMicrophone,
                onCancelSpeech = onCancelSpeech,
                onFinishSpeech = onFinishSpeech,
                onDownloadModel = onDownloadModel,
                onOpenSpeechSettings = onOpenSpeechSettings,
                onKeyboard = onKeyboard,
                colors = colors,
                canOpenConversation = canOpenConversation,
                handoffRunning = handoffRunning,
                baseContentHeightPx = assistantBaseHeightPx(
                    messages = state.messages,
                    maxHeightPx = maxContentHeightPx,
                    density = density.density,
                ),
                maxContentHeightPx = maxContentHeightPx,
                imeOverlapPx = imeOverlap,
                onInputChange = onInputChange,
                onSuggestionClick = { suggestion ->
                    keyboard?.hide()
                    onSuggestionClick(suggestion)
                },
                keyboardVisible = keyboardVisible,
                onSubmit = {
                    keyboard?.hide()
                    onSubmit()
                },
                onStop = onStop,
                onClose = onClose,
                onOpenConversation = onOpenConversation,
                screenContextEnabled = screenContextEnabled,
                onScreenContextEnabledChange = onScreenContextEnabledChange,
            )
        }
    }
}

@Composable
private fun BoxScope.AssistantPanel(
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    recognitionEnabled: Boolean,
    inputFocusRequestKey: Int,
    onInputFocus: () -> Unit,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    colors: EtaVoicePanelColors,
    canOpenConversation: Boolean,
    handoffRunning: Boolean,
    baseContentHeightPx: Float,
    maxContentHeightPx: Float,
    imeOverlapPx: Int,
    onInputChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    keyboardVisible: Boolean,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit = {},
    onOpenConversation: () -> Unit,
    screenContextEnabled: Boolean,
    onScreenContextEnabledChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var settledHeightPx by remember { mutableFloatStateOf(baseContentHeightPx) }
    var draggedHeightPx by remember { mutableStateOf<Float?>(null) }
    var autoExpandSuppressed by remember { mutableStateOf(false) }
    var dismissPullPx by remember { mutableFloatStateOf(0f) }
    var handoffPullPx by remember { mutableFloatStateOf(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    var fittedReplyId by remember { mutableStateOf<String?>(null) }
    // 满高时把手与状态栏之间的空间有限，需在扣除触摸 slop 后仍能完成过顶手势。
    val handoffThresholdPx = with(density) { 24.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val autoSettleTolerancePx = with(density) { 8.dp.toPx() }
    val overflowTolerancePx = with(density) { 16.dp.toPx() }
    val minimumContentHeightPx = with(density) { 140.dp.toPx() }.coerceAtMost(maxContentHeightPx)
    val dragHandleHeightPx = with(density) { 38.dp.toPx() }
    val contentFitSlackPx = with(density) { 12.dp.toPx() }
    val mediumHeightPx = baseContentHeightPx +
        (maxContentHeightPx - baseContentHeightPx) * 0.58f
    val hasMessages = state.messages.isNotEmpty()
    val latestMessageId = state.messages.lastOrNull()?.id
    val targetHeightPx = if (hasMessages) {
        if (settledHeightPx <= 0f) baseContentHeightPx
        else settledHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
    } else {
        0f
    }
    val animatedHeightPx by animateFloatAsState(
        targetValue = targetHeightPx,
        animationSpec = when {
            !autoExpandSuppressed &&
                abs(settledHeightPx - baseContentHeightPx) > autoSettleTolerancePx ->
                folmeSpring(damping = 1f, response = 0.72f)
            autoExpandSuppressed -> folmeSpring(damping = 1f, response = 0.38f)
            else -> folmeSpring(damping = 1f, response = 0.50f)
        },
        label = "assistant_content_height",
    )
    val sheetBackgroundAlpha = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
        label = "assistant_sheet_background",
    )
    val messageRevealProgress = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = if (hasMessages) {
            tween(durationMillis = 180, delayMillis = 50, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 100, easing = FastOutSlowInEasing)
        },
        label = "assistant_message_reveal",
    )
    val currentAnimatedHeight = rememberUpdatedState(animatedHeightPx)
    val sheetHeightPx = draggedHeightPx ?: animatedHeightPx
    val visibleSheetHeightPx = (sheetHeightPx - imeOverlapPx * 0.42f)
        .coerceAtLeast(if (hasMessages) with(density) { 140.dp.toPx() }.coerceAtMost(sheetHeightPx) else 0f)
        .coerceAtMost(
            (maxContentHeightPx - imeOverlapPx).coerceAtLeast(0f),
        )
    val nearFullscreen = sheetHeightPx >= maxContentHeightPx * 0.88f
    // 接管只认把手发起的过顶拉取：列表滚动与惯性 fling 不再触发，避免读回复时误入本体。
    val handoffReady = canOpenConversation && nearFullscreen && handoffPullPx >= handoffThresholdPx
    // 过顶拉取的橡皮筋位移要肉眼可见，用户才知道继续拉会进入本体。
    val sheetTranslationPx = dismissPullPx * 0.28f - (handoffPullPx * 0.36f)
        .coerceAtMost(with(density) { 40.dp.toPx() })

    LaunchedEffect(hasMessages, minimumContentHeightPx, maxContentHeightPx) {
        settledHeightPx = if (hasMessages) {
            if (settledHeightPx <= 0f) baseContentHeightPx
            else settledHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
        } else {
            autoExpandSuppressed = false
            fittedReplyId = null
            0f
        }
    }
    LaunchedEffect(state.phase, latestMessageId) {
        if (state.phase == EtaVoicePhase.PROCESSING) fittedReplyId = null
    }
    // 回复中按真实溢出逐级升高；回复结束且全部条目可测量时交给一次性内容拟合。
    LaunchedEffect(hasMessages, state.phase, latestMessageId, keyboardVisible, baseContentHeightPx, maxContentHeightPx, listState) {
        if (!hasMessages || keyboardVisible) return@LaunchedEffect
        snapshotFlow {
            !autoExpandSuppressed &&
                fittedReplyId != latestMessageId &&
                draggedHeightPx == null &&
                !handoffRunning &&
                keepBottomAnchored &&
                abs(currentAnimatedHeight.value - settledHeightPx) <= autoSettleTolerancePx &&
                run {
                    val measuredHeightPx = measuredConversationHeightPx(listState)
                    if (state.phase != EtaVoicePhase.PROCESSING && measuredHeightPx != null) {
                        false
                    } else if (measuredHeightPx != null) {
                        measuredHeightPx > listState.layoutInfo.viewportSize.height + overflowTolerancePx
                    } else {
                        listState.canScrollBackward || listState.canScrollForward
                    }
                }
        }
            .distinctUntilChanged()
            .collect { overflowAtRest ->
                if (!overflowAtRest) return@collect
                val nextHeight = when {
                    settledHeightPx < baseContentHeightPx - autoSettleTolerancePx -> baseContentHeightPx
                    settledHeightPx < mediumHeightPx - autoSettleTolerancePx -> mediumHeightPx
                    settledHeightPx < maxContentHeightPx - autoSettleTolerancePx -> maxContentHeightPx
                    else -> null
                }
                if (nextHeight != null) settledHeightPx = nextHeight
            }
    }
    // 完成排版后本轮只拟合一次；列表的旧滚动偏移不能把它再次推回高档。
    LaunchedEffect(hasMessages, state.phase, latestMessageId, keyboardVisible, maxContentHeightPx, listState) {
        if (!hasMessages || state.phase == EtaVoicePhase.PROCESSING || keyboardVisible) return@LaunchedEffect
        snapshotFlow {
            if (autoExpandSuppressed || fittedReplyId == latestMessageId ||
                draggedHeightPx != null || handoffRunning ||
                !keepBottomAnchored ||
                abs(currentAnimatedHeight.value - settledHeightPx) > autoSettleTolerancePx
            ) return@snapshotFlow null
            val contentHeightPx = measuredConversationHeightPx(listState) ?: return@snapshotFlow null
            (dragHandleHeightPx + contentHeightPx + contentFitSlackPx)
                .coerceIn(minimumContentHeightPx, maxContentHeightPx)
        }
            .distinctUntilChanged()
            .collectLatest { fittedHeightPx ->
                val targetHeightPx = fittedHeightPx ?: return@collectLatest
                delay(500)
                fittedReplyId = latestMessageId
                if (abs(settledHeightPx - targetHeightPx) > autoSettleTolerancePx) {
                    settledHeightPx = targetHeightPx
                }
            }
    }
    LaunchedEffect(handoffReady) {
        if (handoffReady && !thresholdHapticSent) {
            thresholdHapticSent = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!handoffReady) {
            thresholdHapticSent = false
        }
    }

    fun triggerHandoff() {
        if (handoffRunning || !canOpenConversation) return
        settledHeightPx = maxContentHeightPx
        draggedHeightPx = null
        dismissPullPx = 0f
        handoffPullPx = 0f
        onOpenConversation()
    }

    fun stopAutoExpand() {
        if (autoExpandSuppressed) return
        autoExpandSuppressed = true
        if (settledHeightPx > currentAnimatedHeight.value + autoSettleTolerancePx) {
            settledHeightPx = currentAnimatedHeight.value.coerceIn(minimumContentHeightPx, maxContentHeightPx)
        }
    }

    fun dragBy(deltaY: Float, allowHandoff: Boolean = false): Float {
        if (handoffRunning || state.messages.isEmpty()) return 0f
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        val requested = current + handoffPullPx - dismissPullPx - deltaY
        val minimumDragHeightPx = minOf(baseContentHeightPx, settledHeightPx)
        return when {
            requested > maxContentHeightPx -> {
                draggedHeightPx = maxContentHeightPx
                dismissPullPx = 0f
                handoffPullPx = if (allowHandoff) requested - maxContentHeightPx else 0f
                if (allowHandoff) deltaY else current - maxContentHeightPx
            }
            requested < minimumDragHeightPx -> {
                draggedHeightPx = minimumDragHeightPx
                dismissPullPx = minimumDragHeightPx - requested
                handoffPullPx = 0f
                deltaY
            }
            else -> {
                draggedHeightPx = requested
                dismissPullPx = 0f
                handoffPullPx = 0f
                deltaY
            }
        }
    }

    fun finishDrag(commit: Boolean = true, allowHandoff: Boolean = false) {
        if (handoffRunning) return
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        when {
            commit && dismissPullPx >= dismissThresholdPx -> onClose()
            // 松手可能与最后一个位移在同一帧内到达，直接读取状态，不依赖重组后的派生值。
            commit && allowHandoff && canOpenConversation &&
                current >= maxContentHeightPx && handoffPullPx >= handoffThresholdPx -> triggerHandoff()
            else -> {
                val anchors = floatArrayOf(
                    minOf(baseContentHeightPx, settledHeightPx),
                    baseContentHeightPx,
                    mediumHeightPx,
                    maxContentHeightPx,
                )
                settledHeightPx = anchors.minBy { kotlin.math.abs(it - current) }
                draggedHeightPx = null
                dismissPullPx = 0f
                handoffPullPx = 0f
            }
        }
    }

    // 局部函数引用的相等判断不包含捕获值，不能直接作为结构相等 State 的回调值。
    val stopAutoExpandState = rememberUpdatedState { stopAutoExpand() }
    val startDragState = rememberUpdatedState {
        stopAutoExpand()
        dismissPullPx = 0f
        handoffPullPx = 0f
        draggedHeightPx = currentAnimatedHeight.value
    }
    val dragByState = rememberUpdatedState { deltaY: Float, allowHandoff: Boolean ->
        dragBy(deltaY, allowHandoff)
    }
    val finishDragState = rememberUpdatedState { commit: Boolean, allowHandoff: Boolean ->
        finishDrag(commit, allowHandoff)
    }
    val nestedScrollConnection = remember(
        baseContentHeightPx,
        maxContentHeightPx,
        canOpenConversation,
        listState,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                stopAutoExpandState.value()
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val shouldResize = (available.y < 0f && current < maxContentHeightPx) ||
                    (available.y > 0f && current > minOf(baseContentHeightPx, settledHeightPx) &&
                        !listState.canScrollBackward)
                return if (shouldResize) {
                    Offset(0f, dragByState.value(available.y, false))
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                stopAutoExpandState.value()
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val atLowerEdge = available.y > 0f && current <= minOf(baseContentHeightPx, settledHeightPx)
                return if (atLowerEdge) {
                    Offset(0f, dragByState.value(available.y, false))
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                stopAutoExpandState.value()
                finishDragState.value(true, false)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                stopAutoExpandState.value()
                finishDragState.value(true, false)
                return Velocity.Zero
            }
        }
    }

    val messageRevealOffsetPx = with(density) { 12.dp.toPx() }
    val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // IME 已由系统逐帧插值；布局阶段直接消费，避免组合阶段旧值或二次动画造成追赶。
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
            .offset(y = with(density) { sheetTranslationPx.toDp() })
            .clip(sheetShape)
            .drawBehind {
                drawRect(
                    colors.content.copy(
                        alpha = colors.content.alpha * sheetBackgroundAlpha.value,
                    ),
                )
            }
            .border(
                width = 1.6.dp,
                color = colors.panelStroke.copy(alpha = colors.panelStroke.alpha * sheetBackgroundAlpha.value),
                shape = sheetShape,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { visibleSheetHeightPx.toDp() })
                .nestedScroll(nestedScrollConnection),
        ) {
            DragHandle(
                colors = colors,
                handoffReady = handoffReady,
                modifier = Modifier.pointerInput(baseContentHeightPx, maxContentHeightPx) {
                    // 回复完成会改变接管资格；更新回调而不重启正在进行的手势。
                    detectVerticalDragGestures(
                        onDragStart = { startDragState.value() },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragByState.value(dragAmount, true)
                        },
                        onDragEnd = { finishDragState.value(true, true) },
                        onDragCancel = { finishDragState.value(false, false) },
                    )
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = messageRevealProgress.value
                        translationY = (1f - messageRevealProgress.value) * messageRevealOffsetPx
                    },
            ) {
                if (hasMessages) {
                    AgentConversationMessages(
                        visibleMessages = state.messages,
                        scrollState = listState,
                        isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                        bottomInset = 8.dp,
                        keepBottomAnchored = keepBottomAnchored,
                        onBottomAnchorChanged = { keepBottomAnchored = it },
                        assistantOverlay = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (!hasMessages && !keyboardVisible) AssistantWelcome(
            state = state,
            colors = colors,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 18.dp),
        )
        if (!hasMessages) ContextControls(
            colors = colors,
            screenContextEnabled = screenContextEnabled,
            onScreenContextEnabledChange = onScreenContextEnabledChange,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        )
        EtaAssistantSuggestions(
            onSuggestionClick = onSuggestionClick,
            colors = colors,
            visible = !hasMessages,
        )
        AssistantComposer(
            state = state,
            input = input,
            speech = speech,
            recognitionEnabled = recognitionEnabled,
            inputFocusRequestKey = inputFocusRequestKey,
            onInputFocus = onInputFocus,
            onMicrophone = onMicrophone,
            onCancelSpeech = onCancelSpeech,
            onFinishSpeech = onFinishSpeech,
            onDownloadModel = onDownloadModel,
            onOpenSpeechSettings = onOpenSpeechSettings,
            onKeyboard = onKeyboard,
            keyboardVisible = keyboardVisible,
            colors = colors,
            onInputChange = onInputChange,
            onSubmit = onSubmit,
            onStop = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 10.dp,
                ),
        )
    }
}

@Composable
private fun AssistantWelcome(
    state: EtaVoiceUiState,
    colors: EtaVoicePanelColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.voice_welcome_subtitle),
            color = colors.inputSecondary,
            fontSize = 16.sp,
            maxLines = 2,
        )
        if (state.status is EtaVoiceStatus.Failed) {
            Text(
                text = state.status.detail.orEmpty(),
                color = EtaAssistantPalette.Red,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistantHeader(
    state: EtaVoiceUiState,
    speech: EtaSpeechState,
    speechPlaybackActive: Boolean,
    colors: EtaVoicePanelColors,
    screenContextAppLabel: String?,
    screenContextCapturedAtMillis: Long?,
    screenContextEnabled: Boolean = true,
    canOpenConversation: Boolean,
    onOpenConversation: () -> Unit,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var contextExpanded by remember { mutableStateOf(false) }
    val status = voiceStatusLabel(state, speech, speechPlaybackActive)
    val accent = when {
        speech.active -> EtaAssistantPalette.Blue
        speechPlaybackActive -> EtaAssistantPalette.Green
        state.phase == EtaVoicePhase.ERROR -> EtaAssistantPalette.Red
        state.phase == EtaVoicePhase.PROCESSING -> EtaAssistantPalette.Violet
        else -> EtaAssistantPalette.Yellow
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.focusedInput)
                    .border(1.dp, accent.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                EtaAssistantSparkle(Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = stringResource(R.string.voice_welcome_title),
                    color = colors.inputPrimary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    color = accent,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canOpenConversation) HeaderAction(
                imageVector = Icons.Rounded.OpenInFull,
                description = stringResource(R.string.voice_open_conversation),
                colors = colors,
                onClick = onOpenConversation,
            )
            HeaderAction(
                imageVector = Icons.Rounded.Close,
                description = stringResource(R.string.voice_close_assistant),
                colors = colors,
                onClick = onClose,
            )
        }
        if (screenContextAppLabel != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.input.copy(alpha = 0.82f))
                    .border(1.dp, colors.chipStroke, RoundedCornerShape(18.dp))
                    .clickable { contextExpanded = !contextExpanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Smartphone,
                    contentDescription = null,
                    tint = EtaAssistantPalette.Blue,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.voice_context_source, screenContextAppLabel),
                        color = if (screenContextEnabled) colors.inputPrimary else colors.inputTertiary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (contextExpanded) {
                        val captured = screenContextCapturedAtMillis?.let {
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                        }
                        if (captured != null) Text(
                            text = stringResource(R.string.voice_context_capture_time, captured),
                            color = colors.inputSecondary,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = stringResource(
                                if (screenContextEnabled) R.string.voice_context_notice
                                else R.string.voice_context_toggle,
                            ),
                            color = colors.inputSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextControls(
    colors: EtaVoicePanelColors,
    screenContextEnabled: Boolean,
    onScreenContextEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.voice_context_toggle)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.input.copy(alpha = 0.78f))
            .border(1.dp, colors.chipStroke, CircleShape)
            .clickable { onScreenContextEnabledChange(!screenContextEnabled) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick { onScreenContextEnabledChange(!screenContextEnabled); true }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.inputSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(CircleShape)
                .background(if (screenContextEnabled) EtaAssistantPalette.Blue else colors.tertiary),
            contentAlignment = if (screenContextEnabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun HeaderAction(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    colors: EtaVoicePanelColors,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.input.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = description,
            tint = colors.inputPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun voiceStatusLabel(
    state: EtaVoiceUiState,
    speech: EtaSpeechState,
    speechPlaybackActive: Boolean,
): String = when {
    speech.active -> when (speech.phase) {
        EtaSpeechPhase.STARTING -> stringResource(R.string.voice_starting_label)
        EtaSpeechPhase.RECOGNIZING -> stringResource(R.string.voice_recognizing)
        else -> stringResource(R.string.voice_listening)
    }
    speechPlaybackActive -> stringResource(R.string.voice_speaking_label)
    state.phase == EtaVoicePhase.PROCESSING -> when (val status = state.status) {
        is EtaVoiceStatus.RunningTool -> stringResource(R.string.voice_running_tool, status.name)
        else -> stringResource(R.string.voice_processing)
    }
    state.phase == EtaVoicePhase.ERROR -> stringResource(R.string.voice_failed)
    state.status is EtaVoiceStatus.Completed -> stringResource(R.string.voice_completed)
    state.status is EtaVoiceStatus.Stopped -> stringResource(R.string.action_stop)
    else -> stringResource(R.string.voice_ready_label)
}

@Composable
private fun DragHandle(
    colors: EtaVoicePanelColors,
    handoffReady: Boolean,
    modifier: Modifier = Modifier,
) {
    // 把手是接管的唯一入口：拉到阈值时变宽并染成主色，配合触觉反馈提示松手进入本体。
    val handleWidth by animateDpAsState(
        targetValue = if (handoffReady) 52.dp else 36.dp,
        animationSpec = tween(160),
        label = "assistant_handle_width",
    )
    val handleColor by animateColorAsState(
        targetValue = if (handoffReady) MiuixTheme.colorScheme.primary else colors.tertiary,
        animationSpec = tween(160),
        label = "assistant_handle_color",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .testTag("eta_assistant_drag_handle"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = handleWidth, height = 4.dp)
                .clip(CircleShape)
                .background(handleColor),
        )
    }
}

private fun measuredConversationHeightPx(listState: LazyListState): Float? {
    val layout = listState.layoutInfo
    if (layout.totalItemsCount == 0) return null
    val first = layout.visibleItemsInfo.firstOrNull() ?: return null
    val last = layout.visibleItemsInfo.lastOrNull() ?: return null
    if (first.index != 0 || last.index != layout.totalItemsCount - 1) return null
    return (last.offset + last.size - first.offset +
        layout.beforeContentPadding + layout.afterContentPadding).toFloat()
}

private fun assistantBaseHeightPx(
    messages: List<AgentChatMessageUi>,
    maxHeightPx: Float,
    density: Float,
): Float {
    if (messages.isEmpty()) return 0f
    // 回复增量只改变列表内容，不重新估算浮窗高度，避免与跟底滚动互相追赶。
    return (304f * density).coerceAtMost(maxHeightPx)
}
