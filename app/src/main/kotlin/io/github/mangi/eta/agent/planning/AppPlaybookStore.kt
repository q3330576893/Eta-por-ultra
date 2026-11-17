package io.github.mangi.eta.agent.planning

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class AppPlaybook(
    val packageName: String,
    val id: String,
    val title: String,
    val steps: List<String>,
    val completionCheck: String,
    val enabled: Boolean,
    val verified: Boolean,
    val verifiedAt: Long?,
    val revision: Long,
    val updatedAt: Long,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val sourceRunId: String? = null,
    val lastUsedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val useCount: Long = 0L,
    val errorSummary: String? = null,
) {
    /** Compatibility alias for callers that use the storage-oriented field name. */
    val lastErrorSummary: String? get() = errorSummary

    fun toJson(): JSONObject = JSONObject()
        .put("package_name", packageName)
        .put("id", id)
        .put("title", title)
        .put("steps", JSONArray(steps))
        .put("completion_check", completionCheck)
        .put("enabled", enabled)
        .put("verified", verified)
        .put("verified_at", verifiedAt ?: JSONObject.NULL)
        .put("verification_source", if (verified) "user_confirmed" else "unverified")
        .put("revision", revision)
        .put("updated_at", updatedAt)
        .put("app_version_name", appVersionName ?: JSONObject.NULL)
        .put("app_version_code", appVersionCode ?: JSONObject.NULL)
        .put("source_run_id", sourceRunId ?: JSONObject.NULL)
        .put("last_used_at", lastUsedAt ?: JSONObject.NULL)
        .put("last_success_at", lastSuccessAt ?: JSONObject.NULL)
        .put("last_failure_at", lastFailureAt ?: JSONObject.NULL)
        .put("use_count", useCount)
        .put("error_summary", lastErrorSummary ?: JSONObject.NULL)
}

