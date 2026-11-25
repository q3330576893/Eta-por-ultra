package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorCatalogTest {

    @Test
    fun everyTemplateHasStableIdentityAndHonestHint() {
        val ids = ConnectorCatalog.templates.map { it.id }
        assertEquals("模板 id 必须唯一", ids.size, ids.toSet().size)
        ConnectorCatalog.templates.forEach { template ->
            assertTrue("${template.id} 缺少名称", template.name.isNotBlank())
            assertTrue("${template.id} 缺少说明", template.hint.isNotBlank())
        }
    }

    @Test
    fun endpointsAreHttpsWhenProvided() {
        ConnectorCatalog.templates
            .filter { it.endpoint.isNotBlank() }
            .forEach { template ->
                assertTrue(
                    "${template.id} 端点必须为 HTTPS：${template.endpoint}",
                    template.endpoint.startsWith("https://"),
                )
            }
    }

    @Test
    fun everyCategoryIsUsedAndLookupWorks() {
        val grouped = ConnectorCatalog.grouped()
        assertEquals(ConnectorCatalog.Category.entries.toSet(), grouped.keys)
        assertEquals("github", ConnectorCatalog.byId("github")?.id)
        assertNull(ConnectorCatalog.byId("missing"))
    }

    @Test
    fun customTemplateIsUnauthenticatedByDefault() {
        val custom = requireNotNull(ConnectorCatalog.byId("custom"))
        assertEquals(McpAuthorizationType.NONE, custom.authorization)
        assertTrue(custom.endpoint.isBlank())
    }

    @Test
    fun everyTemplateExplainsHowToGetItsCredential() {
        val longRun = Regex("[A-Za-z0-9_-]{32,}")
        ConnectorCatalog.templates.forEach { template ->
            val credential = template.credentialHint
            assertTrue("${template.id} 缺少凭据获取指引", credential.isNotBlank())
            assertTrue("${template.id} 指引过短，无法操作：$credential", credential.length >= 20)
            // 指引只讲获取路径，绝不能出现像真实令牌的长串。
            assertNull(
                "${template.id} 指引疑似内嵌凭据：${longRun.find(credential)?.value}",
                longRun.find(credential),
            )
        }
        assertEquals(23, ConnectorCatalog.templates.size)
    }

    @Test
    fun botPlatformConnectorsRequireSelfHostedGateway() {
        listOf("qq", "wechat", "wecom", "dingtalk", "telegram", "discord").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(
                "$id 必须说明需要自建 MCP 网关：${template.credentialHint}",
                template.credentialHint.contains("网关"),
            )
        }
    }

    @Test
    fun databaseConnectorsWarnAgainstPastingRawPasswords() {
        listOf("postgres", "supabase").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(
                "$id 必须提示不要把数据库密码填进来：${template.credentialHint}",
                template.credentialHint.contains("密码"),
            )
            assertTrue(
                "$id 必须说明需要网关：${template.credentialHint}",
                template.credentialHint.contains("网关"),
            )
        }
    }

    @Test
    fun officialMcpConnectorsPointAtTheirSettingsPage() {
        listOf("notion", "linear", "github", "sentry", "zapier").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(
                "$id 应说明在设置/后台生成令牌：${template.credentialHint}",
                template.credentialHint.contains("设置") || template.credentialHint.contains("后台"),
            )
        }
    }

    @Test
    fun localDocumentConnectorsSayNoConnectorIsNeeded() {
        listOf("word", "excel", "pdf").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(
                "$id 应说明本地文件不需要连接器：${template.credentialHint}",
                template.credentialHint.contains("不需要连接器") ||
                    template.credentialHint.contains("无需连接器"),
            )
        }
    }

    @Test
    fun everyTemplateHasDrawableBrandIcon() {
        ConnectorCatalog.templates.forEach { template ->
            assertTrue("${template.id} 缺少图标标识", template.badge.isNotBlank())
            assertTrue("${template.id} 标识过长：${template.badge}", template.badge.length <= 3)
            // 0xFF______ 形式的 ARGB：高位必须不透明，否则图标底色会透出胶囊背景。
            assertTrue(
                "${template.id} 品牌色必须是不透明 ARGB：${template.brandColor}",
                template.brandColor in 0xFF000000L..0xFFFFFFFFL,
            )
        }
    }

    @Test
    fun brandColorsAreDistinctPerService() {
        val ids = ConnectorCatalog.templates.map { it.id }
        val colors = ConnectorCatalog.templates.map { it.brandColor }
        assertEquals("品牌色必须唯一，否则图标无法区分", ids.size, colors.toSet().size)
    }

    @Test
    fun newCategoriesHoldExpectedConnectors() {
        val im = ConnectorCatalog.grouped()[ConnectorCatalog.Category.IM].orEmpty().map { it.id }
        assertEquals(
            listOf("qq", "wechat", "wecom", "dingtalk", "telegram", "discord"),
            im,
        )
        val docs = ConnectorCatalog.grouped()[ConnectorCatalog.Category.DOCS].orEmpty().map { it.id }
        assertEquals(
            listOf("google_drive", "onedrive", "word", "excel", "pdf"),
            docs,
        )
    }

    @Test
    fun groupedInOrderFollowsCategoryDeclaration() {
        val order = ConnectorCatalog.groupedInOrder().map { it.first }
        assertEquals(ConnectorCatalog.Category.entries, order)
        order.forEach { category ->
            ConnectorCatalog.groupedInOrder()
                .first { it.first == category }
                .second
                .forEach { assertEquals(category, it.category) }
        }
    }

    @Test
    fun existingConnectorsKeepTheirIdentity() {
        val expected = mapOf(
            "notion" to "https://mcp.notion.com/mcp",
            "linear" to "https://mcp.linear.app/mcp",
            "github" to "https://api.githubcopilot.com/mcp/",
            "gitlab" to "",
            "sentry" to "https://mcp.sentry.dev/mcp",
            "postgres" to "",
            "supabase" to "",
            "slack" to "",
            "feishu" to "",
            "zapier" to "https://mcp.zapier.com/api/mcp/mcp",
            "n8n" to "",
            "custom" to "",
        )
        expected.forEach { (id, endpoint) ->
            val template = requireNotNull(ConnectorCatalog.byId(id)) { "缺少既有连接器 $id" }
            assertEquals("$id 端点被改动", endpoint, template.endpoint)
        }
    }
}
