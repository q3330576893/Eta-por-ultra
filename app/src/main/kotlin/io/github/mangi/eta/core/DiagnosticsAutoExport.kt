package io.github.mangi.eta.core

import android.content.Context
import java.io.File

internal object DiagnosticsAutoExport {
    private const val REQUEST_CODE = 0x0E7B
    private const val FAILURE_PREFS = "eta_diag_auto_export"
    private const val KEY_LAST_FAILURE = "last_failure_at"

    private val preferredDirectory = File(DiagnosticsAutoExportPolicy.PREFERRED_DIR)

    /** Public for tests: choose writable shared dir, otherwise app-private fallback. */
    fun directory(context: Context): File =
        DiagnosticsAutoExportPolicy.resolveDirectory(
            preferredDirectory,
            File(context.applicationContext.filesDir, "diagnostics-auto"),
        )

    fun refresh(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val schedule = DiagnosticsAutoExportPolicy.nextSchedule(
            lastSuccessAt = DiagnosticsAutoExportPolicy.latestExportTime(directory(app), nowMillis),
            lastFailureAt = failureTime(app),
            nowMillis = nowMillis,
        )
        EtaTrace.log("diag.auto.schedule", "下次 ${schedule.reason} +${schedule.triggerAtMillis - nowMillis}ms")
        runCatching {
            app.getSystemService(android.app.AlarmManager::class.java)
                .setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    schedule.triggerAtMillis,
                    pending(app),
                )
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(android.app.AlarmManager::class.java).cancel(pending(app)) }
    }

    suspend fun runOnce(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val targetDir = directory(app)
        runCatching {
            val bytes = DiagnosticsBundle.collect(app)
            val target = File(targetDir, DiagnosticsBundle.defaultFileName(nowMillis))
            target.writeBytes(bytes)
            app.getSharedPreferences(FAILURE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_FAILURE).apply()
            EtaTrace.log("diag.auto", "已自动导出 ${target.name} ${bytes.size} 字节 → ${targetDir.absolutePath}")
            DiagnosticsAutoExportPolicy.filesToPrune(targetDir, target.name).forEach { runCatching { it.delete() } }
        }.onFailure {
            app.getSharedPreferences(FAILURE_PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_FAILURE, nowMillis).apply()
            EtaTrace.log("diag.auto.fail", "自动导出失败（${targetDir.absolutePath}）：${it.javaClass.simpleName}")
        }
        refresh(app, System.currentTimeMillis())
    }

    private fun failureTime(context: Context): Long =
        runCatching {
            context.getSharedPreferences(FAILURE_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_FAILURE, 0L)
        }.getOrDefault(0L)

    private fun pending(context: Context): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            android.content.Intent(context, DiagnosticsAutoExportReceiver::class.java)
                .setAction(DiagnosticsAutoExportReceiver.ACTION_EXPORT),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
}
