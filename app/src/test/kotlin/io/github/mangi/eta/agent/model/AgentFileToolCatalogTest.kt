package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentFileToolCatalogTest {
    @Test fun fileToolDescriptionsReflectAvailableIdentity() {
        val tools = AgentToolCatalog.build(true, false, capabilities = AgentToolCapabilities(rootAvailable = false))
        val fileTools = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .filter { it.getString("name") in AgentFileToolCatalog.names }
        assertEquals(AgentFileToolCatalog.names, fileTools.map { it.getString("name") }.toSet())
        // KB 工具（索引/检索）没有宿主身份概念，不是文件路径工具；只校验真正的文件工具。
        val kbTools = setOf("index_file_to_kb", "search_kb")
        fileTools.filterNot { it.getString("name") in kbTools }.forEach { tool ->
            val identities = tool.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("identity").getJSONArray("enum")
            assertEquals(1, identities.length())
            assertEquals("user", identities.getString(0))
        }
    }
}
