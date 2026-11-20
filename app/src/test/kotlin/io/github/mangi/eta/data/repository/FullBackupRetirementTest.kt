package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FullBackupRetirementTest {
    private lateinit var context: Context
    private val retired = listOf(
        "files/knowledge-library/manifest-v1.json", "files/knowledge-library/manifest-v1.json.bak",
        "shared_prefs/eta_daily_notes.xml", "shared_prefs/eta_daily_notes.xml.bak",
        "shared_prefs/eta_quick_tiles.xml", "shared_prefs/eta_quick_tiles.xml.bak",
    )
    private val kept = "files/retirement-weather-fixture.txt"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        AgentMemoryRepository.replaceAll("retained-memory-fixture")
    }

    @After fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        retired.forEach { File(context.dataDir, it).delete() }
        File(context.dataDir, kept).delete()
        EtaFullBackupArchive.stagingDirectory(context).deleteRecursively()
        File(context.filesDir, "restore-staging-incoming").deleteRecursively()
        File(context.filesDir, "restore-staging-previous").deleteRecursively()
    }

    @Test fun fullExportOmitsRetiredSnapshotsAndKeepsWeatherAndMemory() = runBlocking {
        retired.forEach { write(it, "retired-fixture") }
        write(kept, "retained-weather-fixture")
        assertTrue(context.getSharedPreferences(WeatherPreferences.PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("use_current_location", true).commit())
        var closed = false
        var flushes = 0
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
            override fun flush() { flushes++; super.flush() }
        }
        EtaFullBackupArchive.export(context, output)
        assertFalse("The caller owns the output stream", closed)
        assertTrue("The internally buffered ZIP must be flushed before returning", flushes > 0)
        val entries = unzip(output.toByteArray())
        retired.forEach { assertFalse(it, entries.containsKey("private/$it")) }
        assertEquals("retained-weather-fixture", entries["private/$kept"])
        assertTrue(entries.containsKey("private/shared_prefs/${WeatherPreferences.PREF_NAME}.xml"))
        assertTrue(entries.getValue("eta-backup.json").contains("retained-memory-fixture"))
        assertTrue(WeatherPreferences.useCurrentLocation(context))
    }

    @Test fun fullExportReportsFlushFailuresWithoutClosingTheCallersStream() = runBlocking {
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
            override fun flush() { throw IOException("fixture flush failed") }
        }
        val error = runCatching { EtaFullBackupArchive.export(context, output) }.exceptionOrNull()
        assertTrue("Flush failure must fail the backup instead of returning success", error is IOException)
        assertFalse(closed)
    }

    @Test fun stagingAnOldArchiveFiltersRetiredSnapshotsAndPreservesWeather() = runBlocking {
        val old = archive(retired.associate { "private/$it" to "old-retired" } + ("private/$kept" to "retained-weather"))
        val result = EtaFullBackupArchive.stage(context, ByteArrayInputStream(old))
        assertEquals(1, result.privateFiles)
        val staged = File(EtaFullBackupArchive.stagingDirectory(context), "private")
        retired.forEach { assertFalse(it, File(staged, it).exists()) }
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        retired.forEach { assertFalse(it, File(context.dataDir, it).exists()) }
        assertEquals("retained-weather", File(context.dataDir, kept).readText())
    }

    @Test fun pendingOldStagingCannotReviveRetiredSnapshots() {
        val staged = File(EtaFullBackupArchive.stagingDirectory(context), "private")
        (retired.associateWith { "already-staged-retired" } + (kept to "retained-weather")).forEach { (path, text) ->
            File(staged, path).apply { parentFile?.mkdirs(); writeText(text) }
        }
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        retired.forEach { assertFalse(it, File(context.dataDir, it).exists()) }
        assertEquals("retained-weather", File(context.dataDir, kept).readText())
        assertFalse(EtaFullBackupArchive.hasPendingRestore(context))
    }

    @Test fun failedOrCancelledExtractionDoesNotPublishPartialRestore() = runBlocking {
        for (cancelled in listOf(false, true)) {
            var phases = 0
            val bytes = archive(mapOf("private/$kept" to "new-partial", "private/files/retirement-second.txt" to "second"))
            val error = runCatching {
                EtaFullBackupArchive.stage(context, ByteArrayInputStream(bytes)) {
                    if (++phases == 2) {
                        if (cancelled) throw CancellationException("fixture cancelled")
                        else throw IOException("fixture extraction failed")
                    }
                }
            }.exceptionOrNull()
            assertTrue(if (cancelled) error is CancellationException else error is IOException)
            assertFalse(EtaFullBackupArchive.hasPendingRestore(context))
            assertFalse(File(context.filesDir, "restore-staging-incoming").exists())
            assertFalse(File(context.dataDir, kept).exists())
        }
    }

    @Test fun failedExtractionPreservesAnAlreadyPublishedPendingRestore() = runBlocking {
        val existing = File(EtaFullBackupArchive.stagingDirectory(context), "private/$kept")
        existing.parentFile?.mkdirs()
        existing.writeText("old-complete")
        for (cancelled in listOf(false, true)) {
            var phases = 0
            val bytes = archive(mapOf("private/$kept" to "new-partial", "private/files/retirement-second.txt" to "second"))
            val error = runCatching {
                EtaFullBackupArchive.stage(context, ByteArrayInputStream(bytes)) {
                    if (++phases == 2) {
                        if (cancelled) throw CancellationException("fixture cancelled")
                        else throw IOException("fixture extraction failed")
                    }
                }
            }.exceptionOrNull()
            assertTrue(if (cancelled) error is CancellationException else error is IOException)
            assertTrue(EtaFullBackupArchive.hasPendingRestore(context))
            assertEquals("old-complete", existing.readText())
            assertFalse(File(context.filesDir, "restore-staging-incoming").exists())
        }
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        assertEquals("old-complete", File(context.dataDir, kept).readText())
    }

    @Test fun completedExtractionReplacesOldPendingOnlyAfterSuccess() = runBlocking {
        val existing = File(EtaFullBackupArchive.stagingDirectory(context), "private/$kept")
        existing.parentFile?.mkdirs()
        existing.writeText("old-complete")
        val bytes = archive(mapOf("private/$kept" to "new-complete"))
        assertEquals(1, EtaFullBackupArchive.stage(context, ByteArrayInputStream(bytes)).privateFiles)
        assertEquals("new-complete", existing.readText())
        assertFalse(File(context.filesDir, "restore-staging-previous").exists())
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        assertEquals("new-complete", File(context.dataDir, kept).readText())
    }

    @Test fun entryBoundaryTruncationCannotReplaceACompletePendingRestore() = runBlocking {
        val existing = File(EtaFullBackupArchive.stagingDirectory(context), "private/$kept")
        existing.parentFile?.mkdirs()
        existing.writeText("old-complete")
        val output = ByteArrayOutputStream()
        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry("eta-backup.json"))
        zip.write("{}".toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("private/$kept"))
        zip.write("partial-new".toByteArray()); zip.closeEntry(); zip.flush()
        val truncatedAtCompleteEntryBoundary = output.toByteArray()
        zip.close()
        assertTrue(truncatedAtCompleteEntryBoundary.size < output.toByteArray().size)
        val error = runCatching { EtaFullBackupArchive.stage(context,
            ByteArrayInputStream(truncatedAtCompleteEntryBoundary)) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("old-complete", existing.readText())
        assertTrue(EtaFullBackupArchive.hasPendingRestore(context))
        assertFalse(File(context.filesDir, "restore-staging-incoming").exists())
    }

    @Test fun corruptPrivateEntryCannotBePublished() = runBlocking {
        val content = "unique-retirement-crc-payload".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry("private/$kept").apply {
                method = ZipEntry.STORED; size = content.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(content) }.value
            }
            zip.putNextEntry(entry); zip.write(content); zip.closeEntry()
        }
        val corrupt = output.toByteArray()
        val offset = requireNotNull((0..corrupt.size - content.size).firstOrNull { start ->
            content.indices.all { corrupt[start + it] == content[it] }
        })
        corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
        val error = runCatching { EtaFullBackupArchive.stage(context, ByteArrayInputStream(corrupt)) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(EtaFullBackupArchive.hasPendingRestore(context))
        assertFalse(File(context.filesDir, "restore-staging-incoming").exists())
    }

    @Test fun interruptedStagingPublicationRecoversThePriorCompleteDirectory() {
        val prior = File(context.filesDir, "restore-staging-previous/private/$kept")
        prior.parentFile?.mkdirs()
        prior.writeText("prior-complete")
        assertTrue(EtaFullBackupArchive.hasPendingRestore(context))
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        assertEquals("prior-complete", File(context.dataDir, kept).readText())
        assertFalse(File(context.filesDir, "restore-staging-previous").exists())
    }

    @Test fun applyingNewPendingRestoreDoesNotReviveItsPreviousDirectory() {
        val current = File(EtaFullBackupArchive.stagingDirectory(context), "private/$kept")
        current.parentFile?.mkdirs()
        current.writeText("new-complete")
        val prior = File(context.filesDir, "restore-staging-previous/private/$kept")
        prior.parentFile?.mkdirs()
        prior.writeText("old-complete")
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        assertEquals("new-complete", File(context.dataDir, kept).readText())
        assertFalse(EtaFullBackupArchive.hasPendingRestore(context))
        assertFalse(EtaFullBackupArchive.applyPendingRestore(context))
        assertEquals("new-complete", File(context.dataDir, kept).readText())
    }

    private fun write(path: String, text: String) {
        File(context.dataDir, path).apply { parentFile?.mkdirs(); writeText(text) }
    }

    private fun archive(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
