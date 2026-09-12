package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateHintLifetimeTest {
    private val lifetime = UpdateHintLifetime()

    @Test
    fun pendingHintHasNoDeadlineUntilActualAppearance() {
        lifetime.begin(resultId = 7L)

        assertEquals(7L, lifetime.activeResultId)
        assertEquals(0L, lifetime.expiresAtElapsedMs)
        assertFalse(lifetime.isExpired(nowElapsedMs = 50_000L))

        lifetime.shown(nowElapsedMs = 50_000L)

        assertEquals(60_000L, lifetime.expiresAtElapsedMs)
    }

    @Test
    fun hintOwnsTenSecondsFromFirstAppearance() {
        lifetime.begin(resultId = 7L)
        lifetime.shown(nowElapsedMs = 1_000L)

        assertFalse(lifetime.isExpired(nowElapsedMs = 10_999L))
        assertTrue(lifetime.isExpired(nowElapsedMs = 11_000L))
    }

    @Test
    fun relayoutAndAppearanceChangesDoNotRestartDeadline() {
        lifetime.begin(resultId = 7L)
        lifetime.shown(nowElapsedMs = 1_000L)

        lifetime.shown(nowElapsedMs = 5_000L)
        lifetime.begin(resultId = 7L)

        assertEquals(11_000L, lifetime.expiresAtElapsedMs)
    }

    @Test
    fun differentResultReplacesVisibleHintAsPending() {
        lifetime.begin(resultId = 7L)
        lifetime.shown(nowElapsedMs = 1_000L)

        lifetime.begin(resultId = 8L)

        assertEquals(8L, lifetime.activeResultId)
        assertEquals(0L, lifetime.expiresAtElapsedMs)
        assertFalse(lifetime.isExpired(nowElapsedMs = 20_000L))
    }

    @Test
    fun clearReturnsLifetimeToEmptyState() {
        lifetime.begin(resultId = 7L)
        lifetime.shown(nowElapsedMs = 1_000L)

        lifetime.clear()

        assertEquals(null, lifetime.activeResultId)
        assertEquals(0L, lifetime.expiresAtElapsedMs)
        assertFalse(lifetime.isExpired(nowElapsedMs = 20_000L))
    }

    @Test
    fun shownWithoutPendingHintDoesNothing() {
        lifetime.shown(nowElapsedMs = 1_000L)

        assertEquals(null, lifetime.activeResultId)
        assertEquals(0L, lifetime.expiresAtElapsedMs)
    }
}
