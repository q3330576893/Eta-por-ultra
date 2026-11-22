package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises complete() through actual loopback HTTP, not a duplicate of the hook projector. */
class ModelRequestHookProtocolTest {
    @Test
    fun chatCompletionsSendsEachHookChannelAndPreservesMultimodalToolHistory() =
        assertChannelsAndHistory(Protocol.CHAT)

    @Test
    fun responsesSendsEachHookChannelAndPreservesMultimodalToolHistory() =
        assertChannelsAndHistory(Protocol.RESPONSES)

    @Test
    fun anthropicSendsEachHookChannelAndPreservesMultimodalToolHistory() =
        assertChannelsAndHistory(Protocol.ANTHROPIC)

    @Test
    fun geminiSendsEachHookChannelAndPreservesMultimodalToolHistory() =
        assertChannelsAndHistory(Protocol.GEMINI)

    @Test
    fun stringUserHookPrecedesTheRealUserAndSkipsObservationAndContextSummary() {
        Protocol.entries.forEach { protocol ->
            withServer(protocol) { baseUrl, captured ->
                val messages = simpleMessages(protocol, stringContent = true)
                val original = messages.toString()
                val hook = hook(ModelRequestHookChannel.USER)
                val request = ProviderRequest(config(protocol, baseUrl, hook), messages, JSONArray())

                complete(protocol, request)

                val body = captured.single().body
                assertHookPosition(protocol, body, ModelRequestHookChannel.USER, arrayContent = false)
                assertSyntheticMessagesUnmodified(protocol, body)
                assertNoInternalHookMetadata(body)
                assertEquals("${protocol.name}: source history changed", original, messages.toString())
            }
        }
    }

    @Test
    fun roleplayDepthZeroKeepsHookOnRealUserAndPreservesSignedToolHistory() {
        val depthText = "ETA_ROLEPLAY_DEPTH_USER"
        val context = RoleplayRunContext(
            characterId = "fixture",
            card = CharacterCardCodec.decodeJson("""{
                "name":"Fixture","extensions":{"depth_prompt":{"prompt":"$depthText","depth":0,"role":"user"}}
            }"""),
            userName = "User",
            userDescription = "",
            contextWindow = 128_000,
        )
        val channels = listOf(ModelRequestHookChannel.USER, ModelRequestHookChannel.BOTH)
        for (protocol in Protocol.entries) {
            withServer(protocol, expectedRequests = channels.size) { baseUrl, captured ->
                val source = historyMessages(protocol)
                val sourceBefore = source.toString()
                val roleplayMessages = context.projectMessages(source)
                val roleplayBefore = roleplayMessages.toString()
                assertEquals(depthText, roleplayMessages.getJSONObject(roleplayMessages.length() - 1).getString("content"))
                for (channel in channels) {
                    complete(protocol, ProviderRequest(config(protocol, baseUrl, hook(channel)), roleplayMessages, tools()))
                    val body = captured.last().body
                    assertHookPosition(protocol, body, channel, arrayContent = true)
                    assertImagePreserved(protocol, body)
                    assertToolHistoryPreserved(protocol, body)
                    val sentDepthText = userContents(protocol, body).flatMap(::textParts).single { it.contains(depthText) }
                    assertEquals(depthText, sentDepthText)
                    assertNoInternalHookMetadata(body)
                    assertEquals(sourceBefore, source.toString())
                    assertEquals(roleplayBefore, roleplayMessages.toString())
                }
            }
        }
    }

