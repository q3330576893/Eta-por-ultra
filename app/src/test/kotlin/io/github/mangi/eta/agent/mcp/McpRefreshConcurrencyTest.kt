package io.github.mangi.eta.agent.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpDiscoveryConfigurationChangedException
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Refresh and settings writes deliberately overlap; latches make the commit order deterministic. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class McpRefreshConcurrencyTest {
    private lateinit var context: Context
    private val servers = mutableListOf<DelayedServer>()
    private var previousKeyStoreProvider: Provider? = null
    private var previousKeyStoreProviderIndex = -1

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        previousKeyStoreProvider = Security.getProvider(KEYSTORE_PROVIDER)
        previousKeyStoreProviderIndex = Security.getProviders().indexOf(previousKeyStoreProvider)
        Security.removeProvider(KEYSTORE_PROVIDER)
        Security.addProvider(FixtureKeyStoreProvider())
        fixtureKeys.clear()
        context.getSharedPreferences("eta_mcp_secrets", Context.MODE_PRIVATE).edit().clear().commit()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        McpServerRepository.init(context)
        runBlocking {
            val dao = EtaDatabase.get(context).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
    }

    @After
    fun tearDown() {
        servers.forEach {
            it.release.countDown()
            it.server.stop(0)
            it.executor.shutdownNow()
        }
        servers.clear()
        runBlocking {
            val dao = EtaDatabase.get(context).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        context.getSharedPreferences("eta_mcp_secrets", Context.MODE_PRIVATE).edit().clear().commit()
        fixtureKeys.clear()
        Security.removeProvider(KEYSTORE_PROVIDER)
        previousKeyStoreProvider?.let {
            Security.insertProviderAt(it, previousKeyStoreProviderIndex + 1)
        }
    }

    @Test
    fun refreshUsesSelectionChangedWhileDiscoveryWasWaiting() = runBlocking {
        val remote = delayedServer("read", "write", "new_tool")
        val saved = save(remote)
        val refresh = async(Dispatchers.IO) { McpServerManager.refresh(saved.id) }
        try {
            remote.awaitRequest()
            McpServerRepository.setToolEnabled(saved.id, "write", false)
        } finally {
            remote.release.countDown()
        }
        val refreshed = withTimeout(5_000) { refresh.await() }

        assertEquals(setOf("read"), refreshed.enabledToolNames)
        assertEquals(listOf("read", "write", "new_tool"), refreshed.tools.map { it.name })
        assertNotNull(refreshed.lastRefreshedAt)
        assertEquals(refreshed, McpServerRepository.serverById(saved.id))
    }

    @Test
    fun refreshPreservesCurrentDisabledStateNameOrderAndCreationTime() = runBlocking {
        val remote = delayedServer("read", "write")
        val saved = save(remote)
        val refresh = async(Dispatchers.IO) { McpServerManager.refresh(saved.id) }
        try {
            remote.awaitRequest()
            McpServerRepository.update(
                saved.copy(enabled = false, name = "Renamed", sortOrder = 7, createdAt = 123L),
            )
        } finally {
            remote.release.countDown()
        }
        val refreshed = withTimeout(5_000) { refresh.await() }

        assertFalse(refreshed.enabled)
        assertEquals("Renamed", refreshed.name)
        assertEquals(7, refreshed.sortOrder)
        assertEquals(123L, refreshed.createdAt)
        assertTrue(refreshed.activeTools.isEmpty())
        assertEquals(refreshed, McpServerRepository.serverById(saved.id))
    }

    @Test
    fun runSnapshotOmitsServerDisabledDuringItsRefresh() = runBlocking {
        val remote = delayedServer("read", "write")
        val saved = save(remote)
        val snapshot = async(Dispatchers.IO) { McpRunSnapshot.load() }
        try {
            remote.awaitRequest()
            McpServerRepository.update(saved.copy(enabled = false))
        } finally {
            remote.release.countDown()
        }

        assertTrue(withTimeout(5_000) { snapshot.await() }.tools.isEmpty())
        assertFalse(requireNotNull(McpServerRepository.serverById(saved.id)).enabled)
    }

    @Test
    fun newBearerCredentialDiscardsOldAnonymousDiscoveryWithoutClearingToken() = runBlocking {
        val remote = delayedServer("new_tool")
        val saved = save(remote)
        val refresh = async(Dispatchers.IO) { runCatching { McpServerManager.refresh(saved.id) } }
        try {
            remote.awaitRequest()
            McpServerRepository.update(
                saved.copy(authorizationType = McpAuthorizationType.BEARER),
                bearerToken = "fixture-new-token",
            )
        } finally {
            remote.release.countDown()
        }
        val failure = withTimeout(5_000) { refresh.await() }.exceptionOrNull()

        assertTrue(failure.toString(), failure is McpDiscoveryConfigurationChangedException)
        val current = requireNotNull(McpServerRepository.serverById(saved.id))
        assertEquals(McpAuthorizationType.BEARER, current.authorizationType)
        assertEquals(listOf("read", "write"), current.tools.map { it.name })
        assertEquals("fixture-new-token", McpServerRepository.bearerToken(saved.id))
        assertEquals(null, current.lastProtocolVersion)
        assertFalse(failure?.message.orEmpty().contains("fixture-new-token"))
    }

    @Test
    fun rotatingOnlyTokenInvalidatesDiscoveryForOtherwiseIdenticalConnection() = runBlocking {
        val remote = delayedServer("new_tool")
        val saved = save(remote, "fixture-old-token")
        val refresh = async(Dispatchers.IO) { runCatching { McpServerManager.refresh(saved.id) } }
        try {
            remote.awaitRequest()
            McpServerRepository.update(saved, bearerToken = "fixture-rotated-token")
        } finally {
            remote.release.countDown()
        }
        val failure = withTimeout(5_000) { refresh.await() }.exceptionOrNull()

        assertTrue(failure.toString(), failure is McpDiscoveryConfigurationChangedException)
        assertEquals("fixture-rotated-token", McpServerRepository.bearerToken(saved.id))
        assertEquals(saved.tools, requireNotNull(McpServerRepository.serverById(saved.id)).tools)
    }

    @Test
    fun endpointAndProtocolChangesInvalidateTheirInFlightDiscoveries() = runBlocking {
        val changes: List<(McpServerSetting) -> McpServerSetting> = listOf(
            { it.copy(url = "${it.url}/moved") },
            { it.copy(protocolMode = McpProtocolMode.LEGACY) },
        )
        changes.forEachIndexed { index, change ->
            val remote = delayedServer("new_tool")
            val saved = save(remote, id = "changed-$index")
            val refresh = async(Dispatchers.IO) { runCatching { McpServerManager.refresh(saved.id) } }
            val changed = change(saved)
            try {
                remote.awaitRequest()
                McpServerRepository.update(changed)
            } finally {
                remote.release.countDown()
            }

            val failure = withTimeout(5_000) { refresh.await() }.exceptionOrNull()
            assertTrue(failure.toString(), failure is McpDiscoveryConfigurationChangedException)
            assertEquals(changed, McpServerRepository.serverById(saved.id))
        }
    }

    @Test
    fun delayedEmptyConfirmationPreservesLatestSettingsAndOriginalEmptyResult() = runBlocking {
        val remote = delayedServer()
        val saved = save(remote)
        remote.release.countDown()
        val rejection = runCatching { McpServerManager.refresh(saved.id) }
            .exceptionOrNull() as McpEmptyCatalogException
        McpServerRepository.update(saved.copy(enabled = false, name = "After prompt", sortOrder = 9))
        McpServerRepository.setToolEnabled(saved.id, "write", false)

        val accepted = McpServerManager.acceptEmptyCatalog(rejection)

        assertFalse(accepted.enabled)
        assertEquals("After prompt", accepted.name)
        assertEquals(9, accepted.sortOrder)
        assertTrue(accepted.tools.isEmpty())
        assertTrue(accepted.enabledToolNames.isEmpty())
        assertEquals(rejection.pending.lastRefreshedAt, accepted.lastRefreshedAt)
        assertEquals(rejection.pending.toolsExpireAt, accepted.toolsExpireAt)
        assertEquals(1, remote.requests.get())
        assertEquals(accepted, McpServerRepository.serverById(saved.id))
    }

    @Test
    fun delayedEmptyConfirmationRejectsChangedCredentialsAndKeepsCatalog() = runBlocking {
        val remote = delayedServer()
        val saved = save(remote)
        remote.release.countDown()
        val rejection = runCatching { McpServerManager.refresh(saved.id) }
            .exceptionOrNull() as McpEmptyCatalogException
        McpServerRepository.update(
            saved.copy(authorizationType = McpAuthorizationType.BEARER),
            bearerToken = "fixture-after-prompt-token",
        )

        val failure = runCatching { McpServerManager.acceptEmptyCatalog(rejection) }.exceptionOrNull()

        assertTrue(failure.toString(), failure is McpDiscoveryConfigurationChangedException)
        assertEquals(saved.tools, requireNotNull(McpServerRepository.serverById(saved.id)).tools)
        assertEquals("fixture-after-prompt-token", McpServerRepository.bearerToken(saved.id))
        assertEquals(1, remote.requests.get())
    }

    @Test
    fun emptyConfirmationStaysInvalidAfterEndpointChangesAndChangesBack() = runBlocking {
        val remote = delayedServer()
        val saved = save(remote)
        remote.release.countDown()
        val rejection = runCatching { McpServerManager.refresh(saved.id) }
            .exceptionOrNull() as McpEmptyCatalogException
        McpServerRepository.update(saved.copy(url = "${saved.url}/other"))
        McpServerRepository.update(saved)

        val failure = runCatching { McpServerManager.acceptEmptyCatalog(rejection) }.exceptionOrNull()

        assertTrue(failure.toString(), failure is McpDiscoveryConfigurationChangedException)
        assertEquals(saved, McpServerRepository.serverById(saved.id))
    }

    @Test
    fun failedRefreshDoesNotRestoreDisabledOrDeletedServersInSnapshot() = runBlocking {
        listOf(false, true).forEach { delete ->
            val remote = delayedServer(malformedResponse = true)
            val saved = save(remote, id = "failed-refresh-$delete")
            val snapshot = async(Dispatchers.IO) { McpRunSnapshot.load() }
            try {
                remote.awaitRequest()
                if (delete) {
                    McpServerRepository.delete(saved.id)
                } else {
                    McpServerRepository.update(saved.copy(enabled = false))
                }
            } finally {
                remote.release.countDown()
            }

            assertTrue(withTimeout(5_000) { snapshot.await() }.tools.isEmpty())
            assertEquals(1, remote.requests.get())
            val current = McpServerRepository.serverById(saved.id)
            if (delete) {
                assertEquals(null, current)
            } else {
                assertFalse(requireNotNull(current).enabled)
            }
        }
    }

    @Test
    fun failedOldDiscoveryFreezesCurrentEndpointAndTokenTogetherInSnapshot() = runBlocking {
        val oldRemote = delayedServer("old_discovery_tool")
        val newRemote = delayedServer("unused_discovery_tool")
        val saved = save(oldRemote, "fixture-original-token")
        val currentTool = McpToolDefinition("current_cached_tool", inputSchemaJson = OBJECT_SCHEMA)
        val changed = saved.copy(
            name = "Current connection",
            url = "http://127.0.0.1:${newRemote.server.address.port}/mcp",
            tools = listOf(currentTool),
            enabledToolNames = setOf(currentTool.name),
        )
        val snapshot = async(Dispatchers.IO) { McpRunSnapshot.load() }
        try {
            oldRemote.awaitRequest()
            McpServerRepository.update(changed, bearerToken = "fixture-current-token")
        } finally {
            oldRemote.release.countDown()
        }
        val projected = withTimeout(5_000) { snapshot.await() }.tools.single()
        val current = requireNotNull(McpServerRepository.serverById(saved.id))

        assertEquals(current, projected.server)
        assertEquals(changed.url, projected.server.url)
        assertEquals("Current connection", projected.server.name)
        assertEquals("fixture-current-token", projected.bearerToken)
        assertEquals(currentTool, projected.definition)
        assertEquals(listOf(currentTool), current.tools)
        assertEquals(null, current.lastProtocolVersion)
        assertEquals(1, oldRemote.requests.get())
        assertEquals(0, newRemote.requests.get())
    }

    private suspend fun save(
        remote: DelayedServer,
        token: String = "",
        id: String = "refresh-race",
    ): McpServerSetting = McpServerRepository.add(
        McpServerSetting(
            id = id,
            name = "Refresh race",
            url = "http://127.0.0.1:${remote.server.address.port}/mcp",
            protocolMode = McpProtocolMode.LATEST,
            authorizationType = if (token.isEmpty()) McpAuthorizationType.NONE else McpAuthorizationType.BEARER,
            tools = listOf("read", "write").map { McpToolDefinition(it, inputSchemaJson = OBJECT_SCHEMA) },
            enabledToolNames = setOf("read", "write"),
            lastProtocolVersion = McpProtocolMode.LATEST,
            toolsExpireAt = 0L,
        ),
        token,
    )

    private fun delayedServer(
        vararg toolNames: String,
        malformedResponse: Boolean = false,
    ): DelayedServer {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val worker = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = worker
            createContext("/mcp") { exchange ->
                val request = JSONObject(exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8))
                requests.incrementAndGet()
                received.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "test did not release discovery response" }
                val tools = JSONArray()
                toolNames.forEach { name ->
                    tools.put(JSONObject().put("name", name).put("inputSchema", JSONObject(OBJECT_SCHEMA)))
                }
                exchange.respond(
                    if (malformedResponse) {
                        JSONObject().put("unexpected", true)
                    } else {
                        JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                            .put("result", JSONObject().put("resultType", "complete").put("ttlMs", 60_000L)
                                .put("tools", tools))
                    },
                )
            }
            start()
        }
        return DelayedServer(server, worker, received, release, requests).also { servers += it }
    }

    private fun HttpExchange.respond(body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class DelayedServer(
        val server: HttpServer,
        val executor: ExecutorService,
        val received: CountDownLatch,
        val release: CountDownLatch,
        val requests: AtomicInteger,
    ) {
        fun awaitRequest() {
            check(received.await(5, TimeUnit.SECONDS)) { "discovery request was not received" }
        }
    }

    /** Only this test's synthetic key is stored; AES/GCM still runs through the real JVM cipher. */
    class FixtureKeyStoreProvider : Provider(KEYSTORE_PROVIDER, 1.0, "MCP test credential fixture") {
        init {
            put("KeyStore.AndroidKeyStore", FixtureKeyStore::class.java.name)
            put("KeyGenerator.AES", FixtureKeyGenerator::class.java.name)
        }
    }

    class FixtureKeyGenerator : KeyGeneratorSpi() {
        private var alias = ""
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }
        override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES", "SunJCE").run {
            init(128)
            generateKey().also { fixtureKeys[alias] = it }
        }
    }

    class FixtureKeyStore : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = fixtureKeys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            fixtureKeys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineDeleteEntry(alias: String) { fixtureKeys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(fixtureKeys.keys)
        override fun engineContainsAlias(alias: String): Boolean = fixtureKeys.containsKey(alias)
        override fun engineSize(): Int = fixtureKeys.size
        override fun engineIsKeyEntry(alias: String): Boolean = fixtureKeys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    private companion object {
        const val OBJECT_SCHEMA = """{"type":"object"}"""
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        val fixtureKeys = ConcurrentHashMap<String, SecretKey>()
    }
}
