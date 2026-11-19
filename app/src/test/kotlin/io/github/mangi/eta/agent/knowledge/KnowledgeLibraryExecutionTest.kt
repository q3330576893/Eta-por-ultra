package io.github.mangi.eta.agent.knowledge

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.terminal.FileToolDispatcher
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class KnowledgeLibraryExecutionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store() = KnowledgeLibraryStore(File(temporary.root, "kb/manifest.json"))
    private fun executor(store: KnowledgeLibraryStore, owner: String = "conversation-a", enabled: () -> Boolean = { true },
        environmentKey: (String) -> String = { it }) = KnowledgeToolExecutor(store, owner,
        { name, args -> FileToolDispatcher.execute(LocalFileToolBackend(temporary.root), name, args) }, enabled, environmentKey)
    private fun attach(store: KnowledgeLibraryStore, owner: String = "conversation-a"): String = store.createCollection("资料").id.also { store.attach(owner, it, true) }
    private fun call(tools: KnowledgeToolExecutor, name: String, args: JSONObject): JSONObject = JSONObject(tools.execute(name, args))

    @Test fun catalogNamesDispatchThroughLocalToolsAndRootlessIdentityChecksRemainActive() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = KnowledgeLibraryStore(context)
        val collection = attach(store)
        val schema = JSONArray().also(AgentFileToolCatalog::appendTo)
        val projected = AgentToolRequirements.project(schema, rootAvailable = false)
        val index = (0 until projected.length()).map { projected.getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "index_file_to_kb" }
        assertEquals(listOf("user"), index.getJSONObject("parameters").getJSONObject("properties").getJSONObject("identity")
            .getJSONArray("enum").let { values -> (0 until values.length()).map(values::getString) })
        try {
            local(context).use { tools ->
                val denied = invoke(tools, "index_file_to_kb", JSONObject().put("path", "/private-document.txt").put("identity", "root"))
                assertEquals("ROOT_REQUIRED", denied.getString("code"))
                assertTrue(store.snapshot().documents.none { it.collectionId == collection })
                local(context, "").use { ownerless ->
                    assertEquals("KB_CONVERSATION_REQUIRED", invoke(ownerless, "search_kb", JSONObject().put("query", "anything")).getString("code"))
                }
            }
        } finally { store.deleteCollection(collection) }
    }

    @Test fun nativeAndroidFileBackendIndexesAndSearchesWithoutRootOnSupportedUnixHosts() {
        org.junit.Assume.assumeTrue("Native Android file adapter integration requires Unix absolute paths; Linux release CI executes this case",
            File.separatorChar == '/' && File("/bin/sh").canExecute())
        val context = RuntimeEnvironment.getApplication() as Context
        val store = KnowledgeLibraryStore(context)
        val collection = attach(store)
        val file = temporary.newFile("中文资料.txt").apply { writeText("第一行\n人工智能检索系统\n第三行\n") }
        try {
            local(context).use { tools ->
                val indexed = invoke(tools, "index_file_to_kb", JSONObject().put("path", file.path).put("identity", "user").put("collection_id", collection))
                assertTrue(indexed.toString(), indexed.getBoolean("ok"))
                assertEquals("user", indexed.getString("identity"))
                val searched = invoke(tools, "search_kb", JSONObject().put("query", "智能检索"))
                assertTrue(searched.toString(), searched.getBoolean("ok"))
                val hit = searched.getJSONArray("hits").getJSONObject(0)
                assertEquals(file.canonicalPath, hit.getString("path"))
                assertEquals(file.name, hit.getString("filename"))
                assertTrue(hit.getString("excerpt").contains("人工智能检索系统"))
                assertTrue(hit.getInt("start_line") <= 2 && hit.getInt("end_line") >= 2)
                val source = store.source(hit.getString("collection_id"), hit.getString("document_id"), hit.getString("revision")) { }
                assertTrue(source.content.contains(hit.getString("excerpt")))
            }
            // A fresh executor/store sees persisted selections and source content.
            local(context).use { tools -> assertEquals(1, invoke(tools, "search_kb", JSONObject().put("query", "智能检索")).getInt("count")) }
            local(context, "another-conversation").use { tools ->
                assertEquals("KB_SELECTION_REQUIRED", invoke(tools, "search_kb", JSONObject().put("query", "智能检索")).getString("code"))
            }
            local(context, "").use { tools ->
                assertEquals("KB_CONVERSATION_REQUIRED", invoke(tools, "search_kb", JSONObject().put("query", "智能检索")).getString("code"))
            }
        } finally { store.deleteCollection(collection) }
    }

    @Test fun restartIncrementalReplacementAndCitationRevisionAreConsistent() {
        val store = store()
        attach(store)
        val file = temporary.newFile("document.txt").apply { writeText("alpha\n".repeat(900)) }
        val first = call(executor(store), "index_file_to_kb", JSONObject().put("path", file.path))
        assertTrue(first.toString(), first.getBoolean("ok"))
        val restarted = store()
        assertTrue(restarted.search("conversation-a", "alpha", validate = {}).hits.isNotEmpty())
        file.writeText("omega\n")
        val updated = call(executor(restarted), "index_file_to_kb", JSONObject().put("path", file.path))
        assertTrue(updated.toString(), updated.getBoolean("ok"))
        assertEquals(first.getString("document_id"), updated.getString("document_id"))
        assertNotEquals(first.getString("revision"), updated.getString("revision"))
        assertEquals(1, restarted.snapshot().documents.size)
        assertTrue(restarted.search("conversation-a", "alpha", validate = {}).hits.isEmpty())
        assertEquals(1, call(executor(restarted), "search_kb", JSONObject().put("query", "omega")).getInt("count"))
        try {
            restarted.source(first.getString("collection_id"), first.getString("document_id"), first.getString("revision")) { }
            fail("Old citation revision was accepted")
        } catch (failure: KnowledgeException) { assertEquals("KB_SOURCE_CHANGED", failure.code) }
    }

    @Test fun pinsAndAttachedCollectionsNeverFallBackToGlobalDocuments() {
        val store = store()
        val first = attach(store, "a")
        val second = attach(store, "b")
        val a = store.upsert(imported(first, "alpha"))
        store.upsert(imported(second, "omega"))
        assertTrue(store.search("a", "omega", validate = {}).hits.isEmpty())
        assertTrue(store.search("b", "alpha", validate = {}).hits.isEmpty())
        store.pin("b", a.id, true)
        assertEquals(1, store.search("b", "alpha", validate = {}).hits.size)
        store.pin("b", a.id, false)
        assertTrue(store.search("b", "alpha", validate = {}).hits.isEmpty())
        store.deleteCollection(first)
        assertTrue(store.snapshot("b").pinnedDocumentIds.isEmpty())
    }

    @Test fun deletedChangedRevokedSourcesAndEnvironmentChangesRemoveFormerChunks() {
        val store = store()
        attach(store)
        val file = temporary.newFile("revoked.txt").apply { writeText("secret") }
        val tools = executor(store)
        assertTrue(call(tools, "index_file_to_kb", JSONObject().put("path", file.path)).getBoolean("ok"))
        file.delete()
        assertEquals("KB_SOURCES_UNAVAILABLE", call(tools, "search_kb", JSONObject().put("query", "secret")).getString("code"))
        assertTrue(store.snapshot().documents.isEmpty())
        file.writeText("secret")
        assertTrue(call(tools, "index_file_to_kb", JSONObject().put("path", file.path)).getBoolean("ok"))
        val changedEnvironment = executor(store, environmentKey = { "changed-$it" })
        assertEquals("KB_SOURCES_UNAVAILABLE", call(changedEnvironment, "search_kb", JSONObject().put("query", "secret")).getString("code"))
        assertTrue(store.snapshot().documents.isEmpty())
        assertTrue(call(tools, "index_file_to_kb", JSONObject().put("path", file.path)).getBoolean("ok"))
        val disabled = executor(store, enabled = { false })
        assertEquals("TERMINAL_TOOLS_DISABLED", call(disabled, "search_kb", JSONObject().put("query", "secret")).getString("code"))
        assertTrue(store.snapshot().documents.isEmpty())
    }

    @Test fun failedImportRevisionConflictAndContentBypassNeverPersistPartialDocuments() {
        val store = store()
        attach(store)
        val file = temporary.newFile("valid.txt").apply { writeText("original") }
        val tools = executor(store)
        assertEquals("KB_VERIFIED_READ_REQUIRED", call(tools, "index_file_to_kb", JSONObject().put("path", "/private/unreadable")
            .put("content", "injected")).getString("code"))
        assertEquals("FILE_CHANGED", call(tools, "index_file_to_kb", JSONObject().put("path", file.path)
            .put("expected_revision", "wrong")).getString("code"))
        val binary = temporary.newFile("binary.txt").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        assertFalse(call(tools, "index_file_to_kb", JSONObject().put("path", binary.path)).getBoolean("ok"))
        assertTrue(store.snapshot().documents.isEmpty())
        assertTrue(call(tools, "index_file_to_kb", JSONObject().put("path", file.path)).getBoolean("ok"))
        val bytes = File(temporary.root, "kb/manifest.json").readBytes()
        try { store.upsert(imported(store.snapshot().collections.first().id, "x".repeat(KnowledgeLibraryStore.MAX_DOCUMENT_BYTES + 1))); fail("Oversized import") }
        catch (_: KnowledgeException) { }
        assertArrayEquals(bytes, File(temporary.root, "kb/manifest.json").readBytes())
    }

    @Test fun safRevocationAndModifiedContentInvalidateRetainedText() {
        val store = store()
        val id = attach(store)
        var available = true
        var bytes = "secret".toByteArray()
        val tools = KnowledgeToolExecutor(store, "conversation-a", { _, _ -> error("SAF never enters shell") }, { true },
            uriReader = { if (!available) throw SecurityException("revoked"); bytes })
        tools.importUri(id, "content://docs/1", "source.txt")
        available = false
        assertEquals("KB_SOURCES_UNAVAILABLE", call(tools, "search_kb", JSONObject().put("query", "secret")).getString("code"))
        assertTrue(store.snapshot().documents.isEmpty())
        available = true
        tools.importUri(id, "content://docs/1", "source.txt")
        bytes = "updated".toByteArray()
        assertEquals("KB_SOURCES_UNAVAILABLE", call(tools, "search_kb", JSONObject().put("query", "secret")).getString("code"))
        assertTrue(store.snapshot().documents.isEmpty())
    }

    private fun imported(collection: String, content: String) = KnowledgeDocument("", collection, "source.txt", "import://$content", "android", "user",
        KnowledgeLibraryStore.digest(content.toByteArray()), content, "import")
    private fun local(context: Context, owner: String = "conversation-a") = AgentLocalTools(context, QuietLogger,
        browserToolsEnabled = { false }, terminalToolsEnabled = { true }, memoryToolsEnabled = { false }, rootAvailable = { false },
        virtualScreenSettings = { Settings() }, knowledgeConversationId = owner)
    private fun invoke(tools: AgentLocalTools, name: String, args: JSONObject) = JSONObject(tools.execute(AgentModelClient.ToolCall("kb-test", name, args.toString())).content)
    private object QuietLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
