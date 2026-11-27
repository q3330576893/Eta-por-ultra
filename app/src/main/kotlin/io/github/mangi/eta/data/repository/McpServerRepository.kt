package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.withoutDecodedQueryParameter
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 发现结果只携带目录元数据，不携带可写回的连接设置或凭据。 */
internal data class McpDiscoveredCatalog(
    val tools: List<McpToolDefinition>,
    val refreshedAt: Long?,
    val protocolVersion: String?,
    val expiresAt: Long?,
)

/** 等待网络或空目录确认期间的连接身份；异常中只保留令牌摘要，不保留令牌。 */
internal class McpDiscoveryIdentity(
    val serverId: String,
    private val url: String,
    private val protocolMode: String,
    private val authorizationType: String,
    private val tokenDigest: List<Byte>,
    private val revision: Long,
) {
    fun matches(server: McpServerSetting, bearerToken: String?, currentRevision: Long): Boolean =
        server.withoutQueryCredential().url == url && server.protocolMode == protocolMode &&
            server.authorizationType == authorizationType && currentRevision == revision &&
            tokenDigest == digest(bearerToken)

    companion object {
        fun capture(server: McpServerSetting, bearerToken: String?, revision: Long) =
            McpDiscoveryIdentity(
                server.id, server.withoutQueryCredential().url, server.protocolMode, server.authorizationType,
                digest(bearerToken), revision,
            )

        private fun digest(token: String?): List<Byte> = MessageDigest.getInstance("SHA-256")
            .digest(token.orEmpty().trim().toByteArray(Charsets.UTF_8)).toList()
    }
}

internal class McpDiscoverySource(
    val server: McpServerSetting,
    val bearerToken: String?,
    val identity: McpDiscoveryIdentity,
)

internal class McpDiscoveryConfigurationChangedException : IllegalStateException(
    "MCP 连接配置或凭据已变更，已丢弃旧的工具发现结果，请重新刷新",
)

internal object McpServerRepository {
    @Volatile
    private lateinit var applicationContext: Context

    // 不覆盖网络等待。凭据保存在 Room 之外，短锁让凭据与数据库提交拥有同一修改边界。
    private val mutationMutex = Mutex()
    private val connectionRevisions = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = dao().serversFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun servers(): List<McpServerSetting> = dao().servers().map { it.toDomain() }

    suspend fun enabledServers(): List<McpServerSetting> = servers().filter(McpServerSetting::enabled)

    suspend fun serverById(id: String): McpServerSetting? = dao().serverById(id)?.toDomain()

    /** 同一修改边界内读取连接设置与令牌，避免握手混用两个配置版本。 */
    suspend fun discoverySource(serverId: String): McpDiscoverySource = mutationMutex.withLock {
        val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val token = secrets().bearerToken(serverId)
        McpDiscoverySource(server, token, discoveryIdentity(server, token))
    }

    fun discoveryIdentity(server: McpServerSetting, bearerToken: String?): McpDiscoveryIdentity =
        McpDiscoveryIdentity.capture(server, bearerToken, connectionRevisions[server.id] ?: 0L)

    /** 只更新目录列；最新设置与勾选在事务内读取，绝不走会修改凭据的 update()。 */
    suspend fun updateDiscoveredCatalog(
        identity: McpDiscoveryIdentity,
        catalog: McpDiscoveredCatalog,
    ): McpServerSetting = mutationMutex.withLock {
        val database = EtaDatabase.get(context())
        database.withTransaction {
            val serverDao = database.mcpServerDao()
            val current = requireNotNull(serverDao.serverById(identity.serverId)?.toDomain()) {
                "MCP 服务器不存在"
            }
            if (!identity.matches(
                    current,
                    secrets().bearerToken(current.id),
                    connectionRevisions[current.id] ?: 0L,
                )
            ) {
                throw McpDiscoveryConfigurationChangedException()
            }
            val availableNames = catalog.tools.mapTo(mutableSetOf()) { it.name }
            val updated = current.copy(
                tools = catalog.tools,
                enabledToolNames = current.enabledToolNames.intersect(availableNames),
                lastRefreshedAt = catalog.refreshedAt,
                lastProtocolVersion = catalog.protocolVersion,
                toolsExpireAt = catalog.expiresAt,
            )
            val entity = updated.toEntity()
            require(
                serverDao.updateCatalog(
                    entity.id, entity.toolsJson, entity.enabledToolNamesJson,
                    entity.lastRefreshedAt, entity.lastProtocolVersion, entity.toolsExpireAt,
                ) == 1
            ) { "MCP 服务器不存在" }
            updated
        }
    }

    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting =
        mutationMutex.withLock {
            val added = server.withoutQueryCredential().copy(
                id = server.id.ifBlank { UUID.randomUUID().toString() },
                sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
            )
            val secretStore = secrets()
            updateSecret(secretStore, added, bearerToken)
            try {
                dao().insert(added.toEntity())
                advanceConnectionRevision(added.id)
            } catch (failure: Throwable) {
                runCatching { secretStore.clear(added.id) }
                throw failure
            }
            added
        }

