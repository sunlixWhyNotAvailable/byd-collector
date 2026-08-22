package com.bydcollector.collector.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpsTrustGateTest {
    @Test
    fun rejectsMockFutureClockAndTeleport() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0)).trusted)
        assertEquals("mock_source", gate.offer(sample(2_000_000_000L, 50.0, isMock = true)).reason)
        assertEquals("source_clock_skew", gate.offer(sample(3_000_000_000L, 50.0, wall = 49_000_000_000L, receiveWall = 3_000L)).reason)
        val continuityGate = GpsTrustGate()
        assertTrue(continuityGate.offer(sample(1_000_000_000L, 50.0)).trusted)
        assertEquals("continuity_speed", continuityGate.offer(sample(2_000_000_000L, 61.0)).reason)
    }

    @Test
    fun saneClockMarginsAreAccepted() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0, wall = 10_000L, receiveWall = 12_500L, receiveElapsed = 3_200_000_000L)).trusted)
    }

    @Test
    fun rejectionAndCallbackGapRequireThreeRecoveryFixes() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0)).trusted)
        assertFalse(gate.offer(sample(2_000_000_000L, 50.0, isMock = true)).trusted)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(3_000_000_000L, 50.0001)).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(4_000_000_000L, 50.0002)).status)
        assertTrue(gate.offer(sample(5_000_000_000L, 50.0003)).trusted)
        assertTrue(gate.offer(sample(12_000_000_000L, 50.0004, receiveElapsed = 12_000_000_000L)).pending)
    }

    @Test
    fun initialRejectedFixAlsoNeedsThreeRecoveryFixes() {
        val gate = GpsTrustGate()
        assertEquals(GpsTrustStatus.REJECTED, gate.offer(sample(1_000_000_000L, 50.0, isMock = true)).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(2_000_000_000L, 50.0)).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(3_000_000_000L, 50.0001)).status)
        assertTrue(gate.offer(sample(4_000_000_000L, 50.0002)).trusted)
    }

    @Test
    fun recoveryClusterIsNotComparedToRejectedOldAnchor() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0)).trusted)
        assertEquals("continuity_speed", gate.offer(sample(2_000_000_000L, 61.0)).reason)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(3_000_000_000L, 50.1)).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(4_000_000_000L, 50.1001)).status)
        assertTrue(gate.offer(sample(5_000_000_000L, 50.1002)).trusted)
    }

    @Test
    fun restoredOpenSessionRequiresThreeFreshFixes() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0)).trusted)

        gate.beginRecovery()

        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(2_000_000_000L, 50.0001)).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(3_000_000_000L, 50.0002)).status)
        assertTrue(gate.offer(sample(4_000_000_000L, 50.0003)).trusted)
    }

    private fun sample(
        elapsed: Long,
        latitude: Double,
        wall: Long = 1_000L + elapsed / 1_000_000L,
        receiveWall: Long = wall,
        receiveElapsed: Long = elapsed,
        isMock: Boolean = false
    ) = GpsLocationSample(
        observedAt = "2026-08-20T12:00:00Z",
        wallTimeMs = wall,
        elapsedRealtimeNanos = elapsed,
        bootId = "boot",
        segmentId = "segment",
        latitude = latitude,
        longitude = 30.0,
        accuracyM = 5.0,
        speedMps = 10.0,
        altitudeM = null,
        bearingDeg = null,
        receiveWallTimeMs = receiveWall,
        receiveElapsedRealtimeNanos = receiveElapsed,
        isMock = isMock
    )
}