    @Test
    fun disabledAndDedicatedPurposesDoNotInjectButComparisonDoes() {
        Protocol.entries.forEach { protocol ->
            withServer(protocol) { baseUrl, captured ->
                val messages = simpleMessages(protocol, stringContent = false)
                val original = messages.toString()
                val cases = listOf(
                    Triple(ProviderRequestPurpose.CHAT, hook(ModelRequestHookChannel.BOTH).copy(enabled = false), false),
                    Triple(ProviderRequestPurpose.CHAT, hook(ModelRequestHookChannel.BOTH).copy(enabled = false, prompt = " \n\t "), false),
                    Triple(ProviderRequestPurpose.COMPACTION, hook(ModelRequestHookChannel.BOTH), false),
                    Triple(ProviderRequestPurpose.TRANSCRIPT_REFINE, hook(ModelRequestHookChannel.BOTH), false),
                    Triple(ProviderRequestPurpose.REPLY_REWRITE, hook(ModelRequestHookChannel.BOTH), false),
                    Triple(ProviderRequestPurpose.COMPARISON, hook(ModelRequestHookChannel.BOTH), true),
                )
                cases.forEach { (purpose, selectedHook, enabled) ->
                    complete(protocol, ProviderRequest(
                        config(protocol, baseUrl, selectedHook), messages, JSONArray(), purpose = purpose,
                    ))
                    val body = captured.last().body
                    if (enabled) {
                        assertHookPosition(protocol, body, ModelRequestHookChannel.BOTH, arrayContent = true)
                    } else {
                        assertEquals("${protocol.name}/$purpose", 0, occurrences(body.toString(), HOOK))
                        assertFalse(systemText(protocol, body).contains(HOOK))
                        assertEquals(REAL_USER, textParts(targetContent(protocol, body)).first())
                    }
                    assertSyntheticMessagesUnmodified(protocol, body)
                    assertNoInternalHookMetadata(body)
                    assertEquals("${protocol.name}/$purpose changed source", original, messages.toString())
                }
                assertEquals(cases.size, captured.size)
            }
        }
    }

    @Test
    fun enabledBlankHookFailsBeforeAnyProtocolHttpRequest() {
        Protocol.entries.forEach { protocol ->
            withServer(protocol, expectedRequests = 0) { baseUrl, captured ->
                val messages = simpleMessages(protocol, stringContent = true)
                val original = messages.toString()
                val request = ProviderRequest(
                    config(protocol, baseUrl, hook(ModelRequestHookChannel.BOTH).copy(prompt = " \n\t ")),
                    messages,
                    JSONArray(),
                )

                val failure = runCatching { complete(protocol, request) }.exceptionOrNull()

                assertTrue("${protocol.name}: enabled blank hook must fail validation", failure is IllegalArgumentException)
                assertEquals("${protocol.name}: invalid configuration reached HTTP", 0, captured.size)
                assertEquals(original, messages.toString())
            }
        }
    }

    @Test
    fun customBodyCannotReplaceHookBearingProtocolInstructionsOrMessages() {
        Protocol.entries.forEach { protocol ->
            withServer(protocol) { baseUrl, captured ->
                val messages = historyMessages(protocol)
                val original = messages.toString()
                val request = ProviderRequest(
                    config(protocol, baseUrl, hook(ModelRequestHookChannel.BOTH)).copy(
                        customBody = protocol.customBodyOverrides(),
                    ),
                    messages,
                    tools(),
                )

                complete(protocol, request)

                val body = captured.single().body
                assertFalse("${protocol.name}: custom body replaced protected content", body.toString().contains(CUSTOM_OVERRIDE))
                assertHookPosition(protocol, body, ModelRequestHookChannel.BOTH, arrayContent = true)
                assertImagePreserved(protocol, body)
                assertToolHistoryPreserved(protocol, body)
                assertNoInternalHookMetadata(body)
                assertEquals(original, messages.toString())
            }
        }
    }

    @Test
    fun retryingTheSameRequestAfterHttpFailureDoesNotAccumulateHooks() {
        Protocol.entries.forEach { protocol ->
            withServer(protocol, failFirst = true) { baseUrl, captured ->
                val messages = historyMessages(protocol)
                val original = messages.toString()
                val request = ProviderRequest(
                    config(protocol, baseUrl, hook(ModelRequestHookChannel.BOTH)), messages, tools(),
                )
                val failure = runCatching { complete(protocol, request) }.exceptionOrNull()
                assertTrue("${protocol.name}: expected HTTP failure", failure is AgentModelFailure)
                assertTrue((failure as AgentModelFailure).retryable)
                assertEquals("HTTP_503", failure.code)
                assertEquals(original, messages.toString())

                // The loop owns retry policy; repeat the identical ProviderRequest after the transient failure.
                complete(protocol, request)

                assertEquals(2, captured.size)
                captured.forEach { capture ->
                    assertHookPosition(protocol, capture.body, ModelRequestHookChannel.BOTH, arrayContent = true)
                    assertImagePreserved(protocol, capture.body)
                    assertToolHistoryPreserved(protocol, capture.body)
                    assertNoInternalHookMetadata(capture.body)
                }
                assertEquals(captured.first().body.toString(), captured.last().body.toString())
                assertEquals("${protocol.name}: retry polluted source", original, messages.toString())
            }
        }
    }

