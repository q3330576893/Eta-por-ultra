package io.github.mangi.eta.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 冷启动品牌开屏：细环收拢、字距收紧、细线生长，最后整体淡出。
 *
 * 只做视觉过渡，不阻塞任何初始化；[onFinished] 在淡出结束后调用一次。
 * 时长控制在 1 秒左右，避免拖慢进入首页的手感。
 */
@Composable
internal fun StartupBrandOverlay(onFinished: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val overlayAlpha = remember { Animatable(1f) }
    val ringProgress = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val titleTracking = remember { Animatable(0.62f) }
    val lineProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { ringProgress.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
            launch {
                delay(110)
                titleAlpha.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
            }
            launch {
                delay(110)
                titleTracking.animateTo(0.26f, tween(620, easing = FastOutSlowInEasing))
            }
            launch {
                delay(240)
                lineProgress.animateTo(1f, tween(520, easing = LinearOutSlowInEasing))
            }
        }
        delay(150)
        overlayAlpha.animateTo(0f, tween(320, easing = FastOutLinearInEasing))
        onFinished()
    }

    if (overlayAlpha.value <= 0f) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val stroke = 1.6.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                // 底环：极淡，给收拢的弧一个参照。
                drawArc(
                    color = colors.primary.copy(alpha = 0.14f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // 主弧：从 -90° 起顺时针收拢到整圈。
                drawArc(
                    color = colors.primary,
                    startAngle = -90f,
                    sweepAngle = 360f * ringProgress.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // 中心点：随弧收拢淡入，作为视觉落点。
                drawCircle(
                    color = colors.primary,
                    radius = 3.dp.toPx() * ringProgress.value,
                    center = center,
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "ETA ULTRA",
                style = MiuixTheme.textStyles.body1,
                color = colors.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                letterSpacing = titleTracking.value.em,
                modifier = Modifier.graphicsLayer { alpha = titleAlpha.value },
            )

            Spacer(modifier = Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .width(96.dp * lineProgress.value)
                    .height(1.dp)
                    .background(colors.primary.copy(alpha = 0.45f)),
            )
        }
    }
}
