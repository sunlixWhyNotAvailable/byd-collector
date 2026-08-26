package com.bydcollector.collector.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticZipWriterTest {
    @Test
    fun writerDoesNotDeleteUnrelatedFixedNameTempFile() {
        val source = listOf(
            File("app/src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticZipWriter.kt"),
            File("src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticZipWriter.kt")
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing DiagnosticZipWriter.kt")
        assertFalse(source.contains("File(parent, \"\${zipFile.name}.tmp\").delete()"))
    }

    @Test
    fun latestZipSkipsExistingArchiveAndTempOutputWhenRunDirIsDiagnosticsRoot() {
        val runDir = Files.createTempDirectory("bydcollector-diagnostics").toFile()
        try {
            val eventSnapshot = File(runDir, "collector_events_snapshot.txt").apply {
                writeText("events\n", Charsets.UTF_8)
            }
            val zipFile = File(runDir, "bydcollector_diagnostics_latest.zip").apply {
                writeText("previous zip placeholder", Charsets.UTF_8)
            }
            DiagnosticZipWriter.writeLatestZip(zipFile = zipFile, runDir = runDir)

            val entries = zipEntries(zipFile)
            assertTrue(eventSnapshot.name in entries)
            assertFalse(zipFile.name in entries)
            assertFalse(runDir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            runDir.deleteRecursively()
        }
    }

    @Test
    fun failedPublicationPreservesTheLastGoodZip() {
        val root = Files.createTempDirectory("bydcollector-diagnostics-publish").toFile()
        try {
            File(root, "collector_events_snapshot.txt").writeText("new evidence", Charsets.UTF_8)
            val zipFile = File(root, "bydcollector_diagnostics_latest.zip")
            val previous = "last good bundle".toByteArray(Charsets.UTF_8)
            zipFile.writeBytes(previous)

            assertFailsWith<IllegalStateException> {
                DiagnosticZipWriter.writeLatestZip(zipFile, root) { _, _ ->
                    throw IllegalStateException("injected move failure")
                }
            }

            assertContentEquals(previous, zipFile.readBytes())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipEntries(zipFile: File): Set<String> {
        return ZipInputStream(zipFile.inputStream()).use { zip ->
            buildSet {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name)
                    zip.closeEntry()
                }
            }
        }
    }
}
