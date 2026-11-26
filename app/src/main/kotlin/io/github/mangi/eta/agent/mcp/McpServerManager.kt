package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import java.net.URI

/**
 * 一次发现对「已保存目录」的净变化。
 *
 * 界面据此如实告诉用户「这次握手少了什么」，而不是让勾选默默消失：
 * 服务端删掉工具是正常运维行为，但用户需要知道自己的勾选被谁动了。
 */
internal data class McpDiscoveryChange(
    /** 本次新出现的工具名。 */
    val added: List<String> = emptyList(),
    /** 服务端不再提供、因此从目录里消失的工具名。 */
    val removed: List<String> = emptyList(),
    /** 消失且原本处于启用状态的工具名：这些就是用户「需要重新勾选」的部分。 */
    val disabledSelections: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/**
 * 「原本有工具、这次一个都没有」——需要用户确认后才允许落库。
 *
 * 直接写库会同时清空 `tools` 与 `enabledToolNames`：用户只是点了一次刷新，
 * 却要重新逐个勾选几十个工具。临时返回空列表（网关重启、限流、发布窗口）
 * 完全可能发生，所以把它当成需要确认的操作，而不是一次普通刷新。
 */
internal class McpEmptyCatalogException(
    val previousToolCount: Int,
    /**
     * 已经握手成功、内容为空的结果。
     *
     * 确认时直接落库这一份，不再连第二次：否则第二次握手可能返回另一份目录，
     * 用户确认的「空」和他最终拿到的状态就对不上了。
     */
    val pending: McpServerSetting,
) : IllegalStateException(
    "服务端本次没有返回任何工具（原有 $previousToolCount 个）。" +
        "工具列表与勾选都已保留，确认要按空列表覆盖吗？",
)

internal object McpServerManager {
    /**
     * 显式刷新单个服务器：调用方（设置页 / 快照加载）负责异常边界。
     *
     * 本方法不吞异常、不伪造 TTL：`tools/list` 没给 `ttlMs` 时 `toolsExpireAt` 为 null，
     * 由 [McpSnapshotPolicy.needsRefresh] 决定下次是否重新握手。
     *
     * @param allowEmptyCatalog 服务端返回零工具、而本地已有工具时，默认抛出
     *   [McpEmptyCatalogException] 保护既有目录与勾选；只有用户明确确认后才允许置 true。
     */
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
        allowEmptyCatalog: Boolean = false,
    ): McpServerSetting {
        val discovery = McpHttpClient(server, bearerToken).use { client -> client.discoverTools() }
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        val refreshed = server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
        // 空目录不自动覆盖非空目录：见 McpEmptyCatalogException 的说明。
        if (!allowEmptyCatalog && refreshed.tools.isEmpty() && server.tools.isNotEmpty()) {
            throw McpEmptyCatalogException(
                previousToolCount = server.tools.size,
                pending = refreshed,
            )
        }
        return refreshed
    }

    /**
     * 重新握手并落库。
     *
     * 失败时不写库、不降级：调用方拿到异常后可以继续使用已持久化的目录，
     * 这样「刷新失败」不会顺带把工具与勾选清空。
     */
    suspend fun refresh(serverId: String, allowEmptyCatalog: Boolean = false): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(
            server = server,
            bearerToken = McpServerRepository.bearerToken(serverId),
            allowEmptyCatalog = allowEmptyCatalog,
        )
        McpServerRepository.update(refreshed)
        return refreshed
    }

    /**
     * 用户确认「按空列表覆盖」后落库。
     *
     * 直接写 [McpEmptyCatalogException.pending]：那一份就是用户看到并确认的结果，
     * 不再重新握手，避免第二次返回的内容与确认内容不一致。
     */
    suspend fun acceptEmptyCatalog(rejection: McpEmptyCatalogException): McpServerSetting {
        McpServerRepository.update(rejection.pending)
        return rejection.pending
    }

    /** 对比刷新前后的目录，供界面说明「新增 / 移除 / 被取消的勾选」。 */
    fun discoveryChange(
        before: McpServerSetting,
        after: McpServerSetting,
    ): McpDiscoveryChange {
        val beforeNames = before.tools.mapTo(mutableSetOf()) { it.name }
        val afterNames = after.tools.mapTo(mutableSetOf()) { it.name }
        val removed = beforeNames - afterNames
        return McpDiscoveryChange(
            added = (afterNames - beforeNames).sorted(),
            removed = removed.sorted(),
            disabledSelections = removed.intersect(before.enabledToolNames).sorted(),
        )
    }
}

/**
 * 协议版本的可读描述。
 *
 * AUTO 模式会静默回退到 legacy：不把「当前到底用哪一版、是不是降级了」说出来，
 * 用户排查「为什么有些工具没出现」时完全没有抓手。
 */
internal object McpProtocolStatus {

