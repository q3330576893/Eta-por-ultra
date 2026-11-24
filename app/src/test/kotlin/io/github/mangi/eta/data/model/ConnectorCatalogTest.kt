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
}
