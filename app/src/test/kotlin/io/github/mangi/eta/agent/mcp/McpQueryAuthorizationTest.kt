package io.github.mangi.eta.agent.mcp

import android.content.Context
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.ConnectorCatalog
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpDiscoveredCatalog
import io.github.mangi.eta.data.repository.McpDiscoveryConfigurationChangedException
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.screens.mcp.McpConnectorDraft
import io.github.mangi.eta.ui.screens.mcp.McpCredentialUpdate
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.Provider
import java.security.Security
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Query credentials use synthetic secrets and loopback HTTP; no external service is contacted. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class McpQueryAuthorizationTest {
    private lateinit var context: Context
    private val servers = mutableListOf<HttpServer>()
    private var previousProvider: Provider? = null
    private var previousProviderIndex = -1

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        previousProvider = Security.getProvider(KEYSTORE_PROVIDER)
        previousProviderIndex = Security.getProviders().indexOf(previousProvider)
        Security.removeProvider(KEYSTORE_PROVIDER)
        // Reuse the existing JCA fixture; AES/GCM itself still uses the JVM implementation.
        Security.addProvider(McpRefreshConcurrencyTest.FixtureKeyStoreProvider())
        context.getSharedPreferences(SECRET_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
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
        servers.forEach { it.stop(0) }
        servers.clear()
        runBlocking {
            val dao = EtaDatabase.get(context).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        context.getSharedPreferences(SECRET_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        Security.removeProvider(KEYSTORE_PROVIDER)
        previousProvider?.let { Security.insertProviderAt(it, previousProviderIndex + 1) }
    }

    @Test
    fun authorizationHelpersDistinguishQueryKeysAndStoredCredentials() {
        assertEquals("key", McpAuthorizationType.queryParameterName(McpAuthorizationType.QUERY_KEY))
        assertEquals("ak", McpAuthorizationType.queryParameterName(McpAuthorizationType.QUERY_AK))
        assertNull(McpAuthorizationType.queryParameterName(McpAuthorizationType.BEARER))
        assertNull(McpAuthorizationType.queryParameterName(McpAuthorizationType.NONE))
        listOf(McpAuthorizationType.BEARER, McpAuthorizationType.QUERY_KEY, McpAuthorizationType.QUERY_AK)
            .forEach { assertTrue(McpAuthorizationType.usesStoredSecret(it)) }
        assertFalse(McpAuthorizationType.usesStoredSecret(McpAuthorizationType.NONE))
        assertFalse(McpAuthorizationType.usesStoredSecret("query:unknown"))
    }

    @Test
    fun modernDiscoveryAndCallReplaceDuplicateKeyAndEncodeUnicodeWithoutBearer() {
        val seen = CopyOnWriteArrayList<ObservedRequest>()
        val remote = localServer { exchange ->
            val request = exchange.observe().also { seen += it }
            exchange.respondResult(request.body, if (request.method == "tools/list") toolsResult() else callResult())
        }
        val configured = setting(remote, McpAuthorizationType.QUERY_KEY)
            .copy(url = "${remote.url()}?key=inline-one&scope=map&%6bey=inline-two")

        McpHttpClient(configured, bearerToken = SPECIAL_SECRET).use { client ->
            val tool = client.discoverTools().tools.single()
            assertEquals("ok", client.callTool(tool, JSONObject()).getJSONArray("content")
                .getJSONObject(0).getString("text"))
        }

        assertEquals(listOf("tools/list", "tools/call"), seen.map { it.method })
        seen.forEach {
            assertEquals(listOf(SPECIAL_SECRET), it.url.toHttpUrl().queryParameterValues("key"))
            assertEquals("map", it.url.toHttpUrl().queryParameter("scope"))
            assertNull(it.authorization)
            assertFalse(it.url.contains("inline-one"))
            assertFalse(it.url.contains("inline-two"))
            assertTrue(it.url.contains("%E4%B8%AD"))
            assertTrue(it.url.contains("%26"))
        }
        assertEquals("${remote.url()}?key=inline-one&scope=map&%6bey=inline-two", configured.url)
    }

    @Test
    fun legacyInitializeNotificationDiscoveryCallAndDeleteAllUseQueryAk() {
        val seen = CopyOnWriteArrayList<ObservedRequest>()
        val deleted = CountDownLatch(1)
        val remote = localServer { exchange ->
            val request = exchange.observe().also { seen += it }
            when (request.method) {
                "initialize" -> {
                    exchange.responseHeaders.set("Mcp-Session-Id", "fixture-session")
                    exchange.respondResult(request.body, JSONObject().put("protocolVersion", McpProtocolMode.LEGACY)
                        .put("capabilities", JSONObject()).put("serverInfo", JSONObject().put("name", "fixture").put("version", "1")))
                }
                "notifications/initialized" -> exchange.noContent(202)
                "tools/list" -> exchange.respondResult(request.body, toolsResult())
                "tools/call" -> exchange.respondResult(request.body, callResult())
                "DELETE" -> {
                    exchange.noContent(200)
                    deleted.countDown()
                }
                else -> exchange.noContent(400)
            }
        }
        val configured = setting(remote, McpAuthorizationType.QUERY_AK)
            .copy(protocolMode = McpProtocolMode.LEGACY)

        McpHttpClient(configured, bearerToken = SPECIAL_SECRET).use { client ->
            val tool = client.discoverTools().tools.single()
            client.callTool(tool, JSONObject())
        }
        assertTrue("session cleanup did not arrive", deleted.await(3, TimeUnit.SECONDS))

        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call", "DELETE"), seen.map { it.method })
        seen.forEach {
            assertEquals(listOf(SPECIAL_SECRET), it.url.toHttpUrl().queryParameterValues("ak"))
            assertNull(it.authorization)
        }
        seen.drop(1).forEach { assertEquals("fixture-session", it.sessionId) }
    }

    @Test
    fun sameOrigin307And308PreserveQuerySecretAndReplaceRedirectSuppliedValues() {
        listOf(307, 308).flatMap { status -> listOf(status to false, status to true) }.forEach { (status, supplied) ->
            val seen = CopyOnWriteArrayList<ObservedRequest>()
            val remote = localServer { exchange ->
                val request = exchange.observe().also { seen += it }
                if (exchange.requestURI.path == "/mcp") {
                    exchange.responseHeaders.set("Location", if (supplied) {
                        "/moved?ak=wrong&ak=also-wrong&keep=1"
                    } else {
                        "/moved?keep=1"
                    })
                    exchange.noContent(status)
                } else {
                    exchange.respondResult(request.body, toolsResult())
                }
            }
            McpHttpClient(setting(remote, McpAuthorizationType.QUERY_AK), SPECIAL_SECRET).use { it.discoverTools() }

            assertEquals(2, seen.size)
            seen.forEach {
                assertEquals(listOf(SPECIAL_SECRET), it.url.toHttpUrl().queryParameterValues("ak"))
                assertNull(it.authorization)
                assertEquals("tools/list", it.method)
            }
            assertEquals("1", seen.last().url.toHttpUrl().queryParameter("keep"))
        }
    }

    @Test
    fun crossOriginRedirectNeverSendsQueryCredentialToForeignEndpoint() {
        val foreignHits = AtomicInteger()
        val foreign = localServer { exchange -> foreignHits.incrementAndGet(); exchange.noContent(500) }
        val remote = localServer { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.set("Location", "${foreign.url()}?key=redirect-value")
            exchange.noContent(307)
        }
        val failure = runCatching {
            McpHttpClient(setting(remote, McpAuthorizationType.QUERY_KEY), ASCII_SECRET).use { it.discoverTools() }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(McpFailureKind.UNSAFE_REDIRECT, classifyFailure(requireNotNull(failure)))
        assertEquals(0, foreignHits.get())
        assertSecretNotReported(failure, ASCII_SECRET, remote.url())
    }

    @Test
    fun missingStoredSecretFailsBeforeUsingAnInlineUrlKey() {
        val hits = AtomicInteger()
        val remote = localServer { exchange -> hits.incrementAndGet(); exchange.noContent(500) }
        val configured = setting(remote, McpAuthorizationType.QUERY_KEY).copy(url = "${remote.url()}?key=$ASCII_SECRET")
        val failure = runCatching { McpHttpClient(configured, bearerToken = null).use { it.discoverTools() } }
            .exceptionOrNull()

        assertEquals(McpFailureKind.AUTHENTICATION, classifyFailure(requireNotNull(failure)))
        assertEquals(0, hits.get())
        assertSecretNotReported(failure, ASCII_SECRET, remote.url())
    }

    @Test
    fun queryTemplateDraftRequiresSeparateKeyAndSupportsSavedCredentialRotation() = runBlocking {
        listOf("amap", "baidu_maps").forEach { id ->
            val seen = CopyOnWriteArrayList<ObservedRequest>()
            val remote = localServer { exchange ->
                val request = exchange.observe().also { seen += it }
                exchange.respondResult(request.body, toolsResult())
            }
            val template = requireNotNull(ConnectorCatalog.byId(id))
            val parameter = requireNotNull(McpAuthorizationType.queryParameterName(template.authorization))
            val draft = McpConnectorDraft().selectTemplate(template)
                .copy(url = "${remote.url()}?$parameter=inline-old-key&scope=map")

            listOf(draft, draft.withToken(ASCII_SECRET).withToken("  ")).forEach { missingKey ->
                val failure = runCatching {
                    McpServerManager.discover(missingKey.toServerSetting(), missingKey.token)
                }.exceptionOrNull()
                assertEquals(McpFailureKind.AUTHENTICATION, classifyFailure(requireNotNull(failure)))
                assertTrue("Inline URL keys must not substitute for the separate key field", seen.isEmpty())
            }

            val configured = draft.withToken(ASCII_SECRET)
            val discovered = McpServerManager.discover(configured.toServerSetting(), configured.token)
            val saved = McpServerRepository.add(discovered, configured.token)
            assertEquals(template.authorization, saved.authorizationType)
            assertEquals("${remote.url()}?scope=map", saved.url)
            assertEquals(ASCII_SECRET, McpServerRepository.bearerToken(saved.id))
            assertEquals(listOf(ASCII_SECRET), seen.single().url.toHttpUrl().queryParameterValues(parameter))

            val replacement = McpCredentialUpdate.from(saved.authorizationType, SPECIAL_SECRET)
            McpServerRepository.update(saved.copy(authorizationType = replacement.authorizationType), replacement.value)
            McpServerManager.refresh(saved.id)
            val reloaded = requireNotNull(McpServerRepository.serverById(saved.id))
            assertEquals(template.authorization, reloaded.authorizationType)
            assertEquals(saved.url, reloaded.url)
            assertEquals(SPECIAL_SECRET, McpServerRepository.bearerToken(saved.id))
            assertEquals(listOf(SPECIAL_SECRET), seen.last().url.toHttpUrl().queryParameterValues(parameter))
            assertEquals(2, seen.size)
            assertTrue(seen.all { it.authorization == null && !it.url.contains("inline-old-key") })
        }
    }

    @Test
    fun queryAuthenticationErrorsDoNotEchoSecretsOrEndpointInMessagesAndCauses() {
        val remote = localServer { exchange ->
            val request = exchange.observe()
            exchange.respondJson(JSONObject().put("jsonrpc", "2.0").put("id", request.body.get("id"))
                .put("error", JSONObject().put("code", -32001).put("message", "secret=$ASCII_SECRET url=${request.url}")))
        }
        val failure = runCatching {
            McpHttpClient(setting(remote, McpAuthorizationType.QUERY_KEY), ASCII_SECRET).use { it.discoverTools() }
        }.exceptionOrNull()

        assertTrue(failure is McpJsonRpcException)
        assertSecretNotReported(requireNotNull(failure), ASCII_SECRET, remote.url())
        assertTrue(failure.message.orEmpty().contains("-32001"))
    }

    @Test
    fun httpAuthenticationFailureDiscardsCredentialBearingErrorBody() {
        val remote = localServer { exchange ->
            exchange.requestBody.close()
            val bytes = "echo $ASCII_SECRET at ${exchange.requestURI}".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val failure = runCatching {
            McpHttpClient(setting(remote, McpAuthorizationType.QUERY_KEY), ASCII_SECRET).use { it.discoverTools() }
        }.exceptionOrNull()

        assertEquals(McpFailureKind.AUTHENTICATION, classifyFailure(requireNotNull(failure)))
        assertTrue(failure.message.orEmpty().contains("401"))
        assertSecretNotReported(failure, ASCII_SECRET, remote.url())
    }

    @Test
    fun malformedAndNetworkErrorsDiscardCredentialBearingCauses() {
        val remote = localServer { exchange ->
            exchange.requestBody.close()
            exchange.respondRaw("echo $ASCII_SECRET at ${exchange.requestURI}")
        }
        val malformed = runCatching {
            McpHttpClient(setting(remote, McpAuthorizationType.QUERY_AK), ASCII_SECRET).use {
                it.callTool(TOOL, JSONObject())
            }
        }.exceptionOrNull()
        assertEquals(McpFailureKind.MALFORMED_RESPONSE, classifyFailure(requireNotNull(malformed)))
        assertSecretNotReported(malformed, ASCII_SECRET, remote.url())

        val port = ServerSocket(0).use { it.localPort }
        val unavailable = setting(remote, McpAuthorizationType.QUERY_KEY).copy(url = "http://127.0.0.1:$port/mcp")
        val network = runCatching {
            McpHttpClient(unavailable, ASCII_SECRET, callTimeoutMs = 1_000).use { it.discoverTools() }
        }.exceptionOrNull()
        assertEquals(McpFailureKind.NETWORK_UNREACHABLE, classifyFailure(requireNotNull(network)))
        assertNull(network.cause)
        assertSecretNotReported(network, ASCII_SECRET, unavailable.url)
    }

    @Test
    fun repositoryEncryptsBothQuerySecretsStripsUrlAndPreservesAuthThroughUpdatesAndSerialization() = runBlocking {
        listOf(McpAuthorizationType.QUERY_KEY, McpAuthorizationType.QUERY_AK).forEachIndexed { index, type ->
            val parameter = requireNotNull(McpAuthorizationType.queryParameterName(type))
            val base = McpServerSetting("query-$index", "Fixture", "https://fixture.example/mcp?scope=map&$parameter=$ASCII_SECRET&$parameter=duplicate",
                authorizationType = type, lastProtocolVersion = McpProtocolMode.LATEST)
            val saved = McpServerRepository.add(base, SPECIAL_SECRET)
            val stored = requireNotNull(McpServerRepository.serverById(saved.id))
            assertEquals(type, stored.authorizationType)
            assertEquals("https://fixture.example/mcp?scope=map", stored.url)
            assertEquals(SPECIAL_SECRET, McpServerRepository.bearerToken(saved.id))
            assertEquals(stored, stored.toEntity().toDomain())
            val entity = requireNotNull(EtaDatabase.get(context).mcpServerDao().serverById(saved.id))
            assertFalse(entity.url.contains(ASCII_SECRET))
            assertFalse(context.getSharedPreferences(SECRET_PREFERENCES, Context.MODE_PRIVATE).all.values
                .joinToString().contains(SPECIAL_SECRET))

            McpServerRepository.update(stored.copy(name = "Renamed", url = "${stored.url}&$parameter=inline-update"))
            val renamed = requireNotNull(McpServerRepository.serverById(saved.id))
            assertEquals(stored.url, renamed.url)
            assertEquals(type, renamed.authorizationType)
            assertEquals(SPECIAL_SECRET, McpServerRepository.bearerToken(saved.id))
            McpServerRepository.update(renamed, bearerToken = ASCII_SECRET)
            assertEquals(ASCII_SECRET, McpServerRepository.bearerToken(saved.id))
            McpServerRepository.update(renamed.copy(authorizationType = McpAuthorizationType.NONE))
            assertNull(McpServerRepository.bearerToken(saved.id))
        }
    }

    @Test
    fun refreshIdentityUsesCleanSavedUrlAndSecretAndRejectsRotatedQueryCredential() = runBlocking {
        val remote = localServer { exchange ->
            val request = exchange.observe()
            assertEquals(listOf(ASCII_SECRET), request.url.toHttpUrl().queryParameterValues("key"))
            exchange.respondResult(request.body, toolsResult())
        }
        val saved = McpServerRepository.add(setting(remote, McpAuthorizationType.QUERY_KEY)
            .copy(url = "${remote.url()}?key=discarded-inline"), ASCII_SECRET)
        val identity = McpServerRepository.discoveryIdentity(saved.copy(url = "${saved.url}?key=ignored-inline"), ASCII_SECRET)
        val catalog = McpDiscoveredCatalog(listOf(TOOL), 123L, McpProtocolMode.LATEST, 456L)
        assertEquals(saved.url, McpServerRepository.updateDiscoveredCatalog(identity, catalog).url)
        assertEquals(saved.url, McpServerManager.refresh(saved.id).url)
        assertEquals(saved.url, requireNotNull(McpServerRepository.discoverySource(saved.id)).server.url)
        McpServerRepository.update(requireNotNull(McpServerRepository.serverById(saved.id)), bearerToken = SPECIAL_SECRET)
        val failure = runCatching { McpServerRepository.updateDiscoveredCatalog(identity, catalog) }.exceptionOrNull()
        assertTrue(failure is McpDiscoveryConfigurationChangedException)
        assertSecretNotReported(requireNotNull(failure), SPECIAL_SECRET, saved.url)
    }

    private fun setting(remote: HttpServer, type: String) = McpServerSetting(
        id = "query-fixture", name = "Query fixture", url = remote.url(),
        protocolMode = McpProtocolMode.LATEST, authorizationType = type,
        lastProtocolVersion = McpProtocolMode.LATEST,
    )

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler)
            start()
            servers += this
        }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.observe(): ObservedRequest {
        val body = requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
        return ObservedRequest(
            url = "http://127.0.0.1:${localAddress.port}$requestURI",
            method = if (requestMethod == "DELETE") "DELETE" else JSONObject(body).getString("method"),
            body = if (body.isBlank()) JSONObject() else JSONObject(body),
            authorization = requestHeaders.getFirst("Authorization"),
            sessionId = requestHeaders.getFirst("Mcp-Session-Id"),
        )
    }

    private fun HttpExchange.respondResult(request: JSONObject, result: JSONObject) =
        respondJson(JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result))

    private fun HttpExchange.respondJson(body: JSONObject) = respondRaw(body.toString())

    private fun HttpExchange.respondRaw(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.noContent(status: Int) {
        sendResponseHeaders(status, -1)
        close()
    }

    private fun toolsResult() = JSONObject().put("resultType", "complete").put("ttlMs", 60_000L)
        .put("tools", JSONArray().put(JSONObject().put("name", TOOL.name).put("inputSchema", JSONObject(TOOL.inputSchemaJson))))

    private fun callResult() = JSONObject().put("resultType", "complete")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok")))

    private fun assertSecretNotReported(failure: Throwable, secret: String, endpoint: String) {
        val trace = failure.stackTraceToString()
        assertFalse("exception exposed credential", trace.contains(secret))
        assertFalse("exception exposed endpoint", trace.contains(endpoint))
    }

    private data class ObservedRequest(
        val url: String,
        val method: String,
        val body: JSONObject,
        val authorization: String?,
        val sessionId: String?,
    )

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val SECRET_PREFERENCES = "eta_mcp_secrets"
        const val ASCII_SECRET = "fixture-query-secret-953b"
        const val SPECIAL_SECRET = "中文 +/&?=%#fixture"
        val TOOL = McpToolDefinition("ping", inputSchemaJson = """{"type":"object"}""")
    }
}