/** Package-bound user playbooks are reference data, never instructions or executable scripts. */
internal class AppPlaybookStore(root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val files = TaskWorkspaceFiles(root)

    fun list(packageName: String? = null, includeDisabled: Boolean = true): List<AppPlaybook> = files.locked {
        if (packageName != null) validatePackage(packageName)
        readLocked().filter { (packageName == null || it.packageName == packageName) && (includeDisabled || it.enabled) }
            .sortedWith(compareBy<AppPlaybook> { it.packageName }.thenBy { it.title }.thenBy { it.id })
    }

    fun get(packageName: String, id: String): AppPlaybook? = files.locked {
        validateKey(packageName, id)
        readLocked().firstOrNull { it.packageName == packageName && it.id == id }
    }

    /** All model-authored writes are unverified, even when overwriting a user-verified card. */
    fun saveFromTool(
        packageName: String,
        id: String,
        title: String,
        steps: List<String>,
        completionCheck: String,
        expectedRevision: Long,
        enabled: Boolean = true,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
        sourceRunId: String? = null,
    ): AppPlaybook = save(
        packageName,
        id,
        title,
        steps,
        completionCheck,
        expectedRevision,
        enabled,
        verified = false,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        sourceRunId = sourceRunId,
        preserveMetadataWhenVersionOmitted = true,
    )

    /** This API is called only from an explicit user action in the management screen. */
    fun saveFromUser(
        packageName: String,
        id: String,
        title: String,
        steps: List<String>,
        completionCheck: String,
        expectedRevision: Long,
        enabled: Boolean,
        verified: Boolean,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
        sourceRunId: String? = null,
    ): AppPlaybook = save(
        packageName,
        id,
        title,
        steps,
        completionCheck,
        expectedRevision,
        enabled,
        verified,
        appVersionName,
        appVersionCode,
        sourceRunId,
        preserveMetadataWhenVersionOmitted = false,
    )

    private fun save(
        packageName: String,
        id: String,
        title: String,
        steps: List<String>,
        completionCheck: String,
        expectedRevision: Long,
        enabled: Boolean,
        verified: Boolean,
        appVersionName: String?,
        appVersionCode: Long?,
        sourceRunId: String?,
        preserveMetadataWhenVersionOmitted: Boolean,
    ): AppPlaybook = files.locked {
        validateDefinition(packageName, id, title, steps, completionCheck)
        val all = readLocked()
        val old = all.firstOrNull { it.packageName == packageName && it.id == id }
        if (expectedRevision != (old?.revision ?: 0L)) {
            throw TaskWorkspaceException("PLAYBOOK_REVISION_CONFLICT", "经验卡已变化，请读取最新 revision 后再保存")
        }
        if (old == null && all.size >= MAX_CARDS) {
            throw TaskWorkspaceException("PLAYBOOK_LIMIT", "最多保存 $MAX_CARDS 张应用经验卡")
        }
        val normalizedVersionName = optionalVersionName(appVersionName)
        val normalizedVersionCode = optionalVersionCode(appVersionCode)
        val normalizedSourceRunId = optionalRunId(sourceRunId)
        val now = clock()
        val card = AppPlaybook(
            packageName = packageName,
            id = id,
            title = title.trim(),
            steps = steps.map(String::trim),
            completionCheck = completionCheck.trim(),
            enabled = enabled,
            verified = verified,
            verifiedAt = now.takeIf { verified },
            revision = (old?.revision ?: 0L) + 1L,
            updatedAt = now,
            appVersionName = if (preserveMetadataWhenVersionOmitted && normalizedVersionName == null) old?.appVersionName else normalizedVersionName,
            appVersionCode = if (preserveMetadataWhenVersionOmitted && normalizedVersionCode == null) old?.appVersionCode else normalizedVersionCode,
            sourceRunId = if (preserveMetadataWhenVersionOmitted && normalizedSourceRunId == null) old?.sourceRunId else normalizedSourceRunId,
            lastUsedAt = old?.lastUsedAt,
            lastSuccessAt = old?.lastSuccessAt,
            lastFailureAt = old?.lastFailureAt,
            useCount = old?.useCount ?: 0L,
            errorSummary = old?.errorSummary,
        )
        writeLocked(all.filterNot { it.packageName == packageName && it.id == id } + card)
        card
    }

    /** Record usage for an explicit user-selected reference from a real run. */
    fun recordUse(
        reference: AppPlaybookReference,
        runId: String,
        outcome: AppPlaybookUseOutcome = AppPlaybookUseOutcome.UNKNOWN,
        verificationReceipt: AppPlaybookVerificationReceipt? = null,
        errorSummary: String? = null,
    ): List<AppPlaybook> = files.locked {
        if (!reference.selectedByUser || reference.cardIds.isEmpty() ||
            reference.reference.isBlank() || reference.reference.length > MAX_REFERENCE_CHARS || reference.selectedAt < 0L) {
            throw TaskWorkspaceException("PLAYBOOK_REFERENCE_INVALID", "经验卡引用必须来自用户显式选择")
        }
        validateRunId(runId)
        if (reference.cardIds.size > MAX_REFERENCE_CARDS || reference.cardIds.distinct().size != reference.cardIds.size) {
            throw TaskWorkspaceException("PLAYBOOK_REFERENCE_INVALID", "一次引用最多选择 $MAX_REFERENCE_CARDS 张不同经验卡")
        }
        validatePackage(reference.packageName)
        val all = readLocked()
        val selected = reference.cardIds.map { id ->
            validateId(id)
            all.firstOrNull { it.packageName == reference.packageName && it.id == id }
                ?: throw TaskWorkspaceException("PLAYBOOK_NOT_FOUND", "经验卡不存在或已删除")
        }
        val now = clock()
        val effectiveOutcome = when {
            outcome == AppPlaybookUseOutcome.SUCCESS && verificationReceipt?.isExplicitFor(runId) == true -> AppPlaybookUseOutcome.SUCCESS
            outcome == AppPlaybookUseOutcome.FAILURE -> AppPlaybookUseOutcome.FAILURE
            else -> AppPlaybookUseOutcome.UNKNOWN
        }
        val boundedError = errorSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { boundedErrorSummary(it) }
        val selectedKeys = selected.map { it.packageName to it.id }.toSet()
        val updated = all.map { card ->
            if ((card.packageName to card.id) !in selectedKeys) card
            else card.copy(
                revision = card.revision + 1L,
                updatedAt = now,
                lastUsedAt = now,
                useCount = if (card.useCount == Long.MAX_VALUE) Long.MAX_VALUE else card.useCount + 1L,
                lastSuccessAt = if (effectiveOutcome == AppPlaybookUseOutcome.SUCCESS) now else card.lastSuccessAt,
                lastFailureAt = if (effectiveOutcome == AppPlaybookUseOutcome.FAILURE) now else card.lastFailureAt,
                errorSummary = when (effectiveOutcome) {
                    AppPlaybookUseOutcome.FAILURE -> boundedError
                    AppPlaybookUseOutcome.SUCCESS -> null
                    AppPlaybookUseOutcome.UNKNOWN -> card.lastErrorSummary
                },
            )
        }
        writeLocked(updated)
        updated.filter { (it.packageName to it.id) in selectedKeys }
    }

    /** Package/id overload for callers that already validated a user selection. */
    fun recordUse(
        packageName: String,
        ids: List<String>,
        runId: String,
        outcome: AppPlaybookUseOutcome = AppPlaybookUseOutcome.UNKNOWN,
        verificationReceipt: AppPlaybookVerificationReceipt? = null,
        errorSummary: String? = null,
    ): List<AppPlaybook> = recordUse(
        AppPlaybookReference(
            packageName = packageName,
            cardIds = ids.toList(),
            reference = "",
            appVersionName = null,
            appVersionCode = null,
            selectedByUser = true,
            selectedAt = 0L,
        ),
        runId,
        outcome,
        verificationReceipt,
        errorSummary,
    )

    fun delete(packageName: String, id: String, expectedRevision: Long): Boolean = files.locked {
        validateKey(packageName, id)
        val all = readLocked()
        val current = all.firstOrNull { it.packageName == packageName && it.id == id }
            ?: throw TaskWorkspaceException("PLAYBOOK_NOT_FOUND", "经验卡不存在或已删除")
        if (current.revision != expectedRevision) {
            throw TaskWorkspaceException("PLAYBOOK_REVISION_CONFLICT", "经验卡已变化，请读取最新 revision 后再删除")
        }
        writeLocked(all.filterNot { it.packageName == packageName && it.id == id })
        true
    }

    /** Import is additive and atomic: a single malformed/conflicting entry commits nothing. */
    fun importJson(raw: String): List<AppPlaybook> = importBundle(raw, maxCards = 20, maxBytes = MAX_IMPORT_BYTES)

    /** Explicit SAF import can restore a full user export; tool imports keep the smaller per-call budget. */
    fun importFromUser(raw: String): List<AppPlaybook> = importBundle(raw, maxCards = MAX_CARDS, maxBytes = MAX_USER_IMPORT_BYTES)

    private fun importBundle(raw: String, maxCards: Int, maxBytes: Int): List<AppPlaybook> = files.locked {
        if (raw.toByteArray(Charsets.UTF_8).size > maxBytes) {
            throw TaskWorkspaceException("PLAYBOOK_IMPORT_TOO_LARGE", "导入文件超过当前入口的大小上限")
        }
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw TaskWorkspaceException("PLAYBOOK_INVALID_IMPORT", "导入内容需要 JSON 对象")
        }
        if (json.optInt("schema_version") != 1 || json.keys().asSequence().any { it !in setOf("schema_version", "playbooks") }) {
            throw TaskWorkspaceException("PLAYBOOK_INVALID_IMPORT", "导入格式或版本无效")
        }
        val rows = json.optJSONArray("playbooks")
            ?: throw TaskWorkspaceException("PLAYBOOK_INVALID_IMPORT", "导入内容缺少 playbooks 数组")
        if (rows.length() !in 1..maxCards) {
            throw TaskWorkspaceException("PLAYBOOK_INVALID_IMPORT", "每次导入需要 1–$maxCards 张经验卡")
        }
        val all = readLocked()
        val pending = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            if (row.keys().asSequence().any { it !in IMPORT_FIELDS }) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_IMPORT", "经验卡存在未知字段")
            }
            val packageName = text(row, "package_name")
            val id = text(row, "id")
            val title = text(row, "title")
            val steps = parseSteps(row.getJSONArray("steps"))
            val check = text(row, "completion_check")
            validateDefinition(packageName, id, title, steps, check)
            val now = clock()
            val usage = parseUsage(row)
            // Portable files cannot transfer trust. Every imported card requires a fresh explicit user check.
            AppPlaybook(
                packageName = packageName,
                id = id,
                title = title.trim(),
                steps = steps.map(String::trim),
                completionCheck = check.trim(),
                enabled = row.opt("enabled")?.let {
                    it as? Boolean ?: throw TaskWorkspaceException("PLAYBOOK_INVALID_IMPORT", "enabled 必须是布尔值")
                } ?: true,
                verified = false,
                verifiedAt = null,
                revision = 1L,
                updatedAt = now,
                appVersionName = optionalVersionName(optionalText(row, "app_version_name")),
                appVersionCode = optionalVersionCode(optionalLong(row, "app_version_code")),
                sourceRunId = optionalRunId(optionalText(row, "source_run_id")),
                lastUsedAt = usage.lastUsedAt,
                lastSuccessAt = usage.lastSuccessAt,
                lastFailureAt = usage.lastFailureAt,
                useCount = usage.useCount,
                errorSummary = usage.lastErrorSummary,
            )
        }
        val keys = pending.map { it.packageName to it.id }
        if (keys.distinct().size != keys.size || all.any { (it.packageName to it.id) in keys }) {
            throw TaskWorkspaceException("PLAYBOOK_IMPORT_CONFLICT", "导入包含重复或已有经验卡，未覆盖任何内容")
        }
        if (all.size + pending.size > MAX_CARDS) {
            throw TaskWorkspaceException("PLAYBOOK_LIMIT", "经验卡总数超过 $MAX_CARDS")
        }
        writeLocked(all + pending)
        pending
    }

    fun exportJson(): String = files.locked {
        JSONObject().put("schema_version", 1).put("playbooks", JSONArray(readLocked().map { card ->
            card.toJson().apply {
                // Verification is intentionally omitted from portable exports. The optional operational
                // metadata remains useful as provenance, but importing it still resets trust.
                remove("verified")
                remove("verified_at")
                remove("verification_source")
                remove("revision")
                remove("updated_at")
            }
        })).toString(2)
    }

    private fun readLocked(): List<AppPlaybook> {
        val raw = files.read(FILE_NAME, MAX_STORE_BYTES) ?: return emptyList()
        try {
            val document = JSONObject(raw)
            if (document.getInt("schema_version") != 1) {
                throw TaskWorkspaceException("STORE_CORRUPT", "经验卡版本异常")
            }
            val rows = document.getJSONArray("playbooks")
            if (rows.length() > MAX_CARDS) {
                throw TaskWorkspaceException("STORE_CORRUPT", "经验卡数量异常")
            }
            val cards = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val card = AppPlaybook(
                    packageName = text(row, "package_name"),
                    id = text(row, "id"),
                    title = text(row, "title"),
                    steps = parseSteps(row.getJSONArray("steps")),
                    completionCheck = text(row, "completion_check"),
                    enabled = row.getBoolean("enabled"),
                    verified = row.getBoolean("verified"),
                    verifiedAt = optionalLong(row, "verified_at"),
                    revision = requiredLong(row, "revision"),
                    updatedAt = requiredLong(row, "updated_at"),
                    appVersionName = optionalVersionName(optionalText(row, "app_version_name")),
                    appVersionCode = optionalVersionCode(optionalLong(row, "app_version_code")),
                    sourceRunId = optionalRunId(optionalText(row, "source_run_id")),
                    lastUsedAt = optionalTimestamp(optionalLong(row, "last_used_at"), "last_used_at"),
                    lastSuccessAt = optionalTimestamp(optionalLong(row, "last_success_at"), "last_success_at"),
                    lastFailureAt = optionalTimestamp(optionalLong(row, "last_failure_at"), "last_failure_at"),
                    useCount = optionalLong(row, "use_count") ?: 0L,
                    errorSummary = optionalErrorSummary(row),
                )
                validateDefinition(card.packageName, card.id, card.title, card.steps, card.completionCheck)
                validateUsage(card)
                if (card.revision <= 0L || card.updatedAt < 0L ||
                    card.verified && (card.verifiedAt == null || row.optString("verification_source") != "user_confirmed")) {
                    throw TaskWorkspaceException("STORE_CORRUPT", "经验卡验证记录异常")
                }
                card
            }
            if (cards.map { it.packageName to it.id }.distinct().size != cards.size) {
                throw TaskWorkspaceException("STORE_CORRUPT", "经验卡标识重复")
            }
            return cards
        } catch (error: TaskWorkspaceException) {
            throw error
        } catch (_: Exception) {
            throw TaskWorkspaceException("STORE_CORRUPT", "经验卡文件解析失败，已保留原文件")
        }
    }

    private fun writeLocked(cards: List<AppPlaybook>) = files.write(
        FILE_NAME,
        JSONObject().put("schema_version", 1).put("playbooks", JSONArray(cards.map(AppPlaybook::toJson))).toString(),
        MAX_STORE_BYTES,
    )

    private data class ParsedUsage(
        val lastUsedAt: Long?,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val useCount: Long,
        val lastErrorSummary: String?,
    )

    companion object {
        const val MAX_CARDS = 200
        const val MAX_IMPORT_BYTES = 256 * 1024
        const val MAX_USER_IMPORT_BYTES = 2 * 1024 * 1024
        const val MAX_REFERENCE_CARDS = 3
        const val MAX_REFERENCE_CHARS = 4000
        const val MAX_ERROR_SUMMARY_CHARS = 240
        private const val MAX_STORE_BYTES = 2 * 1024 * 1024
        private const val FILE_NAME = "app-playbooks.json"
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val IMPORT_FIELDS = setOf(
            "package_name", "id", "title", "steps", "completion_check", "enabled", "verified", "verified_at",
            "verification_source", "revision", "updated_at", "app_version_name", "app_version_code", "source_run_id",
            "last_used_at", "last_success_at", "last_failure_at", "use_count", "last_error_summary", "error_summary",
        )

        fun forContext(context: Context): AppPlaybookStore = AppPlaybookStore(context.applicationContext.filesDir)

        fun parseSteps(rows: JSONArray): List<String> {
            if (rows.length() !in 1..20) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_STEPS", "需要 1–20 条操作步骤")
            }
            return (0 until rows.length()).map { index ->
                rows.get(index) as? String
                    ?: throw TaskWorkspaceException("PLAYBOOK_INVALID_STEPS", "步骤必须是文本数组")
            }
        }

        fun text(json: JSONObject, key: String): String = json.opt(key) as? String
            ?: throw TaskWorkspaceException("PLAYBOOK_INVALID_ARGUMENT", "$key 缺失或类型无效")

        fun validatePackage(value: String) {
            if (value.length !in 3..200 || !PACKAGE.matches(value)) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_PACKAGE", "需要完整 Android 包名")
            }
        }

        private fun validateKey(packageName: String, id: String) {
            validatePackage(packageName)
            validateId(id)
        }

        private fun validateId(id: String) {
            if (!ID.matches(id)) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_ID", "经验卡 id 需要 1–64 位小写英文、数字、下划线或连字符")
            }
        }

        private fun validateDefinition(packageName: String, id: String, title: String, steps: List<String>, check: String) {
            validateKey(packageName, id)
            if (title.isBlank() || title.length > 120 || '\u0000' in title) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_TITLE", "标题需要 1–120 字符")
            }
            if (steps.size !in 1..20 || steps.any { it.isBlank() || it.length > 500 || '\u0000' in it }) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_STEPS", "需要 1–20 条步骤，每条最多 500 字符")
            }
            if (check.isBlank() || check.length > 500 || '\u0000' in check) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_CHECK", "完成检查需要 1–500 字符")
            }
        }

        private fun optionalText(json: JSONObject, key: String): String? {
            if (!json.has(key) || json.isNull(key)) return null
            return json.opt(key) as? String
                ?: throw TaskWorkspaceException("PLAYBOOK_INVALID_ARGUMENT", "$key 必须是文本或 null")
        }

        private fun optionalLong(json: JSONObject, key: String): Long? {
            if (!json.has(key) || json.isNull(key)) return null
            val value = json.opt(key) as? Number
                ?: throw TaskWorkspaceException("PLAYBOOK_INVALID_ARGUMENT", "$key 必须是整数或 null")
            val result = value.toLong()
            if (value.toDouble() != result.toDouble()) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_ARGUMENT", "$key 必须是整数")
            }
            return result
        }

        private fun requiredLong(json: JSONObject, key: String): Long = optionalLong(json, key)
            ?: throw TaskWorkspaceException("PLAYBOOK_INVALID_ARGUMENT", "$key 缺失或类型无效")

        private fun optionalVersionName(value: String?): String? {
            val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
            if (normalized != null && (normalized.length > 120 || '\u0000' in normalized)) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_VERSION", "app 版本名需要 1–120 字符")
            }
            return normalized
        }

        private fun optionalVersionCode(value: Long?): Long? {
            if (value != null && value < 0L) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_VERSION", "app 版本号必须是非负整数")
            }
            return value
        }

        private fun optionalRunId(value: String?): String? {
            val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
            if (normalized != null) validateRunId(normalized)
            return normalized
        }

        private fun validateRunId(value: String) {
            if (value.isBlank() || value.length > 512 || '\u0000' in value) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_RUN", "runId 无效")
            }
        }

        private fun optionalTimestamp(value: Long?, field: String): Long? {
            if (value != null && value < 0L) {
                throw TaskWorkspaceException("STORE_CORRUPT", "$field 不能为负数")
            }
            return value
        }

        private fun boundedErrorSummary(value: String): String {
            val normalized = value.replace('\u0000', ' ').trim()
            return normalized.take(MAX_ERROR_SUMMARY_CHARS)
        }

        private fun optionalErrorSummary(row: JSONObject): String? {
            val last = optionalText(row, "last_error_summary")
            val legacy = optionalText(row, "error_summary")
            if (last != null && legacy != null && last != legacy) {
                throw TaskWorkspaceException("PLAYBOOK_INVALID_ARGUMENT", "error_summary 字段重复且内容不一致")
            }
            return (last ?: legacy)?.let(::boundedErrorSummary)
        }

        private fun parseUsage(row: JSONObject): ParsedUsage {
            val lastUsed = optionalTimestamp(optionalLong(row, "last_used_at"), "last_used_at")
            val lastSuccess = optionalTimestamp(optionalLong(row, "last_success_at"), "last_success_at")
            val lastFailure = optionalTimestamp(optionalLong(row, "last_failure_at"), "last_failure_at")
            val count = optionalLong(row, "use_count") ?: 0L
            if (count < 0L) throw TaskWorkspaceException("PLAYBOOK_INVALID_ARGUMENT", "use_count 必须是非负整数")
            return ParsedUsage(lastUsed, lastSuccess, lastFailure, count, optionalErrorSummary(row))
        }

        private fun validateUsage(card: AppPlaybook) {
            if (card.useCount < 0L || card.lastUsedAt?.let { it < 0L } == true ||
                card.lastSuccessAt?.let { it < 0L } == true || card.lastFailureAt?.let { it < 0L } == true ||
                card.errorSummary?.length?.let { it > MAX_ERROR_SUMMARY_CHARS } == true ||
                card.errorSummary?.contains('\u0000') == true) {
                throw TaskWorkspaceException("STORE_CORRUPT", "经验卡使用记录异常")
            }
        }
    }
}
