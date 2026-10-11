package com.bydcollector.collector.direct

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperDiagnosticMaintenanceTest {

    @Test
    fun clearPreservesSnapshotLockAndRawSpoolAndLoggingContinues() {
        withRoots { diagnostic, legacy, raw ->
            val store = HelperDiagnosticFileStore(diagnostic, legacy, 128)
            store.persist("{\"event\":\"before\"}", "{\"current\":7}")
            store.persistBootstrap("bootstrap-before\n".toByteArray())
            File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME + ".1").writeText("old-json\n")
            File(diagnostic, HelperDiagnosticFileStore.BOOTSTRAP_NAME + ".1").writeText("old-bootstrap\n")
            File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME).writeText("legacy-active\n")
            File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".1").writeText("legacy-old\n")
            val snapshot = File(diagnostic, HelperDiagnosticFileStore.SNAPSHOT_NAME)
            val snapshotBefore = snapshot.readBytes()
            val rawRecord = File(raw, "record.ready").apply { writeText("raw telemetry") }

            val output = ByteArrayOutputStream()
            assertEquals(0, HelperDiagnosticsMain.clear(store, output))
            val result = JSONObject(output.toString(Charsets.UTF_8.name()))
            assertEquals("complete", result.getString("overall_status"))
            assertTrue(result.getJSONArray("preserved").toString().contains("raw_telemetry_spool"))
            assertEquals(0L, File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME).length())
            assertEquals(0L, File(diagnostic, HelperDiagnosticFileStore.BOOTSTRAP_NAME).length())
            assertEquals(0L, File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME).length())
            assertFalse(File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME + ".1").exists())
            assertFalse(File(diagnostic, HelperDiagnosticFileStore.BOOTSTRAP_NAME + ".1").exists())
            assertFalse(File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".1").exists())
            assertContentEquals(snapshotBefore, snapshot.readBytes())
            assertTrue(File(diagnostic, HelperDiagnosticFileStore.LOCK_NAME).isFile)
            assertEquals("raw telemetry", rawRecord.readText())

            store.persist("{\"event\":\"after\"}", "{\"current\":8}")
            assertTrue(File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME).readText().contains("after"))
        }
    }

    private fun withRoots(block: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("helper-diagnostic-maintenance").toFile()
        try {
            val diagnostic = File(root, "diagnostic").apply { mkdirs() }
            val legacy = File(root, "legacy").apply { mkdirs() }
            val raw = File(root, "raw-spool").apply { mkdirs() }
            block(diagnostic, legacy, raw)
        } finally {
            root.deleteRecursively()
        }
    }

}
