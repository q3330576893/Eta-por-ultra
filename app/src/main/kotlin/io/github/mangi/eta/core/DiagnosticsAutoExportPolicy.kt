package io.github.mangi.eta.core

import java.io.File

/**
 * Pure scheduling/directory policy for weekly diagnostic export.
 *
 * The device log showed 1194 FileNotFoundException failures: the fixed shared directory was
 * unwritable, so "last successful export" never advanced and the alarm retried every minute.
 * Keep these decisions independent from AlarmManager/Android filesystem for regression tests.
 */
internal object DiagnosticsAutoExportPolicy {
    const val INTERVAL_MS: Long = 7L * 24 * 3_600_000
    const val MIN_DELAY_MS: Long = 60_000
    /** After a failed attempt, back off a full week instead of retrying every minute. */
    const val FAILURE_BACKOFF_MS: Long = INTERVAL_MS
    const val KEEP_FILES = 4
    const val FILE_PREFIX = "Eta-诊断包-"

    /** Preferred shared directory; mirrors [DiagnosticsBundle.DEFAULT_DIR] plus /自动. */
    const val PREFERRED_DIR = "/storage/emulated/0/ai共享文件夹/诊断包备份/自动"

    /** Return the first writable candidate. Private fallback always exists for an app context. */
    fun resolveDirectory(preferred: File, fallback: File): File {
        if (preferred.mkdirs() || preferred.isDirectory) {
            val probe = File(preferred, ".eta-write-probe")
            if (runCatching { probe.createNewFile(); probe.delete() }.getOrDefault(false)) return preferred
        }
        fallback.mkdirs()
        return fallback
    }

    fun latestExportTime(directory: File, nowMillis: Long): Long =
        runCatching {
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".zip") }
                ?.map(File::lastModified)
                ?.filter { it in 0..nowMillis }
                ?.maxOrNull()
                ?: 0L
        }.getOrDefault(0L)

    data class Schedule(
        val triggerAtMillis: Long,
        val reason: String,
    )

    /** next = last success + week; initial = one minute; failure = one week backoff. */
    fun nextSchedule(
        lastSuccessAt: Long,
        lastFailureAt: Long,
        nowMillis: Long,
    ): Schedule {
        val afterSuccess = if (lastSuccessAt > 0L) lastSuccessAt + INTERVAL_MS else nowMillis + MIN_DELAY_MS
        val afterFailure = if (lastFailureAt > 0L) lastFailureAt + FAILURE_BACKOFF_MS else Long.MIN_VALUE
        val candidate = maxOf(afterSuccess, afterFailure)
        return Schedule(
            triggerAtMillis = maxOf(candidate, nowMillis + MIN_DELAY_MS),
            reason = when {
                afterFailure > afterSuccess -> "failure-backoff"
                lastSuccessAt <= 0L -> "initial"
                else -> "weekly"
            },
        )
    }

    fun filesToPrune(directory: File, keepName: String): List<File> =
        runCatching {
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".zip") }
                ?.sortedByDescending(File::lastModified)
                ?.drop(KEEP_FILES)
                ?.filter { it.name != keepName }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
}
