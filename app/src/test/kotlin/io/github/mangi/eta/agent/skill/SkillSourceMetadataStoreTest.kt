package io.github.mangi.eta.agent.skill

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `.eta-source.json` 往返、损坏容错与字段校验。 */
class SkillSourceMetadataStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun metadataRoundTripsThroughSkillRootFile() {
        val skillDir = newSkillDir("round-trip")
        val metadata = sampleMetadata()

        assertTrue(SkillSourceMetadataStore.write(skillDir, metadata))
        assertTrue(File(skillDir, SKILL_SOURCE_METADATA_FILE_NAME).isFile)

        val restored = SkillSourceMetadataStore.read(skillDir)
        assertEquals(metadata, restored)
        assertEquals(SKILL_SOURCE_METADATA_SCHEMA_VERSION, json(skillDir).getInt("schemaVersion"))
    }

    @Test
    fun missingMetadataFileReadsAsNoSource() {
        val skillDir = newSkillDir("no-source")
        assertNull(SkillSourceMetadataStore.read(skillDir))
    }

    @Test
    fun corruptOrOversizedMetadataReadsAsNoSourceInsteadOfFailing() {
        val skillDir = newSkillDir("corrupt")
        val file = File(skillDir, SKILL_SOURCE_METADATA_FILE_NAME)

        file.writeText("{not json")
        assertNull(SkillSourceMetadataStore.read(skillDir))

        // 合法 JSON 但字段缺失/非法：同样视为「无来源」，不能抛给调用方。
        file.writeText(JSONObject().put("schemaVersion", 1).put("repository", "not a slug").toString())
        assertNull(SkillSourceMetadataStore.read(skillDir))

        file.writeText(JSONObject().put("schemaVersion", 999).toString())
        assertNull(SkillSourceMetadataStore.read(skillDir))

        // 超大文件（远超 4 KiB 上限）：直接放弃，不做解析。
        file.writeText("x".repeat(8 * 1024))
        assertNull(SkillSourceMetadataStore.read(skillDir))
    }

    @Test
    fun invalidFieldsAreRejectedInsteadOfWritten() {
        val skillDir = newSkillDir("invalid-write")

        assertFalse(SkillSourceMetadataStore.write(skillDir, sampleMetadata().copy(repository = "../escape")))
        assertFalse(SkillSourceMetadataStore.write(skillDir, sampleMetadata().copy(path = "../escape")))
        assertFalse(SkillSourceMetadataStore.write(skillDir, sampleMetadata().copy(commitSha = "abc")))
        assertFalse(File(skillDir, SKILL_SOURCE_METADATA_FILE_NAME).exists())
    }

    @Test
    fun parserAcceptsRepositoryRootPathAndRejectsTraversal() {
        assertEquals(".", parseSkillSourceMetadata(serialize(sampleMetadata().copy(path = ".")))?.path)
        assertNull(parseSkillSourceMetadata(serialize(sampleMetadata().copy(path = "a/../b"))))
        assertNull(parseSkillSourceMetadata(serialize(sampleMetadata().copy(path = "/abs"))))
        assertNull(parseSkillSourceMetadata(serialize(sampleMetadata().copy(ref = " "))))
    }

    @Test
    fun commitScopeDefaultsToRefAndSurvivesRoundTrip() {
        assertEquals(SKILL_COMMIT_SCOPE_REF, sampleMetadata().commitScope)
        val pathScoped = sampleMetadata().copy(commitScope = SKILL_COMMIT_SCOPE_PATH, path = "skills/alpha")
        assertEquals(SKILL_COMMIT_SCOPE_PATH, parseSkillSourceMetadata(serialize(pathScoped))?.commitScope)
        // ref 口径检查更新时查询仓库根，而不是记录的 path。
        assertEquals(".", sampleMetadata().lookupPath)
        assertEquals(
            "skills/alpha",
            sampleMetadata().copy(commitScope = SKILL_COMMIT_SCOPE_PATH, path = "skills/alpha").lookupPath,
        )
    }

    @Test
    fun metadataFileIsNotExposedAsSkillResource() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "hidden").also { it.mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: hidden\ndescription: d\n---\nbody")
        File(skillDir, "references").mkdirs()
        File(skillDir, "references/guide.md").writeText("guide")
        assertTrue(SkillSourceMetadataStore.write(skillDir, sampleMetadata()))

        val reader = SkillResourceReader(skillsRoot)
        val entry = SkillIndexEntry(
            id = "hidden",
            name = "hidden",
            description = "d",
            rootPath = skillDir.canonicalPath,
            skillFilePath = File(skillDir, "SKILL.md").absolutePath,
            hasScripts = false,
            hasReferences = true,
            hasAssets = false,
            hasEvals = false,
        )

        val listed = reader.listResources(entry, ".") as SkillResourceListResult.Success
        assertFalse(
            "来源记录不能被当成 Skill 资源列出",
            listed.resources.any { it.relativePath.contains(SKILL_SOURCE_METADATA_FILE_NAME) },
        )
        assertTrue(listed.resources.any { it.relativePath == "references/guide.md" })

        val direct = reader.readText(entry, SKILL_SOURCE_METADATA_FILE_NAME)
        assertTrue("按路径直读也必须拒绝", direct is SkillResourceReadResult.Failure)
    }

    private fun newSkillDir(name: String): File {
        val skillsRoot = temporaryFolder.newFolder("skills-$name")
        val skillDir = File(skillsRoot, "demo").also { it.mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: demo\ndescription: d\n---\nbody")
        return skillDir
    }

    private fun json(skillDir: File): JSONObject =
        JSONObject(File(skillDir, SKILL_SOURCE_METADATA_FILE_NAME).readText())

    private fun serialize(metadata: SkillSourceMetadata): String = serializeSkillSourceMetadata(metadata)

    private fun sampleMetadata() = SkillSourceMetadata(
        repository = "openai/skills",
        ref = "main",
        path = "skills/.curated/alpha",
        commitSha = "1111111111111111111111111111111111111111",
        installedAtMillis = 1_700_000_000_000L,
    )
}
