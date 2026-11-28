package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue("保留既有服务并扩展国内服务", ConnectorCatalog.templates.size >= 31)
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
            listOf("feishu", "wps_dbsheet", "laiye_adp", "paddleocr", "google_drive", "onedrive", "word", "excel", "pdf"),
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

    @Test
    fun gatewayFlagMatchesTemplatesThatTrulyLackAMcpEndpoint() {
        // 空端点 + 官方只给 REST/Bot API：这些模板必须标成「需要网关」，
        // 界面才能把「填了地址却连不上」直接归因到缺网关。
        val gatewayIds = ConnectorCatalog.templates.filter { it.requiresGateway }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "postgres", "supabase", "slack",
                "qq", "wechat", "wecom", "dingtalk", "telegram", "discord",
                "google_drive", "onedrive",
            ),
            gatewayIds,
        )
        // 有官方 MCP 端点的模板不能标成需要网关，否则用户会白建一个网关。
        listOf("notion", "linear", "github", "sentry", "zapier").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertFalse("$id 有官方端点，不应标记需要网关", template.requiresGateway)
            assertTrue("$id 端点不应为空", template.endpoint.isNotBlank())
        }
    }

    @Test
    fun noConnectorNeededIsOnlyForLocalDocumentTemplates() {
        val ids = ConnectorCatalog.templates.filter { it.noConnectorNeeded }.map { it.id }.toSet()
        assertEquals(setOf("word", "excel", "pdf"), ids)
        // 劝阻提示只对「本地文件」成立：这些模板的凭据指引必须已经说明不需要连接器。
        ConnectorCatalog.templates.filter { it.noConnectorNeeded }.forEach { template ->
            assertTrue(
                "${template.id} 标了不需要连接器，指引却没说明：${template.credentialHint}",
                template.credentialHint.contains("不需要连接器") ||
                    template.credentialHint.contains("无需连接器"),
            )
        }
    }

    @Test
    fun newFlagsDefaultToFalseAndDoNotAlterTemplateShape() {
        // 新增字段必须全部有默认值：既有 id / endpoint / hint / credentialHint 一律不变。
        val defaults = ConnectorCatalog.Template(
            id = "x",
            name = "X",
            category = ConnectorCatalog.Category.AUTOMATION,
            endpoint = "",
            hint = "h",
        )
        assertFalse(defaults.requiresGateway)
        assertFalse(defaults.noConnectorNeeded)
        assertEquals("", defaults.credentialHint)
        assertEquals(McpAuthorizationType.BEARER, defaults.authorization)
        assertEquals(ConnectorCatalog.NEUTRAL_BRAND, defaults.brandColor)
        assertEquals("", defaults.badge)
    }

    @Test
    fun domesticHttpServicesHaveDocumentedEndpointsAndAuthentication() {
        val expected = mapOf(
            "amap" to ("https://mcp.amap.com/mcp" to McpAuthorizationType.QUERY_KEY),
            "baidu_maps" to ("https://mcp.map.baidu.com/mcp" to McpAuthorizationType.QUERY_AK),
            "bocha" to ("https://mcp.bochaai.com/mcp" to McpAuthorizationType.BEARER),
            "metaso" to ("https://metaso.cn/api/mcp" to McpAuthorizationType.BEARER),
            "zhipu_search" to ("https://open.bigmodel.cn/api/mcp/web_search_prime/mcp" to McpAuthorizationType.BEARER),
            "zhipu_reader" to ("https://open.bigmodel.cn/api/mcp/web_reader/mcp" to McpAuthorizationType.BEARER),
            "bailian_search" to ("https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp" to McpAuthorizationType.BEARER),
        )
        expected.forEach { (id, connection) ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(id, template.domestic)
            assertEquals(id, connection.first, template.endpoint)
            assertEquals(id, connection.second, template.authorization)
            assertFalse("$id 有官方 HTTP 端点", template.requiresGateway)
            assertFalse(id, template.noConnectorNeeded)
            assertTrue(id, template.guideUrl.startsWith("https://"))
        }
    }

    @Test
    fun domesticServicesHaveGuidesAndCatalogUrlsContainNoCredentials() {
        ConnectorCatalog.templates.filter { it.domestic }.forEach { template ->
            assertTrue(template.id, template.guideUrl.startsWith("https://"))
            assertTrue(template.id, template.setupLabel.isNotBlank())
        }
        ConnectorCatalog.templates.forEach { template ->
            listOf(template.endpoint, template.guideUrl).filter(String::isNotBlank).forEach { value ->
                val uri = java.net.URI(value)
                assertNull("${template.id} 不应内嵌用户名密码", uri.userInfo)
                assertNull("${template.id} 不应内嵌查询凭据", uri.rawQuery)
                assertNull("${template.id} 不应内嵌片段", uri.fragment)
            }
        }
    }

    @Test
    fun hostedProviderRequiresSpecificAddressAndCodingPlanExplainsEligibility() {
        val provider = requireNotNull(ConnectorCatalog.byId("bailian_mcp"))
        assertTrue(provider.domestic)
        assertTrue(provider.endpoint.isBlank())
        assertFalse(provider.requiresGateway)
        assertEquals("粘贴专属服务地址", provider.setupLabel)
        listOf("zhipu_search", "zhipu_reader").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(id, template.hint.contains("GLM Coding Plan"))
            assertTrue(id, template.credentialHint.contains("套餐"))
        }
    }

    @Test
    fun domesticBotEntriesStayExplicitAboutGatewayAndAccountType() {
        listOf("qq", "wechat", "wecom", "dingtalk").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(id, template.domestic)
            assertTrue(id, template.requiresGateway)
            assertEquals(id, "需先部署网关", template.setupLabel)
        }
        assertEquals("QQ 机器人", ConnectorCatalog.byId("qq")?.name)
        assertEquals("微信公众号", ConnectorCatalog.byId("wechat")?.name)
    }

    @Test
    fun domesticOfficeServicesUseTheirOwnTransportAndCredentialModel() {
        val wps = requireNotNull(ConnectorCatalog.byId("wps_dbsheet"))
        assertEquals("https://openapi.wps.cn/mcp/v2/kso-dbsheet/message", wps.endpoint)
        assertEquals(McpAuthorizationType.BEARER, wps.authorization)
        assertTrue(wps.credentialHint.contains("用户 access_token"))
        val adp = requireNotNull(ConnectorCatalog.byId("laiye_adp"))
        assertEquals("https://adp.laiye.com/mcp", adp.endpoint)
        assertEquals(McpAuthorizationType.QUERY_KEY, adp.authorization)
        assertTrue(adp.hint.contains("PDF"))
        listOf(wps, adp).forEach { template ->
            assertTrue(template.domestic)
            assertEquals(ConnectorCatalog.Category.DOCS, template.category)
            assertFalse(template.requiresGateway)
        }
    }

    @Test
    fun hostedFeishuAndSelfHostedOcrDoNotInventPublicEndpointsOrOAuthSupport() {
        listOf("feishu", "paddleocr").forEach { id ->
            val template = requireNotNull(ConnectorCatalog.byId(id))
            assertTrue(template.domestic)
            assertEquals(ConnectorCatalog.Category.DOCS, template.category)
            assertTrue(template.endpoint.isEmpty())
            assertFalse(template.requiresGateway)
            assertEquals(McpAuthorizationType.NONE, template.authorization)
            assertTrue(template.guideUrl.startsWith("https://"))
        }
        assertTrue(requireNotNull(ConnectorCatalog.byId("feishu")).credentialHint.contains("地址本身包含访问凭据"))
        assertTrue(requireNotNull(ConnectorCatalog.byId("feishu")).hint.contains("迁移"))
        assertTrue(requireNotNull(ConnectorCatalog.byId("paddleocr")).hint.contains("服务器部署"))
    }

    @Test
    fun gatewayTemplatesStillHaveBlankEndpoints() {
        // 需要网关的模板不能偷偷预填一个地址：预填等于替用户断言「这就是网关」。
        ConnectorCatalog.templates.filter { it.requiresGateway }.forEach { template ->
            assertEquals("${template.id} 需要网关，端点必须留空由用户填", "", template.endpoint)
        }
    }
}
