package io.github.mangi.eta.agent.skill

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SKILL.md 识别规则与 frontmatter 边界解析的回归测试。 */
class SkillFileNamesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun skillFileNamesAreRecognisedCaseInsensitivelyButNothingElseIs() {
        listOf("SKILL.md", "skill.md", "Skill.md", "sKiLl.Md").forEach { name ->
            assertTrue(name, SkillFileNames.isSkillFileName(name))
        }
        listOf(
            "SKILLS.md",
            "SKILL.md.bak",
            "SKILL.md ",
            " SKILL.md",
            "SKILL.mdx",
            "skill.markdown",
            "README.md",
            "SKILL",
            "SKILL.MD ",
            "",
        ).forEach { name ->
            assertFalse(name, SkillFileNames.isSkillFileName(name))
        }
    }

    @Test
    fun preferredVariantPrefersTheCanonicalNameThenFallsBackToStableOrdering() {
        assertEquals("SKILL.md", SkillFileNames.preferredVariant(listOf("skill.md", "SKILL.md")))
        assertEquals("SKILL.md", SkillFileNames.preferredVariant(listOf("SKILL.md", "skill.md")))
        assertEquals("Skill.md", SkillFileNames.preferredVariant(listOf("skill.md", "Skill.md")))
        assertEquals("skill.md", SkillFileNames.preferredVariant(listOf("skill.md")))
        assertNull(SkillFileNames.preferredVariant(listOf("README.md", "notes.md")))
        assertNull(SkillFileNames.preferredVariant(emptyList()))
    }

    @Test
    fun findResolvesCaseVariantsAndIgnoresSymlinkedSkillFiles() {
        val directory = temporaryFolder.newFolder("variant-skill")
        File(directory, "Skill.MD").writeText("---\nname: variant\n---\n")

        assertEquals("Skill.MD", SkillFileNames.find(directory)?.name)

        val external = temporaryFolder.newFile("external-skill.md")
        external.writeText("---\nname: external\n---\n")
        val linkedDirectory = temporaryFolder.newFolder("linked-variant-skill")
        try {
            Files.createSymbolicLink(File(linkedDirectory, "SKILL.md").toPath(), external.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertNull("符号链接的 Skill 文件不能被当成 Skill", SkillFileNames.find(linkedDirectory))
        assertNull(SkillFileNames.find(File(temporaryFolder.root, "missing-directory")))
    }

    @Test
    fun messageNamesStripControlCharactersAndTruncate() {
        assertEquals("a?b.md", SkillFileNames.describeForMessage("a\u0000b.md"))
        assertEquals("x".repeat(80) + "…", SkillFileNames.describeForMessage("x".repeat(200)))
        assertEquals("short.md", SkillFileNames.describeForMessage("short.md"))
    }

    @Test
    fun frontmatterBoundsAcceptBomAndCrlfAndRejectLongerDelimiterRuns() {
        val bom = "\uFEFF---\r\nname: bom-skill\r\ndescription: Saved by a Windows editor.\r\n---\r\n\r\n# Body\r\n"
        val bomBounds = requireNotNull(SkillParser.frontmatterBounds(bom))
        assertEquals(
            mapOf("name" to "bom-skill", "description" to "Saved by a Windows editor."),
            SkillParser.parseSimpleFrontmatter(bomBounds.frontmatter),
        )

        // `----` 不是 frontmatter 结束标记：旧实现搜索 "\n---" 会把它当成结束符。
        val extended = "---\nname: extended\n----\ndescription: after\n---\n"
        val extendedBounds = requireNotNull(SkillParser.frontmatterBounds(extended))
        assertEquals(
            "name: extended\n----\ndescription: after",
            extendedBounds.frontmatter,
        )

        assertNull(SkillParser.frontmatterBounds("# no frontmatter\n"))
        assertNull(SkillParser.frontmatterBounds("---\nname: unterminated\n"))
    }

    @Test
    fun parseSkillFileReadsBomPrefixedFileWithoutLosingFrontmatter() {
        val skillFile = File(temporaryFolder.newFolder("bom-skill"), "SKILL.md")
        skillFile.writeText("\uFEFF---\nname: bom-skill\ndescription: BOM tolerant.\n---\n\n# Body\n")

        val parsed = requireNotNull(SkillParser.parseSkillFile(skillFile))

        assertEquals("bom-skill", parsed.frontmatter["name"])
        assertEquals("BOM tolerant.", parsed.frontmatter["description"])
        assertEquals("# Body", parsed.body)
    }

    @Test
    fun parseSkillFileRefusesSymlinkedSkillFile() {
        val external = temporaryFolder.newFile("parse-external.md")
        external.writeText("---\nname: external\ndescription: Outside the tree.\n---\n")
        val directory = temporaryFolder.newFolder("parse-linked")
        val linked = File(directory, "SKILL.md")
        try {
            Files.createSymbolicLink(linked.toPath(), external.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertNull(SkillParser.parseSkillFile(linked))
    }
}
