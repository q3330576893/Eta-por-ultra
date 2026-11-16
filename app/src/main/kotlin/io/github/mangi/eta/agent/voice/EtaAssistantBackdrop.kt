package io.github.mangi.eta.agent.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.sin

/** Shared accents keep the backdrop, waveform and controls in the same color family. */
internal object EtaAssistantPalette {
    val Blue = Color(0xFF4285F4)
    val Red = Color(0xFFEA4335)
    val Yellow = Color(0xFFFBBC04)
    val Green = Color(0xFF34A853)
    val Violet = Color(0xFFA78BFA)
    val WaveColors = listOf(Blue, Red, Yellow, Green, Blue)
}

@Composable
internal fun EtaAssistantBackdrop(
    dark: Boolean,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "assistant_ambient")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "assistant_ambient_drift",
    )
    val background = remember(dark) {
        if (dark) listOf(Color(0xFF10131C), Color(0xFF171B2C), Color(0xFF24233B))
        else listOf(Color(0xFFF9FAFF), Color(0xFFF0F3FF), Color(0xFFE8E9FA))
    }
    Canvas(modifier.fillMaxSize()) {
        val opacity = progress().coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(background), alpha = opacity)
        val travel = sin(drift * Math.PI).toFloat()
        val blueCenter = Offset(size.width * (0.02f + drift * 0.18f), size.height * 0.72f)
        val violetCenter = Offset(size.width * (0.94f - drift * 0.14f), size.height * 0.46f)
        val warmCenter = Offset(size.width * 0.80f, size.height * (1.04f - travel * 0.04f))
        fun glow(center: Offset, color: Color, radius: Float, strength: Float) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), center, radius),
                radius = radius,
                center = center,
                alpha = opacity,
            )
        }
        glow(blueCenter, EtaAssistantPalette.Blue, size.maxDimension * 0.72f, if (dark) 0.20f else 0.14f)
        glow(violetCenter, EtaAssistantPalette.Violet, size.maxDimension * 0.64f, if (dark) 0.15f else 0.12f)
        glow(warmCenter, EtaAssistantPalette.Red, size.maxDimension * 0.44f, if (dark) 0.10f else 0.07f)
    }
}

@Composable
internal fun EtaAssistantSparkle(modifier: Modifier = Modifier) {
    val path = remember { Path() }
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        path.reset()
        path.moveTo(width * 0.5f, 0f)
        path.cubicTo(width * 0.58f, height * 0.32f, width * 0.68f, height * 0.42f, width, height * 0.5f)
        path.cubicTo(width * 0.68f, height * 0.58f, width * 0.58f, height * 0.68f, width * 0.5f, height)
        path.cubicTo(width * 0.42f, height * 0.68f, width * 0.32f, height * 0.58f, 0f, height * 0.5f)
        path.cubicTo(width * 0.32f, height * 0.42f, width * 0.42f, height * 0.32f, width * 0.5f, 0f)
        path.close()
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                listOf(EtaAssistantPalette.Blue, EtaAssistantPalette.Violet, EtaAssistantPalette.Red),
            ),
        )
    }
}
