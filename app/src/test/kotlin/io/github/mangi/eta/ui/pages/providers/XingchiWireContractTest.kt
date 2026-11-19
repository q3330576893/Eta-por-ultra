package io.github.mangi.eta.ui.pages.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Loopback wire tests, not claims that a blocked live account has received inference access. */
class XingchiWireContractTest {
    private val original = OpenAiCompatibleProviderSetting(id = "xingchi-wire", name = "星驰",
        baseUrl = "https://api.xingchiapi.com", apiKey = "fixture-only-key")
    private fun configured(baseUrl: String): OpenAiCompatibleProviderSetting =
        (XingchiCompatibility.apply(ProviderConfigDraft.from(original)).toProvider(original) as OpenAiCompatibleProviderSetting)
            .copy(baseUrl = baseUrl)
    private fun config(provider: OpenAiCompatibleProviderSetting) = AgentModelClient.ModelConfig(
        baseUrl = provider.baseUrl, apiKey = provider.apiKey, model = "fixture-model", systemPrompt = "系统约束",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES, customHeaders = provider.customHeaders,
    )

    @Test fun catalogRequestUsesSameProviderHeadersAndRemoteModelIds() = runBlocking {
        val observed = AtomicReference<List<String>>()
        withServer { server, base ->
            server.createContext("/v1/models") { exchange ->
                observed.set(listOf(exchange.requestMethod, exchange.requestHeaders.getFirst("User-Agent"), exchange.requestHeaders.getFirst("Authorization")))
                reply(exchange, 200, "application/json", """{"data":[{"id":"fixture-model","object":"model"}]}""")
            }
            val models = RemoteModelFetcher.fetch(configured(base)).getOrThrow()
            assertEquals(listOf("fixture-model"), models.map { it.modelId })
            assertEquals(listOf("GET", XingchiCompatibility.USER_AGENT, "Bearer fixture-only-key"), observed.get())
        }
    }

    @Test fun actualProviderStreamsBeforeCompletionAndReplaysTypedToolResultsWithoutAStoredChain() {
        val firstDelta = CountDownLatch(1)
        val requests = mutableListOf<JSONObject>()
        val sequence = AtomicInteger()
        val requestFailure = AtomicReference<Throwable?>()
        withServer { server, base ->
            server.createContext("/v1/responses") { exchange ->
                try {
                    assertEquals(XingchiCompatibility.USER_AGENT, exchange.requestHeaders.getFirst("User-Agent"))
                    assertEquals("Bearer fixture-only-key", exchange.requestHeaders.getFirst("Authorization"))
                    requests += JSONObject(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        if (sequence.getAndIncrement() == 0) {
                            output.write(event("response.output_text.delta", JSONObject().put("delta", "O").put("item_id", "message-1").put("output_index", 0).put("content_index", 0)).toByteArray())
                            output.flush()
                            check(firstDelta.await(10, TimeUnit.SECONDS)) { "First text delta was buffered until completion" }
                            output.write(event("response.output_text.delta", JSONObject().put("delta", "K").put("item_id", "message-1").put("output_index", 0).put("content_index", 0)).toByteArray())
                            val text = JSONObject().put("type", "message").put("id", "message-1")
                                .put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "OK")))
                            val call = JSONObject().put("type", "function_call").put("id", "function-1")
                                .put("call_id", "call-fixture").put("name", "fixture_tool").put("arguments", "{}")
                            output.write(completed(JSONArray().put(text).put(call)).toByteArray())
                        } else {
                            val text = JSONObject().put("type", "message").put("id", "message-2")
                                .put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "done")))
                            output.write(completed(JSONArray().put(text)).toByteArray())
                        }
                    }
                } catch (failure: Throwable) { requestFailure.set(failure); exchange.close() }
            }
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "系统约束"))
                .put(JSONObject().put("role", "developer").put("content", "补充约束"))
                .put(JSONObject().put("role", "user").put("content", "原始用户问题"))
            val tools = JSONArray().put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", "fixture_tool").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
            val config = config(configured(base))
            val first = OpenAiResponsesProvider.complete(ProviderRequest(config, messages, tools), AgentRunController()) { value ->
                if (value is ProviderEvent.BlockDelta && value.kind == AssistantBlockKind.TEXT && value.delta == "O") firstDelta.countDown()
            }
            assertEquals("OK", first.assistantMessage.getString("content"))
            assertEquals("call-fixture", first.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            messages.put(first.assistantMessage)
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", "call-fixture").put("content", "fixture-result"))
            val second = OpenAiResponsesProvider.complete(ProviderRequest(config, messages, tools), AgentRunController()) {}
            assertEquals("done", second.assistantMessage.getString("content"))
            assertNull(requestFailure.get())
            assertEquals(2, requests.size)
            requests.forEach { body ->
                assertFalse(body.getBoolean("store"))
                assertTrue(body.getBoolean("stream"))
                assertFalse(body.has("previous_response_id"))
                assertTrue(body.getString("instructions").contains("系统约束"))
                assertTrue(body.getString("instructions").contains("补充约束"))
                val input = body.getJSONArray("input")
                assertTrue((0 until input.length()).none { input.getJSONObject(it).optString("role") in setOf("system", "developer") })
            }
            val replay = requests.last().getJSONArray("input")
            val output = (0 until replay.length()).map { replay.getJSONObject(it) }.single { it.optString("type") == "function_call_output" }
            assertEquals("call-fixture", output.getString("call_id"))
            assertEquals("fixture-result", output.getString("output"))
        }
    }

    @Test fun groupDeniedAccountIsNotTreatedAsAClientHeaderProblemOrRetried() = runBlocking {
        val calls = AtomicInteger()
        withServer { server, base ->
            val handler: (HttpExchange) -> Unit = { exchange ->
                calls.incrementAndGet()
                exchange.requestBody.close()
                reply(exchange, 403, "application/json", """{"error":{"type":"permission_error","message":"API Key is not assigned to any group and cannot be used."}}""")
            }
            server.createContext("/v1/models", handler)
            server.createContext("/v1/responses", handler)
            val catalogError = RemoteModelFetcher.fetch(configured(base)).exceptionOrNull() as AgentModelFailure
            assertEquals("PROVIDER_GROUP_UNASSIGNED", catalogError.code)
            val chatError = assertThrows(AgentModelFailure::class.java) {
                OpenAiResponsesProvider.complete(ProviderRequest(config(configured(base)),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "hello")), JSONArray()), AgentRunController()) {}
            }
            assertEquals("PROVIDER_GROUP_UNASSIGNED", chatError.code)
            assertFalse(chatError.retryable)
            assertEquals(2, calls.get())
        }
    }

    private fun event(type: String, fields: JSONObject): String = "event: $type\ndata: ${fields.put("type", type)}\n\n"
    private fun completed(output: JSONArray) = event("response.completed", JSONObject().put("response", JSONObject().put("status", "completed").put("output", output)))
    private fun reply(exchange: HttpExchange, status: Int, contentType: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    private inline fun <T> withServer(block: (HttpServer, String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.start()
        return try { block(server, "http://127.0.0.1:${server.address.port}/v1") }
        finally { server.stop(0); executor.shutdownNow() }
    }
}
