package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/** 二级页面统一返回按钮；设置页使用圆形玻璃，保留语义、RTL 与点击回调。 */
@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalSettingsGlassActive.current
    val buttonModifier = if (glass) {
        modifier
            .padding(horizontal = 6.dp)
            .size(44.dp)
            .etaSettingsGlass(shape = CircleShape)
    } else {
        modifier
    }
    val icon: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            modifier = if (glass) Modifier.size(21.dp) else Modifier,
        )
    }
    if (glass) {
        IconButton(
            onClick = onClick,
            modifier = buttonModifier,
            backgroundColor = Color.Transparent,
            content = icon,
        )
    } else {
        IconButton(onClick = onClick, modifier = modifier, content = icon)
    }
}
