package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置列表页骨架：独立背景玻璃、手机折叠大标题、宽屏小标题、横屏安全区、
 * 宽屏内容居中、滚动边界触感与越界回弹统一由此提供。
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    compactTitle: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    EtaPreferenceTheme {
        EtaSettingsGlassPage {
            val scrollBehavior = MiuixScrollBehavior()
            Scaffold(
                modifier = modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {
                    EtaSettingsGlassNavigation {
                        BrandedTopBar(
                            compactTitle = compactTitle,
                            title = title,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                        )
                    }
                },
            ) { innerPadding ->
                WidePageContent { sidePadding ->
                    // 保留默认越界工厂，短内容页同样保持回弹与原有滚动行为。
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalCutoutPadding()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            top = innerPadding.calculateTopPadding(),
                            end = sidePadding,
                        ),
                    ) {
                        content()
                        item(key = "bottom_spacer") {
                            MiuixPageBottomSpacer()
                        }
                    }
                }
                overlay()
            }
        }
    }
}

/**
 * 自定义设置内容的低层骨架。调用方继续负责顶部 padding、横向安全区与 nested scroll；
 * [content] 的 sidePadding 用于宽屏内容宽度，滚动容器本身保持全宽。
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    EtaPreferenceTheme {
        EtaSettingsGlassPage {
            val scrollBehavior = MiuixScrollBehavior()
            Scaffold(
                modifier = modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {
                    EtaSettingsGlassNavigation {
                        BrandedTopBar(
                            compactTitle = false,
                            title = title,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                        )
                    }
                },
            ) { paddingValues ->
                Box(modifier = Modifier.fillMaxSize()) {
                    WidePageContent { sidePadding ->
                        content(paddingValues, scrollBehavior, sidePadding)
                    }
                }
            }
        }
    }
}

/** 保留原生可折叠标题、动作区和导航语义；玻璃材质由外层提供。 */
@Composable
private fun BrandedTopBar(
    compactTitle: Boolean,
    title: String,
    scrollBehavior: ScrollBehavior,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    if (compactTitle) {
        SmallTopAppBar(
            title = title,
            color = Color.Transparent,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
        )
    } else {
        AdaptiveTopAppBar(
            title = title,
            color = Color.Transparent,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
