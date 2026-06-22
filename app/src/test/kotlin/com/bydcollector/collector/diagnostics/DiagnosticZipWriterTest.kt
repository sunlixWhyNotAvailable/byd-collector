package com.bydcollector.collector.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticZipWriterTest {
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
            val tempZip = File(runDir, "bydcollector_diagnostics_latest.zip.tmp").apply {
                writeText("stale temp placeholder", Charsets.UTF_8)
            }

            DiagnosticZipWriter.writeLatestZip(zipFile = zipFile, runDir = runDir)

            val entries = zipEntries(zipFile)
            assertTrue(eventSnapshot.name in entries)
            assertFalse(zipFile.name in entries)
            assertFalse(tempZip.name in entries)
            assertFalse(tempZip.exists())
        } finally {
            runDir.deleteRecursively()
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
