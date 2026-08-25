package com.bydcollector.collector.diagnostics

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalEventJournalTest {
    @Test
    fun rotatesToActivePlusThreeSegmentsAndDropsTheOldest() {
        val root = Files.createTempDirectory("bydcollector-event-journal").toFile()
        try {
            val sample = OperationalEventJournal.journalLine(
                "2026-08-25T12:00:00Z", 1, "boot", 7, "event_0", "message", null
            )
            val journal = OperationalEventJournal(
                root = root,
                maxFileBytes = sample.toByteArray(Charsets.UTF_8).size.toLong() + 1L,
                retainedRotations = 3,
                bootId = "boot",
                pid = 7
            )

            repeat(6) { index ->
                journal.append(
                    timestamp = "2026-08-25T12:00:00Z",
                    elapsedMs = index.toLong(),
                    category = "event_$index",
                    message = "message",
                    detail = null
                )
            }

            val files = root.listFiles().orEmpty().filter(File::isFile)
            assertEquals(4, files.size)
            assertTrue(File(root, "operational_events.jsonl").readText().contains("event_5"))
            assertTrue(File(root, "operational_events.1.jsonl").readText().contains("event_4"))
            assertTrue(File(root, "operational_events.2.jsonl").readText().contains("event_3"))
            assertTrue(File(root, "operational_events.3.jsonl").readText().contains("event_2"))
            assertFalse(files.any { it.readText().contains("event_0") || it.readText().contains("event_1") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writesValidJsonWithNullableDetailAndExplicitTruncation() {
        val normal = JSONObject(
            OperationalEventJournal.journalLine(
                "2026-08-25T12:00:00Z", 123, "boot", 9, "category", "message", null
            )
        )
        val truncated = JSONObject(
            OperationalEventJournal.journalLine(
                "2026-08-25T12:00:00Z", 124, "boot", 9, "category", "message", "x".repeat(40_000)
            )
        )

        assertEquals(1, normal.getInt("schema_version"))
        assertTrue(normal.isNull("detail"))
        assertFalse(normal.getBoolean("truncated"))
        assertEquals(32_768, truncated.getString("detail").length)
        assertTrue(truncated.getBoolean("truncated"))
    }

    @Test
    fun concurrentAppendsRemainCompleteJsonLinesAndSnapshotThenClearIsSerialized() {
        val root = Files.createTempDirectory("bydcollector-event-journal-concurrent").toFile()
        val snapshot = Files.createTempDirectory("bydcollector-event-journal-snapshot").toFile()
        try {
            val journal = OperationalEventJournal(
                root = root,
                maxFileBytes = 2L * 1024L * 1024L,
                retainedRotations = 3,
                bootId = "boot",
                pid = 11
            )
            val start = CountDownLatch(1)
            val workers = (0 until 4).map { worker ->
                thread(start = true) {
                    start.await()
                    repeat(50) { index ->
                        journal.append("2026-08-25T12:00:00Z", index.toLong(), "w$worker", "e$index", null)
                    }
                }
            }
            start.countDown()
            workers.forEach(Thread::join)

            assertEquals(1, journal.snapshotTo(snapshot))
            val lines = File(snapshot, "operational_events.jsonl").readLines().filter(String::isNotBlank)
            assertEquals(200, lines.size)
            lines.forEach { JSONObject(it) }
            assertEquals(1, journal.clear())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
            snapshot.deleteRecursively()
        }
    }
}