    private fun assertChannelsAndHistory(protocol: Protocol) {
        withServer(protocol) { baseUrl, captured ->
            ModelRequestHookChannel.entries.forEach { channel ->
                val messages = historyMessages(protocol)
                val sourceBefore = messages.toString()
                val toolDefinitions = tools()
                val toolsBefore = toolDefinitions.toString()
                complete(protocol, ProviderRequest(config(protocol, baseUrl, hook(channel)), messages, toolDefinitions))

                val body = captured.last().body
                assertHookPosition(protocol, body, channel, arrayContent = true)
                assertImagePreserved(protocol, body)
                assertToolHistoryPreserved(protocol, body)
                assertTrue(body.toString().contains(OLD_USER))
                assertTrue(body.toString().contains(OLD_ASSISTANT))
                assertNoInternalHookMetadata(body)
                assertEquals("${protocol.name}/$channel changed original messages", sourceBefore, messages.toString())
                assertEquals("${protocol.name}/$channel changed original tools", toolsBefore, toolDefinitions.toString())
            }
            assertEquals(ModelRequestHookChannel.entries.size, captured.size)
        }
    }

    private fun assertHookPosition(
        protocol: Protocol,
        body: JSONObject,
        channel: ModelRequestHookChannel,
        arrayContent: Boolean,
    ) {
        val systemHook = channel != ModelRequestHookChannel.USER
        val userHook = channel != ModelRequestHookChannel.SYSTEM
        val instructions = systemText(protocol, body)
        assertTrue("${protocol.name}: missing original system", instructions.contains(BASE_SYSTEM))
        assertTrue("${protocol.name}: missing last original system", instructions.contains(LAST_SYSTEM))
        assertTrue(instructions.indexOf(BASE_SYSTEM) < instructions.indexOf(LAST_SYSTEM))
        assertEquals(if (systemHook) 1 else 0, occurrences(instructions, HOOK))
        if (systemHook) assertTrue(instructions.indexOf(LAST_SYSTEM) < instructions.indexOf(HOOK))

        val content = targetContent(protocol, body)
        val parts = textParts(content)
        if (arrayContent) {
            // A dedicated hook block leaves every original multimodal block intact and ordered.
            assertEquals(if (userHook) 1 else 0, parts.indexOf(REAL_USER))
            assertTrue(parts.indexOf(USER_TAIL) > parts.indexOf(REAL_USER))
            if (userHook) assertTrue(parts.first().contains(HOOK))
        } else {
            val target = parts.single { it.contains(REAL_USER) }
            if (userHook) {
                assertTrue(target.indexOf(HOOK) in 0 until target.indexOf(REAL_USER))
                assertTrue(target.endsWith(REAL_USER))
            } else assertEquals(REAL_USER, target)
        }
        assertEquals(if (userHook) 1 else 0, occurrences(content.toString(), HOOK))
        assertEquals((if (systemHook) 1 else 0) + (if (userHook) 1 else 0), occurrences(body.toString(), HOOK))
    }

    private fun assertImagePreserved(protocol: Protocol, body: JSONObject) {
        val content = targetContent(protocol, body) as JSONArray
        val image = content.objects().single { it.optString("type") in setOf("image_url", "input_image", "image") }
        val imageIndex = (0 until content.length()).single { content.optJSONObject(it) === image }
        val textIndex = (0 until content.length()).single { content.optJSONObject(it)?.optString("text") == REAL_USER }
        assertEquals(textIndex + 1, imageIndex)
        assertEquals(USER_TAIL, content.getJSONObject(imageIndex + 1).getString("text"))
        when (protocol) {
            Protocol.CHAT -> {
                assertEquals(IMAGE_URL, image.getJSONObject("image_url").getString("url"))
                assertEquals("high", image.getJSONObject("image_url").getString("detail"))
            }
            Protocol.RESPONSES -> assertEquals(IMAGE_URL, image.getString("image_url"))
            Protocol.ANTHROPIC -> {
                assertEquals("base64", image.getJSONObject("source").getString("type"))
                assertEquals("image/png", image.getJSONObject("source").getString("media_type"))
                assertEquals("AA==", image.getJSONObject("source").getString("data"))
            }
            Protocol.GEMINI -> {
                assertEquals("image/png", image.getString("mime_type"))
                assertEquals("AA==", image.getString("data"))
            }
        }
    }