    /** 协议家族：现代无状态 / 旧版会话握手 / 还没握手。 */
    fun familyLabel(version: String?): String = when {
        version == null -> "尚未握手"
        version == McpProtocolMode.LATEST -> "现代协议"
        else -> "旧版协议"
    }

    /** AUTO 模式下是否已经降级：用户手动指定 LEGACY 不算降级。 */
    fun isLegacyFallback(protocolMode: String, lastProtocolVersion: String?): Boolean =
        lastProtocolVersion != null &&
            lastProtocolVersion != McpProtocolMode.LATEST &&
            protocolMode != McpProtocolMode.LATEST &&
            protocolMode != McpProtocolMode.LEGACY

    /**
     * 详情页直接展示的一行文字：家族 + 实际版本 + 这个版本是怎么来的。
     */
    fun summary(protocolMode: String, lastProtocolVersion: String?): String {
        val version = lastProtocolVersion
            ?: return "尚未握手（保存后自动协商，无需手动选版本）"
        val origin = when {
            protocolMode == McpProtocolMode.LATEST || protocolMode == McpProtocolMode.LEGACY -> "手动指定"
            isLegacyFallback(protocolMode, version) -> "自动协商已回退"
            else -> "自动协商"
        }
        return "${familyLabel(version)} · $version（$origin）"
    }
}

/**
 * 「这个地址压根不是 MCP 端点」的识别与解释。
 *
 * QQ / Telegram / Discord 这类模板的端点必须由用户自建网关提供。用户很可能把
 * 官方 Bot API 地址直接粘进来，此时服务端会返回 404、405 或一段非 JSON-RPC 的正文，
 * 而通用错误只会说「协议不兼容」——等于把排查成本全丢给用户。这里按主机名认出
 * 那几家平台，把错误改写成「你需要网关」，不改变任何判定逻辑与安全边界。
 */
internal object McpGatewayGuide {

    private data class KnownHost(
        val suffix: String,
        val service: String,
        val api: String,
    )

    /**
     * 已知「只提供 REST/Bot API」的平台域名。
     *
     * 顺序有意义：先匹配更具体的主机名。`qq.com` 同时是 `weixin.qq.com` 的后缀，
     * 放在前面会把公众号域名误标成 QQ，因此微信/企业微信必须排在 QQ 之前。
     */
    private val KNOWN_HOSTS = listOf(
        KnownHost("qyapi.weixin.qq.com", "企业微信", "企业应用 API"),
        KnownHost("weixin.qq.com", "微信", "公众号 API"),
        KnownHost("telegram.org", "Telegram", "Bot API（https://api.telegram.org/bot<token>/…）"),
        KnownHost("dingtalk.com", "钉钉", "企业内部应用 OpenAPI"),
        KnownHost("feishu.cn", "飞书", "开放平台 API"),
        KnownHost("larksuite.com", "飞书", "开放平台 API"),
        KnownHost("discord.com", "Discord", "Bot API"),
        KnownHost("discordapp.com", "Discord", "Bot API"),
        KnownHost("slack.com", "Slack", "Web API"),
        KnownHost("googleapis.com", "Google", "Drive/Sheets REST API"),
        KnownHost("graph.microsoft.com", "Microsoft", "Graph API"),
        KnownHost("qq.com", "QQ 开放平台", "机器人 OpenAPI"),
    )

    /** 只有这些失败才值得追加网关解释：认证失败、限流、超时都不是「地址填错」。 */
    private val GATEWAY_EXPLAINED_KINDS = setOf(
        McpFailureKind.PROTOCOL_INCOMPATIBLE,
        McpFailureKind.MALFORMED_RESPONSE,
        McpFailureKind.ENDPOINT_NOT_FOUND,
        McpFailureKind.BAD_REQUEST,
        McpFailureKind.UNSAFE_REDIRECT,
    )

    /** 主机名命中已知平台时返回一句「这是 X 的 API，不是 MCP 端点」。 */
    fun hintForEndpoint(url: String): String? {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase() ?: return null
        if (host.isBlank()) return null
        val known = KNOWN_HOSTS.firstOrNull { known ->
            host == known.suffix || host.endsWith(".${known.suffix}")
        } ?: return null
        return "该地址指向 ${known.service} 的 ${known.api}，本身不是 MCP 端点：" +
            "需要先自建 MCP 网关把 ${known.service} 转成 MCP，再把网关地址填到这里。"
    }

    /**
     * 把失败改写成可操作的一句话：协议层失败且主机名可识别时追加网关说明。
     */
    fun describeFailure(url: String, failure: Throwable): String {
        val base = failure.message?.takeIf { it.isNotBlank() }
            ?: classifyFailure(failure).userHint()
        if (classifyFailure(failure) !in GATEWAY_EXPLAINED_KINDS) return base
        val hint = hintForEndpoint(url) ?: return base
        return "$base。$hint"
    }
}
