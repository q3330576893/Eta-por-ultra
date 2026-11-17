package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
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

    /**
     * Eta 品牌视觉（3.5.2 大改版）：从"跟随系统"升级为有辨识度的品牌语言。
     * 主色是一组靛蓝→品红的电光渐变，配合发光与玻璃层，让界面一眼可辨。
     */
    object Brand {
        val Indigo = Color(0xFF5B5BF6)
        val Violet = Color(0xFF8B5CF6)
        val Magenta = Color(0xFFEC4899)
        val Cyan = Color(0xFF22D3EE)
        /** 主渐变色标（用于按钮、头像环、品牌头图、波形）。 */
        val Gradient = listOf(Indigo, Violet, Magenta)
        /** 冷静渐变（用于次要强调、图表、系统反馈）。 */
        val CoolGradient = listOf(Cyan, Indigo)
        /** 发光/高亮只有低透明度，避免刺眼。 */
        const val GlowAlpha = 0.35f
        const val GlassAlpha = 0.72f
    }
}
