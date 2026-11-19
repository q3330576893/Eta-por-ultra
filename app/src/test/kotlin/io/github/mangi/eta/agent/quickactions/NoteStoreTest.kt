package io.github.mangi.eta.agent.quickactions

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NoteStoreTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences(NoteStore.PREF_NAME, Context.MODE_PRIVATE)
    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun legacyEscapedSeparatorsMigrateVerbatimAndKeepStableIdentities() {
        val body = "路径\\文件\u001f字段\u001e下一条"
        prefs.edit().putString(NoteStore.KEY_NOTES, NoteStore.encode(listOf(NoteStore.Note(body, 1L), NoteStore.Note("第二条", 2L)))).commit()
        val migrated = NoteStore.all(prefs)
        assertEquals(body, migrated.first().text)
        assertEquals(2, migrated.size)
        assertFalse(prefs.contains(NoteStore.KEY_NOTES))
        assertTrue(prefs.contains(NoteStore.KEY_VERSIONED_NOTES))
        assertEquals(migrated, NoteStore.all(prefs))
    }

    @Test fun failedMigrationCommitKeepsOriginalDataAndReportsFailure() {
        val legacy = NoteStore.encode(listOf(NoteStore.Note("重要资料", 1L)))
        prefs.edit().putString(NoteStore.KEY_NOTES, legacy).commit()
        val delegate = prefs
        val failurePrefs = object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit() = false
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { NoteStore.all(failurePrefs) }
        assertEquals(legacy, prefs.getString(NoteStore.KEY_NOTES, null))
        assertFalse(prefs.contains(NoteStore.KEY_VERSIONED_NOTES))
    }

    @Test fun longSharedNotesAreKeptAndOverLimitInputNeverReplacesExistingNotes() {
        val text = "分享文字 https://example.test/path\n".repeat(40)
        NoteStore.add(prefs, text, 10L)
        assertEquals(text, NoteStore.all(prefs).single().text)
        assertThrows(IllegalArgumentException::class.java) { NoteStore.add(prefs, "文".repeat(NoteStore.MAX_NOTE_LENGTH + 1), 20L) }
        assertEquals(text, NoteStore.all(prefs).single().text)
    }

    @Test fun repeatedShareAndCompletionIntentsAreIdempotent() {
        repeat(2) { NoteStore.add(prefs, "准备材料", 1L, "share-once") }
        assertEquals(1, NoteStore.all(prefs).size)
        repeat(2) { assertTrue(NoteStore.setCompleted(prefs, "share-once", true)) }
        assertTrue(NoteStore.all(prefs).single().completed)
        assertTrue(NoteStore.setCompleted(prefs, "share-once", false))
        assertFalse(NoteStore.all(prefs).single().completed)
        assertFalse(NoteStore.delete(prefs, "unknown"))
        assertTrue(NoteStore.delete(prefs, "share-once"))
        assertTrue(NoteStore.all(prefs).isEmpty())
    }

    @Test fun concurrentAddsDoNotOverwriteOtherNotes() {
        val workers = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..24).map { index -> workers.submit { NoteStore.add(prefs, "事项$index", index.toLong()) } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(24, NoteStore.all(prefs).size)
            assertEquals(24, NoteStore.all(prefs).map { it.id }.distinct().size)
        } finally { workers.shutdownNow() }
    }
}
