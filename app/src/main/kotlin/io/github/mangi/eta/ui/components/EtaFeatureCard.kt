package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun EtaFeatureCard(
    title: String,
    summary: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    EtaCard(
        modifier = modifier.heightIn(min = EtaDesign.Size.CardMinHeight),
        insideMargin = PaddingValues(EtaDesign.Space.LG),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = EtaDesign.Size.RowMinHeight - EtaDesign.Space.MD), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        Spacer(Modifier.height(EtaDesign.Space.SM))
        Text(
            text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(EtaDesign.Space.XS))
        Text(
            text = summary, style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        footer?.invoke(this)
    }
}
