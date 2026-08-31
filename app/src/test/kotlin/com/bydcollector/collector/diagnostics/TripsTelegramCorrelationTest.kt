package com.bydcollector.collector.diagnostics

import com.bydcollector.collector.data.trips.TripSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TripsTelegramCorrelationTest {
    @Test
    fun pendingLegLooksUpExactPowerParentForOpenAndClosedSessions() {
        listOf(TripSession.STATE_OPEN, TripSession.STATE_CLOSED).forEach { state ->
            val calls = mutableListOf<String>()
            val result = selectDiagnosticTrip(
                pendingTripId = "telegram-leg",
                pendingPowerSessionId = "power-parent",
                findByPowerSessionId = { parent ->
                    calls += parent
                    TripSession(parent, state, "2026-08-31T00:00:00Z")
                },
                findNewestClosed = { error("newest fallback must not run") }
            )

            assertEquals("pending", result.selection)
            assertEquals("power-parent", result.session?.tripId)
            assertEquals(state, result.session?.state)
            assertEquals(listOf("power-parent"), calls)
        }
    }

    @Test
    fun pendingLegacyLegIsUnlinkedWithoutAnyTripsQuery() {
        var byIdCalls = 0
        var newestCalls = 0
        val result = selectDiagnosticTrip(
            pendingTripId = "telegram-leg",
            pendingPowerSessionId = null,
            findByPowerSessionId = {
                byIdCalls += 1
                null
            },
            findNewestClosed = {
                newestCalls += 1
                null
            }
        )

        assertEquals("pending_unlinked", result.selection)
        assertNull(result.session)
        assertEquals(0, byIdCalls)
        assertEquals(0, newestCalls)
    }

    @Test
    fun missingKnownParentDoesNotFallBackToNewestClosed() {
        var newestCalls = 0
        val result = selectDiagnosticTrip(
            pendingTripId = "telegram-leg",
            pendingPowerSessionId = "missing-parent",
            findByPowerSessionId = { null },
            findNewestClosed = {
                newestCalls += 1
                TripSession("newest", TripSession.STATE_CLOSED, "2026-08-31T00:00:00Z")
            }
        )

        assertEquals("pending_missing", result.selection)
        assertNull(result.session)
        assertEquals(0, newestCalls)
    }

    @Test
    fun noPendingLegKeepsNewestClosedSelection() {
        val result = selectDiagnosticTrip(
            pendingTripId = null,
            pendingPowerSessionId = null,
            findByPowerSessionId = { error("parent lookup must not run") },
            findNewestClosed = {
                TripSession("newest", TripSession.STATE_CLOSED, "2026-08-31T00:00:00Z")
            }
        )

        assertEquals("newest_closed", result.selection)
        assertEquals("newest", result.session?.tripId)
    }

    @Test
    fun parentReadFailureRemainsDistinguishable() {
        assertFailsWith<IllegalStateException> {
            selectDiagnosticTrip(
                pendingTripId = "telegram-leg",
                pendingPowerSessionId = "power-parent",
                findByPowerSessionId = { error("read failed") },
                findNewestClosed = { error("newest fallback must not run") }
            )
        }
    }
}
