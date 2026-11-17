package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Eta Ultra 3.5.2 主界面「一眼可辨」视觉件库。
 *
 * 只负责呈现，不承载任何交互/状态逻辑。所有色标、圆角、间距、动效都取自 [EtaDesign]，
 * 保证与主界面其它组件同一套品牌语言。颜色/渐变用 [Brush] 绘制，动效统一走
 * [EtaDesign.Motion]，避免各处再写一套魔法数字。
 */
internal object EtaChatVisuals {

    /** 统一的品牌线性渐变笔刷；[alpha] 用于极淡背景与描边。 */
    fun gradient(alpha: Float = 1f): Brush = Brush.linearGradient(
        colors = EtaDesign.Brand.Gradient.map { it.copy(alpha = alpha) },
    )

    /** 冷静渐变（青→靛），用于次要强调。 */
    fun coolGradient(alpha: Float = 1f): Brush = Brush.linearGradient(
        colors = EtaDesign.Brand.CoolGradient.map { it.copy(alpha = alpha) },
    )
}

/** 品牌渐变描边：卡片、建议卡、状态胶囊共用。 */
internal fun Modifier.gradientBorder(
    width: Dp,
    shape: Shape,
    alpha: Float = 1f,
): Modifier = border(width, EtaChatVisuals.gradient(alpha), shape)

/**
 * 用户气泡极淡的品牌色渐变水洗：透明度很低且末端透明，
 * 叠在原容器底色上即可，不会在圆角/气泡尾角处出现色块硬边。
 */
internal fun Modifier.userBubbleTint(): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                EtaDesign.Brand.Indigo.copy(alpha = 0.02f),
                EtaDesign.Brand.Violet.copy(alpha = 0.10f),
                EtaDesign.Brand.Magenta.copy(alpha = 0.08f),
                Color.Transparent,
            ),
        ),
    )
}

/** 输入框聚焦光晕：[progress] 0..1，由调用方用 EtaDesign.Motion 动画驱动。 */
internal fun Modifier.focusGlow(
    progress: Float,
    radius: Dp = EtaDesign.Radius.MD,
): Modifier = this.drawWithContent {
    drawContent()
    if (progress > 0f) {
        drawRoundRect(
            brush = EtaChatVisuals.gradient(EtaDesign.Brand.GlowAlpha * progress),
            topLeft = androidx.compose.ui.geometry.Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }
}

/**
 * 顶栏下方的极细品牌渐变分隔线：两端透明、中段点亮品牌色，1dp 不遮挡内容。
 */
@Composable
internal fun EtaBrandDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        EtaDesign.Brand.Indigo.copy(alpha = 0.55f),
                        EtaDesign.Brand.Violet,
                        EtaDesign.Brand.Magenta.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/**
 * 空状态英雄区：光晕 + 旋转渐变圆环 + 中央发光星核。
 *
 * 圆环用无限旋转、星核用缓慢呼吸，两者都走 [EtaDesign.Motion] 的时长与缓动；
 * 纯绘制、不依赖 CDP/执行环境，滚动离开组合即释放帧时钟。
 */
@Composable
internal fun EtaBrandHero(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eta_hero")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = EtaDesign.Motion.SLOW_MS * 3,
                easing = EtaDesign.Motion.Standard,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eta_hero_pulse",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = EtaDesign.Motion.SLOW_MS * 14,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "eta_hero_spin",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = pulse
                    scaleY = pulse
                }
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = min(cx, cy)

                    // 外圈柔光：多层递减透明度的同心圆叠出光晕。
                    for (layer in 6 downTo 1) {
                        val spread = layer / 6f
                        drawCircle(
                            color = EtaDesign.Brand.Violet.copy(
                                alpha = EtaDesign.Brand.GlowAlpha * 0.16f * (1.35f - spread),
                            ),
                            radius = radius * (0.5f + spread * 0.48f),
                            center = Offset(cx, cy),
                        )
                    }

                    // 渐变圆环。
                    drawCircle(
                        brush = Brush.linearGradient(
                            colors = EtaDesign.Brand.Gradient,
                            start = Offset(cx - radius, cy - radius),
                            end = Offset(cx + radius, cy + radius),
                        ),
                        radius = radius * 0.66f,
                        center = Offset(cx, cy),
                        style = Stroke(width = radius * 0.035f),
                    )

                    // 中央发光星核。
                    val coreRadius = radius * 0.44f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                EtaDesign.Brand.Cyan,
                                EtaDesign.Brand.Indigo,
                                EtaDesign.Brand.Magenta.copy(alpha = 0f),
                            ),
                            center = Offset(cx, cy),
                            radius = coreRadius,
                        ),
                        radius = coreRadius,
                        center = Offset(cx, cy),
                    )
                },
        )
    }
}

/**
 * 渐变环头像/标识：给助手头像或任意小标识套一圈品牌渐变环。
 * 环画在内容背后且略微越界，半径取尺寸的一半，因此只露出外半圈，不裁内容。
 */
@Composable
internal fun EtaGradientRing(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.drawBehind {
            drawCircle(
                brush = EtaChatVisuals.gradient(),
                radius = min(size.width, size.height) / 2f,
                style = Stroke(width = 2.dp.toPx()),
            )
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}
