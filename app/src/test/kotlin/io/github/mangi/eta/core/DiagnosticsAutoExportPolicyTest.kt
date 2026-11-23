package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsAutoExportPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failureBacksOffAWholeWeekInsteadOfOneMinute() {
        val now = 1_700_000_000_000L
        val failedAt = now - 1_000L
        val scheduled = DiagnosticsAutoExportPolicy.nextSchedule(
            lastSuccessAt = 0L,
            lastFailureAt = failedAt,
            nowMillis = now,
        )
        assertEquals("failure-backoff", scheduled.reason)
        assertEquals(failedAt + DiagnosticsAutoExportPolicy.FAILURE_BACKOFF_MS, scheduled.triggerAtMillis)
        assertTrue(scheduled.triggerAtMillis >= now + DiagnosticsAutoExportPolicy.FAILURE_BACKOFF_MS - 1_000L)
        assertFalse(scheduled.triggerAtMillis <= now + DiagnosticsAutoExportPolicy.MIN_DELAY_MS)
    }

    @Test
    fun firstRunStillSchedulesOneMinuteLater() {
        val now = 1_700_000_000_000L
        val scheduled = DiagnosticsAutoExportPolicy.nextSchedule(0L, 0L, now)
        assertEquals("initial", scheduled.reason)
        assertEquals(now + DiagnosticsAutoExportPolicy.MIN_DELAY_MS, scheduled.triggerAtMillis)
    }

    @Test
    fun successfulFileAdvancesTheWeeklyScheduleEvenAfterAnOldFailure() {
        val now = 1_700_000_000_000L
        val lastSuccess = now - DiagnosticsAutoExportPolicy.INTERVAL_MS + 60_000L
        val directory = temporaryFolder.newFolder("exports")
        val name = DiagnosticsAutoExportPolicy.FILE_PREFIX + "20261122-2321.zip"
        File(directory, name).apply { writeBytes(byteArrayOf(1)); setLastModified(lastSuccess) }
        val observed = DiagnosticsAutoExportPolicy.latestExportTime(directory, now)
        assertEquals(lastSuccess, observed)
        val scheduled = DiagnosticsAutoExportPolicy.nextSchedule(observed, lastSuccess - 10_000L, now)
        assertEquals("weekly", scheduled.reason)
        assertEquals(now + 60_000L, scheduled.triggerAtMillis)
    }

    @Test
    fun unwritablePreferredDirectoryFallsBackAndPruneKeepsTheNewestFour() {
        val preferred = File(temporaryFolder.newFolder("parent"), "missing")
        val fallback = temporaryFolder.newFolder("fallback")
        // A regular file cannot become a directory, so mkdirs() fails deterministically.
        val blocked = File(temporaryFolder.root, "blocked")
        blocked.writeText("not a directory")
        val resolved = DiagnosticsAutoExportPolicy.resolveDirectory(blocked, fallback)
        assertEquals(fallback.absolutePath, resolved.absolutePath)
        assertFalse(preferred.exists())

        val names = (0..6).map { DiagnosticsAutoExportPolicy.FILE_PREFIX + "f$it.zip" }
        names.forEachIndexed { index, fileName ->
            File(fallback, fileName).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_700_000_000_000L + index * 60_000L)
            }
        }
        val keep = names.last()
        val pruned = DiagnosticsAutoExportPolicy.filesToPrune(fallback, keep)
        assertEquals(3, pruned.size)
        assertFalse(pruned.any { it.name == keep })
        assertTrue(File(fallback, keep).exists())
    }
}
