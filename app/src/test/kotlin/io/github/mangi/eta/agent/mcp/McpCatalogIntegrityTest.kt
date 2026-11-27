package io.github.mangi.eta.agent.mcp

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A partial or ambiguous catalog must never replace the user's saved tools and selections. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class McpCatalogIntegrityTest {
    @Test
    fun paginatedCatalogPreservesOrderAndUsesShortestTtl() {
        val cursors = CopyOnWriteArrayList<Any>()
        withServer { request ->
            cursors += request.getJSONObject("params").opt("cursor") ?: JSONObject.NULL
            if (cursors.size == 1) {
                page("first").put("nextCursor", "opaque cursor").put("ttlMs", 60000L)
            } else {
                page("second").put("ttlMs", 5000L)
            }
        }.use { fixture ->
            val discovery = fixture.discover()
            assertEquals(listOf("first", "second"), discovery.tools.map { it.name })
            assertEquals(5000L, discovery.cacheTtlMs)
            assertEquals(listOf(JSONObject.NULL, "opaque cursor"), cursors)
        }
    }

    @Test
    fun emptyOpaqueCursorIsPassedThroughInsteadOfTruncatingCatalog() {
        var requests = 0
        withServer { request ->
            requests += 1
            if (requests == 1) {
                page("first").put("nextCursor", "")
            } else {
                assertEquals("", request.getJSONObject("params").getString("cursor"))
                page("second")
            }
        }.use { fixture ->
            assertEquals(listOf("first", "second"), fixture.discover().tools.map { it.name })
            assertEquals(2, requests)
        }
    }

    @Test
    fun repeatedCursorFailsWithoutReturningPartialToolsOrLegacyFallback() {
        var requests = 0
        withServer {
            requests += 1
            page("tool_$requests").put("nextCursor", "repeat")
        }.use { fixture ->
            assertInvalid(fixture, "分页游标重复")
            assertEquals(2, requests)
        }
    }

    @Test
    fun pageLimitFailsInsteadOfPublishingIncompleteCatalog() {
        var requests = 0
        withServer {
            requests += 1
            page("tool_$requests").put("nextCursor", "page_$requests")
        }.use { fixture ->
            assertInvalid(fixture, "分页超过")
            assertEquals(8, requests)
        }
    }

    @Test
    fun toolLimitFailsInsteadOfSilentlyDroppingLaterTools() {
        withServer {
            page().put("tools", JSONArray().apply {
                repeat(129) { put(tool("tool_$it")) }
            })
        }.use { fixture -> assertInvalid(fixture, "工具数量超过") }
    }

    @Test
    fun exactToolLimitCanCompleteOnAnEmptyFinalPage() {
        var requests = 0
        withServer {
            requests += 1
            if (requests == 1) {
                page().put("tools", JSONArray().apply {
                    repeat(128) { put(tool("tool_$it")) }
                }).put("nextCursor", "final")
            } else {
                page()
            }
        }.use { fixture ->
            assertEquals(128, fixture.discover().tools.size)
            assertEquals(2, requests)
        }
    }

    @Test
    fun duplicateToolNamesAcrossPagesAreRejected() {
        var requests = 0
        withServer {
            requests += 1
            page("duplicate").apply { if (requests == 1) put("nextCursor", "second") }
        }.use { fixture -> assertInvalid(fixture, "重复工具名称") }
    }

    @Test
    fun missingToolsArrayIsRejected() {
        withServer { page().apply { remove("tools") } }
            .use { fixture -> assertInvalid(fixture, "tools 字段不是数组") }
    }

    @Test
    fun nonObjectToolIsRejectedInsteadOfSilentlySkipped() {
        withServer { page().put("tools", JSONArray().put("invalid")) }
            .use { fixture -> assertInvalid(fixture, "工具条目不是对象") }
    }

    @Test
    fun invalidToolNamesAreRejected() {
        listOf<Any>("", "  ", 17, JSONObject.NULL).forEach { name ->
            withServer { page().put("tools", JSONArray().put(tool("valid").put("name", name))) }
                .use { fixture -> assertInvalid(fixture, "工具名称为空或不是字符串") }
        }
    }

    @Test
    fun inputSchemaMustBeAnObject() {
        withServer {
            page().put("tools", JSONArray().put(tool("valid").put("inputSchema", JSONArray())))
        }.use { fixture -> assertInvalid(fixture, "inputSchema 不是对象") }
    }

    @Test
    fun cursorMustBeAStringOrNull() {
        withServer { page("valid").put("nextCursor", 12) }
            .use { fixture -> assertInvalid(fixture, "nextCursor 字段不是字符串") }
        withServer { page("valid").put("nextCursor", JSONObject.NULL) }
            .use { fixture -> assertEquals(listOf("valid"), fixture.discover().tools.map { it.name }) }
    }

    private fun assertInvalid(fixture: Fixture, reason: String) {
        val failure = runCatching { fixture.discover() }.exceptionOrNull()
        assertTrue("Expected classified catalog failure, got $failure", failure is McpTransportException)
        val transport = failure as McpTransportException
        assertEquals(McpFailureKind.MALFORMED_RESPONSE, transport.kind)
        assertFalse("Invalid catalogs must not trigger legacy fallback", transport.compatibility)
        assertTrue(transport.message.orEmpty().contains(reason))
    }

    private fun page(vararg names: String): JSONObject = JSONObject()
        .put("resultType", "complete")
        .put("ttlMs", 1000L)
        .put("tools", JSONArray().apply { names.forEach { put(tool(it)) } })

    private fun tool(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("inputSchema", JSONObject().put("type", "object"))

    private fun withServer(result: (JSONObject) -> JSONObject): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", request.getLong("id"))
                .put("result", result(request))
                .toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        return Fixture(server)
    }

    private class Fixture(private val server: HttpServer) : AutoCloseable {
        fun discover(): McpHttpClient.Discovery = McpHttpClient(
            McpServerSetting("catalog", "Catalog", "http://127.0.0.1:${server.address.port}/mcp"),
            bearerToken = null,
            callTimeoutMs = 5000L,
        ).use { it.discoverTools() }

        override fun close() = server.stop(0)
    }
}
