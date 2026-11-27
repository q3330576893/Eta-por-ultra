package io.github.mangi.eta.ui.screens.mcp

import io.github.mangi.eta.data.model.ConnectorCatalog
import io.github.mangi.eta.data.model.McpAuthorizationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class McpConnectorDraftTest {
    @Test
    fun selectingDifferentTemplateClearsTokenAndAppliesItsDefaults() {
        val original = McpConnectorDraft()
            .selectTemplate(template("github"))
            .withToken("github-only-token")
            .copy(name = "My GitHub", url = "https://github-gateway.example/mcp")

        ConnectorCatalog.templates.filter { it.id != "github" }.forEach { next ->
            val selected = original.selectTemplate(next)

            assertEquals(next.id, selected.connectorId)
            assertEquals(next.name, selected.name)
            assertEquals(next.endpoint, selected.url)
            assertEquals("", selected.token)
            assertEquals(next.authorization, selected.authorizationType)
        }
    }

    @Test
    fun selectingFirstTemplateClearsCredentialsEnteredBeforeChoosingTemplate() {
        val original = McpConnectorDraft(name = "Manual", url = "https://manual.example/mcp")
            .withToken("manual-only-token")

        val selected = original.selectTemplate(template("github"))

        assertEquals("github", selected.connectorId)
        assertEquals("", selected.token)
        assertEquals(McpAuthorizationType.BEARER, selected.authorizationType)
    }

    @Test
    fun selectingSameTemplatePreservesEditedNameUrlAndToken() {
        val original = McpConnectorDraft()
            .selectTemplate(template("github"))
            .withToken("github-only-token")
            .copy(name = "My GitHub", url = "https://github-gateway.example/mcp")

        assertSame(original, original.selectTemplate(template("github")))
    }

    @Test
    fun switchingBackToPreviousTemplateDoesNotRestoreItsToken() {
        val selected = McpConnectorDraft()
            .selectTemplate(template("github"))
            .withToken("github-only-token")
            .selectTemplate(template("linear"))
            .withToken("linear-only-token")
            .selectTemplate(template("github"))

        assertEquals("github", selected.connectorId)
        assertEquals("", selected.token)
        assertEquals(McpAuthorizationType.BEARER, selected.authorizationType)
    }

    @Test
    fun enteringTokenForUnauthenticatedTemplateEnablesBearerAuthorization() {
        val selected = McpConnectorDraft().selectTemplate(template("custom"))
        assertEquals(McpAuthorizationType.NONE, selected.authorizationType)

        val authenticated = selected.withToken("custom-only-token")

        assertEquals("custom-only-token", authenticated.token)
        assertEquals(McpAuthorizationType.BEARER, authenticated.authorizationType)
    }

    @Test
    fun clearingTokenDisablesAuthorizationAndReselectionPreservesThatChoice() {
        val selected = McpConnectorDraft()
            .selectTemplate(template("github"))
            .withToken("github-only-token")
            .withToken("")

        assertEquals(McpAuthorizationType.NONE, selected.authorizationType)
        assertSame(selected, selected.selectTemplate(template("github")))
        assertEquals(McpAuthorizationType.NONE, selected.withToken("  ").authorizationType)
    }

    @Test
    fun queryTemplatesKeepTheirAuthenticationWhenEnteringAndReenteringKeys() {
        listOf(McpAuthorizationType.QUERY_KEY, McpAuthorizationType.QUERY_AK).forEach { type ->
            val selected = McpConnectorDraft().selectTemplate(
                template("custom").copy(id = "query-fixture", authorization = type),
            )
            val authenticated = selected.withToken("fixture-key")

            assertEquals(type, authenticated.authorizationType)
            assertEquals("fixture-key", authenticated.token)
            assertEquals(type, authenticated.templateAuthorizationType)

            val cleared = authenticated.withToken("  ")
            assertEquals(type, cleared.authorizationType)
            assertEquals(type, cleared.toServerSetting().authorizationType)
            assertEquals(type, cleared.withToken("replacement-key").authorizationType)
        }
    }

    @Test
    fun changingFromQueryTemplateToBearerTemplateClearsKeyAndChangesAuthorization() {
        val queryTemplate = template("custom").copy(
            id = "query-fixture",
            authorization = McpAuthorizationType.QUERY_AK,
        )
        val selected = McpConnectorDraft().selectTemplate(queryTemplate).withToken("fixture-key")
            .selectTemplate(template("github"))

        assertEquals("", selected.token)
        assertEquals(McpAuthorizationType.BEARER, selected.withToken("github-token").authorizationType)
    }

    @Test
    fun officialMapTemplatesKeepTheirQueryKeyModesWithoutAddingKeysToVisibleUrl() {
        listOf("amap" to McpAuthorizationType.QUERY_KEY, "baidu_maps" to McpAuthorizationType.QUERY_AK)
            .forEach { (id, type) ->
                val map = template(id)
                val selected = McpConnectorDraft().selectTemplate(map).withToken("map-only-key")

                assertEquals(type, selected.authorizationType)
                assertEquals(map.endpoint, selected.url)
                assertEquals(false, selected.url.contains("map-only-key"))
                assertSame(selected, selected.selectTemplate(map))
            }
    }

    @Test
    fun creatingQueryServerWithoutAKeyNeverDowngradesToAnonymous() {
        listOf("amap" to McpAuthorizationType.QUERY_KEY, "baidu_maps" to McpAuthorizationType.QUERY_AK)
            .forEach { (id, type) ->
                val parameter = requireNotNull(McpAuthorizationType.queryParameterName(type))
                val selected = McpConnectorDraft().selectTemplate(template(id))
                    .copy(url = "https://fixture.example/mcp?$parameter=inline-key")

                listOf(selected, selected.withToken("first-key").withToken("  ")).forEach { draft ->
                    assertEquals(type, draft.authorizationType)
                    assertEquals(type, draft.toServerSetting().authorizationType)
                }
            }
    }

    @Test
    fun creatingBearerServerWithoutATokenStillAllowsAnonymousEndpoints() {
        val selected = McpConnectorDraft().selectTemplate(template("github"))
        assertEquals(McpAuthorizationType.NONE, selected.toServerSetting().authorizationType)
        assertEquals(McpAuthorizationType.BEARER, selected.withToken("fixture-token").toServerSetting().authorizationType)
    }

    private fun template(id: String): ConnectorCatalog.Template =
        requireNotNull(ConnectorCatalog.byId(id))
}
