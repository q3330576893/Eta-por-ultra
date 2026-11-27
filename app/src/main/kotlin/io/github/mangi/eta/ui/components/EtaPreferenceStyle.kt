package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置首页与二级骨架共用中性主题和独立背景材质；重复嵌套复用现有背景源。 */
@Composable
internal fun EtaSettingsTheme(content: @Composable () -> Unit) {
    EtaPreferenceTheme { EtaSettingsGlassPage(content) }
}

internal object EtaPreferenceColors {
    val Blue = Color(0xFF0080FF)
    val Green = StatusSuccess
    val Orange = Color(0xFFFF7700)
    val Yellow = StatusWarning
}

internal object EtaPreferenceDefaults {
    val SidePadding = EtaDesign.Space.LG
    val GroupSpacing = EtaDesign.Space.LG
    val IconSize = EtaDesign.Size.Icon
    val IconTextGap = EtaDesign.Space.LG
    val ContentStart = SidePadding + IconSize + IconTextGap
    fun contentStart(hasLeading: Boolean) = if (hasLeading) ContentStart else SidePadding
    val RowMinHeight = EtaDesign.Size.RowMinHeight
}

@Composable
internal fun EtaPreferenceTheme(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val appearance = LocalAppearanceSettings.current
    val dark = colors.background.luminance() < 0.5f
    val pageColors = colors.copy(
        background = if (dark) Color(0xFF101622) else Color(0xFFEEF3FA),
        surface = if (dark) Color(0xFF202938) else Color(0xFFFAFCFF),
        primary = if (appearance.monetEnabled) colors.primary else EtaPreferenceColors.Blue,
    )
    MiuixTheme(colors = pageColors, content = content)
}

@Composable
internal fun EtaPreferenceGroup(
    modifier: Modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding).padding(bottom = EtaPreferenceDefaults.GroupSpacing),
    insideMargin: PaddingValues = PaddingValues(0.dp),
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    EtaCard(modifier = modifier, insideMargin = insideMargin, colors = colors, content = content)
}

@Composable
internal fun EtaPreferenceGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 30.dp, end = 30.dp, top = 12.dp, bottom = 8.dp),
        style = MiuixTheme.textStyles.subtitle.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
internal fun EtaPreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    val tileColor = if (tint == colors.onBackground) colors.primary else tint
    val disabledColor = colors.disabledOnSurface
    val glyphColor = if (tileColor.luminance() > 0.52f) Color(0xFF17243B) else Color.White
    Box(
        modifier = modifier
            .size(EtaPreferenceDefaults.IconSize)
            .background(
                color = if (enabled) tileColor else disabledColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(7.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (enabled) glyphColor else disabledColor,
        )
    }
}

@Composable
internal fun EtaPreferenceDivider(hasLeading: Boolean = true, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = EtaPreferenceDefaults.contentStart(hasLeading), end = EtaPreferenceDefaults.SidePadding),
        thickness = 0.33.dp,
        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f),
    )
}
