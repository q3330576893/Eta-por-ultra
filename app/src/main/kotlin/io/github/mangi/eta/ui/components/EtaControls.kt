package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal object EtaControlDefaults {
    val ButtonCornerRadius = EtaDesign.Radius.LG
    val ButtonMinHeight = EtaDesign.Size.ButtonMinHeight
    val ButtonPadding = PaddingValues(horizontal = EtaDesign.Space.LG, vertical = 10.dp)
    val DialogInsideMargin = DpSize(24.dp, 24.dp)
    val DialogOutsideMargin = DpSize(16.dp, 16.dp)
}

@Composable
internal fun EtaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = EtaControlDefaults.ButtonCornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = EtaControlDefaults.ButtonMinHeight,
    colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = EtaControlDefaults.ButtonPadding,
    textStyle: TextStyle? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
) {
    val glass = LocalSettingsGlassActive.current
    TextButton(
        text = text, onClick = onClick, modifier = modifier, enabled = enabled,
        cornerRadius = if (glass) 24.dp else cornerRadius,
        minWidth = minWidth, minHeight = if (glass && minHeight < 44.dp) 44.dp else minHeight,
        colors = colors, insideMargin = insideMargin, textStyle = textStyle,
        interactionSource = interactionSource, indication = indication,
    )
}

@Composable
internal fun EtaWindowDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    EtaPreferenceTheme {
        WindowDialog(
            show = show, title = title, summary = summary,
            modifier = if (LocalSettingsGlassActive.current) modifier.border(1.dp, etaSettingsGlassPalette().outline, RoundedCornerShape(28.dp)) else modifier,
            backgroundColor = if (LocalSettingsGlassActive.current) etaSettingsGlassPalette().fallbackSurface else MiuixTheme.colorScheme.surfaceContainer,
            onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
            cornerRadius = if (LocalSettingsGlassActive.current) 28.dp else DialogDefaults.CornerRadius,
            insideMargin = EtaControlDefaults.DialogInsideMargin,
            outsideMargin = EtaControlDefaults.DialogOutsideMargin,
            maxWidth = DialogDefaults.MaxWidth,
            content = content,
        )
    }
}

@Composable
internal fun EtaOverlayDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    EtaPreferenceTheme {
        OverlayDialog(
            show = show, title = title, summary = summary,
            modifier = if (LocalSettingsGlassActive.current) modifier.border(1.dp, etaSettingsGlassPalette().outline, RoundedCornerShape(28.dp)) else modifier,
            backgroundColor = if (LocalSettingsGlassActive.current) etaSettingsGlassPalette().fallbackSurface else MiuixTheme.colorScheme.surfaceContainer,
            onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
            cornerRadius = if (LocalSettingsGlassActive.current) 28.dp else DialogDefaults.CornerRadius,
            insideMargin = EtaControlDefaults.DialogInsideMargin,
            outsideMargin = EtaControlDefaults.DialogOutsideMargin,
            maxWidth = DialogDefaults.MaxWidth,
            content = content,
        )
    }
}

@Composable
internal fun EtaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalSettingsGlassActive.current) {
        val progress by animateFloatAsState(
            targetValue = if (checked) 1f else 0f, animationSpec = EtaDesign.Motion.medium(), label = "settings_switch",
        )
        val colors = MiuixTheme.colorScheme
        val track = if (checked) colors.primary else colors.onSurfaceVariantSummary.copy(alpha = 0.26f)
        Canvas(
            modifier.size(51.dp, 44.dp).then(
                if (onCheckedChange != null) Modifier.toggleable(
                    value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange,
                ) else Modifier,
            ),
        ) {
            val h = 31.dp.toPx()
            val w = 51.dp.toPx()
            val x = (size.width - w) / 2f
            val y = (size.height - h) / 2f
            val a = if (enabled) 1f else 0.38f
            drawRoundRect(track.copy(alpha = track.alpha * a), Offset(x,y), Size(w,h), CornerRadius(h/2f))
            val radius = 13.5.dp.toPx()
            val visualProgress = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) 1f - progress else progress
            val center = Offset(x + h/2f + (w-h)*visualProgress, y+h/2f)
            drawCircle(Color.Black.copy(alpha = 0.13f*a), radius, center + Offset(0f,1.dp.toPx()))
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFF0F3F8)), center = center, radius = radius), radius, center, alpha = a)
        }
        return
    }
    Switch(
    checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
    modifier = modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = 44.dp.roundToPx()
        val height = 24.dp.roundToPx()
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
                scaleX = width.toFloat() / placeable.width
                scaleY = height.toFloat() / placeable.height
            }
        }
    },
)
}