    private fun assertToolHistoryPreserved(protocol: Protocol, body: JSONObject) {
        when (protocol) {
            Protocol.CHAT -> {
                val messages = body.getJSONArray("messages").objects()
                val assistant = messages.single { it.optJSONArray("tool_calls") != null }
                assertEquals(ASSISTANT_TEXT, assistant.getString("content"))
                assertEquals(CHAT_REASONING, assistant.getString("reasoning_content"))
                val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
                assertEquals(CALL_ID, call.getString("id"))
                assertEquals(TOOL_NAME, call.getJSONObject("function").getString("name"))
                assertEquals(TOOL_ARGUMENTS, call.getJSONObject("function").getString("arguments"))
                val result = messages.single { it.optString("role") == "tool" }
                assertEquals(CALL_ID, result.getString("tool_call_id"))
                assertEquals(TOOL_RESULT, result.getString("content"))
            }
            Protocol.RESPONSES -> {
                val input = body.getJSONArray("input").objects()
                assertEquals(RESPONSES_SIGNATURE, input.single { it.optString("type") == "reasoning" }.getString("encrypted_content"))
                val call = input.single { it.optString("type") == "function_call" }
                assertEquals(CALL_ID, call.getString("call_id"))
                assertEquals(TOOL_NAME, call.getString("name"))
                assertEquals(TOOL_ARGUMENTS, call.getString("arguments"))
                val result = input.single { it.optString("type") == "function_call_output" }
                assertEquals(CALL_ID, result.getString("call_id"))
                assertEquals(TOOL_RESULT, result.getString("output"))
            }
            Protocol.ANTHROPIC -> {
                val messages = body.getJSONArray("messages").objects()
                val assistant = messages.single { it.optString("role") == "assistant" && it.optJSONArray("content")
                    ?.objects()?.any { block -> block.optString("type") == "tool_use" } == true }
                val blocks = assistant.getJSONArray("content").objects()
                assertEquals(ANTHROPIC_SIGNATURE, blocks.single { it.optString("type") == "thinking" }.getString("signature"))
                val call = blocks.single { it.optString("type") == "tool_use" }
                assertEquals(CALL_ID, call.getString("id"))
                assertEquals(TOOL_NAME, call.getString("name"))
                assertEquals("fixture", call.getJSONObject("input").getString("query"))
                val result = messages.flatMap { it.getJSONArray("content").objects() }
                    .single { it.optString("type") == "tool_result" }
                assertEquals(CALL_ID, result.getString("tool_use_id"))
                assertEquals(TOOL_RESULT, result.getString("content"))
            }
            Protocol.GEMINI -> {
                val input = body.getJSONArray("input").objects()
                assertEquals(GEMINI_SIGNATURE, input.single { it.optString("type") == "thought" }.getString("signature"))
                val call = input.single { it.optString("type") == "function_call" }
                assertEquals(CALL_ID, call.getString("id"))
                assertEquals(TOOL_NAME, call.getString("name"))
                assertEquals(GEMINI_CALL_SIGNATURE, call.getString("signature"))
                assertEquals("fixture", call.getJSONObject("arguments").getString("query"))
                val result = input.single { it.optString("type") == "function_result" }
                assertEquals(CALL_ID, result.getString("call_id"))
                assertEquals(TOOL_NAME, result.getString("name"))
                assertEquals(TOOL_RESULT, result.getString("result"))
            }
        }
    }

    private fun assertSyntheticMessagesUnmodified(protocol: Protocol, body: JSONObject) {
        val texts = userContents(protocol, body).flatMap(::textParts)
        assertEquals(OBSERVATION, texts.single { it.contains(OBSERVATION) })
        assertEquals(SUMMARY, texts.single { it.contains(SUMMARY) })
    }

