package io.github.mangi.eta.agent.mcp

import android.content.Context
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「发现 -> 执行」链路回归：
 * 缓存失效、单服务器失败降级、同名工具区分、并发安全、请求头合并。
 */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class McpSnapshotLifecycleTest {
    private lateinit var context: Context
    private val servers = mutableListOf<HttpServer>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        McpServerRepository.init(context)
        // 显式清表：Robolectric 下 deleteDatabase 可能被已缓存的连接绕过，
        // 用 DAO 清空才能保证用例之间没有服务器残留。
        runBlocking {
            val dao = EtaDatabase.get(context).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop(0) } }
        servers.clear()
        runBlocking {
            val dao = EtaDatabase.get(context).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    // ---------- 1. cache invalidation ----------

    @Test
    fun freshCacheIsReusedWithoutAnyNetworkTraffic() = runBlocking {
        val requests = AtomicInteger()
        val server = localServer { exchange, body ->
            requests.incrementAndGet()
            exchange.respondJsonRpc(body, listToolsResult("search"))
        }
        McpServerRepository.add(
            McpServerSetting(
                id = "cache-server",
                name = "Cache",
                url = server.url(),
                protocolMode = McpProtocolMode.LATEST,
                tools = listOf(McpToolDefinition("search", inputSchemaJson = OBJECT_SCHEMA)),
                enabledToolNames = setOf("search"),
                lastProtocolVersion = McpProtocolMode.LATEST,
                toolsExpireAt = System.currentTimeMillis() + 600_000L,
            ),
            "",
        )

        val first = McpRunSnapshot.load()
        val second = McpRunSnapshot.load()

        assertEquals(listOf("search"), first.tools.map { it.definition.name })
        assertEquals(listOf("search"), second.tools.map { it.definition.name })
        assertEquals(0, requests.get())
    }

    @Test
    fun expiredCacheTriggersExactlyOneActiveRefreshAndPersistsTtl() = runBlocking {
        val toolsListCalls = AtomicInteger()
        val server = localServer { exchange, body ->
            val method = JSONObject(body).optString("method")
            if (method == "tools/list") {
                toolsListCalls.incrementAndGet()
                exchange.respondJsonRpc(body, listToolsResult("search").put("ttlMs", 60_000L))
            } else {
                exchange.respondJsonRpc(body, JSONObject().put("resultType", "complete"))
            }
        }
        McpServerRepository.add(
            McpServerSetting(
                id = "expired-server",
                name = "Expired",
                url = server.url(),
                protocolMode = McpProtocolMode.LATEST,
                tools = listOf(McpToolDefinition("stale", inputSchemaJson = OBJECT_SCHEMA)),
                // refresh 会按新目录求交集，因此新工具名需要预先启用才会进入本次快照。
                enabledToolNames = setOf("stale", "search"),
                lastProtocolVersion = McpProtocolMode.LATEST,
                toolsExpireAt = System.currentTimeMillis() - 1L,
            ),
            "",
        )

        val snapshot = McpRunSnapshot.load()

        assertEquals(1, toolsListCalls.get())
        assertEquals(listOf("search"), snapshot.tools.map { it.definition.name })
        val stored = requireNotNull(McpServerRepository.serverById("expired-server"))
        assertEquals(listOf("search"), stored.tools.map { it.name })
        assertTrue((stored.toolsExpireAt ?: 0L) > System.currentTimeMillis())
    }

    // ---------- 3. per-server failure isolation ----------

    @Test
    fun brokenServerDoesNotHideHealthyServerTools() = runBlocking {
        val healthy = localServer { exchange, body ->
            exchange.respondJsonRpc(body, listToolsResult("ping"))
        }
        McpServerRepository.add(
            McpServerSetting(
                id = "healthy",
                name = "Healthy",
                url = healthy.url(),
                protocolMode = McpProtocolMode.LATEST,
                tools = listOf(McpToolDefinition("ping", inputSchemaJson = OBJECT_SCHEMA)),
                enabledToolNames = setOf("ping"),
                lastProtocolVersion = McpProtocolMode.LATEST,
                toolsExpireAt = System.currentTimeMillis() + 600_000L,
            ),
            "",
        )
        // 127.0.0.1:1 必然拒绝连接：模拟一台从未握手成功的服务器。
        McpServerRepository.add(
            McpServerSetting(
                id = "broken",
                name = "Broken",
                url = "http://127.0.0.1:1/mcp",
                protocolMode = McpProtocolMode.LATEST,
            ),
            "",
        )

        val snapshot = McpRunSnapshot.load()

        assertEquals(listOf("ping"), snapshot.tools.map { it.definition.name })
    }

    @Test
    fun expiredServerWithCachedToolsKeepsServingThemWhenRefreshFails() = runBlocking {
        McpServerRepository.add(
            McpServerSetting(
                id = "offline",
                name = "Offline",
                url = "http://127.0.0.1:1/mcp",
                protocolMode = McpProtocolMode.LATEST,
                tools = listOf(McpToolDefinition("cached", inputSchemaJson = OBJECT_SCHEMA)),
                enabledToolNames = setOf("cached"),
                lastProtocolVersion = McpProtocolMode.LATEST,
                toolsExpireAt = System.currentTimeMillis() - 1L,
            ),
            "",
        )

        val snapshot = McpRunSnapshot.load()

        assertEquals(listOf("cached"), snapshot.tools.map { it.definition.name })
        assertEquals(
            listOf("cached"),
            requireNotNull(McpServerRepository.serverById("offline")).tools.map { it.name },
        )
    }

    // ---------- 4. tool name collision ----------

    @Test
    fun sameToolNameOnTwoServersGetsDistinctModelNamesAndRoutesByServer() {
        val definition = McpToolDefinition("search", inputSchemaJson = OBJECT_SCHEMA)
        val nameA = McpSnapshotPolicy.modelToolName("server-a", definition.name)
        val nameB = McpSnapshotPolicy.modelToolName("server-b", definition.name)

        assertNotEquals(nameA, nameB)
        assertEquals(nameA, McpSnapshotPolicy.modelToolName("server-a", definition.name))
        assertTrue(nameA, MODEL_NAME_PATTERN.matches(nameA))
        assertTrue(nameB, MODEL_NAME_PATTERN.matches(nameB))

        val snapshot = McpRunSnapshot(
            listOf(
                McpRunTool(
                    nameA,
                    McpServerSetting(id = "server-a", name = "A", url = "https://a.example/mcp"),
                    definition,
                    null,
                ),
                McpRunTool(
                    nameB,
                    McpServerSetting(id = "server-b", name = "B", url = "https://b.example/mcp"),
                    definition,
                    null,
                ),
            ),
        )

        assertEquals("server-a", snapshot.resolve(nameA)?.server?.id)
        assertEquals("server-b", snapshot.resolve(nameB)?.server?.id)
        assertEquals("search", snapshot.resolve(nameA)?.definition?.name)
    }

    // ---------- 2. concurrency ----------

    @Test
    fun concurrentCallsToSameServerAreSerializedAndAllSucceed() {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val completed = AtomicInteger()
        val server = localServer { exchange, body ->
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, now) }
            try {
                Thread.sleep(60)
                completed.incrementAndGet()
                exchange.respondJsonRpc(
                    body,
                    JSONObject().put("resultType", "complete").put("content", JSONArray()),
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val definition = McpToolDefinition("slow", inputSchemaJson = OBJECT_SCHEMA)
        val setting = McpServerSetting(
            id = "concurrent",
            name = "Concurrent",
            url = server.url(),
            protocolMode = McpProtocolMode.LATEST,
            tools = listOf(definition),
            enabledToolNames = setOf(definition.name),
            lastProtocolVersion = McpProtocolMode.LATEST,
        )
        val toolName = McpSnapshotPolicy.modelToolName(setting.id, definition.name)
        val executor = McpToolExecutor(
            McpRunSnapshot(listOf(McpRunTool(toolName, setting, definition, "run-token"))),
        )

        try {
            val results = runBlocking {
                (1..4).map { index ->
                    async(Dispatchers.IO) {
                        executor.execute(
                            AgentModelClient.ToolCall(
                                id = "call-$index",
                                name = toolName,
                                argumentsJson = "{}",
                            ),
                        )
                    }
                }.awaitAll()
            }

            assertEquals(4, results.size)
            results.forEach { result ->
                assertTrue(result.content, JSONObject(result.content).optBoolean("ok"))
            }
            assertEquals(4, completed.get())
            assertEquals(1, maxInFlight.get())
        } finally {
            executor.close()
        }
    }

    // ---------- 5. header merging ----------

    @Test
    fun parameterHeadersCannotClaimTransportOwnedHeaders() {
        val headers = McpToolHeaders.fromSchema(
            JSONObject(
                """
                {"type":"object","properties":{
                  "auth":{"type":"string","x-mcp-header":"Authorization"},
                  "session":{"type":"string","x-mcp-header":"Mcp-Session-Id"},
                  "method":{"type":"string","x-mcp-header":"Mcp-Method"},
                  "region":{"type":"string","x-mcp-header":"Region"}
                }}
                """.trimIndent(),
            ),
        )

        val extracted = headers.extract(
            JSONObject()
                .put("auth", "Bearer attacker")
                .put("session", "injected-session")
                .put("method", "tools/list")
                .put("region", "east"),
        )

        assertFalse(extracted.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(extracted.keys.any { it.equals("Mcp-Session-Id", ignoreCase = true) })
        assertFalse(extracted.keys.any { it.equals("Mcp-Method", ignoreCase = true) })
        assertEquals("east", extracted["Mcp-Param-Region"])
        assertTrue(McpToolHeaders.isProtectedHeader("authorization"))
        assertTrue(McpToolHeaders.isProtectedHeader("  AUTHORIZATION "))
        assertFalse(McpToolHeaders.isProtectedHeader("Region"))
    }

    @Test
    fun configuredBearerTokenWinsOverParameterSuppliedAuthorization() {
        val observed = AtomicReference<String>()
        val server = localServer { exchange, body ->
            observed.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respondJsonRpc(
                body,
                JSONObject().put("resultType", "complete").put("content", JSONArray()),
            )
        }
        val definition = McpToolDefinition(
            name = "secure",
            inputSchemaJson = """
                {"type":"object","properties":{
                  "auth":{"type":"string","x-mcp-header":"Authorization"},
                  "region":{"type":"string","x-mcp-header":"Region"}
                }}
            """.trimIndent(),
        )
        val setting = McpServerSetting(
            id = "header-server",
            name = "Header",
            url = server.url(),
            protocolMode = McpProtocolMode.LATEST,
            authorizationType = McpAuthorizationType.BEARER,
            tools = listOf(definition),
            enabledToolNames = setOf(definition.name),
            lastProtocolVersion = McpProtocolMode.LATEST,
        )

        McpHttpClient(setting, bearerToken = "server-token").use { client ->
            client.callTool(
                definition,
                JSONObject().put("auth", "Bearer attacker").put("region", "east"),
            )
        }

        assertEquals("Bearer server-token", observed.get())
    }

    // ---------- pure refresh policy ----------

    @Test
    fun refreshPolicyDistinguishesLegacyModernAndMissingHandshake() {
        val now = 1_000_000L
        val base = McpServerSetting(id = "s", name = "S", url = "https://example.com/mcp")

        assertTrue(McpSnapshotPolicy.needsRefresh(base, now))
        assertTrue(
            McpSnapshotPolicy.needsRefresh(
                base.copy(lastProtocolVersion = McpProtocolMode.LATEST),
                now,
            ),
        )
        assertTrue(
            McpSnapshotPolicy.needsRefresh(
                base.copy(lastProtocolVersion = McpProtocolMode.LATEST, toolsExpireAt = now),
                now,
            ),
        )
        assertFalse(
            McpSnapshotPolicy.needsRefresh(
                base.copy(lastProtocolVersion = McpProtocolMode.LATEST, toolsExpireAt = now + 1),
                now,
            ),
        )
        assertFalse(
            McpSnapshotPolicy.needsRefresh(
                base.copy(lastProtocolVersion = McpProtocolMode.LEGACY, toolsExpireAt = now - 1),
                now,
            ),
        )
    }

    // ---------- helpers ----------

    private fun listToolsResult(vararg names: String): JSONObject {
        val tools = JSONArray()
        names.forEach { name ->
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("inputSchema", JSONObject(OBJECT_SCHEMA)),
            )
        }
        return JSONObject().put("resultType", "complete").put("tools", tools)
    }

    private fun localServer(handler: (HttpExchange, String) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
                handler(exchange, body)
            }
            executor = Executors.newCachedThreadPool()
            start()
            servers += this
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.respondJsonRpc(requestBody: String, result: JSONObject) {
        val id = runCatching { JSONObject(requestBody).opt("id") }.getOrNull() ?: 1
        respond(
            200,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString(),
        )
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val OBJECT_SCHEMA = """{"type":"object"}"""
        val MODEL_NAME_PATTERN = Regex("""mcp_[a-z0-9]+_[A-Za-z0-9_-]+_[0-9a-f]{8}""")
    }
}
