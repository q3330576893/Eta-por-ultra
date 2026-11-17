package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Eta Ultra 统一设计语言（3.5.1 全量 UI 改版地基）。
 *
 * 所有共享组件从这里取圆角、间距、动效与层级数值，避免各页面各写一套导致视觉漂移。
 * 参考 Material 3 Expressive 的柔和圆角、清晰节奏与有意图的动效，同时保持与既有
 * Miuix 组件（Card / Preference / Button）的兼容。
 */
internal object EtaDesign {

    /** 圆角梯度：越大越靠近"表达型"胶囊。 */
    object Radius {
        val XS: Dp = 8.dp
        val SM: Dp = 12.dp
        val MD: Dp = 16.dp
        val LG: Dp = 24.dp
        val XL: Dp = 28.dp
        val Capsule: Dp = 50.dp
    }

    /** 8dp 基准的间距节奏。 */
    object Space {
        val XS: Dp = 4.dp
        val SM: Dp = 8.dp
        val MD: Dp = 12.dp
        val LG: Dp = 16.dp
        val XL: Dp = 24.dp
        val XXL: Dp = 32.dp
        val EmptyStateVertical: Dp = 40.dp
    }

    /** 有意图的动效：快进快出，强调层用慢速缓动。 */
    object Motion {
        const val FAST_MS = 120
        const val MEDIUM_MS = 220
        const val SLOW_MS = 350
        val Standard: Easing = FastOutSlowInEasing

        fun <T> fast(): AnimationSpec<T> = tween(FAST_MS, easing = Standard)
        fun <T> medium(): AnimationSpec<T> = tween(MEDIUM_MS, easing = Standard)
        fun <T> slow(): AnimationSpec<T> = tween(SLOW_MS, easing = Standard)
    }

    /** 通用组件尺寸。 */
    object Size {
        val IconSm: Dp = 20.dp
        val Icon: Dp = 24.dp
        val IconLg: Dp = 32.dp
        val RowMinHeight: Dp = 52.dp
        val ButtonMinHeight: Dp = 44.dp
        val CardMinHeight: Dp = 136.dp
        val DividerThickness: Dp = 0.33.dp
    }

    /** 卡片内边距，统一节奏。 */
    object Insets {
        val Card = PaddingValues(Space.LG)
        val ScreenHorizontal = Space.LG
        val GroupBottom = Space.LG
    }
}
