package io.github.mangi.eta.agent.device

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文件夹授权导入：完整复制、拒绝穿越与重复、超限即整体清理。 */
class DocumentTreeImporterTest {
    private val root: File = Files.createTempDirectory("eta-tree-import").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private class FakeSource(
        private val tree: Map<String, List<DocumentTreeImporter.Entry>>,
        private val files: Map<String, ByteArray> = emptyMap(),
    ) : DocumentTreeImporter.Source {
        override fun children(id: String) = tree[id].orEmpty()
        override fun open(id: String): InputStream =
            ByteArrayInputStream(files[id] ?: error("missing file $id"))
    }

    private fun entry(id: String, name: String, directory: Boolean = false) =
        DocumentTreeImporter.Entry(id, name, directory)

    @Test
    fun nestedFoldersAndFilesAreCopiedVerbatim() {
        val source = FakeSource(
            tree = mapOf(
                "root" to listOf(entry("d1", "logs", true), entry("f1", "note.txt")),
                "d1" to listOf(entry("f2", "a.log")),
            ),
            files = mapOf("f1" to "hello".toByteArray(), "f2" to "line".toByteArray()),
        )
        val staging = File(root, "staging")
        val result = DocumentTreeImporter.copy(source, "root", staging, "导入")
        assertEquals("导入", result.name)
        assertEquals("hello", File(result, "note.txt").readText())
        assertEquals("line", File(result, "logs/a.log").readText())
        assertTrue(result.isDirectory)
    }

    @Test
    fun pathTraversalAndControlCharactersAreRejected() {
        val staging = File(root, "staging")
        for (name in listOf("../escape", "a/b", "bad\u0000name")) {
            val source = FakeSource(tree = mapOf("root" to listOf(entry("f1", name))), files = mapOf("f1" to byteArrayOf(1)))
            val error = runCatching { DocumentTreeImporter.copy(source, "root", staging, "导入") }.exceptionOrNull()
            assertTrue("应拒绝名称 $name", error is IllegalArgumentException)
            assertFalse(staging.exists())
        }
    }

    @Test
    fun repeatedDocumentIdentifierCannotLoop() {
        val source = FakeSource(tree = mapOf(
            "root" to listOf(entry("d1", "loop", true)),
            "d1" to listOf(entry("d1", "again", true)),
        ))
        val error = runCatching { DocumentTreeImporter.copy(source, "root", File(root, "staging"), "导入") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun duplicateSiblingNamesAreRejectedRatherThanOverwritten() {
        val source = FakeSource(
            tree = mapOf("root" to listOf(entry("a", "same.txt"), entry("b", "same.txt"))),
            files = mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2)),
        )
        val error = runCatching { DocumentTreeImporter.copy(source, "root", File(root, "staging"), "导入") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun exceedingFileCountCleansUpEverything() {
        val many = (0 until DocumentTreeImporter.MAX_FILES + 5).map { entry("f$it", "f$it.bin") }
        val source = FakeSource(tree = mapOf("root" to many), files = many.associate { it.id to byteArrayOf(1) })
        val staging = File(root, "staging")
        val error = runCatching { DocumentTreeImporter.copy(source, "root", staging, "导入") }.exceptionOrNull()
        assertTrue(error is BoundedFileCopy.TooLargeException)
        // 失败后不能留下半成品目录。
        assertFalse(staging.exists())
    }

    @Test
    fun exceedingTotalBytesCleansUpEverything() {
        val big = ByteArray(1_000_000)
        val entries = (0 until 140).map { entry("f$it", "f$it.bin") }
        val source = FakeSource(tree = mapOf("root" to entries), files = entries.associate { it.id to big })
        val staging = File(root, "staging")
        val error = runCatching { DocumentTreeImporter.copy(source, "root", staging, "导入") }.exceptionOrNull()
        assertTrue(error is BoundedFileCopy.TooLargeException)
        assertFalse(staging.exists())
    }

    @Test
    fun depthLimitStopsRunawayNesting() {
        val tree = mutableMapOf<String, List<DocumentTreeImporter.Entry>>()
        tree["root"] = listOf(entry("d0", "d0", true))
        for (index in 0 until DocumentTreeImporter.MAX_DEPTH + 2) {
            tree["d$index"] = listOf(entry("d${index + 1}", "d${index + 1}", true))
        }
        val error = runCatching {
            DocumentTreeImporter.copy(FakeSource(tree), "root", File(root, "staging"), "导入")
        }.exceptionOrNull()
        assertTrue(error is BoundedFileCopy.TooLargeException)
    }
}
