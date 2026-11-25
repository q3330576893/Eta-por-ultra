package io.github.mangi.eta.agent.skill

import android.app.Application
import io.github.mangi.eta.data.db.EtaDatabase
import org.junit.After
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SkillRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @After fun tearDown() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test
    fun seedBuiltinSkillsDoesNotOverwriteExistingBuiltinData() {
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = temporaryFolder.newFolder("skills"),
        )
        service.seedBuiltinSkillsIfNeeded()
        val errorsFile = File(
            temporaryFolder.root,
            "skills/self-improving-agent/data/ERRORS.md",
        )
        errorsFile.parentFile?.mkdirs()
        errorsFile.writeText("preserve existing learning\n")

        service.seedBuiltinSkillsIfNeeded()

        assertEquals("preserve existing learning\n", errorsFile.readText())

        service.listSkillsForManagement()
        val userSkill = File(temporaryFolder.root, "skills/cache-probe/SKILL.md")
        userSkill.parentFile?.mkdirs()
        userSkill.writeText(
            """
            ---
            name: cache-probe
            description: Verify explicit index refresh.
            ---

            # Cache probe
            """.trimIndent()
        )

        // 新增 Skill 目录会改变根目录条目集合，因此不 forceRefresh 也必须立即可见：
        // 旧实现只靠进程内 invalidate 标记，跨实例（UI 安装、Agent 读取）会长期看不到新技能。
        assertTrue(service.listSkillsForManagement().any { it.id == "cache-probe" })
    }

    @Test
    fun builtinSkillInstallerManifestAndFileHaveValidMetadata() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val assetsRoot = listOf(
            File(workingDirectory, "app/src/main/assets"),
            File(workingDirectory, "src/main/assets"),
        ).first { it.isDirectory }
        val manifest = JSONObject(File(assetsRoot, "builtin_skills/manifest.json").readText())
        val skills = manifest.getJSONArray("skills")
        val installer = (0 until skills.length())
            .map { skills.getJSONObject(it) }
            .single { it.getString("id") == "skill-installer" }

        assertTrue(installer.getString("description").isNotBlank())
        val parsed = SkillParser.parseSkillFile(File(assetsRoot, installer.getString("assetPath") + "/SKILL.md"))
        assertEquals("skill-installer", parsed?.frontmatter?.get("name"))
        assertTrue(parsed?.frontmatter?.get("description").orEmpty().isNotBlank())
    }

    @Test
    fun lowercaseSkillFileIsIndexedAndLoadable() {
        val skillsRoot = temporaryFolder.newFolder("lowercase-index-skills")
        val skillRoot = File(skillsRoot, "lowercase-user").also { it.mkdirs() }
        File(skillRoot, "skill.md").writeText(
            "---\nname: lowercase-user\ndescription: Indexed from a lowercase file name.\n---\n\n# Lowercase\n"
        )
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)

        val entries = service.listSkillsForManagement(forceRefresh = true)

        val entry = entries.single { it.id == "lowercase-user" }
        assertEquals("Indexed from a lowercase file name.", entry.description)
        assertEquals(
            File(skillRoot, "skill.md").canonicalPath,
            File(entry.skillFilePath).canonicalPath,
        )
        assertTrue(entry.skillFilePath.endsWith("skill.md"))
        val context = requireNotNull(
            SkillLoader(skillsRoot).load(entry, triggerReason = "test"),
        )
        assertEquals("# Lowercase", context.bodyMarkdown.trim())
    }

    @Test
    fun caseVariantSkillFilesInOneDirectoryProduceASingleEntry() {
        val skillsRoot = temporaryFolder.newFolder("variant-index-skills")
        val skillRoot = File(skillsRoot, "variant-user").also { it.mkdirs() }
        val caseSensitive = run {
            File(skillRoot, "probe.md").createNewFile() && !File(skillRoot, "PROBE.md").exists()
        }
        File(skillRoot, "skill.md").writeText(
            "---\nname: variant-user\ndescription: Lowercase variant.\n---\n\n# Lower\n"
        )
        // 大小写不敏感的文件系统会覆盖同名文件，等于只有一个变体；两种平台都必须只产生一个条目。
        File(skillRoot, "SKILL.md").writeText(
            "---\nname: variant-user\ndescription: Canonical variant.\n---\n\n# Canonical\n"
        )
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)

        val entries = service.listSkillsForManagement(forceRefresh = true)
            .filter { it.id == "variant-user" }

        val entry = entries.single()
        assertEquals("Canonical variant.", entry.description)
        if (caseSensitive) {
            // 两个变体共存时规范名优先，索引结果与目录枚举顺序无关。
            assertEquals(
                File(skillRoot, "SKILL.md").canonicalPath,
                File(entry.skillFilePath).canonicalPath,
            )
            assertTrue(entry.skillFilePath.endsWith("SKILL.md"))
        }
        val skillFile = SkillLoader(skillsRoot).load(entry, triggerReason = "test")
        assertEquals("# Canonical", requireNotNull(skillFile).bodyMarkdown.trim())
    }

    @Test
    fun builtinSafetyCheckAcceptsLowercaseSkillFileButNotSymlinks() {
        val lowercaseRoot = temporaryFolder.newFolder("builtin-lowercase")
        File(lowercaseRoot, "skill.md").writeText("---\nname: builtin\n---\n")
        assertTrue(isSafeBuiltinSkillInstallation(lowercaseRoot))

        val emptyRoot = temporaryFolder.newFolder("builtin-empty")
        assertFalse(isSafeBuiltinSkillInstallation(emptyRoot))

        val externalRoot = temporaryFolder.newFolder("builtin-external")
        File(externalRoot, "SKILL.md").writeText("---\nname: external\n---\n")
        val linkedRoot = File(temporaryFolder.root, "builtin-linked").also { it.mkdirs() }
        try {
            Files.createSymbolicLink(File(linkedRoot, "SKILL.md").toPath(), File(externalRoot, "SKILL.md").toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        assertFalse(isSafeBuiltinSkillInstallation(linkedRoot))
    }

    @Test
    fun scanAndDeleteNeverFollowSkillDirectorySymlinkOutsideRoot() {
        val skillsRoot = temporaryFolder.newFolder("symlink-skills")
        val externalRoot = temporaryFolder.newFolder("external-skill")
        val externalSkill = File(externalRoot, "SKILL.md")
        externalSkill.writeText(
            "---\nname: external-skill\ndescription: Must remain outside.\n---\n"
        )
        val link = File(skillsRoot, "linked-skill")
        try {
            Files.createSymbolicLink(link.toPath(), externalRoot.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = skillsRoot,
        )

        val indexed = service.listSkillsForManagement(forceRefresh = true)

        assertFalse(indexed.any { it.id == "external-skill" })
        assertFalse(service.deleteSkill("external-skill"))
        assertTrue(externalSkill.isFile)
        assertTrue(externalSkill.readText().contains("Must remain outside"))
    }

    @Test
    fun scanRejectsSymlinkedSkillFileAndNormalDeleteUsesPrivateBackupFlow() {
        val skillsRoot = temporaryFolder.newFolder("delete-skills")
        val externalFile = File(temporaryFolder.newFolder("external-file"), "SKILL.md")
        externalFile.writeText(
            "---\nname: linked-file\ndescription: Must not be indexed.\n---\n"
        )
        val linkedRoot = File(skillsRoot, "linked-file").also { it.mkdirs() }
        try {
            Files.createSymbolicLink(File(linkedRoot, "SKILL.md").toPath(), externalFile.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val normalRoot = File(skillsRoot, "normal-user").also { it.mkdirs() }
        File(normalRoot, "SKILL.md").writeText(
            "---\nname: normal-user\ndescription: Delete this user Skill.\n---\n"
        )
        val externalDirectory = temporaryFolder.newFolder("delete-external-directory")
        val externalMarker = File(externalDirectory, "keep.txt").also {
            it.writeText("nested symlink target must survive")
        }
        try {
            Files.createSymbolicLink(
                File(normalRoot, "linked-external").toPath(),
                externalDirectory.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val service = SkillIndexService(
            context = RuntimeEnvironment.getApplication(),
            skillsRoot = skillsRoot,
        )

        val indexed = service.listSkillsForManagement(forceRefresh = true)

        assertFalse(indexed.any { it.id == "linked-file" })
        assertTrue(indexed.any { it.id == "normal-user" })
        assertTrue(service.deleteSkill("normal-user"))
        assertFalse(normalRoot.exists())
        assertTrue(externalFile.exists())
        assertTrue(externalMarker.isFile)
        assertEquals("nested symlink target must survive", externalMarker.readText())
        assertTrue(
            service.listSkillsForManagement(forceRefresh = true).none { it.id == "normal-user" }
        )
    }

    @Test
    fun builtinCleanupUnlinksDirectorySymlinkWithoutDeletingExternalContent() {
        val skillsRoot = temporaryFolder.newFolder("builtin-link-skills")
        val externalRoot = temporaryFolder.newFolder("builtin-link-external")
        val externalMarker = File(externalRoot, "SKILL.md").also {
            it.writeText("external content must survive")
        }
        val targetLink = File(skillsRoot, "self-improving-agent")
        try {
            Files.createSymbolicLink(targetLink.toPath(), externalRoot.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertFalse(isSafeBuiltinSkillInstallation(targetLink))
        val deleted = deleteSkillPathWithoutFollowingLinks(skillsRoot, targetLink)

        assertTrue(deleted)
        assertFalse(Files.exists(targetLink.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(externalMarker.isFile)
        assertEquals("external content must survive", externalMarker.readText())
    }

    @Test
    fun disabledSkillIsExcludedFromModelContextAndAgentLookupButStaysManageable() {
        val skillsRoot = temporaryFolder.newFolder("disable-skills")
        val skillRoot = File(skillsRoot, "disabled-user").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText(
            "---\nname: disabled-user\ndescription: Disabled user Skill.\n---\n\n# Disabled\n"
        )
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        assertTrue(service.listInstalledSkills().any { it.id == "disabled-user" })

        service.setSkillEnabled("disabled-user", false)

        // 模型上下文（SkillContext.installedSkills）与 Agent 的 skills_read 查找都走这两个入口。
        assertFalse(service.listInstalledSkills().any { it.id == "disabled-user" })
        assertEquals(null, service.findInstalledSkill("disabled-user"))
        // 管理视图仍列出并标记为禁用，用户才能再打开它。
        assertFalse(
            service.listSkillsForManagement().single { it.id == "disabled-user" }.enabled
        )
        // 重新启用后立刻回到模型上下文。
        service.setSkillEnabled("disabled-user", true)
        assertTrue(service.listInstalledSkills().any { it.id == "disabled-user" })
        assertTrue(service.findInstalledSkill("disabled-user") != null)
    }

    @Test
    fun builtinSkillCanBeDisabledAndReinstallDoesNotReEnableIt() {
        val skillsRoot = temporaryFolder.newFolder("builtin-disable-skills")
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        service.seedBuiltinSkillsIfNeeded()
        val builtinId = service.listInstalledSkills().first { it.source == BUILTIN_SKILL_SOURCE }.id

        service.setSkillEnabled(builtinId, false)
        assertFalse(service.listInstalledSkills().any { it.id == builtinId })

        // 模拟内置文件被外部清掉后的重新播种：安装文件不等于用户重新启用。
        val builtinDir = File(skillsRoot, builtinId)
        assertTrue(builtinDir.deleteRecursively() || !builtinDir.exists())
        service.listSkillsForManagement(forceRefresh = true)

        val entry = service.listSkillsForManagement().single { it.id == builtinId }
        assertTrue("内置技能应被重新安装", entry.installed)
        assertEquals(BUILTIN_SKILL_SOURCE, entry.source)
        assertFalse("重新安装不得覆盖用户的禁用选择", entry.enabled)
        assertFalse(service.listInstalledSkills().any { it.id == builtinId })
    }

    @Test
    fun indexSeesSkillsInstalledByAnotherServiceInstanceWithoutForceRefresh() {
        val skillsRoot = temporaryFolder.newFolder("cross-instance-skills")
        val writer = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        val reader = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        assertTrue(reader.listSkillsForManagement().none { it.id == "late-user" })

        val skillRoot = File(skillsRoot, "late-user").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText(
            "---\nname: late-user\ndescription: Installed by another instance.\n---\n"
        )

        // 另一个 SkillIndexService（另一个进程/另一份持有者）写入后，无需 forceRefresh 即可见。
        assertTrue(reader.listSkillsForManagement().any { it.id == "late-user" })
        assertTrue(writer.listSkillsForManagement().any { it.id == "late-user" })
    }

    @Test
    fun indexCacheReflectsEnableToggleFromAnotherInstance() {
        val skillsRoot = temporaryFolder.newFolder("cross-instance-toggle-skills")
        val skillRoot = File(skillsRoot, "toggle-user").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText(
            "---\nname: toggle-user\ndescription: Toggle from another instance.\n---\n"
        )
        val first = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        val second = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        assertTrue(first.listInstalledSkills().any { it.id == "toggle-user" })
        assertTrue(second.listInstalledSkills().any { it.id == "toggle-user" })

        first.setSkillEnabled("toggle-user", false)

        // 第二个实例已缓存过旧结果；缓存签名必须让禁用立即生效。
        assertFalse(second.listInstalledSkills().any { it.id == "toggle-user" })
    }

    @Test
    fun repeatedListInstalledSkillsDoesNotRescanDiskEachCall() {
        val skillsRoot = temporaryFolder.newFolder("cached-index-skills")
        repeat(20) { index ->
            val root = File(skillsRoot, "cached-$index").also { it.mkdirs() }
            File(root, "SKILL.md").writeText(
                "---\nname: cached-$index\ndescription: Cached $index.\n---\n"
            )
        }
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        assertEquals(
            20,
            service.listInstalledSkills().count { it.id.startsWith("cached-") },
        )

        // 原地改写 SKILL.md 不改变根目录一级条目集合，因此缓存命中时不会重新解析；
        // forceRefresh 则必须看到新内容。这同时证明“缓存命中”不是空断言。
        File(skillsRoot, "cached-0/SKILL.md").writeText(
            "---\nname: cached-0\ndescription: Updated 0.\n---\n"
        )

        assertEquals(
            "Cached 0.",
            service.listInstalledSkills().single { it.id == "cached-0" }.description,
        )
        assertEquals(
            "Updated 0.",
            service.listSkillsForManagement(forceRefresh = true)
                .single { it.id == "cached-0" }.description,
        )
    }
}
