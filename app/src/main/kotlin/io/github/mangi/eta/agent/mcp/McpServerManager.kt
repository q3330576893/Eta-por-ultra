package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

internal object McpServerManager {
    /**
     * 显式刷新单个服务器：调用方（设置页 / 快照加载）负责异常边界。
     *
     * 本方法不吞异常、不伪造 TTL：`tools/list` 没给 `ttlMs` 时 `toolsExpireAt` 为 null，
     * 由 [McpSnapshotPolicy.needsRefresh] 决定下次是否重新握手。
     */
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpServerSetting = McpHttpClient(server, bearerToken).use { client ->
        val discovery = client.discoverTools()
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(server, McpServerRepository.bearerToken(serverId))
        McpServerRepository.update(refreshed)
        return refreshed
    }
}
