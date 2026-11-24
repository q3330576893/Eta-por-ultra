package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

internal class McpHttpClient(
    private val server: McpServerSetting,
    private val bearerToken: String?,
    /** 单次 MCP 请求的整体预算，覆盖连接、发送、SSE 流读取，避免 keep-alive 流无限等待。 */
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
) : AutoCloseable {
    data class Discovery(
        val protocolVersion: String,
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
    )

    private data class ToolList(
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
    )

    private data class WireResponse(
        val json: JSONObject?,
        val sessionId: String?,
    )

    /** 单次 HTTP 尝试的结果：要么拿到最终响应，要么需要按策略重发到同源新地址。 */
    private sealed interface Attempt {
        class Result(val wire: WireResponse) : Attempt
        class Redirect(val request: Request) : Attempt
    }

    private val endpoint = validateMcpEndpoint(server.url)

    /**
     * 共享 AgentHttpClient 的连接池，但独立收紧策略：
     * - callTimeout 保证整请求有界，SSE 的 keep-alive 注释行无法把调用拖成无限等待；
     * - 关闭自动重定向，避免 Bearer Token 与 Mcp-Session-Id 被跨源转发。
     */
    private val httpClient = AgentHttpClient.client.newBuilder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val requestIds = AtomicLong(1)
    private val toolHeadersByName = mutableMapOf<String, McpToolHeaders>()
    private val lifecycleLock = Any()
    private var activeCall: Call? = null
    private var closed = false
    @Volatile
    private var negotiatedVersion: String? = null

    /**
     * 协商结果是否属于 legacy 家族。
     *
     * 不能再用「版本号是否在已知集合里」判断：服务端可能选定一个我们未收录的
     * 旧版本（例如 2025-08-01），握手仍然成功，后续请求必须继续走 legacy 语义。
     */
    @Volatile
    private var negotiatedLegacy = false
    @Volatile
    private var legacySessionId: String? = null

    fun discoverTools(): Discovery {
        val requestedMode = server.protocolMode
        if (requestedMode == McpProtocolMode.LEGACY) return discoverLegacy()
        if (requestedMode == McpProtocolMode.LATEST) return discoverLatest()
        return try {
            discoverLatest()
        } catch (failure: McpProtocolCompatibilityException) {
            discoverLegacy()
        }
    }

    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject {
        val version = negotiatedVersion ?: run {
            if (isLegacyFamily(server.lastProtocolVersion ?: server.protocolMode)) {
                initializeLegacy()
            } else {
                McpProtocolMode.LATEST.also {
                    negotiatedVersion = it
                    negotiatedLegacy = false
                }
            }
        }
        val legacy = negotiatedLegacy
        val parameterHeaders = if (legacy) {
            emptyMap()
        } else {
            toolHeadersByName.getOrPut(tool.name) {
                McpToolHeaders.fromSchema(JSONObject(tool.inputSchemaJson))
            }.extract(arguments)
        }
        fun invoke(sessionId: String?): JSONObject = unwrapResult(
            requireNotNull(
                request(
                    method = "tools/call",
                    name = tool.name,
                    params = JSONObject()
                        .put("name", tool.name)
                        .put("arguments", arguments),
                    protocolVersion = version,
                    sessionId = sessionId,
                    additionalHeaders = parameterHeaders,
                ).json,
            ) { "MCP 工具返回为空" },
        )
        return if (legacy) withLegacySessionRetry(::invoke) else invoke(sessionId = null)
    }

    override fun close() {
        val call: Call?
        val sessionId: String?
        val protocolVersion: String?
        val legacy: Boolean
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            call = activeCall
            activeCall = null
            sessionId = legacySessionId
            legacySessionId = null
            protocolVersion = negotiatedVersion
            legacy = negotiatedLegacy
        }
        call?.cancel()
        if (sessionId != null && legacy && protocolVersion != null) {
            releaseLegacySession(sessionId, protocolVersion)
        }
    }

    private fun discoverLatest(): Discovery {
        negotiatedVersion = McpProtocolMode.LATEST
        negotiatedLegacy = false
        return try {
            val listed = listTools(McpProtocolMode.LATEST, sessionId = null)
            Discovery(McpProtocolMode.LATEST, listed.tools, listed.cacheTtlMs)
        } catch (failure: McpHttpStatusException) {
            if (
                failure.code in setOf(400, 404, 405) &&
                failure.jsonRpcCode !in MODERN_JSON_RPC_ERRORS
            ) {
                throw McpProtocolCompatibilityException(failure)
            }
            throw failure
        } catch (failure: McpJsonRpcException) {
            if (failure.code == -32600) {
                throw McpProtocolCompatibilityException(failure)
            }
            throw failure
        } catch (failure: McpTransportException) {
            // 现代请求拿到 200 却不是可解析载荷时，同样按「服务端不认识现代协议」处理，
            // 交给 AUTO 模式回退到 legacy 握手，而不是直接把服务器判成不可用。
            if (failure.kind == McpFailureKind.MALFORMED_RESPONSE && failure.compatibility) {
                throw McpProtocolCompatibilityException(failure)
            }
            throw failure
        }
    }

    private fun discoverLegacy(): Discovery {
        val listed = withLegacySessionRetry { sessionId ->
            listTools(requireNotNull(negotiatedVersion), sessionId)
        }
        return Discovery(requireNotNull(negotiatedVersion), listed.tools, cacheTtlMs = null)
    }

    private fun <T> withLegacySessionRetry(block: (String?) -> T): T {
        initializeLegacy()
        val sessionId = legacySessionId
        return try {
            block(sessionId)
        } catch (failure: McpHttpStatusException) {
            if (sessionId == null || failure.code != 404) throw failure
            legacySessionId = null
            negotiatedVersion = null
            negotiatedLegacy = false
            initializeLegacy()
            block(legacySessionId)
        }
    }

    private fun initializeLegacy(): String {
        val currentVersion = negotiatedVersion
        if (negotiatedLegacy && currentVersion != null && legacySessionId != null) {
            return currentVersion
        }
        val initialized = request(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", McpProtocolMode.LEGACY)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                ),
            protocolVersion = McpProtocolMode.LEGACY,
            sessionId = null,
        )
        val initializeResult = unwrapResult(
            requireNotNull(initialized.json) { "MCP initialize 返回为空" }
        )
        // 服务端可能选一个我们未收录的旧版本；只有版本号本身不成形时才回落到默认 legacy 版本。
        val protocolVersion = negotiateLegacyVersion(initializeResult.optString("protocolVersion"))
        val newSessionId = initialized.sessionId
        val accepted = synchronized(lifecycleLock) {
            if (closed) {
                false
            } else {
                legacySessionId = newSessionId
                negotiatedLegacy = true
                true
            }
        }
        if (!accepted) {
            newSessionId?.let { releaseLegacySession(it, protocolVersion) }
            error("MCP 客户端已关闭")
        }
        notification(
            method = "notifications/initialized",
            protocolVersion = protocolVersion,
            sessionId = legacySessionId,
        )
        negotiatedVersion = protocolVersion
        return protocolVersion
    }

    private fun listTools(protocolVersion: String, sessionId: String?): ToolList {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        var cacheTtlMs: Long? = null
        repeat(MAX_LIST_PAGES) {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val response = request(
                method = "tools/list",
                params = params,
                protocolVersion = protocolVersion,
                sessionId = sessionId,
            ).json ?: error("MCP tools/list 返回为空")
            val result = unwrapResult(response)
            if (protocolVersion == McpProtocolMode.LATEST) {
                // 旧服务可能忽略现代协议元数据并仍以 200 返回，
                // 此时需要改走 initialize 协商。
                if (!result.has("resultType") || result.isNull("resultType")) {
                    throw McpProtocolCompatibilityException(
                        IOException("MCP tools/list 返回了旧协议结果")
                    )
                }
                require(result.optString("resultType") == "complete") {
                    "MCP tools/list 返回了不支持的结果类型"
                }
                val pageTtl = result.nullableLong("ttlMs")?.coerceAtLeast(0L) ?: 0L
                cacheTtlMs = cacheTtlMs?.let { minOf(it, pageTtl) } ?: pageTtl
            }
            val page = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until page.length()) {
                if (tools.size >= MAX_DISCOVERED_TOOLS) break
                page.optJSONObject(index)?.let { tools += parseTool(it) }
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null || tools.size >= MAX_DISCOVERED_TOOLS) {
                return ToolList(tools, cacheTtlMs)
            }
        }
        return ToolList(tools, cacheTtlMs)
    }

    private fun parseTool(source: JSONObject): McpToolDefinition {
        val name = source.optString("name").trim()
        val schema = source.optJSONObject("inputSchema")
        val annotations = source.optJSONObject("annotations")
        return McpToolDefinition(
            name = name,
            title = source.optString("title").ifBlank {
                annotations?.optString("title").orEmpty()
            }.take(MAX_TITLE_CHARS),
            description = source.optString("description").take(MAX_DESCRIPTION_CHARS),
            inputSchemaJson = (schema ?: JSONObject()).toString(),
            readOnlyHint = annotations?.nullableBoolean("readOnlyHint"),
            destructiveHint = annotations?.nullableBoolean("destructiveHint"),
            idempotentHint = annotations?.nullableBoolean("idempotentHint"),
            openWorldHint = annotations?.nullableBoolean("openWorldHint"),
        )
    }

    private fun request(
        method: String,
        params: JSONObject,
        protocolVersion: String,
        sessionId: String?,
        name: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): WireResponse {
        val id = requestIds.getAndIncrement()
        val decoratedParams = JSONObject(params.toString())
        if (protocolVersion == McpProtocolMode.LATEST) {
            decoratedParams.put(
                "_meta",
                (decoratedParams.optJSONObject("_meta") ?: JSONObject())
                    .put("io.modelcontextprotocol/protocolVersion", protocolVersion)
                    .put(
                        "io.modelcontextprotocol/clientInfo",
                        JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                    )
                    .put("io.modelcontextprotocol/clientCapabilities", JSONObject()),
            )
        }
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", decoratedParams)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_METHOD, method)
            .apply {
                name?.let {
                    header(
                        HEADER_NAME,
                        if (protocolVersion == McpProtocolMode.LATEST) {
                            encodeMcpHeaderValue(it)
                        } else {
                            it
                        },
                    )
                }
                sessionId?.let { header(HEADER_SESSION_ID, it) }
                additionalHeaders.forEach { (headerName, headerValue) ->
                    header(headerName, headerValue)
                }
            }
            .applyAuthorization()
            .build()
        return execute(httpRequest, expectsBody = true, expectedId = id)
    }

    private fun notification(
        method: String,
        protocolVersion: String,
        sessionId: String?,
    ) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", JSONObject())
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_METHOD, method)
            .apply { sessionId?.let { header(HEADER_SESSION_ID, it) } }
            .applyAuthorization()
            .build()
        execute(httpRequest, expectsBody = false, expectedId = null)
    }

    private fun execute(
        request: Request,
        expectsBody: Boolean,
        expectedId: Long?,
    ): WireResponse {
        var current = request
        var redirects = 0
        while (true) {
            when (val attempt = executeAttempt(current, expectsBody, expectedId)) {
                is Attempt.Result -> return attempt.wire
                is Attempt.Redirect -> {
                    redirects += 1
                    if (redirects > MAX_REDIRECTS) {
                        throw McpTransportException(
                            McpFailureKind.UNSAFE_REDIRECT,
                            "MCP 端点重定向次数超过 $MAX_REDIRECTS 次",
                        )
                    }
                    current = attempt.request
                }
            }
        }
    }

    private fun executeAttempt(
        request: Request,
        expectsBody: Boolean,
        expectedId: Long?,
    ): Attempt {
        val call = newCall(request)
        try {
            call.execute().use { received ->
                redirectRequest(request, received)?.let { return Attempt.Redirect(it) }
                val sessionId = received.header(HEADER_SESSION_ID)
                if (received.isSuccessful && !expectsBody) {
                    return Attempt.Result(WireResponse(null, sessionId))
                }
                val json = if (received.isSuccessful) {
                    received.readWireJson(expectedId)
                } else {
                    runCatching { received.readWireJson(expectedId) }.getOrNull()
                }
                if (!received.isSuccessful) {
                    throw McpHttpStatusException(
                        code = received.code,
                        safeMessage = received.safeErrorSummary(),
                        jsonRpcCode = json?.optJSONObject("error")?.optInt("code"),
                    )
                }
                val wire = json ?: throw McpTransportException(
                    McpFailureKind.MALFORMED_RESPONSE,
                    "MCP 响应正文为空",
                    compatibility = true,
                )
                if (expectedId != null && wire.opt("id")?.toString() != expectedId.toString()) {
                    throw McpTransportException(
                        McpFailureKind.MALFORMED_RESPONSE,
                        "MCP 响应 id 不匹配",
                        compatibility = true,
                    )
                }
                wire.optJSONObject("error")?.let { error ->
                    throw McpJsonRpcException(
                        code = error.optInt("code"),
                        safeMessage = error.optString("message").take(MAX_ERROR_CHARS),
                    )
                }
                return Attempt.Result(WireResponse(wire, sessionId))
            }
        } catch (failure: IOException) {
            // 读取正文阶段（含 SSE 流）的超时与断流同样需要分类，否则上层只能看到裸 IOException。
            throw (transportFailure(failure) ?: failure)
        } finally {
            clearActiveCall(call)
        }
    }

    private fun newCall(request: Request): Call {
        val call = httpClient.newCall(request)
        synchronized(lifecycleLock) {
            if (closed) throw McpTransportException(McpFailureKind.CLIENT_CLOSED, "MCP 客户端已关闭")
            check(activeCall == null) { "MCP 客户端正在执行其他请求" }
            activeCall = call
        }
        return call
    }

    private fun clearActiveCall(call: Call) {
        synchronized(lifecycleLock) {
            if (activeCall === call) activeCall = null
        }
    }

    private fun transportFailure(failure: IOException): McpTransportException? {
        // 已经是分类好的协议/状态失败，原样向上抛出，不重复包装。
        if (failure is McpTransportException || failure is McpHttpStatusException ||
            failure is McpJsonRpcException || failure is McpProtocolCompatibilityException
        ) {
            return null
        }
        // 只把用户主动 close() 视为客户端关闭。callTimeout 到期时 OkHttp 会自行 cancel 该 call，
        // 若用 call.isCanceled() 判断会把超时误报成「客户端已关闭」。
        if (closed) {
            return McpTransportException(McpFailureKind.CLIENT_CLOSED, "MCP 客户端已关闭", failure)
        }
        val kind = classifyFailure(failure).takeUnless { it == McpFailureKind.UNKNOWN }
            ?: McpFailureKind.NETWORK_UNREACHABLE
        return McpTransportException(kind, kind.userHint(), failure)
    }

    /**
     * 只跟随 307/308 的同源重定向。
     *
     * 跨源跳转会把 Authorization 与 Mcp-Session-Id 一起发到别的来源；301/302/303 会把
     * POST 语义改成 GET。两种情况都按不安全重定向拒绝，并给出可诊断的错误分类。
     */
    private fun redirectRequest(request: Request, response: Response): Request? {
        if (response.code !in FOLLOWED_REDIRECT_CODES) return null
        val location = response.header("Location")?.takeIf { it.isNotBlank() } ?: return null
        val target = request.url.resolve(location) ?: throw McpTransportException(
            McpFailureKind.UNSAFE_REDIRECT,
            "MCP 端点返回了无效的重定向地址",
        )
        if (!sameOrigin(request.url, target)) {
            throw McpTransportException(
                McpFailureKind.UNSAFE_REDIRECT,
                "MCP 端点重定向到不同来源，已停止跟随",
            )
        }
        return request.newBuilder().url(target).build()
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun releaseLegacySession(sessionId: String, protocolVersion: String) {
        val request = Request.Builder()
            .url(endpoint)
            .delete()
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_SESSION_ID, sessionId)
            .applyAuthorization()
            .build()
        runCatching {
            httpClient.newCall(request).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit

                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                    }
                }
            )
        }
    }

    private fun Request.Builder.applyAuthorization(): Request.Builder = apply {
        bearerToken?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    private fun unwrapResult(response: JSONObject): JSONObject =
        response.optJSONObject("result") ?: throw McpTransportException(
            McpFailureKind.MALFORMED_RESPONSE,
            "MCP 响应缺少 result",
            compatibility = true,
        )

    private fun Response.readWireJson(expectedId: Long?): JSONObject? =
        if (isEventStream()) readSseJson(expectedId) else {
            readBoundedBody().takeIf(String::isNotBlank)?.let { body ->
                runCatching { JSONObject(body) }.getOrElse { failure ->
                    // 少数网关会丢掉 Content-Type，但正文仍是 SSE 分帧；此时按分帧重试一次。
                    if (!body.looksLikeSse()) throw malformed(failure)
                    parseSsePayload(body, expectedId)
                        ?.let { payload ->
                            runCatching { JSONObject(payload) }.getOrElse { throw malformed(failure) }
                        }
                        ?: throw McpTransportException(
                            McpFailureKind.MALFORMED_RESPONSE,
                            "MCP 事件流没有匹配的响应事件",
                        )
                }
            }
        }

    private fun malformed(cause: Throwable): McpTransportException = McpTransportException(
        McpFailureKind.MALFORMED_RESPONSE,
        "MCP 响应不是有效 JSON",
        cause,
        compatibility = true,
    )

    private fun String.looksLikeSse(): Boolean {
        val head = trimStart()
        return head.startsWith("data:") || head.startsWith("event:") ||
            head.startsWith("id:") || head.startsWith("retry:") || head.startsWith(":")
    }

    /**
     * 把已整段读入内存的 SSE 正文按规范分帧，返回首个 id 匹配的 data 载荷。
     *
     * 注释行（keep-alive）与 event/id/retry 字段被忽略；多行 data 用 \n 连接；
     * 最后一个事件即使没有结尾空行也要派发。
     */
    private fun parseSsePayload(body: String, expectedId: Long?): String? {
        val dataLines = mutableListOf<String>()
        var sawData = false

        fun dispatch(): String? {
            if (!sawData) return null
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            sawData = false
            if (payload.isBlank()) return null
            val candidate = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val matches = expectedId == null ||
                candidate.opt("id")?.toString() == expectedId.toString()
            return payload.takeIf { matches }
        }

        for (rawLine in body.split('\n')) {
            val line = rawLine.removeSuffix("\r")
            if (line.isEmpty()) {
                dispatch()?.let { return it }
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val rawValue = if (colon < 0) "" else line.substring(colon + 1)
            val value = rawValue.removePrefix(" ")
            when (field) {
                SSE_FIELD_DATA -> {
                    dataLines += value
                    sawData = true
                }
                // event/id/retry 只影响重连语义，不影响本次请求的分帧。
                SSE_FIELD_EVENT, SSE_FIELD_ID, SSE_FIELD_RETRY -> Unit
                else -> Unit
            }
        }
        return dispatch()
    }

    private fun Response.isEventStream(): Boolean {
        val contentType = header("Content-Type").orEmpty()
        if (contentType.contains("text/event-stream", ignoreCase = true)) return true
        if (contentType.contains("application/json", ignoreCase = true)) return false
        // Content-Type 缺失时嗅探前缀，避免把 SSE 正文当成 JSON 直接判死。
        return runCatching {
            val peeked = body.source().peek()
            peeked.request(SSE_SNIFF_BYTES)
            val available = peeked.buffer.size
            if (available <= 0L) return false
            peeked.readUtf8(minOf(SSE_SNIFF_BYTES, available)).looksLikeSse()
        }.getOrDefault(false)
    }

    private fun Response.readBoundedBody(): String {
        val bytes = body.source().use { source ->
            val buffer = Buffer()
            while (buffer.size <= MAX_RESPONSE_BYTES) {
                val read = source.read(
                    buffer,
                    minOf(8_192L, MAX_RESPONSE_BYTES + 1L - buffer.size),
                )
                if (read == -1L) break
            }
            buffer.readByteArray()
        }
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw McpTransportException(McpFailureKind.MALFORMED_RESPONSE, "MCP 响应超过大小限制")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun Response.safeErrorSummary(): String =
        message.take(MAX_ERROR_CHARS).ifBlank { "HTTP $code" }

    /**
     * 流式 SSE 解析：按规范累积 data 字段、忽略注释行（keep-alive）、
     * 在空行处派发事件，并整体受 callTimeout 与本地截止时间双重约束。
     */
    private fun Response.readSseJson(expectedId: Long?): JSONObject? {
        val source = body.source()
        val dataLines = mutableListOf<String>()
        var sawData = false
        var totalBytes = 0L
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(callTimeoutMs)

        fun dispatch(): JSONObject? {
            if (!sawData) return null
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            sawData = false
            if (payload.isBlank()) return null
            val candidate = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            return candidate.takeIf {
                expectedId == null || it.opt("id")?.toString() == expectedId.toString()
            }
        }

        while (true) {
            if (System.nanoTime() - deadlineNanos > 0L) {
                throw McpTransportException(McpFailureKind.TIMEOUT, "MCP 事件流超时")
            }
            val line = source.readBoundedSseLine(MAX_RESPONSE_BYTES - totalBytes) ?: break
            totalBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            if (totalBytes > MAX_RESPONSE_BYTES) {
                throw McpTransportException(
                    McpFailureKind.MALFORMED_RESPONSE,
                    "MCP 响应超过大小限制",
                )
            }
            if (line.isEmpty()) {
                dispatch()?.let { return it }
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val rawValue = if (colon < 0) "" else line.substring(colon + 1)
            val value = rawValue.removePrefix(" ")
            when (field) {
                SSE_FIELD_DATA -> {
                    dataLines += value
                    sawData = true
                }
                SSE_FIELD_EVENT, SSE_FIELD_ID, SSE_FIELD_RETRY -> Unit
                else -> Unit
            }
        }
        return dispatch()
    }

    private fun BufferedSource.readBoundedSseLine(remainingBytes: Long): String? {
        if (remainingBytes <= 0L) {
            throw McpTransportException(McpFailureKind.MALFORMED_RESPONSE, "MCP 响应超过大小限制")
        }
        val newline = indexOf('\n'.code.toByte(), 0L, remainingBytes + 1L)
        if (newline >= 0L) {
            val line = readUtf8(newline)
            skip(1L)
            return line.removeSuffix("\r")
        }
        if (request(remainingBytes + 1L)) {
            throw McpTransportException(McpFailureKind.MALFORMED_RESPONSE, "MCP 响应超过大小限制")
        }
        if (exhausted()) return null
        val tail = readUtf8().removeSuffix("\r")
        return tail.ifEmpty { null }
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CLIENT_VERSION = "1"
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val HEADER_METHOD = "Mcp-Method"
        const val HEADER_NAME = "Mcp-Name"
        const val HEADER_SESSION_ID = "Mcp-Session-Id"
        const val DEFAULT_CALL_TIMEOUT_MS = 120_000L
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val MAX_ERROR_CHARS = MCP_MAX_ERROR_CHARS
        const val MAX_TITLE_CHARS = 160
        const val MAX_DESCRIPTION_CHARS = 2_000
        const val MAX_DISCOVERED_TOOLS = 128
        const val MAX_LIST_PAGES = 8
        const val MAX_REDIRECTS = 3
        const val SSE_SNIFF_BYTES = 8L
        const val SSE_FIELD_DATA = "data"
        const val SSE_FIELD_EVENT = "event"
        const val SSE_FIELD_ID = "id"
        const val SSE_FIELD_RETRY = "retry"
        val FOLLOWED_REDIRECT_CODES = setOf(307, 308)
        val MODERN_JSON_RPC_ERRORS = setOf(-32601, -32020, -32021, -32022)
    }
}

private val MCP_VERSION_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")

/** AUTO 模式下服务端选定的版本是否属于 legacy 家族。 */
private fun isLegacyFamily(hint: String): Boolean =
    hint != McpProtocolMode.LATEST && hint != McpProtocolMode.AUTO

/**
 * 服务端在 initialize 中返回的版本号：
 * - 空值或形状非法 → 回落到默认 legacy 版本；
 * - 任何成形日期版本（含我们未收录的旧版本）→ 原样采用，避免直接判死。
 */
private fun negotiateLegacyVersion(serverVersion: String): String {
    val candidate = serverVersion.trim()
    if (candidate.isEmpty() || candidate == McpProtocolMode.LATEST) return McpProtocolMode.LEGACY
    return if (MCP_VERSION_SHAPE.matches(candidate)) candidate else McpProtocolMode.LEGACY
}

internal class McpHttpStatusException(
    val code: Int,
    safeMessage: String,
    val jsonRpcCode: Int? = null,
    val kind: McpFailureKind = classifyHttpStatus(code, jsonRpcCode),
) : IOException(
    "MCP 请求失败：HTTP $code ${safeMessage.take(MCP_MAX_ERROR_CHARS)}（${kind.userHint()}）"
)

internal class McpJsonRpcException(
    val code: Int,
    safeMessage: String,
) : IOException("MCP 协议错误：code=$code ${safeMessage.take(MCP_MAX_ERROR_CHARS)}") {
    val kind: McpFailureKind get() = McpFailureKind.PROTOCOL_INCOMPATIBLE
}

internal class McpProtocolCompatibilityException(cause: Throwable) : IOException(cause.message, cause)

internal fun validateMcpEndpoint(raw: String): okhttp3.HttpUrl {
    return raw.trim().toHttpUrlOrNull() ?: error("MCP 地址无效")
}
