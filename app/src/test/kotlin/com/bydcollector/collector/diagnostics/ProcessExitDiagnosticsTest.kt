package com.bydcollector.collector.diagnostics

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessExitDiagnosticsTest {
    @Test fun anrKeepsMainStackBeyondTheOldPrefixAndBoundsScanning() {
        val input = ByteArrayInputStream(("vendor header\n".repeat(3000) +
            "\"main\" prio=5 tid=1 Blocked\n  at android.database.sqlite.SQLiteConnectionPool.waitForConnection\n" +
            "\"other\" prio=5\n" + "x".repeat(2 * 1024 * 1024)).toByteArray())
        val (trace, truncated) = readAnrTrace(input)
        assertTrue(trace.startsWith("[main thread]\n\"main\""))
        assertTrue(trace.contains("SQLiteConnectionPool.waitForConnection"))
        assertTrue(trace.length < 17 * 1024)
        assertTrue(truncated)
        assertTrue(input.available() > 0)
    }

    @Test
    fun fatalEvidenceFailureStillDelegatesOriginalExceptionExactlyOnce() {
        val failingThread = Thread("crashing-worker")
        val originalError = IllegalStateException("original failure")
        var evidenceCalls = 0
        var delegateCalls = 0
        var recordedThread: Thread? = null
        var recordedError: Throwable? = null
        var delegatedThread: Thread? = null
        var delegatedError: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { thread, error ->
            delegateCalls += 1
            delegatedThread = thread
            delegatedError = error
        }
        val handler = FatalEvidenceHandler(previous) { thread, error ->
            evidenceCalls += 1
            recordedThread = thread
            recordedError = error
            throw IllegalStateException("evidence storage failed")
        }

        handler.uncaughtException(failingThread, originalError)

        assertEquals(1, evidenceCalls)
        assertEquals(1, delegateCalls)
        assertSame(failingThread, recordedThread)
        assertSame(originalError, recordedError)
        assertSame(failingThread, delegatedThread)
        assertSame(originalError, delegatedError)
    }

    @Test
    fun unseenExitKeysDeduplicateAndFailedEvidenceRemainsEligible() {
        val keys = listOf("old-exit", "stable-exit", "stable-exit", "retry-exit")
        val initiallySeen = setOf("old-exit")
        val firstAttempts = mutableListOf<Int>()

        val firstRecorded = recordUnseenExits(keys, initiallySeen) { index ->
            firstAttempts += index
            keys[index] != "retry-exit"
        }

        assertEquals(setOf("stable-exit"), firstRecorded)
        assertEquals(listOf(1, 3), firstAttempts)

        val retryAttempts = mutableListOf<Int>()
        val retried = recordUnseenExits(keys, initiallySeen + firstRecorded) { index ->
            retryAttempts += index
            true
        }

        assertEquals(setOf("retry-exit"), retried)
        assertEquals(listOf(3), retryAttempts)
    }

    @Test
    fun traceReadStopsAtLimitPlusOneAndPreservesUtf8Prefix() {
        val prefix = "trace ✓"
        val bytes = (prefix + " trailing data").toByteArray(StandardCharsets.UTF_8)
        val limit = prefix.toByteArray(StandardCharsets.UTF_8).size
        val input = ByteArrayInputStream(bytes)

        val (text, truncated) = readExitTracePrefix(input, limit)

        assertEquals(prefix, text)
        assertTrue(truncated)
        assertEquals(bytes.size - limit - 1, input.available())
    }

    @Test
    fun traceReadHandlesZeroAndShortReadsWithoutDroppingBytes() {
        val payload = "short reads and zero"
        val input = ZeroThenShortReadInputStream(payload.toByteArray(StandardCharsets.UTF_8))

        val (text, truncated) = readExitTracePrefix(input, 64)

        assertEquals(payload, text)
        assertFalse(truncated)
        assertEquals(payload.toByteArray(StandardCharsets.UTF_8).size, input.bytesReturned)
    }

    private class ZeroThenShortReadInputStream(private val data: ByteArray) : InputStream() {
        private var offset = 0
        private var returnZero = true
        var bytesReturned = 0
            private set

        override fun read(): Int {
            if (offset >= data.size) return -1
            bytesReturned += 1
            return data[offset++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (returnZero) {
                returnZero = false
                return 0
            }
            if (offset >= data.size) return -1
            val count = minOf(length, 2, data.size - offset)
            System.arraycopy(data, offset, buffer, start, count)
            offset += count
            bytesReturned += count
            return count
        }
    }
}
