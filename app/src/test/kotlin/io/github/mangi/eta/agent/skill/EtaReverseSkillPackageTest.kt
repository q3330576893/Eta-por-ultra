package io.github.mangi.eta.agent.skill

import android.app.Application
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaReverseSkillPackageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @After fun tearDown() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test fun optionalReversePackageImportsThroughTheExistingUserSkillInstaller() {
        val project = projectRoot()
        val context = RuntimeEnvironment.getApplication()
        val skillsRoot = temporary.newFolder("skills")
        val service = SkillIndexService(context, skillsRoot)
        assertTrue(service.listSkillsForManagement().none { it.id == ID })
        val source = File(project, "app/src/main/assets/reverse/EtaReverse.java").readBytes()
        val entries = linkedMapOf(
            "SKILL.md" to File(project, "plugins/$ID/SKILL.md").readBytes(),
            "README.md" to File(project, "plugins/$ID/README.md").readBytes(),
            "LICENSE" to File(project, "LICENSE").readBytes(),
            "assets/EtaReverse.java" to source,
        )
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
                }
            }
        }.toByteArray()
        val result = SkillPackageInstaller(skillsRoot, service).installLocalZip({ bytes.inputStream() })
        assertTrue(result.toString(), result is SkillInstallResult.Success)
        assertEquals(listOf(ID), (result as SkillInstallResult.Success).installed.map { it.id })
        val installed = service.listSkillsForManagement(forceRefresh = true).single { it.id == ID }
        assertEquals(USER_SKILL_SOURCE, installed.source)
        assertTrue("The explicit import is the user's enable action", installed.enabled)
        assertFalse("The package contains source resources, not an auto-run script", installed.hasScripts)
        val retainedSource = File(skillsRoot, "$ID/assets/EtaReverse.java").readBytes()
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source), MessageDigest.getInstance("SHA-256").digest(retainedSource))
        assertTrue(File(skillsRoot, "$ID/SKILL.md").readText().contains("eta-reverse probe"))
    }

    @Test fun reverseWorkflowIsOptionalAndDoesNotSeedAnEnabledBuiltinOnUpgrade() {
        val context = RuntimeEnvironment.getApplication()
        val manifest = context.assets.open("builtin_skills/manifest.json").bufferedReader().use { it.readText() }
        assertFalse(manifest.contains(ID))
        val project = projectRoot()
        val body = File(project, "plugins/$ID/SKILL.md").readText()
        assertTrue(body.startsWith("---\nname: $ID\n"))
        assertTrue("Slash-command content remains within the existing budget", body.length < 6000)
        assertTrue(body.contains("environment=linux"))
        assertTrue(body.contains("report_sha256"))
    }

    private fun projectRoot(): File {
        val current = File(System.getProperty("user.dir")).canonicalFile
        return listOfNotNull(current, current.parentFile).firstOrNull {
            File(it, "app/src/main/assets/reverse/EtaReverse.java").isFile && File(it, "plugins/$ID/SKILL.md").isFile
        } ?: error("Run the reverse-package tests from the Eta checkout or app module")
    }

    private companion object { const val ID = "eta-reverse-analysis" }
}
