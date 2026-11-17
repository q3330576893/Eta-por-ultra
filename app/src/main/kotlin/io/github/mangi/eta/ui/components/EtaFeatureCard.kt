package io.github.mangi.eta.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
        modifier = modifier
            .heightIn(min = EtaDesign.Size.CardMinHeight)
            // 品牌渐变描边：顶部主渐变细边，一眼区别于普通卡片。
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(EtaDesign.Brand.Gradient),
                shape = RoundedCornerShape(EtaCardDefaults.CornerRadius),
            ),
        insideMargin = PaddingValues(EtaDesign.Space.LG),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = EtaDesign.Size.RowMinHeight - EtaDesign.Space.MD), verticalAlignment = Alignment.CenterVertically) {
            // 图标槽加品牌渐变底盘，让特性卡图标也有相同品牌语言。
            Box(
                Modifier
                    .padding(end = EtaDesign.Space.MD)
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(EtaDesign.Brand.CoolGradient),
                        shape = RoundedCornerShape(EtaDesign.Radius.SM),
                    )
                    .padding(6.dp),
            ) { icon() }
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
