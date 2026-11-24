package io.github.mangi.eta.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MCP 传输层生态兼容性回归测试。
 *
 * 覆盖：协议版本协商回退、SSE 分帧（跨 chunk / keep-alive / event-id-retry）、
 * 错误分类、超时与取消、重定向策略。
 */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class McpTransportRobustnessTest {

    // ---------------------------------------------------------------- 协议版本协商

    @Test
    fun autoModeAcceptsLegacyVersionUnknownToClientAndKeepsLegacySemantics() {
        val requestIndex = AtomicInteger()
        val bodies = CopyOnWriteArrayList<String>()
        val versions = CopyOnWriteArrayList<String>()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            bodies += exchange.requestBody.bufferedReader().use { it.readText() }
            versions += exchange.requestHeaders.getFirst("MCP-Protocol-Version").orEmpty()
            val requestId = JSONObject(bodies.last()).optLong("id", -1L)
            when (requestIndex.getAndIncrement()) {
                0 -> exchange.respond(400, "unsupported modern version")
                1 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":$requestId,"result":{"protocolVersion":"2025-08-01","capabilities":{},"serverInfo":{"name":"old","version":"1"}}}""",
                    mapOf("Mcp-Session-Id" to "session-unknown"),
                )
                2 -> exchange.respond(202, "")
                else -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":$requestId,"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                )
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            // 服务端选定的版本不在客户端白名单里，但握手成功，不能直接判死。
            assertEquals("2025-08-01", discovery.protocolVersion)
            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
            assertEquals(
                listOf(
                    McpProtocolMode.LATEST,
                    McpProtocolMode.LEGACY,
                    "2025-08-01",
                    "2025-08-01",
                ),
                versions,
            )
            // legacy 语义：后续请求不带现代协议 _meta。
            assertNull(JSONObject(bodies[1]).getJSONObject("params").optJSONObject("_meta"))
            assertNull(JSONObject(bodies[3]).getJSONObject("params").optJSONObject("_meta"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun callToolTreatsUnlistedLastProtocolVersionAsLegacyFamily() {
        val methods = CopyOnWriteArrayList<String>()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            methods += body.getString("method")
            when (body.getString("method")) {
                "initialize" -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"protocolVersion":"2025-08-01","capabilities":{},"serverInfo":{"name":"old","version":"1"}}}""",
                    mapOf("Mcp-Session-Id" to "s1"),
                )
                "notifications/initialized" -> exchange.respond(202, "")
                "tools/call" -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"content":[{"type":"text","text":"ok"}]}}""",
                )
                else -> exchange.respond(400, "unexpected")
            }
        }
        try {
            val tool = McpToolDefinition("search", inputSchemaJson = """{"type":"object"}""")
            val configured = McpServerSetting(
                id = "test",
                name = "Test",
                url = server.url(),
                // 未收录的 legacy 版本：旧实现会误判成现代协议，直接发 tools/call。
                lastProtocolVersion = "2025-08-01",
            )
            val result = McpHttpClient(configured, bearerToken = null).use {
                it.callTool(tool, JSONObject())
            }

            assertEquals("ok", result.getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals(
                listOf("initialize", "notifications/initialized", "tools/call"),
                methods,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun autoModeFallsBackToLegacyWhenModernResponseIsNotParseableJson() {
        val requestIndex = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            methods += body.getString("method")
            when (requestIndex.getAndIncrement()) {
                0 -> exchange.respond(200, "this is definitely not json")
                1 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"t","version":"1"}}}""",
                    mapOf("Mcp-Session-Id" to "s1"),
                )
                2 -> exchange.respond(202, "")
                else -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                )
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals("2024-11-05", discovery.protocolVersion)
            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
            assertEquals(
                listOf("tools/list", "initialize", "notifications/initialized", "tools/list"),
                methods,
            )
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------- SSE 分帧

    @Test
    fun sseStreamIgnoresKeepAliveCommentsAndHonoursEventIdRetryFields() {
        val server = localServer { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                // keep-alive 注释行 + 一个 id 不匹配的事件 + 目标事件。
                output.write(": keep-alive\n\n".toByteArray())
                output.flush()
                output.write(
                    """
                    |id: 1
                    |retry: 3000
                    |event: message
                    |data: {"jsonrpc":"2.0","id":99,"result":{"content":[]}}
                    |
                    |""".trimMargin().toByteArray()
                )
                output.flush()
                output.write(
                    """
                    |: another keep-alive
                    |id: 2
                    |event: message
                    |data: {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","ttlMs":1000,"tools":[{"name":"sse","inputSchema":{"type":"object"}}]}}
                    |
                    |""".trimMargin().toByteArray()
                )
                output.flush()
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(listOf("sse"), discovery.tools.map { it.name })
            assertEquals(1_000L, discovery.cacheTtlMs)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sseLineSplitAcrossChunkBoundariesIsReassembled() {
        val server = localServer { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                // 在 data 行中间切开，并且切开一个多字节 UTF-8 字符。
                output.write("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"resultType\":\"complete\",\"ttlMs\":1,\"tools\":[{\"name\":\"跨".toByteArray())
                output.flush()
                Thread.sleep(120)
                output.write("块\",\"inputSchema\":{\"type\":\"object\"}}]}}\n".toByteArray())
                output.flush()
                Thread.sleep(120)
                output.write("\n".toByteArray())
                output.flush()
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(listOf("跨块"), discovery.tools.map { it.name })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun ssePayloadIsParsedEvenWhenGatewayDropsEventStreamContentType() {
        val server = localServer { exchange ->
            exchange.respondRaw(
                200,
                "application/json",
                ": ping\n\nid: 5\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"resultType\":\"complete\",\"ttlMs\":7,\"tools\":[{\"name\":\"sniffed\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n",
            )
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(listOf("sniffed"), discovery.tools.map { it.name })
            assertEquals(7L, discovery.cacheTtlMs)
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------- 错误分类

    @Test
    fun httpStatusCodesMapToDistinctFailureKinds() {
        assertEquals(McpFailureKind.AUTHENTICATION, classifyHttpStatus(401))
        assertEquals(McpFailureKind.AUTHENTICATION, classifyHttpStatus(403))
        assertEquals(McpFailureKind.ENDPOINT_NOT_FOUND, classifyHttpStatus(404))
        assertEquals(McpFailureKind.RATE_LIMITED, classifyHttpStatus(429))
        assertEquals(McpFailureKind.PROTOCOL_INCOMPATIBLE, classifyHttpStatus(405))
        assertEquals(McpFailureKind.PROTOCOL_INCOMPATIBLE, classifyHttpStatus(400, -32600))
        assertEquals(McpFailureKind.BAD_REQUEST, classifyHttpStatus(400))
        assertEquals(McpFailureKind.SERVER_ERROR, classifyHttpStatus(503))
        assertEquals(McpFailureKind.UNSAFE_REDIRECT, classifyHttpStatus(302))
    }

    @Test
    fun failureKindsExposeActionableHintsAndToolCodes() {
        assertEquals("MCP_AUTH_FAILED", McpFailureKind.AUTHENTICATION.toolFailureCode)
        assertEquals("MCP_ENDPOINT_NOT_FOUND", McpFailureKind.ENDPOINT_NOT_FOUND.toolFailureCode)
        assertEquals("MCP_RATE_LIMITED", McpFailureKind.RATE_LIMITED.toolFailureCode)
        assertEquals("MCP_TIMEOUT", McpFailureKind.TIMEOUT.toolFailureCode)
        assertEquals("MCP_PROTOCOL_INCOMPATIBLE", McpFailureKind.PROTOCOL_INCOMPATIBLE.toolFailureCode)
        assertEquals("MCP_CALL_FAILED", McpFailureKind.UNKNOWN.toolFailureCode)
        McpFailureKind.entries.forEach { kind ->
            assertTrue("hint missing for $kind", kind.userHint().isNotBlank())
        }
    }

    @Test
    fun discoverySurfacesAuthenticationFailureAsHttpStatusKind() {
        val server = localServer { exchange ->
            exchange.respond(401, """{"error":"invalid token"}""")
        }
        try {
            val configured = McpServerSetting(
                id = "test",
                name = "Test",
                url = server.url(),
                protocolMode = McpProtocolMode.LEGACY,
            )
            val failure = runCatching {
                McpHttpClient(configured, bearerToken = "bad").use { it.discoverTools() }
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(McpFailureKind.AUTHENTICATION, classifyFailure(failure!!))
            assertTrue(failure.message.orEmpty().contains("401"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unreachableEndpointIsClassifiedAsNetworkFailure() {
        val port = ServerSocket(0).use { it.localPort }
        val configured = McpServerSetting(
            id = "test",
            name = "Test",
            url = "http://127.0.0.1:$port/mcp",
            protocolMode = McpProtocolMode.LEGACY,
        )
        val failure = runCatching {
            McpHttpClient(configured, bearerToken = null, callTimeoutMs = 3_000).use {
                it.discoverTools()
            }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(McpFailureKind.NETWORK_UNREACHABLE, classifyFailure(failure!!))
    }

    @Test
    fun toolExecutorReportsClassifiedFailureCodeForRateLimit() {
        val server = localServer { exchange ->
            exchange.respond(429, """{"error":"slow down"}""")
        }
        try {
            val definition = McpToolDefinition("search", inputSchemaJson = """{"type":"object"}""")
            val setting = McpServerSetting(
                id = "server",
                name = "Server",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
            )
            val executor = McpToolExecutor(
                McpRunSnapshot(listOf(McpRunTool("mcp_server_search", setting, definition, null)))
            )
            try {
                val result = executor.execute(
                    AgentModelClient.ToolCall("call-1", "mcp_server_search", "{}")
                )
                val payload = JSONObject(result.content)

                assertFalse(payload.getBoolean("ok"))
                assertEquals("MCP_RATE_LIMITED", payload.getString("code"))
                assertTrue(payload.getString("message").contains("429"))
            } finally {
                executor.close()
            }
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------- 超时与取消

    @Test
    fun keepAliveOnlyStreamIsBoundedByCallTimeout() {
        val streamOpened = CountDownLatch(1)
        val stopStream = CountDownLatch(1)
        val server = localServer { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                streamOpened.countDown()
                // 只发 keep-alive 注释行，永不发事件：旧实现会一直阻塞到 60s 读超时。
                while (stopStream.count > 0L) {
                    output.write(": keep-alive\n\n".toByteArray())
                    output.flush()
                    Thread.sleep(50)
                }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val startedAt = System.nanoTime()
            val future = executor.submit<Throwable?> {
                runCatching {
                    McpHttpClient(configured, bearerToken = null, callTimeoutMs = 700)
                        .use { it.discoverTools() }
                }.exceptionOrNull()
            }
            check(streamOpened.await(2, TimeUnit.SECONDS)) { "stream did not open" }
            val failure = future.get(6, TimeUnit.SECONDS)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertNotNull(failure)
            assertEquals(McpFailureKind.TIMEOUT, classifyFailure(failure!!))
            assertTrue("call took ${elapsedMs}ms", elapsedMs < 5_000L)
        } finally {
            stopStream.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun closeDuringStreamReportsClientClosedNotTimeout() {
        val streamOpened = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = localServer { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write(": keep-alive\n\n".toByteArray())
                output.flush()
                streamOpened.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val client = McpHttpClient(configured, bearerToken = null)
            val future = executor.submit<Throwable?> {
                runCatching { client.discoverTools() }.exceptionOrNull()
            }
            check(streamOpened.await(2, TimeUnit.SECONDS)) { "stream did not open" }
            client.close()
            val failure = future.get(5, TimeUnit.SECONDS)
            releaseServer.countDown()

            assertNotNull(failure)
            assertEquals(McpFailureKind.CLIENT_CLOSED, classifyFailure(failure!!))
        } finally {
            releaseServer.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------- 重定向策略

    @Test
    fun sameOriginTemporaryRedirectIsFollowed() {
        val secondHit = AtomicInteger()
        val server = localServer { exchange ->
            if (exchange.requestURI.path == "/mcp") {
                exchange.responseHeaders.set("Location", "/mcp/v2")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            } else {
                secondHit.incrementAndGet()
                exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","ttlMs":10,"tools":[{"name":"moved","inputSchema":{"type":"object"}}]}}""",
                )
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(listOf("moved"), discovery.tools.map { it.name })
            assertEquals(1, secondHit.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun crossOriginRedirectIsRejectedInsteadOfLeakingBearerToken() {
        val foreignHits = AtomicInteger()
        val foreign = localServer { exchange ->
            foreignHits.incrementAndGet()
            exchange.respond(200, "{}")
        }
        val server = localServer { exchange ->
            exchange.responseHeaders.set("Location", foreign.url())
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val failure = runCatching {
                McpHttpClient(configured, bearerToken = "secret").use { it.discoverTools() }
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(McpFailureKind.UNSAFE_REDIRECT, classifyFailure(failure!!))
            assertEquals(0, foreignHits.get())
        } finally {
            server.stop(0)
            foreign.stop(0)
        }
    }

    @Test
    fun postRedirectIsNotDowngradedToGet() {
        val server = localServer { exchange ->
            if (exchange.requestURI.path == "/mcp") {
                exchange.responseHeaders.set("Location", "/mcp/v2")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                exchange.respond(200, "{}")
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val failure = runCatching {
                McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }
            }.exceptionOrNull()

            // 302 不跟随：交给上层按状态码分类，避免 POST 被改写成 GET。
            assertNotNull(failure)
            assertEquals(McpFailureKind.UNSAFE_REDIRECT, classifyFailure(failure!!))
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler)
            start()
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.respond(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach(responseHeaders::set)
        if (body.isNotEmpty()) responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondRaw(status: Int, contentType: String, body: String) {
        responseHeaders.set("Content-Type", contentType)
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
