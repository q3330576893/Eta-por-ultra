package io.github.mangi.eta.ui.screens.mcp

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import io.github.mangi.eta.data.model.ConnectorCatalog
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.components.SettingsComposeTestHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpConnectorNavigationTest {
    @Test
    fun firstLevelHasTwoRegionEntriesAndCustomWithoutExpandingServiceCards() {
        val selected = mutableListOf<McpConnectorRegion>()
        var customClicks = 0
        SettingsComposeTestHost(content = {
            Column { McpConnectorMenu(onRegion = selected::add, onCustom = { customClicks++ }) }
        }).use { host ->
            assertFalse(host.nodes().any { it.hasText("高德地图") || it.hasText("GitHub") })
            host.tap(host.clickableText("国内连接器").boundsInRoot)
            host.tap(host.clickableText("国外连接器").boundsInRoot)
            assertEquals(listOf(McpConnectorRegion.DOMESTIC, McpConnectorRegion.OVERSEAS), selected)
            assertFalse(host.nodes().any { it.hasText("高德地图") || it.hasText("GitHub") })
            host.tap(host.clickableText("自定义 MCP").boundsInRoot)
            assertEquals(1, customClicks)
            host.captureIfRequested("mcp_regions_menu")
        }
    }

    @Test
    fun regionTitlesAreNotEllipsizedAtTwoHundredPercentBeforeAndAfterScrolling() {
        for (region in McpConnectorRegion.entries) {
            val title = if (region == McpConnectorRegion.DOMESTIC) "国内" else "国外"
            val listState = LazyListState()
            SettingsComposeTestHost(fontScale = 2f, content = {
                McpConnectorDiscoveryPage(
                    region = region, enabled = true, onBack = {}, onCustom = {},
                    onConfigure = {}, onGuide = {}, listState = listState,
                )
            }).use { host ->
                for (atBottom in listOf(false, true)) {
                    if (atBottom) {
                        listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    }
                    host.advance(30)
                    host.captureIfRequested("mcp_${region.name.lowercase()}_title_${if (atBottom) "bottom" else "top"}_2x")
                    val titleNode = host.nodes().single {
                        it.hasText(title) && it.config.contains(SemanticsActions.GetTextLayoutResult)
                    }
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue("Title must expose its measured layout: $region/$atBottom",
                        titleNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { layout ->
                        val context = "$region/$atBottom; size=${layout.size}; " +
                            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                            "bounds=${titleNode.boundsInRoot}"
                        assertEquals(title, layout.layoutInput.text.text)
                        assertEquals("Title must remain on one line: $context", 1, layout.lineCount)
                        assertFalse("Title height must fit: $context", layout.didOverflowHeight)
                        assertFalse("Title must not be ellipsized: $context", layout.isLineEllipsized(0))
                        assertEquals("All title characters must be visible: $context",
                            title.length, layout.getLineEnd(0, visibleEnd = true))
                        // The paragraph may retain unused constraint width after the text node shrinks.
                        // Check the drawn line and character bounds, not hasVisualOverflow's paragraph width.
                        assertTrue("Title line must fit: $context",
                            layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.size.width + 0.5f)
                        val characterBounds = title.indices.map(layout::getBoundingBox)
                        characterBounds.forEach { bounds ->
                            assertTrue("Title character must fit: $context; character=$bounds",
                                bounds.left >= 0f && bounds.right <= layout.size.width + 0.5f &&
                                    bounds.top >= 0f && bounds.bottom <= layout.size.height + 0.5f)
                        }
                        assertTrue("Title node must fit the viewport: $context",
                            titleNode.boundsInRoot.left >= 0f && titleNode.boundsInRoot.right <= host.dp(320f) &&
                                titleNode.boundsInRoot.top >= 0f && titleNode.boundsInRoot.bottom <= host.dp(568f))
                        println("MCP_TITLE_LAYOUT $context; line=${layout.getLineLeft(0)}..${layout.getLineRight(0)}; " +
                            "visibleEnd=${layout.getLineEnd(0, visibleEnd = true)}; ellipsized=${layout.isLineEllipsized(0)}; " +
                            "characters=$characterBounds")
                    }
                }
            }
        }
    }

    @Test
    fun lastConnectorConfigureButtonIsVisibleAndPhysicallyClickableOnSmallScreenAtBothFontScales() {
        for (fontScale in listOf(1f, 2f)) {
            for (region in McpConnectorRegion.entries) {
                val listState = LazyListState()
                var configured: String? = null
                val expected = ConnectorCatalog.Category.entries.flatMap { category ->
                    filterMcpConnectorTemplates(ConnectorCatalog.templates, region, category = category)
                }.last().id
                SettingsComposeTestHost(fontScale = fontScale, content = {
                    McpConnectorDiscoveryPage(
                        region = region, enabled = true, onBack = {}, onCustom = {},
                        onConfigure = { configured = it.id }, onGuide = {}, listState = listState,
                    )
                }).use { host ->
                    listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    host.advance(30)
                    val button = host.clickableText("配置", last = true)
                    val bitmap = host.captureBitmap()
                    try {
                        assertEquals("Native viewport width", 320, bitmap.width)
                        assertEquals("Native viewport height", 568, bitmap.height)
                        assertTrue("Button top must be visible: $region/$fontScale", button.boundsInRoot.top >= 0f)
                        assertTrue("Bottom action must clear the viewport edge: $region/$fontScale",
                            button.boundsInRoot.bottom <= bitmap.height - host.dp(16f))
                        assertTrue(button.boundsInRoot.left >= 0f && button.boundsInRoot.right <= bitmap.width)
                    } finally { bitmap.recycle() }
                    host.tap(button.boundsInRoot)
                    assertEquals("Actual touch must configure the last connector: $region/$fontScale", expected, configured)
                    host.captureIfRequested("mcp_${region.name.lowercase()}_bottom_${fontScale.toInt()}x")
                }
            }
        }
    }

    @Test
    fun customActionStaysReachableWithNoSearchMatchesInEitherRegion() {
        for (region in McpConnectorRegion.entries) {
            var customClicks = 0
            val listState = LazyListState()
            SettingsComposeTestHost(content = {
                McpConnectorDiscoveryPage(
                    region = region, enabled = true, onBack = {}, onCustom = { customClicks++ },
                    onConfigure = {}, onGuide = {}, listState = listState,
                )
            }).use { host ->
                val field = host.nodes().single { it.config.contains(SemanticsActions.SetText) }
                assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("no-match-zzzz")) == true)
                host.advance(20)
                val custom = host.nodes(merged = true).single { node ->
                    node.config.contains(SemanticsActions.OnClick) &&
                        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("自定义 MCP") == true
                }
                host.tap(custom.boundsInRoot)
                assertEquals(1, customClicks)
                listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                host.advance(20)
                host.tap(host.clickableText("清除筛选").boundsInRoot)
                host.advance(20)
                assertFalse(host.nodes().any { it.hasText("没有匹配的连接器") })
            }
        }
    }

    @Test
    fun officeCategoryHasAVisibleSelectableFilterInDomesticPage() {
        SettingsComposeTestHost(content = {
            McpConnectorDiscoveryPage(
                region = McpConnectorRegion.DOMESTIC, enabled = true, onBack = {},
                onCustom = {}, onConfigure = {}, onGuide = {},
            )
        }).use { host ->
            host.tap(host.clickableText("办公文档").boundsInRoot)
            val selected = host.clickableText("办公文档")
            assertEquals(true, selected.config.getOrNull(SemanticsProperties.Selected))
            assertFalse(host.nodes().any { it.hasText("高德地图") })
            host.captureIfRequested("mcp_domestic_office")
        }
    }

    private fun SemanticsNode.hasText(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true

    private fun SettingsComposeTestHost.clickableText(text: String, last: Boolean = false): SemanticsNode {
        val matches = nodes(merged = true).filter {
            it.hasText(text) && it.config.contains(SemanticsActions.OnClick) &&
                it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
        }
        assertTrue("No visible clickable text: $text", matches.isNotEmpty())
        return if (last) matches.last() else matches.first()
    }
}
