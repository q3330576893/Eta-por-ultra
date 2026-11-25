package io.github.mangi.eta.agent.skill

import android.app.Application
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 来源记录与索引服务的集成：往返、无记录跳过、内置 Skill 不参与。
 *
 * 用真实的 [SkillIndexService] 而不是桩，是因为「来源记录挂在哪个目录」这件事
 * 只有索引服务知道；桩会把路径推导错误藏起来。
 */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SkillIndexServiceSourceRecordTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var skillsRoot: File
    private lateinit var service: SkillIndexService

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("eta.db")
        skillsRoot = File(temporaryFolder.newFolder("skills-root"), "skills").also { it.mkdirs() }
        service = SkillIndexService(context, skillsRoot)
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test
    fun installedUserSkillSourceRecordRoundTripsThroughIndexService() {
        writeSkill("alpha", description = "alpha skill")
        service.registerInstalledUserSkills(listOf("alpha"))

        assertNull("刚装上时还没有来源记录", service.readSkillSource("alpha"))

        val metadata = SkillSourceMetadata(
            repository = "openai/skills",
            ref = "main",
            path = "skills/.curated/alpha",
            commitSha = "1111111111111111111111111111111111111111",
            installedAtMillis = 1_700_000_000_000L,
        )
        assertTrue(service.recordSkillSource("alpha", metadata))
        assertEquals(metadata, service.readSkillSource("alpha"))
        assertEquals(mapOf("alpha" to metadata), service.readInstalledSkillSources())
        assertTrue(File(File(skillsRoot, "alpha"), SKILL_SOURCE_METADATA_FILE_NAME).isFile)
    }

    @Test
    fun skillWithoutSourceRecordIsAbsentFromInstalledSources() {
        writeSkill("alpha", description = "alpha skill")
        writeSkill("manual", description = "hand installed")
        service.registerInstalledUserSkills(listOf("alpha", "manual"))
        service.recordSkillSource(
            "alpha",
            SkillSourceMetadata(
                repository = "openai/skills",
                ref = "main",
                path = "alpha",
                commitSha = "1111111111111111111111111111111111111111",
            ),
        )

        val sources = service.readInstalledSkillSources()

        assertEquals(setOf("alpha"), sources.keys)
        assertNull(service.readSkillSource("manual"))
        // 无来源记录不影响 Skill 本身可用。
        assertTrue(service.findInstalledSkill("manual") != null)
    }

    @Test
    fun corruptSourceRecordDegradesToNoSourceAndSkillStaysUsable() {
        writeSkill("alpha", description = "alpha skill")
        service.registerInstalledUserSkills(listOf("alpha"))
        File(File(skillsRoot, "alpha"), SKILL_SOURCE_METADATA_FILE_NAME).writeText("{broken")

        assertNull(service.readSkillSource("alpha"))
        assertTrue(service.readInstalledSkillSources().isEmpty())
        assertTrue("损坏的来源记录不能让 Skill 不可用", service.findInstalledSkill("alpha") != null)
        // 损坏后仍可被重新写入（重新安装时就是这条路径）。
        assertTrue(
            service.recordSkillSource(
                "alpha",
                SkillSourceMetadata(
                    repository = "openai/skills",
                    ref = "main",
                    path = "alpha",
                    commitSha = "2222222222222222222222222222222222222222",
                ),
            ),
        )
    }

    @Test
    fun builtinSkillNeverGetsSourceRecord() {
        writeSkill("alpha", description = "alpha skill")
        service.registerInstalledUserSkills(listOf("alpha"))

        assertFalse(
            "内置 Skill 不存在来源仓库",
            service.recordSkillSource(
                "skill-creator",
                SkillSourceMetadata(
                    repository = "openai/skills",
                    ref = "main",
                    path = "skill-creator",
                    commitSha = "1111111111111111111111111111111111111111",
                ),
            ),
        )
        assertNull(service.readSkillSource("skill-creator"))
    }

    @Test
    fun deletingSkillRemovesItsSourceRecord() {
        writeSkill("alpha", description = "alpha skill")
        service.registerInstalledUserSkills(listOf("alpha"))
        service.recordSkillSource(
            "alpha",
            SkillSourceMetadata(
                repository = "openai/skills",
                ref = "main",
                path = "alpha",
                commitSha = "1111111111111111111111111111111111111111",
            ),
        )

        assertTrue(service.deleteSkill("alpha"))

        assertNull(service.readSkillSource("alpha"))
        assertFalse(File(skillsRoot, "alpha").exists())
    }

    private fun writeSkill(id: String, description: String) {
        val directory = File(skillsRoot, id).also { it.mkdirs() }
        File(directory, "SKILL.md").writeText(
            "---\nname: $id\ndescription: $description\n---\n\nbody",
        )
    }
}
