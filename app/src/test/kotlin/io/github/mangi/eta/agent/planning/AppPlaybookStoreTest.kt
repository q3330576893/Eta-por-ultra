package io.github.mangi.eta.agent.planning

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPlaybookStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val steps = listOf("打开目标页面", "核对可见结果")

    @Test fun saveReadDeleteArePackageScopedAndRevisionChecked() {
        val root = temporary.newFolder()
        val store = AppPlaybookStore(root) { 1000L }
        val one = store.saveFromTool("com.example.one", "lookup", "查资料", steps, "结果显示目标条目", 0)
        val two = store.saveFromTool("com.example.two", "lookup", "另一应用", steps, "结果显示目标条目", 0)
        assertEquals(listOf(one), store.list("com.example.one"))
        assertEquals(two, AppPlaybookStore(root).get("com.example.two", "lookup"))
        fails("PLAYBOOK_REVISION_CONFLICT") { store.delete(one.packageName, one.id, 0) }
        assertTrue(store.delete(one.packageName, one.id, one.revision))
        assertNull(store.get(one.packageName, one.id))
        assertNotNull(store.get(two.packageName, two.id))
    }

    @Test fun onlyExplicitUserActionCanMarkVerifiedAndToolEditsClearTrust() {
        val store = AppPlaybookStore(temporary.newFolder()) { 12345L }
        val draft = store.saveFromTool("com.example.app", "flow", "流程", steps, "出现成功页面", 0)
        assertFalse(draft.verified)
        assertNull(draft.verifiedAt)
        val verified = store.saveFromUser(draft.packageName, draft.id, draft.title, draft.steps, draft.completionCheck, draft.revision, true, true)
        assertTrue(verified.verified)
        assertEquals(12345L, verified.verifiedAt)
        assertEquals("user_confirmed", verified.toJson().getString("verification_source"))
        val changed = store.saveFromTool(verified.packageName, verified.id, "修改流程", steps, verified.completionCheck, verified.revision)
        assertFalse(changed.verified)
        assertNull(changed.verifiedAt)
    }

    @Test fun staleSaveDoesNotOverwriteExistingCardAndDisabledFilterIsApplied() {
        val root = temporary.newFolder()
        val store = AppPlaybookStore(root)
        val card = store.saveFromTool("com.example.app", "flow", "流程", steps, "看到结果", 0, false)
        assertTrue(store.list(includeDisabled = false).isEmpty())
        fails("PLAYBOOK_REVISION_CONFLICT") { AppPlaybookStore(root).saveFromTool(card.packageName, card.id, "覆盖", steps, "覆盖", 0) }
        assertEquals("流程", store.get(card.packageName, card.id)!!.title)
    }

    @Test fun packageAndIdsCannotEscapeThePrivateDirectory() {
        val root = temporary.newFolder()
        val store = AppPlaybookStore(root)
        fails("PLAYBOOK_INVALID_PACKAGE") { store.saveFromTool("../escape", "flow", "流程", steps, "结果", 0) }
        fails("PLAYBOOK_INVALID_ID") { store.saveFromTool("com.example.app", "../escape", "流程", steps, "结果", 0) }
        assertTrue(store.list().isEmpty())
        assertFalse(root.resolve("escape").exists())
    }

    @Test fun exportImportPreservesDefinitionsButDropsVerifiedClaims() {
        val first = AppPlaybookStore(temporary.newFolder())
        first.saveFromUser("com.example.app", "lookup", "查找", steps, "显示目标", 0, true, true)
        val second = AppPlaybookStore(temporary.newFolder())
        val imported = second.importJson(first.exportJson()).single()
        assertEquals(steps, imported.steps)
        assertFalse(imported.verified)
        assertNull(imported.verifiedAt)
        val hostileClaim = imported.toJson().put("verified", true).put("verification_source", "user_confirmed").put("verified_at", 2000)
            .put("id", "claimed")
        val claimImport = second.importJson(bundle(hostileClaim)).single()
        assertFalse(claimImport.verified)
        assertNull(claimImport.verifiedAt)
    }

    @Test fun conflictingOrInvalidImportCommitsNoPartialCards() {
        val store = AppPlaybookStore(temporary.newFolder())
        val row = definition("existing")
        store.importJson(bundle(row))
        fails("PLAYBOOK_IMPORT_CONFLICT") { store.importJson(bundle(definition("new"), row)) }
        assertNull(store.get("com.example.app", "new"))
        fails("PLAYBOOK_INVALID_STEPS") { store.importJson(bundle(definition("new"), definition("bad").put("steps", JSONArray()))) }
        assertEquals(1, store.list().size)
    }

    @Test fun explicitUserImportRestoresMoreThanOneToolBatch() {
        val store = AppPlaybookStore(temporary.newFolder())
        val document = bundle(*(0..20).map { definition("flow$it") }.toTypedArray())
        fails("PLAYBOOK_INVALID_IMPORT") { store.importJson(document) }
        assertEquals(21, store.importFromUser(document).size)
        val restored = AppPlaybookStore(temporary.newFolder())
        assertEquals(21, restored.importFromUser(store.exportJson()).size)
    }

    @Test fun importedPrivatePathsAndOversizedDataAreRejected() {
        val store = AppPlaybookStore(temporary.newFolder())
        fails("PLAYBOOK_INVALID_IMPORT") { store.importJson(bundle(definition("flow").put("path", "/sdcard/private"))) }
        fails("PLAYBOOK_IMPORT_TOO_LARGE") { store.importJson("x".repeat(AppPlaybookStore.MAX_IMPORT_BYTES + 1)) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun legacySchemaOneRowsRemainReadableAndNewTrustClaimsAreDropped() {
        val store = AppPlaybookStore(temporary.newFolder()) { 9000L }
        val legacy = store.importJson(bundle(definition("legacy"))).single()
        assertNull(legacy.appVersionName)
        assertNull(legacy.appVersionCode)
        assertEquals(0L, legacy.useCount)
        assertFalse(legacy.verified)

        val rich = definition("rich")
            .put("app_version_name", "4.2.0")
            .put("app_version_code", 420L)
            .put("source_run_id", "old-run")
            .put("last_used_at", 10L)
            .put("last_success_at", 11L)
            .put("last_failure_at", 12L)
            .put("use_count", 7L)
            .put("error_summary", "old failure")
            .put("verified", true)
            .put("verified_at", 13L)
            .put("verification_source", "user_confirmed")
        val restored = AppPlaybookStore(temporary.newFolder()).importJson(bundle(rich)).single()
        assertEquals("4.2.0", restored.appVersionName)
        assertEquals(420L, restored.appVersionCode)
        assertEquals("old-run", restored.sourceRunId)
        assertEquals(7L, restored.useCount)
        assertEquals("old failure", restored.errorSummary)
        assertFalse(restored.verified)
        assertNull(restored.verifiedAt)
    }

    @Test fun contextMatchesExactPackageAndVersionAndBoundsReference() {
        val store = AppPlaybookStore(temporary.newFolder()) { 100L }
        store.saveFromTool("com.example.app", "generic", "通用", steps, "看到结果", 0)
        store.saveFromTool("com.example.app", "v1", "版本一", steps, "看到结果", 0, true, "1.2", 12L)
        store.saveFromTool("com.example.app", "v2", "版本二", steps, "看到结果", 0, true, "2.0", 20L)
        store.saveFromTool("com.other.app", "v1", "其他应用", steps, "看到结果", 0, true, "1.2", 12L)

        val context = AppPlaybookContext.fromStore(store) { 200L }
        assertEquals(setOf("generic", "v1"), context.pickCandidates("com.example.app", "1.2", 12L).map { it.id }.toSet())
        assertEquals(listOf("generic"), context.pickCandidates("com.example.app", "9.9", 99L).map { it.id })
        assertNull(context.pickCandidate("com.example.app", listOf("v2"), "1.2", 12L))
        val reference = context.pickCandidate("com.example.app", listOf("v1"), "1.2", 12L)
        assertNotNull(reference)
        assertTrue(reference!!.selectedByUser)
        assertTrue(reference.reference.length <= AppPlaybookStore.MAX_REFERENCE_CHARS)
        assertEquals("com.example.app", reference.packageName)
    }

    @Test fun usageCountTracksFailureAndUnknownWithoutPromotingVerification() {
        var now = 100L
        val store = AppPlaybookStore(temporary.newFolder()) { now }
        val card = store.saveFromUser("com.example.app", "flow", "流程", steps, "看到结果", 0, true, true)
        val context = AppPlaybookContext.fromStore(store) { now }
        val reference = context.pickCandidate("com.example.app", listOf(card.id))!!

        val unknown = context.recordUse(reference, "run-unknown", AppPlaybookUseOutcome.SUCCESS)
        assertEquals(1L, unknown.single().useCount)
        assertNull(unknown.single().lastSuccessAt)
        assertTrue(unknown.single().verified)

        now = 200L
        val failed = context.recordUse(reference, "run-failure", AppPlaybookUseOutcome.FAILURE, errorSummary = "x".repeat(500))
            .single()
        assertEquals(2L, failed.useCount)
        assertEquals(200L, failed.lastFailureAt)
        assertEquals(AppPlaybookStore.MAX_ERROR_SUMMARY_CHARS, failed.errorSummary!!.length)
        assertTrue(failed.verified)

        now = 300L
        val success = context.recordUse(
            reference,
            "run-success",
            AppPlaybookUseOutcome.SUCCESS,
            AppPlaybookVerificationReceipt("run-success", "receipt-1", verified = true),
        ).single()
        assertEquals(3L, success.useCount)
        assertEquals(300L, success.lastSuccessAt)
        assertTrue(success.verified)
    }

    @Test fun draftHelperStoresActualSummarySourceAndLeavesCardUnverified() {
        val store = AppPlaybookStore(temporary.newFolder()) { 42L }
        val context = AppPlaybookContext.fromStore(store)
        val draft = context.saveDraftFromTask(
            packageName = "com.example.app",
            id = "draft",
            title = "任务草稿",
            steps = steps,
            completionCheck = "看到结果",
            expectedRevision = 0L,
            toolName = "tap_element",
            toolSummary = "实际观察到目标页面",
            appVersionName = "3.1",
            appVersionCode = 31L,
            runId = "run-42",
        )
        assertFalse(draft.verified)
        assertEquals("3.1", draft.appVersionName)
        assertEquals(31L, draft.appVersionCode)
        assertEquals("run-42", draft.sourceRunId)
    }

    private fun definition(id: String) = JSONObject().put("package_name", "com.example.app").put("id", id)
        .put("title", "经验卡").put("steps", JSONArray(steps)).put("completion_check", "看到目标结果")
    private fun bundle(vararg rows: JSONObject) = JSONObject().put("schema_version", 1).put("playbooks", JSONArray(rows.toList())).toString()
    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") }
        catch (error: TaskWorkspaceException) { assertEquals(code, error.code) }
    }
}
