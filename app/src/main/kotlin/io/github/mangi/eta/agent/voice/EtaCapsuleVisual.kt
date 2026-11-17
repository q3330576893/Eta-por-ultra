package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.components.EtaDesign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 语音胶囊的品牌视觉组件集（3.5.2 大改版）。
 *
 * 这里集中承载「电光渐变」语言：流动渐变描边、发光 sparkle、品牌波形、渐变芯片。
 * 全部以 [EtaDesign.Brand] 为唯一色源、以 [EtaDesign.Motion] 为唯一节奏，
 * 并且只用渐变做「装饰层」——所有文本容器仍由调用方铺不透明实底，保证悬浮高对比。
 */

/** 品牌渐变沿对角线缓缓往返流动；只在挂载期间运行，节奏来自 EtaDesign.Motion。 */
@Composable
internal fun rememberEtaFlowPhase(): Float {
    val transition = rememberInfiniteTransition(label = "eta_capsule_flow")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(EtaDesign.Motion.SLOW_MS * 5, easing = EtaDesign.Motion.Standard),
            RepeatMode.Reverse,
        ),
        label = "eta_capsule_flow_phase",
    ).value
}

/** 随相位流动的品牌线性渐变刷（像素坐标，供 drawBehind / draw* 使用）。 */
internal fun etaCapsuleFlowBrush(
    phase: Float,
    colors: List<Color> = EtaDesign.Brand.Gradient,
    width: Float,
    height: Float,
): Brush {
    val drift = phase * width * 0.55f
    return Brush.linearGradient(
        colors = colors,
        start = Offset(drift, 0f),
        end = Offset(width + drift, height),
    )
}

/** 横向流动渐变（细装饰线 / 分隔线）。 */
private fun etaCapsuleLineBrush(phase: Float, colors: List<Color>, width: Float): Brush =
    Brush.linearGradient(
        colors = colors,
        start = Offset(phase * width, 0f),
        end = Offset(width + phase * width, 0f),
    )

/** 竖向流动渐变（分区标题左侧强调条）。 */
private fun etaCapsuleVerticalBrush(phase: Float, colors: List<Color>, height: Float): Brush =
    Brush.linearGradient(
        colors = colors,
        start = Offset(0f, phase * height),
        end = Offset(0f, height + phase * height),
    )

/**
 * 发光 sparkle。[solidColor] 非空时用纯色（叠在渐变圆底上保证对比），否则用流动品牌渐变。
 */
@Composable
internal fun EtaCapsuleSparkle(modifier: Modifier = Modifier, solidColor: Color? = null) {
    val flow = rememberEtaFlowPhase()
    val path = remember { Path() }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        path.reset()
        path.moveTo(w * 0.5f, 0f)
        path.cubicTo(w * 0.58f, h * 0.32f, w * 0.68f, h * 0.42f, w, h * 0.5f)
        path.cubicTo(w * 0.68f, h * 0.58f, w * 0.58f, h * 0.68f, w * 0.5f, h)
        path.cubicTo(w * 0.42f, h * 0.68f, w * 0.32f, h * 0.58f, 0f, h * 0.5f)
        path.cubicTo(w * 0.32f, h * 0.42f, w * 0.42f, h * 0.32f, w * 0.5f, 0f)
        path.close()
        val brush = if (solidColor != null) {
            Brush.linearGradient(listOf(solidColor, solidColor))
        } else {
            etaCapsuleFlowBrush(flow, EtaDesign.Brand.Gradient, w, h)
        }
        drawPath(path, brush = brush, alpha = EtaDesign.Brand.GlowAlpha)
        drawPath(path, brush = brush)
    }
}

/** 细渐变装饰线（回复卡顶部 / 卡片分隔），横向铺满。 */
@Composable
internal fun EtaCapsuleGradientLine(
    modifier: Modifier = Modifier,
    thickness: Dp = 2.dp,
) {
    val flow = rememberEtaFlowPhase()
    Canvas(modifier) {
        val t = thickness.toPx().coerceAtMost(size.height)
        drawRoundRect(
            brush = etaCapsuleLineBrush(flow, EtaDesign.Brand.Gradient, size.width),
            topLeft = Offset(0f, (size.height - t) / 2f),
            size = androidx.compose.ui.geometry.Size(size.width, t),
            cornerRadius = CornerRadius(t / 2f),
        )
    }
}

