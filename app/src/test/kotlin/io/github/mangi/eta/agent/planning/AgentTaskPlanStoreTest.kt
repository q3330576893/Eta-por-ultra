package io.github.mangi.eta.agent.planning

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTaskPlanStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun plansPersistAndAreIsolatedByOpaqueRunId() {
        val root = temporary.newFolder()
        val one = AgentTaskPlanStore(root, "run-1")
        val two = AgentTaskPlanStore(root, "run-2")
        val plan = one.update(0, "完成当前任务", listOf(step("first", TaskPlanStatus.IN_PROGRESS)))
        assertEquals(1L, plan.revision)
        assertEquals("完成当前任务", AgentTaskPlanStore(root, "run-1").snapshot().plan!!.title)
        assertNull(two.snapshot().plan)
        assertTrue(root.listFiles()!!.none { "run-1" in it.name })
    }

    @Test fun multipleActiveStepsAndOversizedPlansAreRejectedWithoutMutation() {
        val store = AgentTaskPlanStore(temporary.newFolder(), "run")
        fails("PLAN_MULTIPLE_ACTIVE") { store.update(0, "任务", listOf(step("a", TaskPlanStatus.IN_PROGRESS), step("b", TaskPlanStatus.IN_PROGRESS))) }
        fails("PLAN_INVALID_STEPS") { store.update(0, "任务", (0..20).map { step("s$it") }) }
        fails("PLAN_INVALID_STEP_ID") { store.update(0, "任务", listOf(step("duplicate"), step("duplicate"))) }
        assertEquals(0L, store.snapshot().revision)
    }

    @Test fun staleRevisionsDoNotOverwriteAnotherWriter() {
        val root = temporary.newFolder()
        val first = AgentTaskPlanStore(root, "run")
        val second = AgentTaskPlanStore(root, "run")
        first.update(0, "第一个计划", listOf(step("a")))
        fails("PLAN_REVISION_CONFLICT") { second.update(0, "覆盖", listOf(step("b"))) }
        assertEquals("第一个计划", first.snapshot().plan!!.title)
    }

    @Test fun completedStepsRequireRealReceiptsFromTheSameRun() {
        val root = temporary.newFolder()
        val one = AgentTaskPlanStore(root, "one")
        val two = AgentTaskPlanStore(root, "two")
        one.update(0, "任务", listOf(step("a", TaskPlanStatus.IN_PROGRESS)))
        val done = step("a", TaskPlanStatus.COMPLETED).copy(completionNote = "观察到目标结果", evidenceToolCallIds = listOf("observed"))
        fails("PLAN_EVIDENCE_REQUIRED") { one.update(1, "任务", listOf(done)) }
        two.recordSuccessfulTool("observed", "observe_screen")
        fails("PLAN_EVIDENCE_REQUIRED") { one.update(1, "任务", listOf(done)) }
        one.recordSuccessfulTool("observed", "observe_screen")
        assertEquals(TaskPlanStatus.COMPLETED, one.update(1, "任务", listOf(done)).plan!!.steps.single().status)
        assertTrue(one.snapshot().toJson().getString("completion_semantics").contains("reported_complete"))
    }

    @Test fun completedStepReceiptsCannotBeFabricatedUsingPlanTools() {
        val store = AgentTaskPlanStore(temporary.newFolder(), "run")
        store.recordSuccessfulTool("fake", "update_plan")
        store.recordSuccessfulTool("fake2", "app_playbooks_save")
        assertTrue(store.snapshot().evidence.isEmpty())
    }

    @Test fun hashedRunIdentifiersNeverBecomePaths() {
        val root = temporary.newFolder()
        val store = AgentTaskPlanStore(root, "../../other/run")
        store.update(0, "安全作用域", listOf(step("a")))
        assertNotNull(store.snapshot().plan)
        val files = root.resolve("agent_task_workspace").listFiles()!!
        assertTrue(files.any { it.name.matches(Regex("plan-[a-f0-9]{64}\\.json")) })
        assertFalse(root.resolve("other").exists())
    }

    @Test fun symlinkedStoreDirectoryIsRejected() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        try { Files.createSymbolicLink(root.resolve("agent_task_workspace").toPath(), outside.toPath()) }
        catch (_: UnsupportedOperationException) { return }
        catch (_: java.nio.file.FileSystemException) { return }
        fails("STORE_PATH_INVALID") { AgentTaskPlanStore(root, "run").snapshot() }
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun concurrentWritersPreserveRevisionConflictInsteadOfLosingUpdates() {
        val root = temporary.newFolder()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (0..1).map { index -> executor.submit<String> {
                val store = AgentTaskPlanStore(root, "concurrent")
                ready.countDown(); start.await(5, TimeUnit.SECONDS)
                try { store.update(0, "task-$index", listOf(step("a"))); "saved" }
                catch (error: TaskWorkspaceException) { error.code }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            assertEquals(listOf("PLAN_REVISION_CONFLICT", "saved"), results.map { it.get(5, TimeUnit.SECONDS) }.sorted())
            assertEquals(1L, AgentTaskPlanStore(root, "concurrent").snapshot().revision)
        } finally { executor.shutdownNow() }
    }

    private fun step(id: String, status: TaskPlanStatus = TaskPlanStatus.PENDING) = TaskPlanStep(id, "步骤 $id", status)
    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") }
        catch (error: TaskWorkspaceException) { assertEquals(code, error.code) }
    }
}
