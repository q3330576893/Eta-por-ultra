package io.github.mangi.eta.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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

/** 设置首页的玻璃身份卡。高度随文字缩放，不固定横幅高度。 */
@Composable
internal fun EtaBrandHeroCard(modifier: Modifier = Modifier) {
    val colors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 8.dp)
            .etaSettingsGlass(shape = RoundedCornerShape(28.dp), emphasized = true)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图形徽标与文字分层；玻璃只采样页面背景，不模糊前景。
        androidx.compose.foundation.Canvas(
            Modifier.size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF398BFF), Color(0xFF4664DA)))),
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension * 0.4f, center = center)
            drawCircle(
                Color.White.copy(alpha = 0.92f), radius = size.minDimension * 0.26f,
                center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
            )
            drawCircle(Color.White, radius = size.minDimension * 0.08f, center = center)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Eta Ultra",
                fontWeight = FontWeight.Bold,
                fontSize = 25.sp,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(io.github.mangi.eta.R.string.settings_glass_tagline),
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primary,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(colors.primary.copy(alpha = 0.08f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}
