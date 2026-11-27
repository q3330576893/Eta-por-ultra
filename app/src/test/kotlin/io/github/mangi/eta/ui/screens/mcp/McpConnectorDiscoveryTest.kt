package io.github.mangi.eta.ui.screens.mcp

import io.github.mangi.eta.data.model.ConnectorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectorDiscoveryTest {
    private val domesticSearch = ConnectorCatalog.Template(
        id = "domestic-search",
        name = "中文搜索",
        category = ConnectorCatalog.Category.SEARCH,
        endpoint = "https://search.example/mcp",
        hint = "检索资料并阅读网页",
        domestic = true,
    )
    private val domesticMaps = ConnectorCatalog.Template(
        id = "domestic-maps",
        name = "出行服务",
        category = ConnectorCatalog.Category.MAPS,
        endpoint = "https://maps.example/mcp",
        hint = "使用 API Key 查询路线",
        domestic = true,
    )
    private val overseas = ConnectorCatalog.Template(
        id = "overseas-code",
        name = "Code Service",
        category = ConnectorCatalog.Category.CODE,
        endpoint = "https://code.example/mcp",
        hint = "Repository collaboration",
    )
    private val custom = requireNotNull(ConnectorCatalog.byId("custom"))
    private val templates = listOf(overseas, domesticSearch, custom, domesticMaps)

    @Test
    fun defaultScopeHidesOverseasServicesAndKeepsCustomEntry() {
        assertEquals(
            listOf(domesticSearch, custom, domesticMaps),
            filterMcpConnectorTemplates(templates),
        )
    }

    @Test
    fun allServicesRetainsEveryTemplateInCatalogOrder() {
        assertEquals(templates, filterMcpConnectorTemplates(templates, domesticOnly = false))
    }

    @Test
    fun customEntryRemainsReachableInEitherScopeEvenWhenSearchHasNoResults() {
        listOf(true, false).forEach { domesticOnly ->
            assertEquals(
                listOf(custom),
                filterMcpConnectorTemplates(templates, domesticOnly, "no-matching-service"),
            )
        }
    }

    @Test
    fun chineseNameSearchTrimsWhitespace() {
        assertEquals(
            listOf(domesticSearch, custom),
            filterMcpConnectorTemplates(templates, query = "  中文  "),
        )
    }

    @Test
    fun categorySearchFindsServicesByUsageScenario() {
        assertEquals(
            listOf(custom, domesticMaps),
            filterMcpConnectorTemplates(templates, query = "地图"),
        )
    }

    @Test
    fun searchMatchesHintAndIgnoresLatinLetterCase() {
        assertEquals(
            listOf(custom, domesticMaps),
            filterMcpConnectorTemplates(templates, query = "api key"),
        )
        assertEquals(
            listOf(domesticSearch, custom),
            filterMcpConnectorTemplates(templates, query = "阅读网页"),
        )
    }

    @Test
    fun unmatchedSearchLeavesNoServiceCards() {
        val result = filterMcpConnectorTemplates(templates, query = "不存在的用途")
        assertTrue(result.none { it.id != "custom" })
    }

    @Test
    fun currentCatalogUsesTheSameDomesticPolicyWithoutHardCodedServiceCounts() {
        val result = filterMcpConnectorTemplates(ConnectorCatalog.templates)
        assertEquals(
            ConnectorCatalog.templates.filter { it.domestic || it.id == "custom" },
            result,
        )
        assertTrue(result.any { it.id == "custom" })
        assertEquals(
            ConnectorCatalog.templates,
            filterMcpConnectorTemplates(ConnectorCatalog.templates, domesticOnly = false),
        )
    }
}
