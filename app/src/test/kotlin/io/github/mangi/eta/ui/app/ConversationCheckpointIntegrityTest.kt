package io.github.mangi.eta.ui.app

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.UserMessageUi
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
class ConversationCheckpointIntegrityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun prepare() {
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        java.io.File(context.filesDir, "textstore-maintenance-v1.flag").delete()
    }
    @After fun close() { EtaDatabase.closeForTests(); context.deleteDatabase("eta.db") }

    @Test fun malformedCheckpointRemainsOnDiskAndPlaceholderSavingCannotReplaceIt() = runBlocking {
        val history = listOf(AgentModelClient.ConversationMessage("user", "必须保留的原文"))
        val state = AgentChatUiState(messages = listOf(UserMessageUi("user", "必须保留的原文")),
            history = history, journal = history, input = "", isStreaming = false, thinkingEnabled = false)
        AgentConversationStore.save(context, "damaged", mapOf("damaged" to state), mapOf("damaged" to "原会话"), mapOf("damaged" to 1L))
        val dao = EtaDatabase.get(context).conversationDao()
        dao.updateContextCheckpoint("damaged", "[invalid]", null)
        val before = dao.contextCheckpointRow("damaged")!!
        val snapshot = AgentConversationStore.load(context)
        assertTrue("damaged" in snapshot.conversationsById)
        assertTrue("damaged" in snapshot.partiallyLoadedConversationIds)
        AgentConversationPersistence(snapshot).save(context, snapshot)
        assertEquals(before, dao.contextCheckpointRow("damaged"))
        assertEquals(1, dao.messageCount("damaged"))
    }

    @Test fun upgradeStartupInspectsOversizedFieldsWithoutTrimmingOriginalText() = runBlocking {
        val text = "original-" + "x".repeat(io.github.mangi.eta.data.db.ChunkedTextDao.MAX_FIELD_CHARS + 256)
        val state = AgentChatUiState(messages = listOf(UserMessageUi("oversized-user", text)),
            input = "", isStreaming = false, thinkingEnabled = false)
        AgentConversationStore.save(context, "large", mapOf("large" to state), mapOf("large" to "large"), mapOf("large" to 1L))
        val dao = EtaDatabase.get(context).conversationDao()
        val before = dao.messageRow("oversized-user")!!
        io.github.mangi.eta.data.db.TextStoreMaintenance.runOnceIfNeeded(context)
        assertEquals(before, dao.messageRow("oversized-user"))
        assertTrue(dao.restoreMessage(before).content == text)
        val flag = java.io.File(context.filesDir, "textstore-maintenance-v1.flag").readText()
        assertTrue(flag.contains("oversized=1"))
        assertTrue(flag.contains("trimmed=0 saved=0"))
    }
}
