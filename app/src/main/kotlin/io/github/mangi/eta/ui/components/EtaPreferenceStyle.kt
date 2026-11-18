package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** Explicit opt-in: shared cards, dialogs and scaffolds keep the app theme elsewhere. */
internal val LocalEtaSettingsStyle = staticCompositionLocalOf { false }

internal object EtaPreferenceColors {
    val Blue = Color(0xFF0080FF)
    val Green = StatusSuccess
    val Orange = Color(0xFFFF7700)
    val Yellow = StatusWarning
}

internal object EtaPreferenceDefaults {
    val SidePadding = EtaDesign.Space.LG
    val GroupSpacing = EtaDesign.Space.LG
    val IconSize = 36.dp
    val IconTextGap = 14.dp
    val ContentStart = SidePadding + IconSize + IconTextGap
    fun contentStart(hasLeading: Boolean) = if (hasLeading) ContentStart else SidePadding
    val RowMinHeight = 68.dp
}

/** Grape in daylight, lavender at night. Only the settings subtree receives this palette. */
@Composable
internal fun EtaSettingsTheme(content: @Composable () -> Unit) {
    if (LocalEtaSettingsStyle.current) {
        content()
        return
    }
    val inherited = MiuixTheme.colorScheme
    val dark = inherited.background.luminance() < 0.5f
    val pureBlack = LocalAppearanceSettings.current.pureBlackEnabled && dark
    val accent = if (dark) Color(0xFFD2B8FA) else Color(0xFF64408D)
    val onAccent = if (dark) Color(0xFF30203F) else Color.White
    val background = when {
        pureBlack -> Color.Black
        dark -> Color(0xFF17131D)
        else -> Color(0xFFF5F2F8)
    }
    val card = if (dark) Color(0xFF231E2B) else Color(0xFFFFFDFF)
    val elevated = if (dark) Color(0xFF302938) else Color(0xFFF0EAF5)
    val tonal = if (dark) Color(0xFF3B2F4B) else Color(0xFFEDE3F6)
    val text = if (dark) Color(0xFFF1EAF7) else Color(0xFF292132)
    val secondaryText = if (dark) Color(0xFFC4B8CD) else Color(0xFF6B5E76)
    val outline = if (dark) Color(0xFF71627E) else Color(0xFF9888A5)
    val divider = if (dark) Color(0xFF3C3346) else Color(0xFFE8E0EE)
    val disabled = if (dark) Color(0xFF8F8399) else Color(0xFF94879D)
    val colors = inherited.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryVariant = tonal,
        onPrimaryVariant = accent,
        primaryContainer = tonal,
        onPrimaryContainer = text,
        disabledPrimary = elevated,
        disabledOnPrimary = disabled,
        disabledPrimaryButton = elevated,
        disabledOnPrimaryButton = disabled,
        disabledPrimarySlider = elevated,
        secondary = tonal,
        onSecondary = text,
        secondaryVariant = elevated,
        onSecondaryVariant = secondaryText,
        disabledSecondary = elevated,
        disabledOnSecondary = disabled,
        disabledSecondaryVariant = elevated,
        disabledOnSecondaryVariant = disabled,
        secondaryContainer = tonal,
        onSecondaryContainer = text,
        secondaryContainerVariant = elevated,
        onSecondaryContainerVariant = secondaryText,
        tertiaryContainer = elevated,
        onTertiaryContainer = text,
        tertiaryContainerVariant = tonal,
        background = background,
        onBackground = text,
        onBackgroundVariant = secondaryText,
        surface = background,
        onSurface = text,
        surfaceVariant = elevated,
        onSurfaceSecondary = secondaryText,
        onSurfaceVariantSummary = secondaryText,
        onSurfaceVariantActions = accent,
        disabledOnSurface = disabled,
        surfaceContainer = card,
        onSurfaceContainer = text,
        onSurfaceContainerVariant = secondaryText,
        surfaceContainerHigh = elevated,
        onSurfaceContainerHigh = text,
        surfaceContainerHighest = tonal,
        onSurfaceContainerHighest = text,
        outline = outline,
        dividerLine = divider,
        sliderKeyPoint = accent,
        sliderKeyPointForeground = onAccent,
        sliderBackground = tonal,
    )
    CompositionLocalProvider(LocalEtaSettingsStyle provides true) {
        MiuixTheme(colors = colors, content = content)
    }
}

/** Retain the inherited app palette; settings opt in through [EtaSettingsTheme]. */
@Composable
internal fun EtaPreferenceTheme(content: @Composable () -> Unit) = content()

@Composable
internal fun EtaPreferenceGroup(
    modifier: Modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding)
        .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
    insideMargin: PaddingValues = PaddingValues(0.dp),
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    EtaCard(modifier = modifier, insideMargin = insideMargin, colors = colors, content = content)
}

@Composable
internal fun EtaPreferenceGroupTitle(
    text: String,
    modifier: Modifier = Modifier,
    number: String? = null,
    description: String? = null,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (number != null) {
            Box(
                modifier = Modifier.size(28.dp)
                    .background(colors.primaryContainer, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = number, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(
                text = text,
                modifier = Modifier.semantics { heading() },
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
            )
            if (description != null) {
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
internal fun EtaPreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    val settings = LocalEtaSettingsStyle.current
    val colors = MiuixTheme.colorScheme
    // LanguagePreference is shared outside settings; adapt its legacy glyph only in this subtree.
    val glyph = if (settings && icon.name.substringAfterLast('.') == "Language") Icons.Outlined.Language else icon
    val resolvedTint = when {
        !enabled -> colors.disabledOnSurface
        settings -> colors.primary
        else -> tint
    }
    Box(
        modifier = modifier.size(if (settings) EtaPreferenceDefaults.IconSize else EtaDesign.Size.Icon)
            .then(
                if (settings) Modifier.background(
                    if (enabled) colors.primaryContainer else colors.surfaceContainerHigh,
                    RoundedCornerShape(12.dp),
                ) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(if (settings) 20.dp else EtaDesign.Size.Icon),
            tint = resolvedTint,
        )
    }
}

@Composable
internal fun EtaPreferenceDivider(hasLeading: Boolean = true, modifier: Modifier = Modifier) {
    val settings = LocalEtaSettingsStyle.current
    val start = if (settings) EtaPreferenceDefaults.contentStart(hasLeading) else {
        EtaPreferenceDefaults.SidePadding + if (hasLeading) EtaDesign.Size.Icon + EtaDesign.Space.LG else 0.dp
    }
    HorizontalDivider(
        modifier = modifier.padding(start = start, end = EtaPreferenceDefaults.SidePadding),
        thickness = if (settings) 0.5.dp else 0.33.dp,
        color = if (settings) MiuixTheme.colorScheme.dividerLine else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f),
    )
}
