package io.github.mangi.eta.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaDatabaseMigrationTest {
    @Test
    fun migration6ToCurrentPreservesDataAndMovesCompleteConversationContext() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-${UUID.randomUUID()}.db"
        createVersion6Database(context, databaseName)

        val migration16To17WithMcpData = Migration(16, 17) { database ->
            EtaDatabase.MIGRATION_16_17.migrate(database)
            database.execSQL(
                "INSERT INTO mcp_servers (id, name, url, enabled, protocol_mode, " +
                    "authorization_type, tools_json, enabled_tool_names_json, created_at, " +
                    "sort_order, last_refreshed_at, last_protocol_version) VALUES " +
                    "('mcp-1', 'MCP', 'http://127.0.0.1:8787/mcp', 1, 'auto', 'none', " +
                    "'[]', '[]', 1, 0, 2, '2026-07-28')"
            )
        }

        val database = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName)
            .addMigrations(
                EtaDatabase.MIGRATION_6_7,
                EtaDatabase.MIGRATION_7_8,
                EtaDatabase.MIGRATION_8_9,
                EtaDatabase.MIGRATION_9_10,
                EtaDatabase.MIGRATION_10_11,
                EtaDatabase.MIGRATION_11_12,
                EtaDatabase.MIGRATION_12_13,
                EtaDatabase.MIGRATION_13_14,
                EtaDatabase.MIGRATION_14_15,
                EtaDatabase.MIGRATION_15_16,
                migration16To17WithMcpData,
                EtaDatabase.MIGRATION_17_18,
                EtaDatabase.MIGRATION_18_19,
                EtaDatabase.MIGRATION_19_20,
                EtaDatabase.MIGRATION_20_21,
                EtaDatabase.MIGRATION_21_22,
                EtaDatabase.MIGRATION_22_23,
                EtaDatabase.MIGRATION_23_24,
                EtaDatabase.MIGRATION_24_25,
                *MIGRATIONS_25_TO_CURRENT,
            )
            .build()
        try {
            val result = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().runtimeResults().single()
            }
            assertEquals("", result.contextSnapshotJson)
            assertEquals("chat", result.operation)
            val archive = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().archivedRuns().single().run
            }
            val conversations = runBlocking(Dispatchers.IO) {
                database.conversationDao().conversations()
            }
            val retainedCheckpoint = runBlocking(Dispatchers.IO) {
                database.conversationDao().contextCheckpoint("conv-1")
            }
            val oversizedCheckpoint = runBlocking(Dispatchers.IO) {
                database.conversationDao().contextCheckpoint("conv-oversized")
            }
            val clearedLegacyHistory = database.openHelper.readableDatabase
                .query("SELECT history_json FROM conversations WHERE id = 'conv-oversized'")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
            val provider = runBlocking(Dispatchers.IO) {
                database.providerDao().providerById("provider-1")!!.toDomain()
            }
            val migratedMessage = runBlocking(Dispatchers.IO) {
                database.conversationDao().messages().single()
            }
            val inFlightRuns = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().inFlightRuns()
            }
            val mcpServers = runBlocking(Dispatchers.IO) {
                database.mcpServerDao().servers()
            }

            assertEquals("保留的结果", result.content)
            assertEquals("[]", result.transcriptJson)
            assertEquals("保留的归档", archive.content)
            assertEquals("[]", archive.transcriptJson)
            assertEquals("[]", archive.userImagePreviewsJson)
            assertEquals(
                setOf("conv-1", "conv-enabled", "conv-custom-empty", "conv-oversized"),
                conversations.mapTo(mutableSetOf()) { it.id },
            )
            assertEquals(
                "[{\"role\":\"user\",\"content\":\"保留上下文\"}]",
                retainedCheckpoint?.historyJson,
            )
            assertEquals("[\"${"x".repeat(140_000)}\"]", oversizedCheckpoint?.historyJson)
            assertEquals(oversizedCheckpoint?.historyJson, oversizedCheckpoint?.journalJson)
            assertEquals("[]", clearedLegacyHistory)
            assertEquals("[]", conversations.first { it.id == "conv-1" }.appliedRuntimeRunIdsJson)
            assertEquals("", conversations.first { it.id == "conv-1" }.roleplayJson)
            assertEquals("", conversations.first { it.id == "conv-1" }.revisionsJson)
            assertEquals(emptyList<CharacterEntity>(), runBlocking(Dispatchers.IO) { database.characterDao().characters() })
            assertEquals("off", conversations.first { it.id == "conv-1" }.reasoningEffort)
            assertEquals(null, conversations.first { it.id == "conv-1" }.modelId)
            assertEquals("default", conversations.first { it.id == "conv-enabled" }.reasoningEffort)
            assertEquals(null, runBlocking(Dispatchers.IO) { database.conversationDao().state() })
            assertEquals(listOf("built-in", "manual"), provider.models.map { it.modelId })
            assertEquals(false, provider.hostedWebSearchEnabled)
            assertEquals(ProviderAuthModes.API_KEY, provider.authMode)
            assertEquals("fixture-existing-api-key", provider.apiKey)
            assertEquals(false, migratedMessage.isEdited)
            assertEquals(emptyList<RuntimeInFlightRunWithEvents>(), inFlightRuns)
            assertEquals(listOf("mcp-1"), mcpServers.map { it.id })
            assertEquals(null, mcpServers.single().toolsExpireAt)
            assertEquals(null, provider.models.first().contextWindowOverride)
            assertTrue(provider.models.all { it.requestHook == ModelRequestHook() })
            assertEquals(null, provider.models.first().reasoningOverride)
            assertEquals(null, provider.models.first().reasoningCapabilitiesOverride)
            assertEquals(
                listOf(ModelSource.CATALOG, ModelSource.MANUAL),
                provider.models.map { it.source },
            )
            assertEquals(ETA_DB_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
            assertTokenStatisticsRemoved(database.openHelper.readableDatabase)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun upstreamVersion22KeepsBoundModelAndGainsTaskAndUsageTables() {
        verifyLegacyUpgrade(vivoBranch = false)
    }

    @Test
    fun vivoVersion24KeepsTasksAndUsageWhileAddingModelBinding() {
        verifyLegacyUpgrade(vivoBranch = true)
    }

    private fun verifyLegacyUpgrade(vivoBranch: Boolean) {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "legacy-${UUID.randomUUID()}.db"
        createVersion6Database(context, databaseName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(if (vivoBranch) 24 else 22) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Expected existing version 6 fixture")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(6, oldVersion)
                    listOf(EtaDatabase.MIGRATION_6_7, EtaDatabase.MIGRATION_7_8, EtaDatabase.MIGRATION_8_9,
                        EtaDatabase.MIGRATION_9_10, EtaDatabase.MIGRATION_10_11, EtaDatabase.MIGRATION_11_12,
                        EtaDatabase.MIGRATION_12_13, EtaDatabase.MIGRATION_13_14, EtaDatabase.MIGRATION_14_15,
                        EtaDatabase.MIGRATION_15_16, EtaDatabase.MIGRATION_16_17, EtaDatabase.MIGRATION_17_18,
                        EtaDatabase.MIGRATION_18_19, EtaDatabase.MIGRATION_19_20, EtaDatabase.MIGRATION_20_21)
                        .forEach { it.migrate(db) }
                    if (vivoBranch) {
                        EtaDatabase.MIGRATION_22_23.migrate(db)
                        EtaDatabase.MIGRATION_23_24.migrate(db)
                        db.execSQL("UPDATE conversations SET last_model_usage_json = ? WHERE id = 'conv-1'",
                            arrayOf("{\"outputTokens\":12,\"requestDurationMs\":1000}"))
                        db.execSQL("INSERT INTO agent_tasks (id, name, prompt, triggerJson, enabled, cooldownSeconds, " +
                            "maxRuns, runCount, lastStatus, createdAt, updatedAt) VALUES " +
                            "('task-1', 'Retained task', 'Summarize', '{}', 0, 900, 10, 2, 'completed', 1, 2)")
                    } else {
                        EtaDatabase.MIGRATION_21_22.migrate(db)
                        db.execSQL("UPDATE conversations SET model_id = 'bound-model' WHERE id = 'conv-1'")
                    }
                }
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
        val database = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName)
            .addMigrations(
                EtaDatabase.MIGRATION_22_23,
                EtaDatabase.MIGRATION_23_24,
                EtaDatabase.MIGRATION_24_25,
                *MIGRATIONS_25_TO_CURRENT,
            )
            .build()
        try {
            runBlocking(Dispatchers.IO) {
                val conversation = database.conversationDao().conversationMetadataRows().first { it.id == "conv-1" }
                assertEquals("保留的对话", conversation.title)
                assertEquals(if (vivoBranch) null else "bound-model", conversation.modelId)
                assertEquals(if (vivoBranch) "{\"outputTokens\":12,\"requestDurationMs\":1000}" else "", conversation.lastModelUsageJson)
                assertEquals(1, database.conversationDao().messages().size)
                val tasks = database.agentTaskDao().tasks()
                assertEquals(if (vivoBranch) listOf("task-1") else emptyList<String>(), tasks.map { it.id })
                if (vivoBranch) assertEquals(2, tasks.single().runCount)
            }
            assertEquals(ETA_DB_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
            assertTokenStatisticsRemoved(database.openHelper.readableDatabase)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 30 → 31：上下文构成与耗时明细五列。旧库升级后必须都能写空值，不能因为缺列整表读失败。
     */
    @Test
    fun migration30To31AddsUsageDetailColumns() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-30-31-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(30) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE conversation_messages (id TEXT NOT NULL PRIMARY KEY, " +
                                "conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, " +
                                "type TEXT NOT NULL, content TEXT NOT NULL)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        try {
            FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
                val database = helper.writableDatabase
                EtaDatabase.MIGRATION_30_31.migrate(database)
                EtaDatabase.MIGRATION_31_32.migrate(database)
                val columns = database.query("PRAGMA table_info(conversation_messages)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
                }
                val expected = setOf(
                    "system_prompt_tokens",
                    "tool_definition_tokens",
                    "conversation_tokens",
                    "first_token_ms",
                    "tool_duration_ms",
                    "request_count",
                )
                assertEquals(expected, columns.intersect(expected))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * 33 → 34：新增本地技能反馈表；旧 `skill_registry` 数据必须原样保留，
     * 新表建好后可正常插入、按技能查询与统计平均分。
     */
    @Test
    fun migration33To34CreatesSkillFeedbackAndPreservesRegistry() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-33-34-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(33) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE skill_registry (skill_id TEXT NOT NULL, enabled INTEGER NOT NULL, " +
                                "source TEXT NOT NULL, install_state TEXT NOT NULL, PRIMARY KEY(skill_id))"
                        )
                        db.execSQL(
                            "INSERT INTO skill_registry (skill_id, enabled, source, install_state) VALUES " +
                                "('skill-a', 1, 'builtin', 'installed'), " +
                                "('skill-b', 0, 'user', 'installed')"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        try {
            FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
                val db = helper.writableDatabase
                EtaDatabase.MIGRATION_33_34.migrate(db)

                // 旧技能数据逐字段保留，建表幂等且不碰 skill_registry。
                val registry = db.query("SELECT skill_id, enabled, source, install_state FROM skill_registry ORDER BY skill_id")
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(listOf(cursor.getString(0), cursor.getInt(1), cursor.getString(2), cursor.getString(3)))
                            }
                        }
                    }
                assertEquals(2, registry.size)
                assertEquals(listOf("skill-a", 1, "builtin", "installed"), registry[0])
                assertEquals(listOf("skill-b", 0, "user", "installed"), registry[1])

                // 新表确实建立，且字段/索引结构正确、可直接写入与聚合。
                val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'skill_feedback'")
                    .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                assertEquals(listOf("skill_feedback"), tables)
                db.execSQL("INSERT INTO skill_feedback (skill_id, rating, comment, created_at) VALUES ('skill-a', 5, '好用', 1)")
                db.execSQL("INSERT INTO skill_feedback (skill_id, rating, comment, created_at) VALUES ('skill-a', 3, '一般', 2)")
                val average = db.query("SELECT AVG(rating), COUNT(*) FROM skill_feedback WHERE skill_id = 'skill-a'")
                    .use { cursor -> cursor.moveToFirst(); cursor.getDouble(0) to cursor.getInt(1) }
                assertEquals(4.0, average.first, 0.0001)
                assertEquals(2, average.second)

                // 幂等：重复迁移不应报错或破坏数据。
                EtaDatabase.MIGRATION_33_34.migrate(db)
                assertEquals(2, db.query("SELECT COUNT(*) FROM skill_feedback").use { c -> c.moveToFirst(); c.getInt(0) })
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration34To35DefaultsHooksToDisabledAndPreservesModelsAndBoundConversation() {
        val context = RuntimeEnvironment.getApplication() as Context
        // 每个 Robolectric 用例已有独立数据目录；短名称避免 Windows 原生 SQLite 路径上限。
        val databaseName = "m35.db"
        createVersion6Database(context, databaseName)
        val version34 = SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(34) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Expected existing historical fixture")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(6, oldVersion)
                    val migrationsTo25 = listOf(
                        EtaDatabase.MIGRATION_6_7, EtaDatabase.MIGRATION_7_8, EtaDatabase.MIGRATION_8_9,
                        EtaDatabase.MIGRATION_9_10, EtaDatabase.MIGRATION_10_11, EtaDatabase.MIGRATION_11_12,
                        EtaDatabase.MIGRATION_12_13, EtaDatabase.MIGRATION_13_14, EtaDatabase.MIGRATION_14_15,
                        EtaDatabase.MIGRATION_15_16, EtaDatabase.MIGRATION_16_17, EtaDatabase.MIGRATION_17_18,
                        EtaDatabase.MIGRATION_18_19, EtaDatabase.MIGRATION_19_20, EtaDatabase.MIGRATION_20_21,
                        EtaDatabase.MIGRATION_21_22, EtaDatabase.MIGRATION_22_23, EtaDatabase.MIGRATION_23_24,
                        EtaDatabase.MIGRATION_24_25,
                    )
                    (migrationsTo25 + MIGRATIONS_25_TO_CURRENT.filter { it.endVersion <= 34 })
                        .forEach { it.migrate(db) }
                }
            }).build()
        try {
            FrameworkSQLiteOpenHelperFactory().create(version34).use { helper ->
                val db = helper.writableDatabase
                val columns = db.query("PRAGMA table_info(provider_models)").use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                }
                assertFalse(columns.contains("request_hook_json"))
                db.execSQL("UPDATE conversations SET model_id = 'manual-id' WHERE id = 'conv-1'")
            }
            val database = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName)
                .addMigrations(EtaDatabase.MIGRATION_34_35)
                .build()
            try {
                val rawHooks = database.openHelper.readableDatabase
                    .query("SELECT id, request_hook_json FROM provider_models ORDER BY sort_order").use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                    }
                assertEquals(listOf("built-in-id" to "{}", "manual-id" to "{}"), rawHooks)
                runBlocking(Dispatchers.IO) {
                    val provider = requireNotNull(database.providerDao().providerById("provider-1")).toDomain()
                    assertEquals(listOf("built-in", "manual"), provider.models.map { it.modelId })
                    assertTrue(provider.models.all { it.requestHook == ModelRequestHook() && !it.requestHook.enabled })
                    val conversation = database.conversationDao().conversationEntities().first { it.id == "conv-1" }
                    assertEquals("保留的对话", conversation.title)
                    assertEquals("manual-id", conversation.modelId)
                    assertEquals("旧消息", database.conversationDao().messages().single().content)
                }
                assertEquals(ETA_DB_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun createVersion6Database(
        context: Context,
        databaseName: String,
    ) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_6_SCHEMA.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-enabled', '启用推理', 1, '[]', 4, 4)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-1', '保留的对话', 0, " +
                                "'[{\"role\":\"user\",\"content\":\"保留上下文\"}]', 1, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-bug-empty', '新对话', 0, '[]', 2, 2)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-custom-empty', '用户命名', 0, '[]', 3, 3)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf<Any>(
                                "conv-oversized",
                                "超长上下文",
                                0,
                                "[\"${"x".repeat(140_000)}\"]",
                                5,
                                5,
                            ),
                        )
                        db.execSQL(
                            "INSERT INTO conversation_state (id, selected_conversation_id) " +
                                "VALUES ('main', 'conv-bug-empty')"
                        )
                        db.execSQL(
                            "INSERT INTO conversation_messages " +
                                "(id, conversation_id, sort_index, type, content, images_json, " +
                                "image_count, tools_json) VALUES " +
                                "('message-1', 'conv-1', 0, 'user', '旧消息', '[]', 0, '[]')"
                        )
                        db.execSQL(
                            "INSERT INTO model_providers " +
                                "(id, type, name, base_url, api_key, is_enabled, is_built_in, sort_order, " +
                                "system_prompt, custom_headers_json, custom_body_json, created_at, endpoint_mode, anthropic_version) " +
                                "VALUES ('provider-1', 'openai_compatible', 'Provider', 'https://example.com/v1', 'fixture-existing-api-key', 1, 0, 0, " +
                                "NULL, '[]', '[]', 1, 'chat_completions', '2023-06-01')"
                        )
                        db.execSQL(providerModelInsert("built-in-id", "built-in", 1, 0))
                        db.execSQL(providerModelInsert("manual-id", "manual", 0, 1))
                        db.execSQL(providerModelInsert("blank-id", "", 0, 2))
                        db.execSQL(
                            "INSERT INTO runtime_results " +
                                "(run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('run-1', 'handoff-1', 'test', '{}', 0, 1, '保留的结果', NULL, '', 1)"
                        )
                        db.execSQL(
                            "INSERT INTO runtime_archive_runs " +
                                "(archive_run_id, run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('archive-1', 'run-1', 'handoff-1', 'test', '{}', 0, 1, " +
                                "'保留的归档', NULL, '', 1)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }
            )
            .build()
        FrameworkSQLiteOpenHelperFactory()
            .create(configuration)
            .also { helper ->
                helper.writableDatabase
                helper.close()
            }
    }

    private companion object {
        /** 25 及之后的完整升级链：从任意旧版本都能一路升到当前结构。 */
        val MIGRATIONS_25_TO_CURRENT = arrayOf(
            EtaDatabase.MIGRATION_25_26,
            EtaDatabase.MIGRATION_26_27,
            EtaDatabase.MIGRATION_27_28,
            EtaDatabase.MIGRATION_28_29,
            EtaDatabase.MIGRATION_29_30,
            EtaDatabase.MIGRATION_30_31,
            EtaDatabase.MIGRATION_31_32,
            EtaDatabase.MIGRATION_32_33,
            EtaDatabase.MIGRATION_33_34,
            EtaDatabase.MIGRATION_34_35,
        )

        /** 32 → 33 之后统计表必须消失，会话与消息数据不受影响。 */
        fun assertTokenStatisticsRemoved(database: SupportSQLiteDatabase) {
            val tables = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'token_usage_records'",
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue("token_usage_records 应已删除，实际 $tables", tables.isEmpty())
        }

        fun providerModelInsert(id: String, modelId: String, builtIn: Int, sortOrder: Int): String =
            "INSERT INTO provider_models " +
                "(id, provider_id, model_id, display_name, is_enabled, is_built_in, sort_order, owned_by, " +
                "context_window, input_modalities_json, output_modalities_json, attachment, tool_call, reasoning, " +
                "structured_output, supports_temperature, custom_headers_json, custom_body_json, created_at) " +
                "VALUES ('$id', 'provider-1', '$modelId', 'Model', 1, $builtIn, $sortOrder, NULL, NULL, " +
                "'[\"text\"]', '[\"text\"]', NULL, NULL, NULL, NULL, NULL, '[]', '[]', 1)"

        val VERSION_6_SCHEMA = listOf(
            "CREATE TABLE conversations (id TEXT NOT NULL, title TEXT NOT NULL, thinking_enabled INTEGER NOT NULL, history_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE conversation_messages (id TEXT NOT NULL, conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, type TEXT NOT NULL, content TEXT NOT NULL, images_json TEXT NOT NULL, render_markdown INTEGER, context_tokens INTEGER, input_tokens INTEGER, output_tokens INTEGER, reasoning_tokens INTEGER, cached_tokens INTEGER, elapsed_seconds INTEGER, tool_name TEXT, tool_status TEXT, arguments_summary TEXT, result_summary TEXT, image_count INTEGER NOT NULL, tools_json TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_conversation_messages_conversation_id ON conversation_messages(conversation_id)",
            "CREATE UNIQUE INDEX index_conversation_messages_conversation_id_sort_index ON conversation_messages(conversation_id, sort_index)",
            "CREATE TABLE conversation_state (id TEXT NOT NULL, selected_conversation_id TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE model_providers (id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, base_url TEXT NOT NULL, api_key TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, system_prompt TEXT, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, endpoint_mode TEXT NOT NULL, anthropic_version TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE provider_models (id TEXT NOT NULL, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, display_name TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, owned_by TEXT, context_window INTEGER, input_modalities_json TEXT NOT NULL, output_modalities_json TEXT NOT NULL, attachment INTEGER, tool_call INTEGER, reasoning INTEGER, structured_output INTEGER, supports_temperature INTEGER, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(provider_id) REFERENCES model_providers(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_provider_models_provider_id ON provider_models(provider_id)",
            "CREATE INDEX index_provider_models_provider_id_sort_order ON provider_models(provider_id, sort_order)",
            "CREATE TABLE runtime_results (run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(run_id))",
            "CREATE TABLE runtime_archive_runs (archive_run_id TEXT NOT NULL, run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(archive_run_id))",
            "CREATE TABLE runtime_archive_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, archive_run_id TEXT NOT NULL, sort_index INTEGER NOT NULL, event_json TEXT NOT NULL, FOREIGN KEY(archive_run_id) REFERENCES runtime_archive_runs(archive_run_id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_runtime_archive_events_archive_run_id ON runtime_archive_events(archive_run_id)",
            "CREATE UNIQUE INDEX index_runtime_archive_events_archive_run_id_sort_index ON runtime_archive_events(archive_run_id, sort_index)",
            "CREATE TABLE skill_registry (skill_id TEXT NOT NULL, enabled INTEGER NOT NULL, source TEXT NOT NULL, install_state TEXT NOT NULL, PRIMARY KEY(skill_id))",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'bd87dd0053b011246cba304c35316f07')",
        )
    }
}
