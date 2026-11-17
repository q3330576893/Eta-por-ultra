package io.github.mangi.eta.agent.planning

import android.content.Context

/** The only success authority accepted when a playbook use is recorded. */
internal enum class AppPlaybookUseOutcome {
    UNKNOWN,
    SUCCESS,
    FAILURE,
}

/** A receipt produced by an observed tool/runtime check for one concrete run. */
internal data class AppPlaybookVerificationReceipt(
    val runId: String,
    val receiptId: String,
    val verified: Boolean,
) {
    fun isExplicitFor(expectedRunId: String): Boolean =
        verified && runId == expectedRunId && runId.isNotBlank() && receiptId.isNotBlank()
}

/** Read-only, user-selected context handed to the runtime for one run. */
internal data class AppPlaybookReference(
    val packageName: String,
    val cardIds: List<String>,
    val reference: String,
    val appVersionName: String?,
    val appVersionCode: Long?,
    val selectedByUser: Boolean,
    val selectedAt: Long,
) {
    /** Stable alias for callers that describe selected cards as ids. */
    val ids: List<String> get() = cardIds
}

/** Actual tool observations supplied to the draft helper; summaries are deliberately bounded. */
internal data class AppPlaybookToolSummary(
    val toolName: String,
    val summary: String,
    val observedAt: Long? = null,
    val success: Boolean? = null,
) {
    init {
        require(toolName.isNotBlank() && toolName.length <= MAX_TOOL_NAME_CHARS && '\u0000' !in toolName)
        require(summary.isNotBlank() && summary.length <= MAX_TOOL_SUMMARY_CHARS && '\u0000' !in summary)
        require(observedAt == null || observedAt >= 0L)
    }

    companion object {
        const val MAX_TOOL_NAME_CHARS = 120
        const val MAX_TOOL_SUMMARY_CHARS = 600
    }
}

/**
 * Runtime-facing facade for app-bound playbooks.
 *
 * Candidate lookup is exact-package only. A versioned card is usable only when its version
 * exactly matches the runtime version; an unversioned card remains a fallback. This helper only
 * returns reference data and never executes a step.
 */
