package com.bydcollector.collector.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpsStartRetryGateTest {
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
