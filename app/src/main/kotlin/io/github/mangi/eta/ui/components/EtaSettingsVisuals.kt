package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.BuildConfig
import top.yukonga.miuix.kmp.basic.Text

/**
 * Eta Ultra 品牌视觉原语（3.5.2 大改版）。
 *
 * 这里集中承载"一眼可辨"的品牌元素：主渐变笔刷、设置页 Hero 横幅、图标渐变底、
 * 分组标题竖条与顶栏星核 dot。所有新动效统一走 [EtaDesign.Motion]，颜色统一取
 * [EtaDesign.Brand]，不触碰任何导航/路由/点击/搜索逻辑，也不改字符串资源。
 */

/** 主品牌渐变笔刷：靛蓝 → 紫 → 品红。 */
internal fun etaBrandBrush(): Brush = Brush.linearGradient(EtaDesign.Brand.Gradient)

/** 冷静渐变笔刷：青 → 靛蓝（用于次要强调、菜单图标底）。 */
internal fun etaCoolBrush(): Brush = Brush.linearGradient(EtaDesign.Brand.CoolGradient)

/**
 * 偏好项图标的渐变底：给裸图标垫一层其 tint 的浅色渐变圆角方块。
 *
 * 通过 [drawBehind] 外扩 4dp 绘制，视觉上是 32dp 的小方块，但布局占位仍是原本的
 * IconSize(24dp)，因此不会破坏 EtaPreferenceRow 的内容对齐与分割线起始位置。
 */
internal fun Modifier.etaIconBackdrop(tint: Color, enabled: Boolean): Modifier = this.drawBehind {
    val grow = 4.dp.toPx()
    val alpha = if (enabled) 0.20f else 0.06f
    val brush = Brush.linearGradient(
        listOf(tint.copy(alpha = alpha), tint.copy(alpha = alpha * 0.35f)),
    )
    drawRoundRect(
        brush = brush,
        topLeft = Offset(-grow, -grow),
        size = Size(size.width + grow * 2, size.height + grow * 2),
        cornerRadius = CornerRadius(9.dp.toPx()),
    )
}

/** 品牌星核 dot：渐变实心小圆 + 内嵌高光，用于顶栏应用标识/标题旁。 */
@Composable
internal fun EtaStarCoreDot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(etaBrandBrush()),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.34f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.9f)),
        )
    }
}

/** 分组标题左侧的渐变竖条装饰。 */
@Composable
internal fun EtaGradientAccentBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 3.dp, height = 15.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(etaBrandBrush()),
    )
}

/**
 * 设置页品牌 Hero 横幅：进设置第一眼看到的渐变主视觉。
 *
 * 渐变背景 + 柔和发光底座 + "Eta Ultra"标识 + slogan + 版本 chip，圆角 XL，
 * 带一次性的淡入上浮入场动效（[EtaDesign.Motion.slow]）。纯视觉，无点击/路由。
 */
@Composable
internal fun EtaBrandHeroCard(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val entranceAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = EtaDesign.Motion.slow(),
        label = "eta_hero_alpha",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 24f,
        animationSpec = EtaDesign.Motion.slow(),
        label = "eta_hero_slide",
    )
    val glow by animateFloatAsState(
        targetValue = if (visible) EtaDesign.Brand.GlowAlpha else 0f,
        animationSpec = EtaDesign.Motion.slow(),
        label = "eta_hero_glow",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = EtaDesign.Space.LG, vertical = EtaDesign.Space.SM),
        contentAlignment = Alignment.Center,
    ) {
        // 发光底座：同款渐变、低透明度、比主体四周各大几 dp，制造柔和光晕。
        Box(
            Modifier
                .fillMaxWidth()
                .height(118.dp)
                .graphicsLayer { alpha = glow }
                .clip(RoundedCornerShape(EtaDesign.Radius.XL))
                .background(etaBrandBrush()),
        )
        // 主体横幅。
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp)
                .height(109.dp)
                .graphicsLayer {
                    alpha = entranceAlpha
                    translationY = slide
                }
                .clip(RoundedCornerShape(EtaDesign.Radius.XL))
                .background(etaBrandBrush())
                .padding(horizontal = EtaDesign.Space.XL, vertical = EtaDesign.Space.LG),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 品牌星核徽标。
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(EtaDesign.Radius.MD))
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    EtaStarCoreDot(size = 22.dp)
                }
                Spacer(Modifier.width(EtaDesign.Space.MD))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Eta Ultra",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "让智能，随心而动",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                }
                // 版本 chip。
                Box(
                    Modifier
                        .clip(RoundedCornerShape(EtaDesign.Radius.Capsule))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 11.sp,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
