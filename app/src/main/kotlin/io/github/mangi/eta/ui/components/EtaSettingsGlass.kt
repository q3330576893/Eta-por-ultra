package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Only settings page descendants opt in; shared chat cards keep their original material. */
internal val LocalSettingsGlassActive = staticCompositionLocalOf { false }

@Immutable
internal data class EtaSettingsGlassPalette(
    val background: Color,
    val surfaceTint: Color,
    val emphasizedTint: Color,
    val fallbackSurface: Color,
    val outline: Color,
    val shadow: Color,
    val onSurface: Color,
    val secondaryText: Color,
)

private data class SettingsGlassContext(
    val backdrop: LayerBackdrop?,
    val palette: EtaSettingsGlassPalette,
)

private val LocalSettingsGlassContext = staticCompositionLocalOf<SettingsGlassContext?> { null }

@Composable
internal fun etaSettingsGlassPalette(): EtaSettingsGlassPalette {
    LocalSettingsGlassContext.current?.let { return it.palette }
    val colors = MiuixTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return EtaSettingsGlassPalette(
        background = if (dark) Color(0xFF101622) else Color(0xFFEEF3FA),
        surfaceTint = if (dark) Color(0xB3242D40) else Color(0xA6FFFFFF),
        emphasizedTint = if (dark) Color(0xD12B354C) else Color(0xD1FFFFFF),
        fallbackSurface = if (dark) Color(0xF2242C3B) else Color(0xF5FFFFFF),
        outline = if (dark) Color(0x35DDE8FF) else Color(0xD9FFFFFF),
        shadow = if (dark) Color(0x33000000) else Color(0x1A374875),
        onSurface = colors.onSurface,
        secondaryText = colors.onSurfaceVariantSummary,
    )
}

/**
 * Static source-backed material. The sampled layer contains only the background sibling below:
 * never the scaffold, a card, or its text. Nesting reuses the nearest source rather than recording it.
 */
@Composable
internal fun EtaSettingsGlassPage(content: @Composable () -> Unit) {
    if (LocalSettingsGlassActive.current) {
        content()
        return
    }
    val palette = etaSettingsGlassPalette()
    val dark = palette.background.luminance() < 0.5f
    val blurEnabled = LocalBlurEnabled.current && isRuntimeShaderSupported()
    val backdrop = if (blurEnabled) rememberLayerBackdrop { drawContent() } else null
    val context = SettingsGlassContext(backdrop, palette)
    CompositionLocalProvider(
        LocalSettingsGlassActive provides true,
        LocalSettingsGlassContext provides context,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .matchParentSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .drawWithCache {
                        val wash = Brush.linearGradient(
                            colors = if (dark) {
                                listOf(Color(0xFF101622), Color(0xFF1C2034), Color(0xFF172637))
                            } else {
                                listOf(Color(0xFFF1F6FC), Color(0xFFE9EDF9), Color(0xFFF2F5FC))
                            },
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        )
                        val radius = maxOf(size.width * 0.82f, size.height * 0.46f).coerceAtLeast(1f)
                        val blueCenter = Offset(size.width * 0.02f, size.height * 0.14f)
                        val lilacCenter = Offset(size.width * 0.94f, size.height * 0.48f)
                        val blue = Brush.radialGradient(
                            listOf(
                                if (dark) Color(0x553B74AC) else Color(0x757CBDF3),
                                Color.Transparent,
                            ),
                            center = blueCenter,
                            radius = radius,
                        )
                        val lilac = Brush.radialGradient(
                            listOf(
                                if (dark) Color(0x4D8272B7) else Color(0x70B9A9EC),
                                Color.Transparent,
                            ),
                            center = lilacCenter,
                            radius = radius * 1.08f,
                        )
                        onDrawBehind {
                            drawRect(wash)
                            drawCircle(blue, radius, blueCenter)
                            drawCircle(lilac, radius * 1.08f, lilacCenter)
                        }
                    },
            )
            content()
        }
    }
}

/** Material-only modifier: blur draws a sampled background and leaves child content sharp. */
@Composable
internal fun Modifier.etaSettingsGlass(
    shape: Shape = RoundedCornerShape(24.dp),
    emphasized: Boolean = false,
): Modifier {
    val context = LocalSettingsGlassContext.current ?: return this
    val palette = context.palette
    val material = if (context.backdrop != null) {
        Modifier.textureBlur(
            backdrop = context.backdrop,
            shape = shape,
            blurRadius = if (emphasized) 32f else 24f,
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(if (emphasized) palette.emphasizedTint else palette.surfaceTint),
                ),
            ),
        )
    } else {
        Modifier.background(palette.fallbackSurface, shape)
    }
    return this
        .shadow(
            elevation = if (emphasized) 8.dp else 4.dp,
            shape = shape,
            clip = false,
            ambientColor = palette.shadow,
            spotColor = palette.shadow,
        )
        .clip(shape)
        .then(material)
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(palette.outline, palette.outline.copy(alpha = palette.outline.alpha * 0.3f)),
            ),
            shape = shape,
        )
}

/** Edge-to-edge top bars share the source without a card shadow or a rounded clipping boundary. */
@Composable
internal fun EtaSettingsGlassNavigation(content: @Composable () -> Unit) {
    val context = LocalSettingsGlassContext.current
    if (context == null) {
        content()
        return
    }
    val modifier = if (context.backdrop != null) {
        Modifier.textureBlur(
            backdrop = context.backdrop,
            shape = androidx.compose.ui.graphics.RectangleShape,
            blurRadius = 28f,
            colors = BlurColors(
                blendColors = listOf(BlendColorEntry(context.palette.surfaceTint)),
            ),
        )
    } else {
        Modifier.background(context.palette.fallbackSurface)
    }
    Box(modifier) { content() }
}
