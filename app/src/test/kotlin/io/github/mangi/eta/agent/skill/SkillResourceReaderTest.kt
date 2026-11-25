package io.github.mangi.eta.agent.skill

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillResourceReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun listsNestedResourcesAsRelativePathsAndReadsUtf8Text() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillRoot = File(skillsRoot, "reader").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText("main")
        val nested = File(skillRoot, "references/nested/guide.md")
        nested.parentFile?.mkdirs()
        nested.writeText("嵌套说明")
        val entry = entry(skillRoot)
        val reader = SkillResourceReader(skillsRoot)

        val listed = reader.listResources(entry, "references") as SkillResourceListResult.Success
        assertEquals(listOf("references/nested/guide.md"), listed.resources.map { it.relativePath })

        val read = reader.readText(entry, "references/nested/guide.md") as
            SkillResourceReadResult.Success
        assertEquals("嵌套说明", read.text)
    }

    @Test
    fun rejectsTraversalAbsoluteAndBackslashPaths() {
        val skillsRoot = temporaryFolder.newFolder("traversal-skills")
        val skillRoot = File(skillsRoot, "reader").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText("main")
        val reader = SkillResourceReader(skillsRoot)
        val entry = entry(skillRoot)

        listOf(
            "../secret.txt",
            "/absolute.txt",
            "references\\guide.md",
            "C:/windows/system.ini",
        ).forEach { path ->
            val result = reader.readText(entry, path) as SkillResourceReadResult.Failure
            assertEquals(SkillResourceErrorCode.INVALID_RELATIVE_PATH, result.error.code)
        }
    }

    @Test
    fun rejectsBinaryAndOversizedResources() {
        val skillsRoot = temporaryFolder.newFolder("bounded-skills")
        val skillRoot = File(skillsRoot, "reader").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText("main")
        File(skillRoot, "references").mkdirs()
        File(skillRoot, "references/binary.bin").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        File(skillRoot, "references/large.txt").writeText("12345")
        val reader = SkillResourceReader(
            skillsRoot = skillsRoot,
            limits = SkillResourceLimits(maxTextBytes = 4),
        )
        val entry = entry(skillRoot)

        val binary = reader.readText(entry, "references/binary.bin") as
            SkillResourceReadResult.Failure
        assertEquals(SkillResourceErrorCode.BINARY_RESOURCE, binary.error.code)

        val large = reader.readText(entry, "references/large.txt") as
            SkillResourceReadResult.Failure
        assertEquals(SkillResourceErrorCode.RESOURCE_TOO_LARGE, large.error.code)
    }

    @Test
    fun rejectsEntryWhoseRootIsOutsideSkillsDirectory() {
        val skillsRoot = temporaryFolder.newFolder("private-skills")
        val external = temporaryFolder.newFolder("external-skill")
        File(external, "SKILL.md").writeText("main")
        val result = SkillResourceReader(skillsRoot).readText(
            entry = entry(external),
            relativePath = "SKILL.md",
        )

        val failure = result as SkillResourceReadResult.Failure
        assertEquals(SkillResourceErrorCode.INVALID_SKILL_ROOT, failure.error.code)
    }

    @Test
    fun loaderDiscoversNestedReferencesWithoutExposingAbsolutePaths() {
        val skillsRoot = temporaryFolder.newFolder("loader-skills")
        val skillRoot = File(skillsRoot, "reader").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText(
            "---\nname: reader\ndescription: Reader fixture.\n---\n\n# Reader"
        )
        val nested = File(skillRoot, "references/nested/guide.md")
        nested.parentFile?.mkdirs()
        nested.writeText("guide")

        val loaded = SkillLoader(skillsRoot).load(entry(skillRoot), "test")

        assertEquals(listOf("references/nested/guide.md"), loaded?.loadedReferences)
        assertTrue(loaded?.loadedReferences.orEmpty().none { it.startsWith('/') })
    }

    @Test
    fun rejectsWindowsDrivePrefixEvenThoughFileWouldTreatItAsAbsolute() {
        val skillsRoot = temporaryFolder.newFolder("drive-prefix-skills")
        val skillRoot = File(skillsRoot, "reader").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText("main")
        val reader = SkillResourceReader(skillsRoot)
        val entry = entry(skillRoot)

        listOf("C:/temp/x.txt", "C:x.txt", "c:/windows/win.ini").forEach { path ->
            val result = reader.readText(entry, path) as SkillResourceReadResult.Failure
            assertEquals(
                path,
                SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                result.error.code,
            )
        }
    }

    @Test
    fun rejectsSymlinkedResourceFileAndSymlinkedDirectory() {
        val skillsRoot = temporaryFolder.newFolder("symlink-resource-skills")
        val skillRoot = File(skillsRoot, "reader").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText("main")
        val external = temporaryFolder.newFolder("symlink-resource-external")
        val secret = File(external, "secret.txt").also { it.writeText("outside") }
        try {
            Files.createSymbolicLink(File(skillRoot, "references").toPath(), external.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val reader = SkillResourceReader(skillsRoot)
        val entry = entry(skillRoot)

        val read = reader.readText(entry, "references/secret.txt") as SkillResourceReadResult.Failure
        assertEquals(SkillResourceErrorCode.INVALID_RELATIVE_PATH, read.error.code)
        val listed = reader.listResources(entry, "references") as SkillResourceListResult.Failure
        assertEquals(SkillResourceErrorCode.INVALID_RELATIVE_PATH, listed.error.code)
        assertEquals("outside", secret.readText())
    }

    @Test
    fun listResourcesRejectsWideDirectoryTreesBeyondScanBudget() {
        val skillsRoot = temporaryFolder.newFolder("wide-resource-skills")
        val skillRoot = File(skillsRoot, "reader").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText("main")
        repeat(20) { index ->
            File(skillRoot, "references/dir-$index").mkdirs()
        }
        val reader = SkillResourceReader(
            skillsRoot = skillsRoot,
            limits = SkillResourceLimits(maxScannedEntries = 5),
        )

        val result = reader.listResources(entry(skillRoot), "references")
            as SkillResourceListResult.Failure

        assertEquals(SkillResourceErrorCode.TOO_MANY_RESOURCES, result.error.code)
    }

    private fun entry(root: File, enabled: Boolean = true): SkillIndexEntry = SkillIndexEntry(
        id = "reader",
        name = "reader",
        description = "Reader fixture.",
        rootPath = root.canonicalPath,
        skillFilePath = File(root, "SKILL.md").canonicalPath,
        hasScripts = false,
        hasReferences = true,
        hasAssets = false,
        hasEvals = false,
        enabled = enabled,
    )
}