    suspend fun update(server: McpServerSetting, bearerToken: String? = null) =
        mutationMutex.withLock { updateUnlocked(server, bearerToken) }

    private suspend fun updateUnlocked(server: McpServerSetting, bearerToken: String? = null) {
        val previous = requireNotNull(serverById(server.id)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(server.id)
        val normalized = server.withoutQueryCredential()
        val changesSecret = bearerToken != null || !McpAuthorizationType.usesStoredSecret(normalized.authorizationType)
        val updated = if (bearerToken != null) {
            normalized.copy(lastProtocolVersion = null, toolsExpireAt = null)
        } else {
            normalized
        }
        if (changesSecret) updateSecret(secretStore, updated, bearerToken.orEmpty())
        try {
            require(dao().update(updated.toEntity()) == 1) { "MCP 服务器不存在" }
            if (bearerToken != null || previous.url != updated.url ||
                previous.protocolMode != updated.protocolMode ||
                previous.authorizationType != updated.authorizationType
            ) {
                advanceConnectionRevision(server.id)
            }
        } catch (failure: Throwable) {
            if (changesSecret) runCatching {
                updateSecret(secretStore, previous, previousToken.orEmpty())
            }
            throw failure
        }
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean) =
        mutationMutex.withLock {
            val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
            val names = server.enabledToolNames.toMutableSet()
            if (enabled) names += toolName else names -= toolName
            updateUnlocked(server.copy(enabledToolNames = names))
        }

    /** 批量启用/停用工具：一次事务写入，避免 46 个工具逐个 update。 */
    suspend fun setToolsEnabled(serverId: String, toolNames: Collection<String>, enabled: Boolean) {
        if (toolNames.isEmpty()) return
        mutationMutex.withLock {
            val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
            val names = server.enabledToolNames.toMutableSet()
            if (enabled) names += toolNames else names -= toolNames
            if (names == server.enabledToolNames) return@withLock
            updateUnlocked(server.copy(enabledToolNames = names))
        }
    }

    suspend fun delete(serverId: String) = mutationMutex.withLock {
        val previous = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(serverId)
        secretStore.clear(serverId)
        try {
            require(dao().delete(serverId) == 1) { "MCP 服务器不存在" }
            advanceConnectionRevision(serverId)
        } catch (failure: Throwable) {
            runCatching { updateSecret(secretStore, previous, previousToken.orEmpty()) }
            throw failure
        }
    }

    fun bearerToken(serverId: String): String? = secrets().bearerToken(serverId)

    private fun advanceConnectionRevision(serverId: String) {
        connectionRevisions[serverId] = (connectionRevisions[serverId] ?: 0L) + 1L
    }

    private fun updateSecret(
        secretStore: McpSecretStore,
        server: McpServerSetting,
        bearerToken: String,
    ) {
        if (McpAuthorizationType.usesStoredSecret(server.authorizationType)) {
            secretStore.setBearerToken(server.id, bearerToken)
        } else {
            secretStore.clear(server.id)
        }
    }

    private fun dao() = EtaDatabase.get(context()).mcpServerDao()

    private fun secrets() = McpSecretStore(context())

    private fun context(): Context {
        check(::applicationContext.isInitialized) {
            "McpServerRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }
}

/** 已保存端点不含查询密钥；运行时由 McpHttpClient 从加密 secret 注入。 */
private fun McpServerSetting.withoutQueryCredential(): McpServerSetting {
    val parameter = McpAuthorizationType.queryParameterName(authorizationType) ?: return this
    val endpoint = url.trim().toHttpUrlOrNull() ?: error("MCP 地址无效")
    return copy(url = endpoint.withoutDecodedQueryParameter(parameter).toString())
}
