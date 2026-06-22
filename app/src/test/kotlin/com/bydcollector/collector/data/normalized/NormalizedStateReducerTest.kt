package com.bydcollector.collector.data.normalized

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizedStateReducerTest {
    @Test
    fun sameValueUpdatesObservedAtWithoutHistoryInsertAndPreservesChangedAt() {
        val previous = storedState(
            valueNumber = 73.0,
            quality = "OK",
            observedAt = "2026-06-12T12:00:00+03:00",
            changedAt = "2026-06-12T12:00:00+03:00"
        )
        val next = storedState(
            valueNumber = 73.0,
            quality = "OK",
            observedAt = "2026-06-12T12:00:05+03:00",
            changedAt = "2026-06-12T12:00:05+03:00"
        )

        val decision = NormalizedStateReducer.decide(previous, next)

        assertFalse(decision.insertHistory)
        assertEquals("2026-06-12T12:00:05+03:00", decision.current.observedAt)
        assertEquals("2026-06-12T12:00:00+03:00", decision.current.changedAt)
    }

    @Test
    fun changedValueInsertsHistoryAndUpdatesChangedAtToNextObservedAt() {
        val previous = storedState(
            valueNumber = 73.0,
            quality = "OK",
            observedAt = "2026-06-12T12:00:00+03:00",
            changedAt = "2026-06-12T12:00:00+03:00"
        )
        val next = storedState(
            valueNumber = 74.0,
            quality = "OK",
            observedAt = "2026-06-12T12:00:05+03:00",
            changedAt = "2026-06-12T12:00:05+03:00"
        )

        val decision = NormalizedStateReducer.decide(previous, next)

        assertTrue(decision.insertHistory)
        assertEquals("2026-06-12T12:00:05+03:00", decision.current.observedAt)
        assertEquals("2026-06-12T12:00:05+03:00", decision.current.changedAt)
    }

    @Test
    fun sameValueWithDifferentQualityInsertsHistory() {
        val previous = storedState(
            valueNumber = null,
            quality = "MISSING",
            observedAt = "2026-06-12T12:00:00+03:00",
            changedAt = "2026-06-12T12:00:00+03:00"
        )
        val next = storedState(
            valueNumber = null,
            quality = "INVALID",
            observedAt = "2026-06-12T12:00:05+03:00",
            changedAt = "2026-06-12T12:00:05+03:00"
        )

        val decision = NormalizedStateReducer.decide(previous, next)

        assertTrue(decision.insertHistory)
        assertEquals("2026-06-12T12:00:05+03:00", decision.current.changedAt)
    }

    @Test
    fun staleTransitionPreservesObservedAtAndSetsChangedAtToStaleTime() {
        val previous = storedState(
            valueNumber = 73.0,
            quality = "OK",
            observedAt = "2026-06-12T12:00:00+03:00",
            changedAt = "2026-06-12T12:00:00+03:00"
        )

        val stale = NormalizedStateReducer.markStale(
            previous = previous,
            nowIso = "2026-06-12T12:05:00+03:00"
        )

        assertEquals("2026-06-12T12:00:00+03:00", stale.observedAt)
        assertEquals("2026-06-12T12:05:00+03:00", stale.changedAt)
        assertEquals("STALE", stale.quality)
    }

    private fun storedState(
        valueNumber: Double?,
        quality: String,
        observedAt: String,
        changedAt: String
    ): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = "soc",
            category = "battery",
            valueType = "NUMBER",
            valueText = null,
            valueNumber = valueNumber,
            valueBool = null,
            quality = quality,
            unit = "%",
            sourcePollId = 42L,
            sourceKeys = "statistic_1014_1145045040_5",
            observedAt = observedAt,
            changedAt = changedAt
        )
    }
}