internal class AppPlaybookContext private constructor(
    private val store: AppPlaybookStore,
    private val clock: () -> Long,
) {
    fun pickCandidates(
        packageName: String,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
    ): List<AppPlaybook> {
        AppPlaybookStore.validatePackage(packageName)
        val versionName = normalizeVersionName(appVersionName)
        val versionCode = normalizeVersionCode(appVersionCode)
        val cards = store.list(packageName = packageName, includeDisabled = false)
        return cards.filter { card ->
            when {
                card.appVersionName == null && card.appVersionCode == null -> true
                card.appVersionName != null && card.appVersionCode != null ->
                    card.appVersionName == versionName && card.appVersionCode == versionCode
                card.appVersionName != null -> versionName != null && card.appVersionName == versionName
                else -> versionCode != null && card.appVersionCode == versionCode
            }
        }.sortedWith(
            compareByDescending<AppPlaybook> { it.appVersionName != null || it.appVersionCode != null }
                .thenByDescending { it.verified }
                .thenByDescending { it.lastSuccessAt ?: Long.MIN_VALUE }
                .thenByDescending { it.lastUsedAt ?: Long.MIN_VALUE }
                .thenBy { it.title }
                .thenBy { it.id },
        ).take(AppPlaybookStore.MAX_REFERENCE_CARDS)
    }

    /** Select only cards explicitly named by the user and render a bounded read-only reference. */
    fun pickCandidate(
        packageName: String,
        selectedIds: List<String>,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
    ): AppPlaybookReference? {
        if (selectedIds.isEmpty() || selectedIds.size > AppPlaybookStore.MAX_REFERENCE_CARDS) return null
        val candidates = pickCandidates(packageName, appVersionName, appVersionCode)
        val selected = selectedIds.distinct().mapNotNull { id -> candidates.firstOrNull { it.id == id } }
        if (selected.size != selectedIds.size) return null
        val reference = renderReference(selected, appVersionName, appVersionCode)
        return AppPlaybookReference(
            packageName = packageName,
            cardIds = selected.map { it.id },
            reference = reference,
            appVersionName = normalizeVersionName(appVersionName),
            appVersionCode = normalizeVersionCode(appVersionCode),
            selectedByUser = true,
            selectedAt = clock(),
        )
    }

    /** Convenience for a caller that presents the bounded candidate list and records a user choice. */
    fun pickCandidate(
        packageName: String,
        selectedId: String,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
    ): AppPlaybookReference? = pickCandidate(packageName, listOf(selectedId), appVersionName, appVersionCode)

    /** Record usage only after a real run has ended; model prose alone supplies no success receipt. */
    fun recordUse(
        reference: AppPlaybookReference,
        runId: String,
        outcome: AppPlaybookUseOutcome = AppPlaybookUseOutcome.UNKNOWN,
        verificationReceipt: AppPlaybookVerificationReceipt? = null,
        errorSummary: String? = null,
    ): List<AppPlaybook> = store.recordUse(reference, runId, outcome, verificationReceipt, errorSummary)

    /**
     * Save a reusable draft from actual tool observations. The helper always writes unverified data;
     * the caller supplies the user-facing card id/title and the expected CAS revision.
     */
    fun saveDraftFromTask(
        packageName: String,
        id: String,
        title: String,
        steps: List<String>,
        completionCheck: String,
        expectedRevision: Long,
        toolSummaries: List<AppPlaybookToolSummary>,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
        runId: String? = null,
        enabled: Boolean = true,
    ): AppPlaybook {
        require(toolSummaries.size in 1..MAX_TOOL_SUMMARIES) { "需要 1–$MAX_TOOL_SUMMARIES 条实际工具摘要" }
        val boundedSteps = steps.map(String::trim)
        require(boundedSteps.isNotEmpty() && boundedSteps.size <= MAX_DRAFT_STEPS)
        val observed = toolSummaries.joinToString("\n") { summary ->
            "${summary.toolName}: ${summary.summary}"
        }
        val boundedCheck = listOf(
            completionCheck.trim().takeIf { it.isNotEmpty() },
            "Observed tool summary:\n$observed",
        ).filterNotNull().joinToString("\n\n").take(MAX_COMPLETION_CHECK_CHARS)
        return store.saveFromTool(
            packageName = packageName,
            id = id,
            title = title,
            steps = boundedSteps,
            completionCheck = boundedCheck.take(MAX_COMPLETION_CHECK_CHARS),
            expectedRevision = expectedRevision,
            enabled = enabled,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            sourceRunId = runId,
        )
    }

    /** Template helper for a task that already has a concrete tool trace. */
    fun saveDraftFromTask(
        packageName: String,
        id: String,
        title: String,
        steps: List<String>,
        completionCheck: String,
        expectedRevision: Long,
        toolName: String,
        toolSummary: String,
        appVersionName: String? = null,
        appVersionCode: Long? = null,
        runId: String? = null,
        enabled: Boolean = true,
    ): AppPlaybook = saveDraftFromTask(
        packageName = packageName,
        id = id,
        title = title,
        steps = steps,
        completionCheck = completionCheck,
        expectedRevision = expectedRevision,
        toolSummaries = listOf(AppPlaybookToolSummary(toolName, toolSummary)),
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        runId = runId,
        enabled = enabled,
    )

    private fun renderReference(
        cards: List<AppPlaybook>,
        appVersionName: String?,
        appVersionCode: Long?,
    ): String {
        val header = buildString {
            append("App playbook reference; package=")
            append(cards.first().packageName)
            if (appVersionName != null || appVersionCode != null) {
                append("; version=")
                append(appVersionName ?: "?")
                append("/")
                append(appVersionCode ?: "?")
            }
            append("; user-selected; reference-only\n")
        }
        val body = cards.joinToString("\n\n") { card ->
            buildString {
                append("[")
                append(card.id)
                append("] ")
                append(card.title)
                append(if (card.verified) " (user-verified reference)" else " (unverified reference)")
                append("\nSteps:\n")
                card.steps.forEachIndexed { index, step ->
                    append(index + 1)
                    append(". ")
                    append(step)
                    append('\n')
                }
                append("Check: ")
                append(card.completionCheck)
            }
        }
        return (header + body).take(AppPlaybookStore.MAX_REFERENCE_CHARS)
    }

    companion object {
        const val MAX_TOOL_SUMMARIES = 20
        const val MAX_DRAFT_STEPS = 20
        const val MAX_COMPLETION_CHECK_CHARS = 500

        fun forContext(context: Context, clock: () -> Long = System::currentTimeMillis): AppPlaybookContext =
            AppPlaybookContext(AppPlaybookStore.forContext(context), clock)

        fun fromStore(store: AppPlaybookStore, clock: () -> Long = System::currentTimeMillis): AppPlaybookContext =
            AppPlaybookContext(store, clock)

        private fun normalizeVersionName(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        private fun normalizeVersionCode(value: Long?): Long? = value?.takeIf { it >= 0L }
    }
}
