package com.bydcollector.collector.diagnostics

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagnosticLogRecorderContractTest {
    @Test
    fun recordsUnfilteredSystemLogcatThroughAuthenticatedAdbStream() {
        val source = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt",
            "src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt"
        ).readText()

        assertTrue(source.contains("logcat -b all -v threadtime"))
        assertTrue(source.contains("openShellStream("))
        assertTrue(source.contains("logcat_error.txt"))
        assertFalse(source.contains("ProcessBuilder(\"logcat\""))
        assertFalse(source.contains("--pid"))
    }

    @Test
    fun sameSecondRunsUseDistinctDirectoriesWithoutTruncatingPriorEvidence() {
        val root = Files.createTempDirectory("bydcollector-diagnostic-runs").toFile()
        try {
            val first = createDiagnosticRunDirectory(root, "20260825_120000")
            File(first, "logcat_threadtime.txt").writeText("first run", Charsets.UTF_8)
            val second = createDiagnosticRunDirectory(root, "20260825_120000")
            File(second, "logcat_threadtime.txt").writeText("second run", Charsets.UTF_8)

            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertEquals("first run", File(first, "logcat_threadtime.txt").readText(Charsets.UTF_8))
            assertEquals("second run", File(second, "logcat_threadtime.txt").readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearCompletedLogsPreservesTheActiveCapture() {
        val root = Files.createTempDirectory("bydcollector-diagnostic-clear").toFile()
        try {
            val active = createDiagnosticRunDirectory(root, "20260825_120000")
            File(active, "logcat_threadtime.txt").writeText("active", Charsets.UTF_8)
            val completed = createDiagnosticRunDirectory(root, "20260825_120001")
            File(completed, "logcat_threadtime.txt").writeText("completed", Charsets.UTF_8)
            val bundle = File(root, "bydcollector_diagnostics_latest.zip").apply { writeText("zip") }

            assertEquals(2, clearCompletedDiagnosticFiles(root, active))
            assertTrue(active.isDirectory)
            assertEquals("active", File(active, "logcat_threadtime.txt").readText(Charsets.UTF_8))
            assertFalse(completed.exists())
            assertFalse(bundle.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diagnosticSharesAreUniqueAndFreshCopiesSurviveCleanupWindow() {
        val root = Files.createTempDirectory("bydcollector-diagnostic-shares").toFile()
        try {
            val source = File(root, "source.zip").apply { writeText("diagnostics", Charsets.UTF_8) }
            val shares = File(root, "shares")
            val first = createDiagnosticShareCopy(source, shares, "20260825_120000")
            val second = createDiagnosticShareCopy(source, shares, "20260825_120000")
            val now = 1_800_000L
            first.setLastModified(now - DiagnosticLogRecorder.SHARE_HANDOFF_RETENTION_MS - 1L)
            second.setLastModified(now)

            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertEquals("diagnostics", second.readText(Charsets.UTF_8))
            assertEquals(
                1,
                pruneExpiredDiagnosticShareFiles(
                    shares,
                    now,
                    DiagnosticLogRecorder.SHARE_HANDOFF_RETENTION_MS
                )
            )
            assertFalse(first.exists())
            assertTrue(second.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun optionsShareUsesTheNativeChooserAndAppPrivateDiagnosticsPath() {
        val activity = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/MainActivity.kt",
            "src/main/kotlin/com/bydcollector/collector/MainActivity.kt"
        ).readText()
        val paths = projectFile(
            "app/src/main/res/xml/update_file_paths.xml",
            "src/main/res/xml/update_file_paths.xml"
        ).readText()

        assertTrue(activity.contains("DiagnosticLogRecorder.prepareShareBundle(applicationContext)"))
        assertTrue(activity.contains("Intent.ACTION_SEND"))
        assertTrue(activity.contains("Intent.createChooser(sendIntent, title)"))
        assertTrue(activity.contains("type = \"application/zip\""))
        assertTrue(activity.contains("DiagnosticLogRecorder.clearCompleted(applicationContext)"))
        assertTrue(paths.contains("<cache-path name=\"diagnostic_shares\" path=\"diagnostic_shares/\" />"))
        assertFalse(paths.contains("<files-path name=\"diagnostics\""))
    }

    @Test
    fun collectorEventSnapshotUsesTheApplicationDatabaseReadGate() {
        val source = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt",
            "src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt"
        ).readText()
        val snapshot = source.substringAfter("private fun writeCollectorEventsSnapshot(")
            .substringBefore("private fun writeLatestZip(")

        assertTrue(snapshot.contains("context.applicationContext as BydCollectorApplication"))
        assertTrue(snapshot.indexOf("withDatabaseRead") < snapshot.indexOf("SQLiteDatabase.openDatabase"))
    }

    private fun projectFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("Missing project file: ${paths.joinToString()}")
}