    private fun assertNoInternalHookMetadata(body: JSONObject) {
        fun inspect(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    assertFalse("Internal key leaked to HTTP: $key", key.startsWith("_eta_") ||
                        key in setOf("requestHook", "request_hook", "hookChannel", "hook_channel", "hook_enabled"))
                    inspect(value.opt(key))
                }
                is JSONArray -> (0 until value.length()).forEach { inspect(value.opt(it)) }
            }
        }
        inspect(body)
    }

    private fun systemText(protocol: Protocol, body: JSONObject): String = when (protocol) {
        Protocol.CHAT -> body.getJSONArray("messages").objects().filter { it.optString("role") == "system" }
            .joinToString("\n\n") { it.getString("content") }
        Protocol.RESPONSES -> body.getString("instructions")
        Protocol.ANTHROPIC -> body.getJSONArray("system").objects().joinToString("\n\n") { it.getString("text") }
        Protocol.GEMINI -> body.getString("system_instruction")
    }

    private fun targetContent(protocol: Protocol, body: JSONObject): Any = userContents(protocol, body)
        .single { textParts(it).any { text -> text.contains(REAL_USER) } }

    private fun userContents(protocol: Protocol, body: JSONObject): List<Any> = when (protocol) {
        Protocol.CHAT, Protocol.ANTHROPIC -> body.getJSONArray("messages").objects()
            .filter { it.optString("role") == "user" }.map { it.get("content") }
        Protocol.RESPONSES -> body.getJSONArray("input").objects()
            .filter { it.optString("type") == "message" && it.optString("role") == "user" }.map { it.get("content") }
        Protocol.GEMINI -> body.getJSONArray("input").objects()
            .filter { it.optString("type") == "user_input" }.map { it.get("content") }
    }

    private fun textParts(content: Any): List<String> = when (content) {
        is JSONArray -> content.objects().filter { it.optString("type") in setOf("text", "input_text") }.map { it.getString("text") }
        else -> listOf(content.toString())
    }

    private fun historyMessages(protocol: Protocol): JSONArray {
        val user = JSONObject().put("role", "user").put("content", imageContent())
        if (protocol == Protocol.GEMINI) GeminiEphemeralState.markRunStart(user)
        val assistant = JSONObject().put("role", "assistant").put("content", ASSISTANT_TEXT)
            .put("tool_calls", JSONArray().put(JSONObject().put("id", CALL_ID).put("type", "function")
                .put("function", JSONObject().put("name", TOOL_NAME).put("arguments", TOOL_ARGUMENTS))))
        when (protocol) {
            Protocol.CHAT -> assistant.put("reasoning_content", CHAT_REASONING)
            Protocol.RESPONSES -> ResponsesEphemeralState.attachOutputItems(assistant, JSONArray()
                .put(JSONObject().put("id", "rs_hook_fixture").put("type", "reasoning").put("encrypted_content", RESPONSES_SIGNATURE))
                .put(JSONObject().put("type", "message").put("role", "assistant").put("content", ASSISTANT_TEXT))
                .put(JSONObject().put("id", "fc_hook_fixture").put("type", "function_call").put("call_id", CALL_ID)
                    .put("name", TOOL_NAME).put("arguments", TOOL_ARGUMENTS)))
            Protocol.ANTHROPIC -> AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "ETA_THINKING_FIXTURE").put("signature", ANTHROPIC_SIGNATURE))
                .put(JSONObject().put("type", "text").put("text", ASSISTANT_TEXT))
                .put(JSONObject().put("type", "tool_use").put("id", CALL_ID).put("name", TOOL_NAME)
                    .put("input", JSONObject(TOOL_ARGUMENTS))))
            Protocol.GEMINI -> GeminiEphemeralState.attachSteps(assistant, JSONArray()
                .put(JSONObject().put("type", "thought").put("signature", GEMINI_SIGNATURE))
                .put(JSONObject().put("type", "function_call").put("id", CALL_ID).put("name", TOOL_NAME)
                    .put("arguments", JSONObject(TOOL_ARGUMENTS)).put("signature", GEMINI_CALL_SIGNATURE)))
        }
        return prefix().put(user).put(assistant)
            .put(JSONObject().put("role", "tool").put("tool_call_id", CALL_ID).put("content", TOOL_RESULT))
            .put(JSONObject().put("role", "user").put("content", OBSERVATION).put("_eta_observation", true))
    }

    private fun simpleMessages(protocol: Protocol, stringContent: Boolean): JSONArray = prefix()
        .put(JSONObject().put("role", "user").put("content", if (stringContent) REAL_USER else imageContent())
            .also { if (protocol == Protocol.GEMINI) GeminiEphemeralState.markRunStart(it) })
        .put(JSONObject().put("role", "user").put("content", OBSERVATION).put("_eta_observation", true))
        .put(JSONObject().put("role", "user").put("content", SUMMARY).put("_eta_context_summary", true))

    private fun prefix() = JSONArray()
        .put(JSONObject().put("role", "system").put("content", BASE_SYSTEM))
        .put(JSONObject().put("role", "system").put("content", LAST_SYSTEM))
        .put(JSONObject().put("role", "user").put("content", OLD_USER))
        .put(JSONObject().put("role", "assistant").put("content", OLD_ASSISTANT))

    private fun imageContent() = JSONArray()
        .put(JSONObject().put("type", "text").put("text", REAL_USER))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", IMAGE_URL).put("detail", "high")))
        .put(JSONObject().put("type", "text").put("text", USER_TAIL))

    private fun tools() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL_NAME).put("description", "Loopback-only hook fixture")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string"))))))

    private fun hook(channel: ModelRequestHookChannel) = ModelRequestHook(enabled = true, channel = channel, prompt = HOOK)

    private fun config(protocol: Protocol, baseUrl: String, hook: ModelRequestHook) = AgentModelClient.ModelConfig(
        providerType = when (protocol) {
            Protocol.ANTHROPIC -> ProviderTypes.ANTHROPIC
            Protocol.GEMINI -> ProviderTypes.GEMINI
            else -> ProviderTypes.OPENAI_COMPATIBLE
        },
        baseUrl = baseUrl,
        apiKey = "eta-loopback-fixture-key",
        model = if (protocol == Protocol.GEMINI) "gemini-3.8-flash" else "test-model",
        systemPrompt = "ETA_CONFIG_FALLBACK",
        openAiEndpointMode = if (protocol == Protocol.RESPONSES) OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.CHAT_COMPLETIONS,
        reasoningEffort = ReasoningEffort.DEFAULT,
        requestHook = hook,
    )

    private fun Protocol.customBodyOverrides(): List<CustomBody> = when (this) {
        Protocol.CHAT -> listOf(CustomBody("messages", Json.parseToJsonElement("""[{"role":"user","content":"$CUSTOM_OVERRIDE"}]""")))
        Protocol.RESPONSES -> listOf(
            CustomBody("instructions", Json.parseToJsonElement("\"$CUSTOM_OVERRIDE\"")),
            CustomBody("input", Json.parseToJsonElement("""[{"role":"user","content":"$CUSTOM_OVERRIDE"}]""")),
        )
        Protocol.ANTHROPIC -> listOf(
            CustomBody("system", Json.parseToJsonElement("\"$CUSTOM_OVERRIDE\"")),
            CustomBody("messages", Json.parseToJsonElement("""[{"role":"user","content":"$CUSTOM_OVERRIDE"}]""")),
        )
        Protocol.GEMINI -> listOf(
            CustomBody("system_instruction", Json.parseToJsonElement("\"$CUSTOM_OVERRIDE\"")),
            CustomBody("input", Json.parseToJsonElement("""[{"type":"user_input","content":[{"type":"text","text":"$CUSTOM_OVERRIDE"}]}]""")),
        )
    }

    private fun complete(protocol: Protocol, request: ProviderRequest) {
        val result = protocol.provider.complete(request, AgentRunController())
        assertEquals("${protocol.name}: SSE fixture was not consumed", "ETA_OK", result.assistantMessage.getString("content"))
    }

    private fun withServer(
        protocol: Protocol,
        failFirst: Boolean = false,
        expectedRequests: Int? = null,
        block: (String, List<CapturedRequest>) -> Unit,
    ) {
        val captured = CopyOnWriteArrayList<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext(protocol.path) { exchange ->
            captured += CapturedRequest(exchange.requestMethod, exchange.requestURI.path,
                JSONObject(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }))
            val failed = failFirst && captured.size == 1
            val bytes = (if (failed) """{"error":{"type":"service_unavailable","message":"ETA_TRANSIENT_FIXTURE"}}"""
                else protocol.response()).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (failed) "application/json" else "text/event-stream")
            exchange.sendResponseHeaders(if (failed) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}${protocol.basePath}", captured)
            if (expectedRequests == null) assertTrue(captured.isNotEmpty())
            else assertEquals(expectedRequests, captured.size)
            captured.forEach {
                assertEquals("POST", it.method)
                assertEquals(protocol.path, it.path)
            }
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun Protocol.response(): String = when (this) {
        Protocol.CHAT -> "data: {\"choices\":[{\"delta\":{\"content\":\"ETA_OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        Protocol.RESPONSES -> "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"id\":\"msg_hook\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ETA_OK\"}]}]}}\n\n"
        Protocol.ANTHROPIC -> "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"ETA_OK\"}}\n\n" +
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        Protocol.GEMINI -> "event: step.start\ndata: {\"event_type\":\"step.start\",\"index\":0,\"step\":{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"ETA_OK\"}]}}\n\n" +
            "event: step.stop\ndata: {\"event_type\":\"step.stop\",\"index\":0}\n\n" +
            "event: interaction.completed\ndata: {\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"completed\"}}\n\n"
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    private fun occurrences(text: String, marker: String): Int = Regex(Regex.escape(marker)).findAll(text).count()

    private data class CapturedRequest(val method: String, val path: String, val body: JSONObject)

    private enum class Protocol(val path: String, val basePath: String) {
        CHAT("/v1/chat/completions", "/v1"),
        RESPONSES("/v1/responses", "/v1"),
        ANTHROPIC("/v1/messages", ""),
        GEMINI("/v1beta/interactions", "/v1beta");

        val provider: AgentProviderClient get() = when (this) {
            CHAT -> OpenAiChatCompletionsProvider
            RESPONSES -> OpenAiResponsesProvider
            ANTHROPIC -> AnthropicMessagesProvider
            GEMINI -> GeminiInteractionsProvider
        }
    }

    private companion object {
        const val HOOK = "ETA_HOOK_TEST_REQUEST_PROMPT"
        const val BASE_SYSTEM = "ETA_BASE_SYSTEM"
        const val LAST_SYSTEM = "ETA_LAST_ORIGINAL_SYSTEM"
        const val OLD_USER = "ETA_OLD_USER_HISTORY"
        const val OLD_ASSISTANT = "ETA_OLD_ASSISTANT_HISTORY"
        const val REAL_USER = "ETA_CURRENT_REAL_USER"
        const val USER_TAIL = "ETA_ORIGINAL_USER_TAIL"
        const val OBSERVATION = "ETA_SYNTHETIC_OBSERVATION"
        const val SUMMARY = "ETA_SYNTHETIC_CONTEXT_SUMMARY"
        const val IMAGE_URL = "data:image/png;base64,AA=="
        const val ASSISTANT_TEXT = "ETA_TOOL_ASSISTANT"
        const val CHAT_REASONING = "ETA_CHAT_REASONING"
        const val CALL_ID = "call_eta_hook_fixture"
        const val TOOL_NAME = "eta_fixture_lookup"
        const val TOOL_ARGUMENTS = "{\"query\":\"fixture\"}"
        const val TOOL_RESULT = "{\"ok\":true,\"value\":\"ETA_TOOL_RESULT\"}"
        const val RESPONSES_SIGNATURE = "ETA_RESPONSES_ENCRYPTED_STATE"
        const val ANTHROPIC_SIGNATURE = "ETA_ANTHROPIC_THINKING_SIGNATURE"
        const val GEMINI_SIGNATURE = "ETA_GEMINI_THOUGHT_SIGNATURE"
        const val GEMINI_CALL_SIGNATURE = "ETA_GEMINI_CALL_SIGNATURE"
        const val CUSTOM_OVERRIDE = "ETA_CUSTOM_BODY_OVERRIDE"
    }
}
