package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaDesign
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AssistantComposer(
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
    keyboardVisible: Boolean,
    colors: EtaVoicePanelColors,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
    statusLabel: String = "",
) {
    Column(
        modifier = modifier,
    ) {
        AnimatedContent(
            // 按住说话期间保持输入栏：切换布局会移除正被按住的按钮，松手事件随之丢失。
            targetState = recognitionEnabled && speech.active && !speech.holdToTalk && state.messages.isEmpty(),
            transitionSpec = {
                fadeIn(tween(250)) togetherWith fadeOut(tween(160)) using
                    SizeTransform(clip = false)
            },
            contentAlignment = Alignment.BottomCenter,
            label = "assistant_input_mode",
        ) { voiceMode ->
            if (voiceMode) {
                AssistantVoiceEntry(
                    speech = speech,
                    input = input,
                    colors = colors,
                    onKeyboard = onKeyboard,
                    onFinishSpeech = onFinishSpeech,
                    onExpand = onExpand,
                )
            } else {
                AssistantInputBar(
                    state = state,
                    input = input,
                    speech = speech,
                    recognitionEnabled = recognitionEnabled,
                    inputFocusRequestKey = inputFocusRequestKey,
                    onInputFocus = onInputFocus,
                    onMicrophone = onMicrophone,
                    onCancelSpeech = onCancelSpeech,
                    onFinishSpeech = onFinishSpeech,
                    keyboardVisible = keyboardVisible,
                    colors = colors,
                    onInputChange = onInputChange,
                    onSubmit = onSubmit,
                    onStop = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    onExpand = onExpand,
                    statusLabel = statusLabel,
                )
            }
        }
    }
}

@Composable
private fun AssistantInputBar(
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    recognitionEnabled: Boolean,
    inputFocusRequestKey: Int,
    onInputFocus: () -> Unit,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    keyboardVisible: Boolean,
    colors: EtaVoicePanelColors,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    statusLabel: String,
) {
    val canSubmit = input.isNotBlank() && state.phase != EtaVoicePhase.PROCESSING
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val inputEnabled = state.phase != EtaVoicePhase.PROCESSING && !speech.active
    LaunchedEffect(inputFocusRequestKey, inputEnabled) {
        if (inputFocusRequestKey < 0 || !inputEnabled) return@LaunchedEffect
        // 输入栏挂载且浮窗取得焦点后再连接 IME，避免动画切换时请求落在尚不存在的输入框上。
        withFrameNanos { }
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focusRequester.requestFocus()
        withFrameNanos { }
        keyboard?.show()
    }
    var textFocused by remember { mutableStateOf(false) }
    val inputHighlighted = textFocused || keyboardVisible
    val inputBackground by animateColorAsState(
        targetValue = if (inputHighlighted) colors.focusedInput else colors.input,
        animationSpec = tween(180, easing = LinearOutSlowInEasing),
        label = "assistant_input_background",
    )
    val contentColor = colors.inputPrimary
    val hintColor = colors.inputTertiary
    val inputShape = RoundedCornerShape(32.dp)
    val capsuleFlow = rememberEtaFlowPhase()
    val rimWidth = if (inputHighlighted) 2.dp else 1.6.dp
    val trailingIcon = when {
        speech.active || state.phase == EtaVoicePhase.PROCESSING -> R.drawable.ic_assistant_stop
        canSubmit || !recognitionEnabled -> R.drawable.ic_assistant_send
        else -> R.drawable.ic_assistant_voice
    }
    val trailingDescription = when {
        speech.active -> R.string.voice_finish_speech
        state.phase == EtaVoicePhase.PROCESSING -> R.string.action_stop
        canSubmit || !recognitionEnabled -> R.string.voice_send
        else -> R.string.voice_tap_to_speak
    }
    val trailingAction = when {
        speech.active -> onFinishSpeech
        state.phase == EtaVoicePhase.PROCESSING -> onStop
        canSubmit || !recognitionEnabled -> onSubmit
        else -> { { onMicrophone(false) } }
    }
    Row(
        modifier = modifier
            .heightIn(min = 60.dp)
            .dropShadow(
                shape = inputShape,
                shadow = Shadow(
                    radius = 18.dp,
                    color = Color.Black,
                    alpha = 0.14f,
                ),
            )
            // 品牌电光渐变描边：外层画流动渐变，内缩后铺不透明实底，描边清晰且内容永不被透。
            .drawBehind {
                drawRoundRect(
                    brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .padding(rimWidth)
            .clip(inputShape)
            .background(inputBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(
                        brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                        alpha = EtaDesign.Brand.GlowAlpha,
                    )
                }
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.capsule_expand), onClick = onExpand)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            EtaCapsuleSparkle(Modifier.size(24.dp), solidColor = Color.White)
        }
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
                .onFocusChanged {
                    if (it.isFocused && !textFocused) onInputFocus()
                    textFocused = it.isFocused
                }
                .focusRequester(focusRequester),
            enabled = inputEnabled,
            textStyle = TextStyle(
                color = contentColor,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            cursorBrush = SolidColor(contentColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSubmit) onSubmit() }),
            maxLines = 4,
            minLines = 1,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = if (speech.message != null && speech.feedbackIsError) speech.message
                            else if (speech.errorRes != null) stringResource(speech.errorRes)
                            else if (state.phase == EtaVoicePhase.PROCESSING) statusLabel else stringResource(
                                if (speech.holdToTalk) R.string.voice_release_to_send else R.string.voice_input_hint,
                            ),
                            color = hintColor,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        AssistantTrailingButton(
            iconRes = trailingIcon,
            contentDescription = stringResource(trailingDescription),
            tint = EtaDesign.Brand.Violet,
            enabled = speech.phase != EtaSpeechPhase.RECOGNIZING &&
                (recognitionEnabled || canSubmit || state.phase == EtaVoicePhase.PROCESSING),
            holdToTalkAvailable = recognitionEnabled && !speech.active && !canSubmit && state.phase != EtaVoicePhase.PROCESSING,
            onClick = trailingAction,
            onHoldStart = { onMicrophone(true) },
            onHoldEnd = onFinishSpeech,
            onHoldCancel = onCancelSpeech,
        )
    }
}

