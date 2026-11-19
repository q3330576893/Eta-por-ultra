package io.github.mangi.eta.agent.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.quickactions.NoteStore
import java.util.concurrent.Executors

/** Displays local pending notes; checked-state intents set an explicit value and are safe to redeliver. */
internal class MemoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = updateAll(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_COMPLETE) return
        val id = intent.getStringExtra(EXTRA_NOTE_ID)?.takeIf { it.isNotBlank() && it.length <= 150 } ?: return
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try { NoteStore.setCompleted(app, id, true) }
            catch (_: Exception) { updateAll(app, app.getString(R.string.memo_save_failed)) }
            finally { pending.finish() }
        }
    }

    companion object {
        internal const val ACTION_COMPLETE = "io.github.mangi.eta.memo.COMPLETE"
        internal const val EXTRA_NOTE_ID = "noteId"
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "eta-memo-widget").apply { isDaemon = true } }
        private val rowIds = intArrayOf(R.id.memo_widget_row1, R.id.memo_widget_row2, R.id.memo_widget_row3)
        private val textIds = intArrayOf(R.id.memo_widget_text1, R.id.memo_widget_text2, R.id.memo_widget_text3)
        private val checkIds = intArrayOf(R.id.memo_widget_check1, R.id.memo_widget_check2, R.id.memo_widget_check3)

        internal fun completeIntent(context: Context, noteId: String): Intent = Intent(context, MemoWidgetProvider::class.java)
            .setAction(ACTION_COMPLETE).setData(Uri.parse("eta-memo://complete").buildUpon().appendPath(noteId).build())
            .putExtra(EXTRA_NOTE_ID, noteId)

        fun updateAll(context: Context, notice: String? = null) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, MemoWidgetProvider::class.java))
            if (ids.isEmpty()) return
            var error = notice
            val notes = try { NoteStore.all(app).filterNot { it.completed }.take(3) }
                catch (_: Exception) { error = app.getString(R.string.memo_load_failed); emptyList() }
            ids.forEach { widgetId ->
                val views = RemoteViews(app.packageName, R.layout.widget_memo_inbox)
                val open = PendingIntent.getActivity(app, widgetId, MemoInboxActivity.intent(app),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.memo_widget_title, open)
                views.setOnClickPendingIntent(R.id.memo_widget_empty, open)
                views.setTextViewText(R.id.memo_widget_title, app.getString(R.string.memo_title))
                views.setTextViewText(R.id.memo_widget_empty, error ?: app.getString(R.string.memo_widget_empty))
                views.setViewVisibility(R.id.memo_widget_empty, if (notes.isEmpty() || error != null) View.VISIBLE else View.GONE)
                for (index in 0..2) {
                    val note = notes.getOrNull(index)
                    views.setViewVisibility(rowIds[index], if (note == null) View.GONE else View.VISIBLE)
                    if (note != null) {
                        views.setTextViewText(textIds[index], note.text)
                        views.setBoolean(checkIds[index], "setChecked", false)
                        views.setContentDescription(checkIds[index], app.getString(R.string.memo_complete_accessibility, note.text.take(50)))
                        views.setOnClickPendingIntent(textIds[index], open)
                        views.setOnClickPendingIntent(checkIds[index], PendingIntent.getBroadcast(app, widgetId,
                            completeIntent(app, note.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }
}
