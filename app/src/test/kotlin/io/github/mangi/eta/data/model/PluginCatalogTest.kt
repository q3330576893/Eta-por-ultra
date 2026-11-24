package io.github.mangi.eta.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogTest {

    @Test
    fun everySourceHasUniqueIdAndSummary() {
        val ids = PluginCatalog.sources.map { it.id }
        assertEquals("来源 id 必须唯一", ids.size, ids.toSet().size)
        PluginCatalog.sources.forEach { source ->
            assertTrue("${source.id} 缺少名称", source.name.isNotBlank())
            assertTrue("${source.id} 缺少说明", source.summary.isNotBlank())
        }
    }

    @Test
    fun installableSourcesAllPointAtGithubSlugs() {
        PluginCatalog.installable().forEach { source ->
            assertTrue(
                "${source.id} 仓库格式无效：${source.repository}",
                Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(source.repository),
            )
        }
    }

    @Test
    fun builtinSourceIsNotInstallableButIsDiscoverable() {
        val builtin = requireNotNull(PluginCatalog.byId("eta-builtin"))
        assertTrue(builtin.repository.isBlank())
        assertTrue(builtin !in PluginCatalog.installable())
        assertNull(PluginCatalog.byId("missing"))
    }

    @Test
    fun builtinSkillManifestListsEveryBundledSkillWithDistinctId() {
        // 单元测试不打包 assets，直接读工作树里的清单文件。
        val manifestFile = java.io.File("src/main/assets/builtin_skills/manifest.json")
        val resolved = if (manifestFile.isFile) manifestFile
            else java.io.File("app/src/main/assets/builtin_skills/manifest.json")
        assertTrue("找不到内置技能清单：${resolved.absolutePath}", resolved.isFile)
        val manifest = JSONObject(resolved.readText())
        val skills = manifest.getJSONArray("skills")
        val ids = (0 until skills.length()).map { skills.getJSONObject(it).getString("id") }
        assertEquals("内置技能 id 必须唯一", ids.size, ids.toSet().size)
        assertTrue("应包含 connector-setup", "connector-setup" in ids)
        assertTrue("应包含 android-automation", "android-automation" in ids)
        ids.forEach { id ->
            val assetPath = (0 until skills.length())
                .map { skills.getJSONObject(it) }
                .first { it.getString("id") == id }
                .getString("assetPath")
            val dir = if (manifestFile.isFile) java.io.File("src/main/assets/$assetPath")
                else java.io.File("app/src/main/assets/$assetPath")
            assertTrue("$id 缺少 SKILL.md：${dir.absolutePath}", java.io.File(dir, "SKILL.md").isFile)
        }
    }
}
