package com.bydcollector.collector.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelperDiagnosticTransportTest {
    @Test fun commandOnlyInvokesDiagnosticEntrypointWithQuotedApk() {
        val command = HelperDiagnosticTransport.command("/data/app/a'b/base.apk", "snapshot")
        assertTrue(command.contains("a'\\''b"))
        assertTrue(command.endsWith("HelperDiagnosticsMain snapshot"))
        assertFalse(command.contains("CollectorHelperDaemon"))
        assertFails { HelperDiagnosticTransport.command("base.apk", "stop") }
    }

    @Test fun fixedAllowlistExcludesSpoolTraversalAndExtraRotations() {
        assertEquals(2L * 1024 * 1024, helperDiagnosticEntryLimit("helper-diagnostics/helper-bootstrap.log.3"))
        assertNull(helperDiagnosticEntryLimit("../helper-diagnostics/helper-bootstrap.log"))
        assertNull(helperDiagnosticEntryLimit("helper-diagnostics/helper-bootstrap.log.4"))
        assertNull(helperDiagnosticEntryLimit("helper-diagnostics/../a.log"))
        assertNull(helperDiagnosticEntryLimit("bydcollector_telemetry_spool/sample.ready"))
        assertNull(helperDiagnosticEntryLimit("helper-diagnostics/helper-diagnostics.lock"))
    }

    @Test fun streamedOutputIsBoundedAndSignalsCompletion() {
        val target = ByteArrayOutputStream()
        val output = BoundedDiagnosticOutput(target, 4)
        output.write(byteArrayOf(1, 2, 3, 4))
        assertFails { output.write(5) }
        assertTrue(output.failure != null)
        output.close()
        output.close()
        assertEquals(0L, output.finished.count)
        assertEquals(4, target.size())
    }

    @Test fun partialManifestIsUsableAndNewFilesPassExistingPrivacyPolicy() = withTemporary { root ->
        val archive = zip(root, mapOf(
            "helper-diagnostics/manifest.json" to """{"schema_version":1,"operation":"snapshot","overall_status":"partial"}""",
            "helper-diagnostics/helper-diagnostics-current.json" to """{"bytes":123,"firmware":"1.2.3.4","latitude":50.1234}""",
            "helper-diagnostics/helper-diagnostics.jsonl.2" to """{"version":"2.8.0","vehicle":"Sea Lion 07","password":"private"}""",
            "helper-diagnostics/bydcollector_helper.log.3" to "bot_token=private firmware=1.2.3.4"
        ))
        val destination = File(root, "snapshot_test").apply { mkdirs() }
        assertTrue(extractHelperDiagnosticArchive(archive, destination).startsWith("partial files=3"))
        assertEquals("ok", sanitizeDiagnosticSnapshot(destination))
        val json = File(destination, "helper-diagnostics/helper-diagnostics-current.json").readText()
        assertTrue(json.contains("1.2.3.4"))
        assertTrue(json.contains("123"))
        assertFalse(json.contains("50.1234"))
        val history = File(destination, "helper-diagnostics/helper-diagnostics.jsonl.2").readText()
        assertTrue(history.contains("Sea Lion 07"))
        assertFalse(history.contains("private"))
        assertFalse(File(destination, "helper-diagnostics/bydcollector_helper.log.3").readText().contains("private"))
    }

    @Test fun rejectsTruncatedArchiveAndMissingManifest() = withTemporary { root ->
        val archive = zip(root, mapOf("helper-diagnostics/helper-bootstrap.log" to "data"))
        assertFails { extractHelperDiagnosticArchive(archive, File(root, "snapshot_missing")) }
        archive.writeBytes(archive.readBytes().dropLast(15).toByteArray())
        assertFails { extractHelperDiagnosticArchive(archive, File(root, "snapshot_torn")) }
    }

    @Test fun rejectsForeignEntriesWithoutWritingThem() = withTemporary { root ->
        val archive = zip(root, mapOf("../escape.log" to "bad"))
        assertFails { extractHelperDiagnosticArchive(archive, File(root, "snapshot_bad")) }
        assertFalse(File(root, "escape.log").exists())
    }

    private fun zip(root: File, files: Map<String, String>): File = File(root, "input.zip").apply {
        ZipOutputStream(outputStream()).use { out ->
            files.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    private fun withTemporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("collector-helper-share").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
