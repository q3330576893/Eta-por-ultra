package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Other app surfaces retain their existing brand brush; settings use their local solid palette. */
internal fun etaBrandBrush(): Brush = Brush.linearGradient(EtaDesign.Brand.Gradient)
internal fun etaCoolBrush(): Brush = Brush.linearGradient(EtaDesign.Brand.CoolGradient)

@Composable
internal fun EtaStarCoreDot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(etaBrandBrush()),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.34f).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)))
    }
}

/** Retained for existing callers; settings section headings use numbered labels. */
@Composable
internal fun EtaGradientAccentBar(modifier: Modifier = Modifier) {
    Box(modifier.size(width = 3.dp, height = 15.dp).clip(RoundedCornerShape(1.5.dp)).background(etaBrandBrush()))
}

/** A quiet, fully opaque cover. Flexible height also accommodates large accessibility fonts. */
@Composable
internal fun EtaBrandHeroCard(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(0.5.dp, colors.dividerLine, shape)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(colors.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = "Eta Ultra", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                Text(
                    text = stringResource(R.string.settings_refinement_version, BuildConfig.VERSION_NAME),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_refinement_hero_title),
            modifier = Modifier.padding(top = 18.dp).semantics { heading() },
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_refinement_hero_summary),
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = colors.onSurfaceVariantSummary,
        )
    }
}