/**
 * 输入栏尾部按钮。空闲时长按进入按住说话：松手发送，手指移出按钮取消；
 * 长按触发后不再派发点击，避免松手时又按旧状态开始一次新的录音。
 */
@Composable
private fun AssistantTrailingButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    enabled: Boolean,
    holdToTalkAvailable: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentClick by rememberUpdatedState(onClick)
    val currentHoldAvailable by rememberUpdatedState(holdToTalkAvailable)
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentHoldCancel by rememberUpdatedState(onHoldCancel)
    var pressed by remember { mutableStateOf(false) }
    val capsuleFlow = rememberEtaFlowPhase()
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                val scale = if (pressed) 0.9f else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .drawBehind {
                if (enabled) {
                    // 激活/可发送：品牌渐变实心圆 + 低透明外发光。
                    drawCircle(
                        brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                        alpha = EtaDesign.Brand.GlowAlpha,
                        radius = size.minDimension / 2f + 3.dp.toPx(),
                    )
                    drawCircle(
                        brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                    )
                } else {
                    // 非激活：品牌渐变描边圆，内部透明。
                    val sw = 1.6.dp.toPx()
                    drawCircle(
                        brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                        radius = size.minDimension / 2f - sw / 2f,
                        style = Stroke(sw),
                    )
                }
            }
            // 手势检测不产生无障碍动作；读屏用户只需要点击语义，按住说话依赖触摸时长不适合读屏。
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onClick { if (enabled) currentClick(); enabled }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var holding = false
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (holding) {
                            holding = false
                            if (released) currentHoldEnd() else currentHoldCancel()
                        }
                    },
                    onLongPress = {
                        if (currentHoldAvailable) {
                            holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentHoldStart()
                        }
                    },
                    onTap = { currentClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) Color.White else tint.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun AssistantVoiceEntry(
    speech: EtaSpeechState,
    input: String,
    colors: EtaVoicePanelColors,
    onKeyboard: () -> Unit,
    onFinishSpeech: () -> Unit,
    onExpand: () -> Unit,
) {
    val controls = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(220)
        controls.animateTo(1f, tween(180))
    }
    val shape = RoundedCornerShape(32.dp)
    val capsuleFlow = rememberEtaFlowPhase()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .graphicsLayer { alpha = controls.value }
            .dropShadow(
                shape = shape,
                shadow = Shadow(
                    radius = 18.dp,
                    color = Color.Black,
                    alpha = 0.14f,
                ),
            )
            // 品牌流动渐变描边 + 不透明实底。
            .drawBehind {
                drawRoundRect(
                    brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                )
            }
            .padding(1.6.dp)
            .clip(shape)
            .background(colors.focusedInput)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .drawBehind {
                    drawCircle(
                        brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                        alpha = EtaDesign.Brand.GlowAlpha,
                    )
                }
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.capsule_expand), onClick = onExpand)
                .padding(8.dp),
        ) { EtaCapsuleSparkle(Modifier.size(24.dp), solidColor = Color.White) }
        AssistantVoiceAction(
            iconRes = R.drawable.ic_assistant_keyboard_float,
            contentDescription = stringResource(R.string.voice_use_keyboard),
            enabled = controls.value >= 0.5f,
            onClick = onKeyboard,
        )
        Text(
            text = input.ifBlank {
                stringResource(
                    when (speech.phase) {
                        EtaSpeechPhase.STARTING -> R.string.voice_starting_label
                        EtaSpeechPhase.RECOGNIZING -> R.string.voice_recognizing
                        else -> R.string.voice_listening
                    },
                )
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.inputPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        AssistantVoiceAction(
            iconRes = R.drawable.ic_assistant_stop,
            contentDescription = stringResource(R.string.voice_finish_speech),
            enabled = controls.value >= 0.5f && speech.phase != EtaSpeechPhase.RECOGNIZING,
            onClick = onFinishSpeech,
        )
    }
}

@Composable
private fun AssistantVoiceAction(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val capsuleFlow = rememberEtaFlowPhase()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .drawBehind {
                if (enabled) {
                    drawCircle(
                        brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                    )
                } else {
                    val sw = 1.6.dp.toPx()
                    drawCircle(
                        brush = etaCapsuleFlowBrush(capsuleFlow, EtaDesign.Brand.Gradient, size.width, size.height),
                        radius = size.minDimension / 2f - sw / 2f,
                        style = Stroke(sw),
                    )
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onClick { if (enabled) onClick(); enabled }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else EtaDesign.Brand.Violet.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
    }
}
