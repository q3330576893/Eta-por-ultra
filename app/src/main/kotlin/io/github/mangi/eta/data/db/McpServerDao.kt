package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY sort_order ASC")
    fun serversFlow(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY sort_order ASC")
    suspend fun servers(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun serverById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(server: McpServerEntity)

    @Update
    suspend fun update(server: McpServerEntity): Int

    @Query(
        """UPDATE mcp_servers SET tools_json = :toolsJson,
            enabled_tool_names_json = :enabledToolNamesJson,
            last_refreshed_at = :lastRefreshedAt,
            last_protocol_version = :lastProtocolVersion,
            tools_expire_at = :toolsExpireAt WHERE id = :id"""
    )
    suspend fun updateCatalog(
        id: String,
        toolsJson: String,
        enabledToolNamesJson: String,
        lastRefreshedAt: Long?,
        lastProtocolVersion: String?,
        toolsExpireAt: Long?,
    ): Int

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String): Int
}
