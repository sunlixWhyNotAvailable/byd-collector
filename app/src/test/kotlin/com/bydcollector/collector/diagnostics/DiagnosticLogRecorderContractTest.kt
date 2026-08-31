package com.bydcollector.collector.diagnostics

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

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
    fun activeCaptureStateUsesSeparateLocksAndPublishesAfterStreamOpen() {
        val source = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt",
            "src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt"
        ).readText()

        assertTrue(source.contains("private val workLock = Any()"))
        assertTrue(source.contains("private val stateLock = Any()"))
        assertTrue(source.indexOf("val stream = try") < source.indexOf("adbStream = stream"))
    }

    @Test
    fun activeLogcatUsesEightSixteenMiBSegmentsAndDropsTheOldest() {
        val root = Files.createTempDirectory("bydcollector-logcat-rotation").toFile()
        try {
            assertEquals(16L * 1024L * 1024L, DiagnosticLogRecorder.LOGCAT_SEGMENT_BYTES)
            assertEquals(8, DiagnosticLogRecorder.LOGCAT_SEGMENT_COUNT)
            DiagnosticLogcatOutputStream(root, segmentBytes = 4L, segmentCount = 8).use { output ->
                output.write(ByteArray(4 * 9) { it.toByte() })
            }

            val segments = root.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("logcat_") }
                .sortedBy { it.name }
            assertEquals(8, segments.size)
            assertTrue(segments.all { it.length() <= 4L })
            assertEquals(4L, File(root, "logcat_0.txt").length())
            assertEquals(4L, File(root, "logcat_7.txt").length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun shareSpacePreflightIncludesFourCopiesAndFixedHeadroom() {
        assertTrue(hasDiagnosticShareSpace(4_000L + 128L, 1_000L, 128L))
        assertFalse(hasDiagnosticShareSpace(4_000L + 127L, 1_000L, 128L))
        assertFalse(hasDiagnosticShareSpace(Long.MAX_VALUE, Long.MAX_VALUE, 128L))
    }

    @Test
    fun postPublicationPruneProtectsTheHandedOffFile() {
        val root = Files.createTempDirectory("bydcollector-diagnostic-prune-protected").toFile()
        try {
            val expired = File(root, "expired.zip").apply { writeText("old") }
            val protected = File(root, "handed-off.zip").apply { writeText("new") }
            val now = 1_800_000L
            expired.setLastModified(now - 1_000L)
            protected.setLastModified(now - 1_000L)

            assertEquals(1, pruneExpiredDiagnosticShareFiles(root, now, 500L, protectedFile = protected))
            assertFalse(expired.exists())
            assertTrue(protected.exists())
        } finally {
            root.deleteRecursively()
        }
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
        val shareMethod = activity.substringAfter("private fun shareDiagnosticLogs()")
            .substringBefore("private fun clearDiagnosticLogs()")
        val workerBody = shareMethod.substringBefore("handler.post")
        assertTrue(shareMethod.contains("Intent.EXTRA_STREAM"))
        assertTrue(workerBody.contains("FileProvider.getUriForFile"))
        assertTrue(workerBody.contains("ClipData.newUri"))
        assertTrue(workerBody.contains("bundle.length()"))
        assertTrue(shareMethod.contains("runCatching { startActivity(Intent.createChooser(sendIntent, title)) }"))
        assertFalse(shareMethod.contains("Intent.EXTRA_SUBJECT"))
        assertFalse(shareMethod.contains("Intent.EXTRA_TEXT"))
        assertTrue(activity.contains("DiagnosticLogRecorder.clearCompleted(applicationContext)"))
        assertTrue(paths.contains("<cache-path name=\"diagnostic_shares\" path=\"diagnostic_shares/\" />"))
        assertFalse(paths.contains("<files-path name=\"diagnostics\""))
    }

    @Test
    fun eachShareUsesAFreshSnapshotWithBoundedHelperTailAndLogcatProvenance() {
        val source = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt",
            "src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt"
        ).readText()
        val share = source.substringAfter("fun prepareShareBundle(context: Context)")
            .substringBefore("fun clearCompleted(context: Context)")

        assertTrue(share.contains("createDiagnosticSnapshotDirectory"))
        assertTrue(share.contains("writeLogcatSnapshot"))
        assertTrue(share.contains("writeOperationalJournalSnapshot"))
        assertTrue(share.contains("writeKeepAliveLogSnapshot"))
        assertTrue(source.contains("private const val KEEP_ALIVE_LOG_TAIL_BYTES = 512 * 1024"))
        assertTrue(source.contains("tail -c \$KEEP_ALIVE_LOG_TAIL_BYTES"))
        assertTrue(source.contains("logcat_provenance.txt"))
        assertTrue(source.contains("file.name == \"logcat_error.txt\""))
        assertTrue(source.contains(": > \$KEEP_ALIVE_LOG_PATH"))
        assertFalse(source.contains("DirectDebugDatabaseHelper"))
        assertFalse(source.contains("TripsDatabase"))
    }

    @Test
    fun latestLogcatSourceIgnoresNewerShareSnapshots() {
        val root = Files.createTempDirectory("bydcollector-diagnostic-source").toFile()
        try {
            val logcat = createDiagnosticRunDirectory(root, "20260825_120000")
            val snapshot = File(root, "snapshot_20260825_120001").apply { mkdirs() }
            logcat.setLastModified(1_000L)
            snapshot.setLastModified(2_000L)

            assertEquals(logcat.canonicalPath, latestCompletedDiagnosticRun(root)?.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
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

    @Test
    fun tripsTelegramEvidenceIsExplicitShareOnlyAndRedacted() {
        val source = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt",
            "src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt"
        ).readText()
        val share = source.substringAfter("fun prepareShareBundle(context: Context)")
            .substringBefore("fun clearCompleted(context: Context)")
        assertTrue(share.contains("writeTripsTelegramEvidence"))
        assertTrue(source.contains("TRIPS_TELEGRAM_EVIDENCE_MAX_BYTES = 64 * 1024"))
        assertTrue(source.contains("pending_missing"))
        assertTrue(source.contains("pending_unlinked"))
        assertTrue(source.contains("trip_correlation"))
        assertTrue(source.contains("telegram_pending_power_session_ref"))
        assertTrue(source.contains("findByPowerSessionId = trips::session"))
        assertTrue(source.contains("tripsStoreOrNull"))
        assertFalse(source.contains("BydCollectorApplication.trips(app)"))
        assertTrue(source.contains("\"not_initialized\" -> \"not_initialized\""))
        assertTrue(source.contains("tripsFileOperationLock.withLock"))
        assertTrue(source.contains("trips.withLease"))
        assertFalse(source.contains("latitude"))
        assertFalse(source.contains("longitude"))
        assertFalse(source.contains("payload"))
        assertFalse(source.contains("botToken"))
        assertFalse(source.contains("chatId"))
        assertFalse(source.contains("run-as"))
        assertFalse(source.contains("TripsDatabase"))
    }

    @Test
    fun boundedEvidenceUsesUtf8ByteCapAndExplicitTruncationMarker() {
        val lines = listOf("schema_version=1") + List(100) { "дані=" + "x".repeat(20) }
        val capped = boundedDiagnosticUtf8(lines, 128)
        assertTrue(capped.size <= 128)
        assertTrue(String(capped, Charsets.UTF_8).endsWith("truncated=1\n"))

        val complete = boundedDiagnosticUtf8(listOf("ok=так"), 128)
        assertTrue(complete.size <= 128)
        assertTrue(String(complete, Charsets.UTF_8).endsWith("truncated=0\n"))
        assertContentEquals(complete, boundedDiagnosticUtf8(listOf("ok=так"), 128))
    }

    private fun projectFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("Missing project file: ${paths.joinToString()}")
}
