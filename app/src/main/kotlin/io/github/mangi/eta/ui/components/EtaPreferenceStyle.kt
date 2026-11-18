package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 兼容透传：设置页视觉已恢复为品牌造型，此入口只保留统一包装点，不注入任何主题色。
 */
@Composable
internal fun EtaSettingsTheme(content: @Composable () -> Unit) = content()

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
    val pageColors = if (!appearance.monetEnabled && colors.background.luminance() > 0.5f) {
        colors.copy(background = Color(0xFFF0F1F2), primary = EtaPreferenceColors.Blue)
    } else {
        colors
    }
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
    Row(
        modifier = modifier.padding(start = 26.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌渐变竖条：分组标题一眼可辨。
        EtaGradientAccentBar()
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
internal fun EtaPreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    // 满幅轮廓稍作光学校正，但所有图标都占相同宽度，保证正文和分割线对齐。
    val glyphSize = when (icon) {
        Icons.Rounded.Extension, Icons.Rounded.TheaterComedy, Icons.AutoMirrored.Rounded.MenuBook -> 22.dp
        else -> EtaPreferenceDefaults.IconSize
    }
    Box(
        modifier = modifier
            .size(EtaPreferenceDefaults.IconSize)
            .etaIconBackdrop(tint = tint, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSurface,
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
