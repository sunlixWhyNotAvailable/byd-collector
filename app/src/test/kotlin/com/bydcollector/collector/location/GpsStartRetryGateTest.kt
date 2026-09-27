package com.bydcollector.collector.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpsStartRetryGateTest {
    @Test
    fun providerQueryAndRequestFailuresShareTheRetryGateWithoutFalseDisabledState() {
        val gate = GpsStartRetryGate()
        var requests = 0
        val queryError = attemptGpsStart(true, { throw NullPointerException("Binder provider") }, { requests++ })
        assertEquals("gps_provider_query_failed:NullPointerException", queryError.failureReason)
        assertEquals(null, queryGpsProvider { throw SecurityException("provider") }.getOrNull())
        assertTrue(queryError.recordGap)
        assertEquals(0, requests)
        assertTrue(gate.onFailure(0))
        assertFalse(gate.canAttempt(999))
        assertTrue(gate.canAttempt(1_000))
        val requestError = attemptGpsStart(true, { true }, { requests++; throw IllegalStateException("request") })
        assertEquals("gps_request_failed:IllegalStateException", requestError.failureReason)
        assertFalse(gate.onFailure(1_000))
        assertFalse(gate.canAttempt(2_999))
        assertTrue(gate.canAttempt(3_000))
        // Provider re-enable retries immediately but must retain recovery evidence.
        gate.reset(preserveOutage = true)
        assertTrue(gate.canAttempt(1_500))
        assertTrue(gate.hasFailures)
        assertTrue(attemptGpsStart(true, { true }, { requests++ }).started)
        assertTrue(gate.hasFailures)
        gate.reset()
        assertFalse(gate.hasFailures)
        assertTrue(gate.canAttempt(3_000))
        assertEquals(2, requests)
        assertEquals("gps_provider_disabled", attemptGpsStart(true, { false }, { error("must not request") }).failureReason)
        assertEquals("gps_permission_missing", attemptGpsStart(false, { error("must not query") }, {}).failureReason)
    }

    @Test
    fun oneHundredUnavailablePollsProduceOneReportAndBoundedAttempts() {
        val gate = GpsStartRetryGate()
        val attempts = mutableListOf<Long>()
        var reports = 0

        repeat(100) { poll ->
            val nowMs = poll * 500L
            if (gate.canAttempt(nowMs)) {
                attempts += nowMs
                if (gate.onFailure(nowMs)) reports += 1
            }
        }

        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L), attempts)
        assertEquals(1, reports)
    }

    @Test
    fun resetMakesRetryImmediateAndStartsANewOutage() {
        val gate = GpsStartRetryGate()
        assertTrue(gate.onFailure(0L))
        assertFalse(gate.canAttempt(500L))

        gate.reset()

        assertTrue(gate.canAttempt(500L))
        assertTrue(gate.onFailure(500L))
    }
}
