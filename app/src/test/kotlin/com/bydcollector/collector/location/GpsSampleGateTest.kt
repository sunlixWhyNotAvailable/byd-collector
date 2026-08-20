package com.bydcollector.collector.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpsSampleGateTest {
    @Test
    fun acceptsFirstAndThenAtMostOneChangedPointPerSecond() {
        val gate = GpsSampleGate()
        val first = sample(1_000_000_000L, 50.0)
        assertEquals(first, gate.offer(first))
        assertNull(gate.offer(sample(1_500_000_000L, 50.1)))
        assertEquals(50.1, gate.flushFinal()?.latitude)
        assertNull(gate.offer(sample(1_800_000_000L, 50.2)))
        assertEquals(50.2, gate.flushFinal()?.latitude)
    }

    @Test
    fun rejectsOutOfOrderSamples() {
        val gate = GpsSampleGate()
        assertEquals(50.0, gate.offer(sample(1_000_000_000L, 50.0))?.latitude)
        assertNull(gate.offer(sample(1_500_000_000L, 50.5)))
        assertNull(gate.offer(sample(900_000_000L, 51.0)))
        assertEquals(50.5, gate.flushFinal()?.latitude)
    }

    private fun sample(elapsed: Long, latitude: Double) = GpsLocationSample(
        observedAt = "2026-08-20T12:00:00Z", wallTimeMs = 1L, elapsedRealtimeNanos = elapsed,
        bootId = "boot", segmentId = "segment", latitude = latitude, longitude = 30.0,
        accuracyM = 5.0, speedMps = 10.0, altitudeM = null, bearingDeg = null
    )
}