/** 品牌色胶囊底状态标签：渐变实心底 + 高对比白字，绝不透明。 */
@Composable
internal fun EtaCapsuleStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    maxLines: Int = 1,
) {
    val flow = rememberEtaFlowPhase()
    Box(
        modifier = modifier
            .clip(CircleShape)
            .drawBehind {
                drawRoundRect(
                    brush = etaCapsuleFlowBrush(flow, EtaDesign.Brand.Gradient, size.width, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = fontSize,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分区标题：左侧竖向渐变强调条 + 品牌主色文字。 */
@Composable
internal fun EtaCapsuleSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    val flow = rememberEtaFlowPhase()
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(width = 3.dp, height = 16.dp)
                .drawBehind {
                    val w = size.width
                    drawRoundRect(
                        brush = etaCapsuleVerticalBrush(flow, EtaDesign.Brand.Gradient, size.height),
                        size = androidx.compose.ui.geometry.Size(w, size.height),
                        cornerRadius = CornerRadius(w / 2f),
                    )
                },
        )
        Text(text, color = EtaDesign.Brand.Violet, fontSize = 13.sp)
    }
}

private val capsuleDefaultSuggestions = listOf(
    "帮我总结当前屏幕内容",
    "这个页面怎么操作",
    "屏幕上有什么值得注意的信息",
)

/** 渐变描边推荐芯片：外层流动渐变描边 + 内层不透明实底，wrap-content 保持。 */
@Composable
internal fun EtaCapsuleSuggestions(
    onSuggestionClick: (String) -> Unit,
    colors: EtaVoicePanelColors,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    suggestions: List<String> = capsuleDefaultSuggestions,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(durationMillis = EtaDesign.Motion.FAST_MS, easing = EtaDesign.Motion.Standard),
        ) + fadeOut(tween(durationMillis = EtaDesign.Motion.FAST_MS, easing = EtaDesign.Motion.Standard)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val flow = rememberEtaFlowPhase()
            suggestions.take(4).forEachIndexed { index, suggestion ->
                val transform = remember(suggestion) { Animatable(0f) }
                val opacity = remember(suggestion) { Animatable(0f) }
                LaunchedEffect(visible) {
                    if (visible) {
                        transform.snapTo(0f)
                        opacity.snapTo(0f)
                        delay(900L + when (index) {
                            0 -> 200L
                            1 -> 117L
                            else -> 0L
                        })
                        coroutineScope {
                            launch { transform.animateTo(1f, EtaDesign.Motion.medium()) }
                            launch { opacity.animateTo(1f, EtaDesign.Motion.slow()) }
                        }
                    } else {
                        coroutineScope {
                            launch { transform.animateTo(0f, EtaDesign.Motion.fast()) }
                            launch { opacity.animateTo(0f, EtaDesign.Motion.fast()) }
                        }
                    }
                }
                val stroke = 1.6.dp
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .graphicsLayer {
                            alpha = opacity.value
                            scaleX = transform.value
                            scaleY = transform.value
                            translationY = (1f - transform.value) * 18.dp.toPx()
                        }
                        .drawBehind {
                            drawRoundRect(
                                brush = etaCapsuleFlowBrush(flow, EtaDesign.Brand.Gradient, size.width, size.height),
                                cornerRadius = CornerRadius(size.height / 2f),
                                style = Stroke(stroke.toPx()),
                            )
                        }
                        .padding(stroke)
                        .clip(CircleShape)
                        .background(colors.input)
                        .clickable(enabled = visible) { onSuggestionClick(suggestion) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = suggestion,
                        color = colors.inputPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 品牌渐变波形：三层正弦波叠加，随 [EtaDesign.Brand] 渐变流动并带低透明度发光底层。
 * 签名与原 EtaAssistantWave 完全一致，便于在面板里无缝替换。
 */
@Composable
internal fun EtaCapsuleWave(
    active: Boolean,
    level: () -> Float,
    modifier: Modifier = Modifier,
    energized: Boolean = true,
) {
    val currentActive = rememberUpdatedState(active)
    val currentEnergized = rememberUpdatedState(energized)
    val currentLevel = rememberUpdatedState(level)
    val flow = rememberEtaFlowPhase()
    var elapsed by remember { mutableFloatStateOf(0f) }
    var smoothed by remember { mutableFloatStateOf(0f) }
    var waveAlpha by remember { mutableFloatStateOf(0f) }
    val path = remember { Path() }
    LaunchedEffect(active) {
        var last = 0L
        while (isActive && (currentActive.value || waveAlpha > 0f)) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                elapsed += dt
                val target = if (currentActive.value) 1f else 0f
                waveAlpha += (target - waveAlpha) * (dt * 7f)
                val measured = if (currentActive.value && currentEnergized.value) {
                    currentLevel.value().coerceIn(0f, 1f)
                } else 0f
                smoothed += (measured - smoothed) * (dt * (if (measured > smoothed) 14f else 6f))
            }
            if (!currentActive.value && waveAlpha < 0.01f) waveAlpha = 0f
        }
    }
    Canvas(modifier.fillMaxWidth()) {
        if (waveAlpha <= 0f) return@Canvas
        val w = size.width
        val h = size.height
        val colors = EtaDesign.Brand.Gradient
        val amplitude = (h * 0.30f) * (if (currentEnergized.value) 1f + smoothed * 1.8f else 0.55f)
        val points = 48
        for (layer in 0..2) {
            path.reset()
            val baseY = h * (0.40f + layer * 0.10f)
            val phase = elapsed * (if (currentEnergized.value) 1.6f else 0.45f) + layer * 1.1f
            for (i in 0..points) {
                val fraction = i.toFloat() / points
                val envelope = abs(sin(fraction * PI.toFloat()))
                val y = baseY + (
                    sin(fraction * 10f + phase) * 0.72f +
                        sin(fraction * 19f - phase * 1.3f) * 0.28f
                    ) * amplitude * envelope
                val x = fraction * w
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val brush = etaCapsuleFlowBrush(flow, colors, w, h)
            // 低透明度发光底层：宽笔画打底，营造柔和光晕。
            drawPath(
                path,
                brush = brush,
                alpha = waveAlpha * (EtaDesign.Brand.GlowAlpha * 0.7f),
                style = Stroke(width = (16f + layer * 4f) * density, cap = StrokeCap.Round),
            )
            // 亮线：颜色随品牌渐变流动。
            drawPath(
                path,
                brush = brush,
                alpha = waveAlpha * (0.85f - layer * 0.18f),
                style = Stroke(width = (3.6f - layer * 0.7f) * density, cap = StrokeCap.Round),
            )
        }
    }
}
