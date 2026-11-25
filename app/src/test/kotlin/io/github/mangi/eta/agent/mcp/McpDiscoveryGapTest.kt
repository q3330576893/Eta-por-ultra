package io.github.mangi.eta.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config

/**
 * 「模板到可用」落差回归。
 *
 * 覆盖四个真实卡点：
 * 1. 发现失败时连接到底有没有被保存；
 * 2. 空端点模板填了官方 API 地址时错误是否指向「需要网关」；
 * 3. 工具数从 N 掉到 0 时是否要用户确认；
 * 4. AUTO 回退 legacy 后用户能否知道当前协议版本。
 *
 * 全部使用本地 HttpServer 夹具，不访问任何真实服务，也不读写凭据。
 */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class McpDiscoveryGapTest {
    private val servers = mutableListOf<HttpServer>()

    @Before
    fun setUp() {
        // refresh()/acceptEmptyCatalog() 会走仓储：初始化一个空库，用例之间互不残留。
        val context = org.robolectric.RuntimeEnvironment.getApplication()
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
        servers.forEach { runCatching { it.stop(0) } }
        servers.clear()
        runBlocking {
            val dao = EtaDatabase.get(org.robolectric.RuntimeEnvironment.getApplication()).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
        EtaDatabase.closeForTests()
    }

    // ---------- 1. 发现失败：既不保存，也不伪造空目录 ----------

    @Test
    fun discoveryFailureThrowsInsteadOfReturningAnEmptySavedServer() {
        // 404：端点不存在。discover 必须抛异常，让调用方不落库。
        val server = localServer { exchange -> exchange.respond(404, "not found") }
        val draft = McpServerSetting(id = "", name = "Unreachable", url = server.url())

        val failure = runCatching { McpServerManager.discover(draft, bearerToken = null) }
            .exceptionOrNull()

        assertNotNull("发现失败必须抛出，不能返回一个空目录的「已保存」对象", failure)
        assertEquals(McpFailureKind.ENDPOINT_NOT_FOUND, classifyFailure(failure!!))
    }

    @Test
    fun refreshOfAMissingServerFailsBeforeAnyNetworkWork() {
        // 没有落库的服务器：refresh 必须在本地就失败，绝不能产生「已保存但空目录」的假象。
        val failure = runCatching {
            kotlinx.coroutines.runBlocking { McpServerManager.refresh("does-not-exist") }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            "应先在本地判定服务器不存在：${failure!!.message}",
            failure.message.orEmpty().contains("MCP 服务器不存在"),
        )
    }

    // ---------- 3. 工具数骤降：默认不覆盖，确认后才允许清空 ----------

    @Test
    fun emptyCatalogFromServerIsRejectedAndExistingToolsSurvive() {
        // 服务端返回 200 + 空 tools：这是「临时返回空列表」的典型形态。
        val server = localServer { exchange ->
            exchange.respondJsonRpc(exchange.readBody(), listToolsResult())
        }
        val configured = McpServerSetting(
            id = "empty-catalog",
            name = "Empty",
            url = server.url(),
            tools = listOf(
                McpToolDefinition("search", inputSchemaJson = OBJECT_SCHEMA),
                McpToolDefinition("write", inputSchemaJson = OBJECT_SCHEMA),
            ),
            enabledToolNames = setOf("search", "write"),
        )

        val failure = runCatching { McpServerManager.discover(configured, bearerToken = null) }
            .exceptionOrNull()

        assertNotNull("工具数从 N 掉到 0 必须要求确认", failure)
        val rejected = failure as? McpEmptyCatalogException
        assertNotNull("必须抛出可确认的空目录异常", rejected)
        assertEquals(2, rejected!!.previousToolCount)
        assertTrue(
            "错误必须说明工具与勾选都还在：${rejected.message}",
            rejected.message.orEmpty().contains("保留"),
        )
        // 确认时落库的就是这一份：否则第二次握手返回别的目录，用户确认的内容会失效。
        assertEquals(emptyList<String>(), rejected.pending.tools.map { it.name })
        assertEquals(emptySet<String>(), rejected.pending.enabledToolNames)
        assertEquals(McpProtocolMode.LATEST, rejected.pending.lastProtocolVersion)
        assertNotNull("被拒绝的这一次握手本身是成功的，时间要记下来", rejected.pending.lastRefreshedAt)
    }

    @Test
    fun confirmedEmptyCatalogOverwritesAndClearsSelections() {
        val server = localServer { exchange ->
            exchange.respondJsonRpc(exchange.readBody(), listToolsResult())
        }
        val configured = McpServerSetting(
            id = "confirmed-empty",
            name = "Confirmed",
            url = server.url(),
            tools = listOf(McpToolDefinition("search", inputSchemaJson = OBJECT_SCHEMA)),
            enabledToolNames = setOf("search"),
        )

        val refreshed = McpServerManager.discover(
            configured,
            bearerToken = null,
            allowEmptyCatalog = true,
        )

        assertEquals(emptyList<String>(), refreshed.tools.map { it.name })
        assertEquals(emptySet<String>(), refreshed.enabledToolNames)
    }

    @Test
    fun acceptingTheRejectionPersistsExactlyTheConfirmedEmptyCatalog() {
        // 端到端：拒绝 → 用户确认 → 落库内容就是刚才那一份，不重新握手。
        val server = localServer { exchange ->
            exchange.respondJsonRpc(exchange.readBody(), listToolsResult())
        }
        val configured = McpServerSetting(
            id = "persist-empty",
            name = "Persist",
            url = server.url(),
            tools = listOf(McpToolDefinition("search", inputSchemaJson = OBJECT_SCHEMA)),
            enabledToolNames = setOf("search"),
        )
        runBlocking { McpServerRepository.add(configured, "") }

        val rejection = runCatching { McpServerManager.discover(configured, bearerToken = null) }
            .exceptionOrNull() as? McpEmptyCatalogException
        assertNotNull("空目录必须先被拒绝一次", rejection)

        // 拒绝阶段不能有任何写库：勾选必须还在。
        assertEquals(
            setOf("search"),
            runBlocking { requireNotNull(McpServerRepository.serverById("persist-empty")) }.enabledToolNames,
        )

        val accepted = runBlocking { McpServerManager.acceptEmptyCatalog(requireNotNull(rejection)) }

        assertEquals(emptyList<String>(), accepted.tools.map { it.name })
        val stored = runBlocking { requireNotNull(McpServerRepository.serverById("persist-empty")) }
        assertEquals(emptyList<String>(), stored.tools.map { it.name })
        assertEquals(emptySet<String>(), stored.enabledToolNames)
        assertNotNull("确认后要留下握手时间，界面才不会显示成「从未握手」", stored.lastRefreshedAt)
    }

    @Test
    fun runSnapshotKeepsCachedToolsWhenServerTemporarilyReturnsNothing() {
        // 后台快照路径同样不能被一次空返回清空：它没有 UI 可以确认，
        // 只能沿用已持久化目录，等用户在详情页决定。
        val server = localServer { exchange ->
            exchange.respondJsonRpc(exchange.readBody(), listToolsResult())
        }
        runBlocking {
            McpServerRepository.add(
                McpServerSetting(
                    id = "snapshot-empty",
                    name = "Snapshot",
                    url = server.url(),
                    protocolMode = McpProtocolMode.LATEST,
                    tools = listOf(McpToolDefinition("cached", inputSchemaJson = OBJECT_SCHEMA)),
                    enabledToolNames = setOf("cached"),
                    lastProtocolVersion = McpProtocolMode.LATEST,
                    toolsExpireAt = System.currentTimeMillis() - 1L,
                ),
                "",
            )
        }

        val snapshot = runBlocking { McpRunSnapshot.load() }

        assertEquals(listOf("cached"), snapshot.tools.map { it.definition.name })
        val stored = runBlocking { requireNotNull(McpServerRepository.serverById("snapshot-empty")) }
        assertEquals(listOf("cached"), stored.tools.map { it.name })
        assertEquals(setOf("cached"), stored.enabledToolNames)
    }

    @Test
    fun firstTimeConnectionWithNoToolsIsStillSaved() {
        // 首次接入、服务端本来就没有工具：没有「既有目录」可保护，不能拦。
        val server = localServer { exchange ->
            exchange.respondJsonRpc(exchange.readBody(), listToolsResult())
        }
        val draft = McpServerSetting(id = "", name = "Fresh", url = server.url())

        val discovered = McpServerManager.discover(draft, bearerToken = null)

        assertEquals(emptyList<String>(), discovered.tools.map { it.name })
        assertNotNull("握手成功就要记录时间，界面据此区分「没握手」与「没工具」", discovered.lastRefreshedAt)
        assertEquals(McpProtocolMode.LATEST, discovered.lastProtocolVersion)
    }

    @Test
    fun partialToolRemovalKeepsSelectionsForSurvivingTools() {
        // 服务端只删掉一个工具：不能整表拒绝，但要能算出被取消的勾选。
        val server = localServer { exchange ->
            exchange.respondJsonRpc(exchange.readBody(), listToolsResult("search"))
        }
        val configured = McpServerSetting(
            id = "partial",
            name = "Partial",
            url = server.url(),
            tools = listOf(
                McpToolDefinition("search", inputSchemaJson = OBJECT_SCHEMA),
                McpToolDefinition("legacy_tool", inputSchemaJson = OBJECT_SCHEMA),
            ),
            enabledToolNames = setOf("search", "legacy_tool"),
        )

        val refreshed = McpServerManager.discover(configured, bearerToken = null)
        val change = McpServerManager.discoveryChange(configured, refreshed)

        assertEquals(listOf("search"), refreshed.tools.map { it.name })
        assertEquals(setOf("search"), refreshed.enabledToolNames)
        assertEquals(listOf("legacy_tool"), change.removed)
        assertEquals(listOf("legacy_tool"), change.disabledSelections)
        assertTrue(change.added.isEmpty())
        assertFalse(change.isEmpty)
    }

    @Test
    fun addedToolsAreReportedSoUsersKnowToEnableThem() {
        val server = localServer { exchange ->
            exchange.respondJsonRpc(exchange.readBody(), listToolsResult("search", "brand_new"))
        }
        val configured = McpServerSetting(
            id = "added",
            name = "Added",
            url = server.url(),
            tools = listOf(McpToolDefinition("search", inputSchemaJson = OBJECT_SCHEMA)),
            enabledToolNames = setOf("search"),
        )

        val refreshed = McpServerManager.discover(configured, bearerToken = null)
        val change = McpServerManager.discoveryChange(configured, refreshed)

        assertEquals(listOf("brand_new"), change.added)
        assertTrue(change.removed.isEmpty())
        // 新增工具默认不启用：启用意味着授权，不能靠刷新自动扩大权限。
        assertEquals(setOf("search"), refreshed.enabledToolNames)
    }

    // ---------- 2. 空端点模板：错误要指向「需要网关」 ----------

    @Test
    fun officialBotApiHostIsRecognizedAsNeedingAGateway() {
        val telegram = requireNotNull(McpGatewayGuide.hintForEndpoint("https://api.telegram.org/bot123/"))
        assertTrue(telegram, telegram.contains("网关"))
        assertTrue(telegram, telegram.contains("Telegram"))

        val wecom = requireNotNull(McpGatewayGuide.hintForEndpoint("https://qyapi.weixin.qq.com/cgi-bin/gettoken"))
        assertTrue("企业微信域名不能被 QQ 规则抢走：$wecom", wecom.contains("企业微信"))

        val wechat = requireNotNull(McpGatewayGuide.hintForEndpoint("https://api.weixin.qq.com/cgi-bin/token"))
        assertTrue(wechat, wechat.contains("微信"))

        val qq = requireNotNull(McpGatewayGuide.hintForEndpoint("https://api.sgroup.qq.com/"))
        assertTrue(qq, qq.contains("QQ"))
    }

    @Test
    fun unknownHostGetsNoGatewayBlame() {
        // 自建网关常用自有域名：不能硬说它需要网关，否则会误导用户改错的地址。
        assertNull(McpGatewayGuide.hintForEndpoint("https://mcp.mycompany.internal/mcp"))
        assertNull(McpGatewayGuide.hintForEndpoint(""))
        assertNull(McpGatewayGuide.hintForEndpoint("not a url"))
    }

    @Test
    fun protocolFailureOnOfficialApiExplainsGatewayButAuthFailureDoesNot() {
        val protocolFailure = McpTransportException(
            McpFailureKind.PROTOCOL_INCOMPATIBLE,
            "MCP 协议不兼容，服务端拒绝了当前协议版本或方法",
        )
        val described = McpGatewayGuide.describeFailure("https://api.telegram.org/bot1/", protocolFailure)
        assertTrue("协议失败应追加网关说明：$described", described.contains("网关"))
        assertTrue("原始原因不能被吞掉：$described", described.contains("协议不兼容"))

        // 认证失败说明地址是通的，再提「需要网关」只会把人带偏。
        val authFailure = McpTransportException(
            McpFailureKind.AUTHENTICATION,
            "MCP 服务器拒绝认证（HTTP 401/403），请检查 Bearer Token",
        )
        val authDescribed = McpGatewayGuide.describeFailure("https://api.telegram.org/bot1/", authFailure)
        assertFalse("认证失败不该被改写成网关问题：$authDescribed", authDescribed.contains("网关"))
        assertEquals(authFailure.message, authDescribed)
    }

    @Test
    fun gatewayHintNeverAsksForASecret() {
        val hint = requireNotNull(McpGatewayGuide.hintForEndpoint("https://api.telegram.org/bot1/"))
        // 只描述「要自建网关」，不索要令牌，也不出现任何凭据形态的示例。
        assertTrue(hint, hint.contains("网关"))
        assertFalse(hint, hint.contains("Authorization", ignoreCase = true))
        assertFalse(hint, hint.contains("Bearer", ignoreCase = true))
        assertNull(hint, Regex("[A-Za-z0-9_-]{20,}").find(hint))
    }

    // ---------- 4. 协议模式：用户能看出当前跑在哪一版 ----------

    @Test
    fun protocolSummaryNamesFamilyAndOrigin() {
        assertEquals(
            "尚未握手（保存后自动协商，无需手动选版本）",
            McpProtocolStatus.summary(McpProtocolMode.AUTO, null),
        )
        assertEquals(
            "现代协议 · ${McpProtocolMode.LATEST}（自动协商）",
            McpProtocolStatus.summary(McpProtocolMode.AUTO, McpProtocolMode.LATEST),
        )
        assertEquals(
            "旧版协议 · ${McpProtocolMode.LEGACY}（自动协商已回退）",
            McpProtocolStatus.summary(McpProtocolMode.AUTO, McpProtocolMode.LEGACY),
        )
        assertEquals(
            "旧版协议 · ${McpProtocolMode.LEGACY}（手动指定）",
            McpProtocolStatus.summary(McpProtocolMode.LEGACY, McpProtocolMode.LEGACY),
        )
        assertEquals(
            "现代协议 · ${McpProtocolMode.LATEST}（手动指定）",
            McpProtocolStatus.summary(McpProtocolMode.LATEST, McpProtocolMode.LATEST),
        )
    }

    @Test
    fun unknownServerChosenLegacyVersionStillReadsAsLegacy() {
        // 服务端可能选一个我们未收录的旧版本，界面同样要标成旧版并提示已回退。
        assertEquals("旧版协议", McpProtocolStatus.familyLabel("2024-11-05"))
        assertTrue(McpProtocolStatus.isLegacyFallback(McpProtocolMode.AUTO, "2024-11-05"))
        val summary = McpProtocolStatus.summary(McpProtocolMode.AUTO, "2024-11-05")
        assertTrue(summary, summary.contains("2024-11-05"))
        assertTrue(summary, summary.contains("回退"))
    }

    @Test
    fun manualLegacySelectionIsNotCalledAFallback() {
        assertFalse(McpProtocolStatus.isLegacyFallback(McpProtocolMode.LEGACY, McpProtocolMode.LEGACY))
        assertFalse(McpProtocolStatus.isLegacyFallback(McpProtocolMode.LATEST, McpProtocolMode.LATEST))
        assertFalse(McpProtocolStatus.isLegacyFallback(McpProtocolMode.AUTO, null))
    }

    @Test
    fun autoFallbackToLegacyRecordsTheVersionThatActuallyRan() {
        // 端到端确认第 4 点：AUTO 真的回退后，落库的 lastProtocolVersion 是 legacy。
        val requestIndex = java.util.concurrent.atomic.AtomicInteger()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = exchange.readBody()
            val id = JSONObject(body).opt("id") ?: 1
            when (requestIndex.getAndIncrement()) {
                0 -> exchange.respond(400, "unsupported modern version")
                1 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"old","version":"1"}}}""",
                    mapOf("Mcp-Session-Id" to "session-1"),
                )
                2 -> exchange.respond(202, "")
                else -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                )
            }
        }
        val configured = McpServerSetting(id = "auto-fallback", name = "Auto", url = server.url())

        val discovered = McpServerManager.discover(configured, bearerToken = null)

        assertEquals(McpProtocolMode.LEGACY, discovered.lastProtocolVersion)
        assertEquals(listOf("legacy"), discovered.tools.map { it.name })
        assertTrue(
            "回退后界面必须能标出这是回退结果",
            McpProtocolStatus.isLegacyFallback(discovered.protocolMode, discovered.lastProtocolVersion),
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

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange -> handler(exchange) }
            executor = Executors.newCachedThreadPool()
            start()
            servers += this
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.readBody(): String =
        requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)

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

    private fun HttpExchange.respond(
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        extraHeaders.forEach { (name, value) -> responseHeaders.set(name, value) }
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val OBJECT_SCHEMA = """{"type":"object"}"""
    }
}
