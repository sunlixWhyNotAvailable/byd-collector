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

}
