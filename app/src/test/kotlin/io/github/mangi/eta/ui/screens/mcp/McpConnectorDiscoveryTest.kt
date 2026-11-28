package io.github.mangi.eta.ui.screens.mcp

import io.github.mangi.eta.data.model.ConnectorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectorDiscoveryTest {
    private val domesticSearch = ConnectorCatalog.Template(
        id = "domestic-search", name = "中文搜索", category = ConnectorCatalog.Category.SEARCH,
        endpoint = "https://search.example/mcp", hint = "检索资料并阅读网页", domestic = true,
    )
    private val domesticMaps = ConnectorCatalog.Template(
        id = "domestic-maps", name = "出行服务", category = ConnectorCatalog.Category.MAPS,
        endpoint = "https://maps.example/mcp", hint = "使用 API Key 查询路线", domestic = true,
    )
    private val domesticDocs = ConnectorCatalog.Template(
        id = "domestic-docs", name = "文档解析", category = ConnectorCatalog.Category.DOCS,
        endpoint = "https://docs.example/mcp", hint = "解析 PDF 与表格", domestic = true,
    )
    private val overseas = ConnectorCatalog.Template(
        id = "overseas-code", name = "Code Service", category = ConnectorCatalog.Category.CODE,
        endpoint = "https://code.example/mcp", hint = "Repository collaboration",
    )
    private val custom = requireNotNull(ConnectorCatalog.byId("custom"))
    private val templates = listOf(overseas, domesticSearch, custom, domesticMaps, domesticDocs)

    @Test
    fun defaultScopeContainsOnlyDomesticServicesInCatalogOrder() {
        assertEquals(listOf(domesticSearch, domesticMaps, domesticDocs), filterMcpConnectorTemplates(templates))
    }

    @Test
    fun overseasScopeDoesNotMixInDomesticServices() {
        assertEquals(listOf(overseas), filterMcpConnectorTemplates(templates, McpConnectorRegion.OVERSEAS))
    }

    @Test
    fun customEntryIsIndependentOfEveryRegionAndSearch() {
        McpConnectorRegion.entries.forEach { region ->
            assertFalse(filterMcpConnectorTemplates(templates, region).any { it.id == "custom" })
            assertTrue(filterMcpConnectorTemplates(templates, region, "no-matching-service").isEmpty())
        }
        assertEquals(custom, ConnectorCatalog.byId("custom"))
    }

    @Test
    fun chineseNameSearchTrimsWhitespace() {
        assertEquals(listOf(domesticSearch), filterMcpConnectorTemplates(templates, query = "  中文  "))
    }

    @Test
    fun categorySearchFindsServicesByUsageScenario() {
        assertEquals(listOf(domesticMaps), filterMcpConnectorTemplates(templates, query = "地图"))
    }

    @Test
    fun searchMatchesHintAndIgnoresLatinLetterCase() {
        assertEquals(listOf(domesticMaps), filterMcpConnectorTemplates(templates, query = "api key"))
        assertEquals(listOf(domesticSearch), filterMcpConnectorTemplates(templates, query = "阅读网页"))
    }

    @Test
    fun officeCategoryCombinesWithSearchAndRegion() {
        assertEquals(listOf(domesticDocs), filterMcpConnectorTemplates(
            templates, query = "pdf", category = ConnectorCatalog.Category.DOCS,
        ))
        assertTrue(filterMcpConnectorTemplates(
            templates, McpConnectorRegion.OVERSEAS, "pdf", ConnectorCatalog.Category.DOCS,
        ).isEmpty())
        assertTrue(filterMcpConnectorTemplates(
            templates, query = "出行", category = ConnectorCatalog.Category.DOCS,
        ).isEmpty())
    }

    @Test
    fun searchNeverCrossesTheSelectedRegion() {
        assertTrue(filterMcpConnectorTemplates(templates, query = "Code").isEmpty())
        assertTrue(filterMcpConnectorTemplates(templates, McpConnectorRegion.OVERSEAS, "中文").isEmpty())
    }

    @Test
    fun regionsAreDisjointAndCoverEveryNonCustomTemplateExactlyOnce() {
        val domestic = filterMcpConnectorTemplates(ConnectorCatalog.templates, McpConnectorRegion.DOMESTIC)
        val foreign = filterMcpConnectorTemplates(ConnectorCatalog.templates, McpConnectorRegion.OVERSEAS)
        assertTrue(domestic.all { it.domestic })
        assertTrue(foreign.none { it.domestic })
        assertTrue(domestic.map { it.id }.intersect(foreign.map { it.id }.toSet()).isEmpty())
        assertEquals(ConnectorCatalog.templates.filter { it.id != "custom" }.toSet(), (domestic + foreign).toSet())
    }
}
