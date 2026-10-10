package com.bydcollector.collector.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PollHealthCacheTest {
    @Test
    fun lateOlderFailureFillsErrorFieldsWithoutRegressingNewestStatus() {
        val cache = PollHealthCache.fromRecentPage(
            sessionId = 42L,
            newestFirstRows = listOf(
                row(7L, ok = true, timestamp = "newest"),
                row(6L, ok = true, timestamp = "older success")
            ),
            pageLimit = 2
        )
        assertTrue(cache.snapshot.historyMayBeIncomplete)

        val merged = cache.merge(
            row(
                pollId = 5L,
                ok = false,
                timestamp = "late failure",
                errors = "transport error",
                errorCategory = "network",
                errorMessage = "offline"
            )
        )

        assertEquals("newest", merged.snapshot.lastSuccessAt)
        assertEquals("transport error", merged.snapshot.lastError)
        assertEquals("late failure", merged.snapshot.lastErrorAt)
        assertEquals("ok at newest", merged.snapshot.lastPollStatus)
        assertEquals(7L, merged.latestPollId)
        assertFalse(merged.snapshot.historyMayBeIncomplete)
    }

    @Test
    fun olderReplayCannotRegressPerFieldValues() {
        val cache = PollHealthCache.empty(sessionId = 42L)
            .merge(row(8L, ok = true, timestamp = "latest success"))
            .merge(row(7L, ok = false, timestamp = "older failure", errors = "old error"))

        val duplicate = cache.merge(row(7L, ok = false, timestamp = "replay", errors = "replayed error"))

        assertEquals("latest success", duplicate.snapshot.lastSuccessAt)
        assertEquals("old error", duplicate.snapshot.lastError)
        assertEquals("older failure", duplicate.snapshot.lastErrorAt)
        assertEquals("ok at latest success", duplicate.snapshot.lastPollStatus)
        assertEquals(8L, duplicate.latestPollId)
    }

    @Test
    fun latestSuccessDoesNotHideUncertainOlderFailureFields() {
        val cache = PollHealthCache.fromRecentPage(
            sessionId = 42L,
            newestFirstRows = listOf(
                row(7L, ok = true, timestamp = "latest success"),
                row(6L, ok = true, timestamp = "older success")
            ),
            pageLimit = 2
        )

        val merged = cache.merge(row(8L, ok = true, timestamp = "newer success"))

        assertEquals("newer success", merged.snapshot.lastSuccessAt)
        assertTrue(merged.snapshot.historyMayBeIncomplete)
    }

    @Test
    fun emptySessionFilterFromFullGlobalWindowKeepsOlderHistoryUnknown() {
        val cache = PollHealthCache.fromRecentPage(
            sessionId = 42L,
            newestFirstRows = emptyList(),
            pageLimit = 128,
            globalPageFilled = true
        )

        assertEquals(42L, cache.snapshot.activeSessionId)
        assertEquals(null, cache.snapshot.lastPollStatus)
        assertTrue(cache.snapshot.historyMayBeIncomplete)
    }

    private fun row(
        pollId: Long,
        ok: Boolean,
        timestamp: String,
        errors: String? = null,
        errorCategory: String? = null,
        errorMessage: String? = null
    ) = PollHealthRow(
        pollId = pollId,
        timestamp = timestamp,
        ok = ok,
        errors = errors,
        errorCategory = errorCategory,
        errorMessage = errorMessage,
        elapsedMs = pollId,
        requestCount = pollId.toInt()
    )
}
