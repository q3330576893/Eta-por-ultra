package io.github.mangi.eta.agent.quickactions

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.provider.CalendarContract
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DailyAgendaTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clock = Clock.fixed(Instant.parse("2026-11-16T16:30:00Z"), ZoneId.of("Asia/Shanghai"))
    private val start = Instant.parse("2026-11-16T16:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-11-17T16:00:00Z").toEpochMilli()

    @Before fun grantExistingCalendarPermission() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR)
    }

    @Test fun permissionMissingReturnsExplicitFailureBeforeQueryOrActivity() = runBlocking {
        val provider = install()
        shadowOf(context).denyPermissions(Manifest.permission.READ_CALENDAR)
        val result = QuickActionsExecutor.executeTodayAgenda(context, clock)
        assertTrue(result is DailyCommandResult.Failure)
        assertEquals("CALENDAR_PERMISSION_REQUIRED", (result as DailyCommandResult.Failure).code)
        assertTrue(result.summary.contains("READ_CALENDAR"))
        assertFalse(result.verified)
        assertEquals(0, provider.queries)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun instancesQueryUsesLocalDayOnlyAndProducesVerifiedFields() = runBlocking {
        val provider = install(listOf(Event("晨会", start + 3_600_000, start + 7_200_000)))
        val result = QuickActionsExecutor.executeTodayAgenda(context, clock)
        assertTrue(result is DailyCommandResult.CalendarAnswer)
        assertEquals(listOf(start.toString(), end.toString()), provider.uri!!.pathSegments.takeLast(2))
        assertTrue(provider.uri.toString().startsWith(CalendarContract.Instances.CONTENT_URI.toString() + "/"))
        assertEquals("20", provider.uri!!.getQueryParameter("limit"))
        assertEquals(listOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY), provider.projection)
        assertEquals("begin < ? AND end > ?", provider.selection)
        assertEquals(listOf(end.toString(), start.toString()), provider.selectionArgs)
        assertEquals("begin ASC, end ASC", provider.sortOrder)
        assertFalse(provider.wasMainThread)
        assertTrue(provider.hadCancellationSignal)
        assertEquals(DailyIntent.TodayAgenda, result.request)
        assertEquals(clock.instant(), result.asOf)
        assertEquals("设备日历", result.sourceLabel)
        assertTrue(result.verified)
        assertEquals(DailyResultField("日期", "2026-11-17"), result.fields[0])
        assertEquals(DailyResultField("时区", "Asia/Shanghai"), result.fields[1])
        assertEquals(DailyResultField("01:00–02:00", "晨会"), result.fields[2])
        assertTrue(provider.cursor!!.isClosed)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun daylightSavingDaysUseNextLocalMidnightRatherThanTwentyFourHours() = runBlocking {
        val examples = listOf(
            Triple("2026-03-08T16:00:00Z", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z"),
            Triple("2026-11-01T16:00:00Z", "2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z"),
        )
        examples.forEachIndexed { index, (now, beginning, finish) ->
            val provider = install()
            val localClock = Clock.fixed(Instant.parse(now), ZoneId.of("America/New_York"))
            val result = QuickActionsExecutor.executeTodayAgenda(context, localClock)
            assertTrue(result is DailyCommandResult.CalendarAnswer)
            val range = provider.uri!!.pathSegments.takeLast(2).map(String::toLong)
            assertEquals(listOf(Instant.parse(beginning).toEpochMilli(), Instant.parse(finish).toEpochMilli()), range)
            assertEquals(if (index == 0) 23L else 25L, Duration.ofMillis(range[1] - range[0]).toHours())
            assertEquals("今天的设备日历中暂无日程。", result.summary)
            assertTrue(provider.cursor!!.isClosed)
        }
    }

    @Test fun cursorAndFieldsAreBoundedToTwentyEventsAndEightyTitleCharacters() = runBlocking {
        val provider = install((0 until 25).map { Event("会议\n" + "长".repeat(300), start + 60_000, start + 120_000) })
        val result = QuickActionsExecutor.executeTodayAgenda(context, clock)
        assertTrue(result is DailyCommandResult.CalendarAnswer)
        assertEquals(22, result.fields.size)
        assertTrue(result.fields.drop(2).all { it.value.length <= 80 && '\n' !in it.value })
        assertTrue(result.summary.contains("最多显示 20 项"))
        assertEquals(20, provider.cursor!!.moves)
        assertTrue(provider.cursor!!.isClosed)
    }

    @Test fun halfOpenBoundsAndAllDayEventsStayWithinToday() = runBlocking {
        install(listOf(
            Event("昨天结束", start - 3_600_000, start),
            Event("跨入今天", start - 60_000, start + 1_800_000),
            Event(null, start, end, 1),
            Event("无效区间", start + 2_000_000, start + 2_000_000),
            Event("跨到明天", end - 1_800_000, end + 60_000),
            Event("明天开始", end, end + 3_600_000),
        ))
        val result = QuickActionsExecutor.executeTodayAgenda(context, clock)
        assertTrue(result is DailyCommandResult.CalendarAnswer)
        assertEquals(listOf(DailyResultField("00:00–00:30", "跨入今天"),
            DailyResultField("全天", "未命名日程"), DailyResultField("23:30–24:00", "跨到明天")), result.fields.drop(2))
    }

    @Test fun providerErrorsAndMissingColumnsReturnFailureAndCloseAnyCursor() = runBlocking {
        val denied = install().apply { failure = SecurityException("permission revoked") }
        val permissionResult = QuickActionsExecutor.executeTodayAgenda(context, clock) as DailyCommandResult.Failure
        assertEquals("CALENDAR_PERMISSION_REQUIRED", permissionResult.code)
        assertEquals(1, denied.queries)
        val missing = install().apply { missingColumns = true }
        val missingResult = QuickActionsExecutor.executeTodayAgenda(context, clock) as DailyCommandResult.Failure
        assertEquals("CALENDAR_UNAVAILABLE", missingResult.code)
        assertTrue(missing.cursor!!.isClosed)
        install().apply { nullCursor = true }
        assertEquals("CALENDAR_UNAVAILABLE",
            (QuickActionsExecutor.executeTodayAgenda(context, clock) as DailyCommandResult.Failure).code)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun coroutineCancellationCancelsAnInFlightProviderQuery() = runBlocking {
        val provider = install().apply { blockQuery = true }
        val pending = async(Dispatchers.Default) { QuickActionsExecutor.executeTodayAgenda(context, clock) }
        try {
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
            pending.cancel()
            assertTrue(provider.cancelled.await(5, TimeUnit.SECONDS))
            pending.join()
            assertTrue(pending.isCancelled)
        } finally {
            provider.release.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun coroutineCancellationDuringCursorReadAlsoClosesCursor() = runBlocking {
        val provider = install(listOf(Event("会议", start + 60_000, start + 120_000))).apply { blockMove = true }
        val pending = async(Dispatchers.Default) { QuickActionsExecutor.executeTodayAgenda(context, clock) }
        try {
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS))
            pending.cancel()
            assertTrue(provider.cancelled.await(5, TimeUnit.SECONDS))
            pending.join()
            assertTrue(pending.isCancelled)
            assertTrue(provider.cursor!!.isClosed)
        } finally {
            provider.release.countDown()
            pending.cancelAndJoin()
        }
    }

    private fun install(events: List<Event> = emptyList()): CalendarProvider = CalendarProvider(events).also {
        it.attachInfo(context, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, it)
    }

    private data class Event(val title: String?, val begin: Long, val end: Long, val allDay: Int = 0)

    private class TrackingCursor(columns: Array<String>, private val move: (() -> Unit)? = null) : MatrixCursor(columns) {
        var moves = 0
        override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
            moves++
            move?.invoke()
            return super.onMove(oldPosition, newPosition)
        }
    }

    private class CalendarProvider(private val events: List<Event>) : ContentProvider() {
        var queries = 0
        var uri: Uri? = null
        var projection: List<String>? = null
        var selection: String? = null
        var selectionArgs: List<String>? = null
        var sortOrder: String? = null
        var wasMainThread = true
        var hadCancellationSignal = false
        var failure: RuntimeException? = null
        var nullCursor = false
        var missingColumns = false
        var blockQuery = false
        var blockMove = false
        var cursor: TrackingCursor? = null
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun onCreate() = true
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/event"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            query(uri, projection, selection, selectionArgs, sortOrder, null)

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? {
            queries++
            this.uri = uri
            this.projection = projection?.toList()
            this.selection = selection
            this.selectionArgs = selectionArgs?.toList()
            this.sortOrder = sortOrder
            wasMainThread = Looper.myLooper() == Looper.getMainLooper()
            hadCancellationSignal = cancellationSignal != null
            failure?.let { throw it }
            if (blockQuery) awaitCancellation(requireNotNull(cancellationSignal))
            if (nullCursor) return null
            val columns = if (missingColumns) arrayOf(CalendarContract.Instances.TITLE) else requireNotNull(projection).toList().toTypedArray()
            return TrackingCursor(columns, if (blockMove) ({ awaitCancellation(requireNotNull(cancellationSignal)) }) else null).also { rows ->
                events.forEach { event -> rows.addRow(columns.map<String, Any?> { column -> when (column) {
                    CalendarContract.Instances.TITLE -> event.title
                    CalendarContract.Instances.BEGIN -> event.begin
                    CalendarContract.Instances.END -> event.end
                    CalendarContract.Instances.ALL_DAY -> event.allDay
                    else -> null
                } }.toTypedArray()) }
                cursor = rows
            }
        }

        private fun awaitCancellation(signal: CancellationSignal) {
            signal.setOnCancelListener { cancelled.countDown(); release.countDown() }
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "calendar cancellation timed out" }
            signal.throwIfCanceled()
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("write attempted")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("write attempted")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("write attempted")
    }
}
