package com.bydcollector.collector.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun persistedAnchorRejectsStableDistantRecoveryCluster() {
        val anchor = sample(1_000_000_000L, 50.0)
        val gate = GpsTrustGate()
        gate.beginRecovery(anchor)

        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(2_000_000_000L, 61.0)).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(3_000_000_000L, 61.0001)).status)
        assertEquals("recovery_anchor_speed", gate.offer(sample(4_000_000_000L, 61.0002)).reason)
        assertEquals("recovery_anchor_speed", gate.offer(sample(5_000_000_000L, 61.0003)).reason)
        assertEquals(anchor, gate.lastTrusted())
    }

    @Test
    fun restoredOpenSessionRequiresThreeFreshFixes() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0)).trusted)
        val anchor = gate.lastTrusted()

        gate.beginRecovery(anchor)

        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(2_000_000_000L, 50.0001)).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(3_000_000_000L, 50.0002)).status)
        assertTrue(gate.offer(sample(4_000_000_000L, 50.0003)).trusted)
    }

    @Test
    fun plausibleLongGapRelocationUsesSameBootMonotonicClock() {
        val gate = GpsTrustGate()
        gate.beginRecovery(sample(1_000_000_000L, 50.0))

        assertTrue(gate.offer(sample(300_000_000_000L, 50.1)).pending)
        assertTrue(gate.offer(sample(301_000_000_000L, 50.10001)).pending)
        assertTrue(gate.offer(sample(302_000_000_000L, 50.10002)).trusted)
    }

    @Test
    fun plausibleLongGapRelocationUsesReceiveWallClockAcrossBoots() {
        val gate = GpsTrustGate()
        gate.beginRecovery(sample(500_000_000_000L, 50.0, wall = 1_000_000L, receiveWall = 1_000_000L, bootId = "old-boot"))

        assertTrue(gate.offer(sample(1_000_000_000L, 51.0, wall = 4_601_000L, receiveWall = 4_601_000L, bootId = "new-boot")).pending)
        assertTrue(gate.offer(sample(2_000_000_000L, 51.00001, wall = 4_602_000L, receiveWall = 4_602_000L, bootId = "new-boot")).pending)
        assertTrue(gate.offer(sample(3_000_000_000L, 51.00002, wall = 4_603_000L, receiveWall = 4_603_000L, bootId = "new-boot")).trusted)
    }

    @Test
    fun legacySameBootAnchorWithoutElapsedTimeFallsBackToReceiveWallClock() {
        val gate = GpsTrustGate()
        gate.beginRecovery(sample(0L, 50.0, wall = 1_000_000L, receiveWall = 1_000_000L))

        assertTrue(gate.offer(sample(1_000_000_000L, 51.0, wall = 4_601_000L, receiveWall = 4_601_000L)).pending)
        assertTrue(gate.offer(sample(2_000_000_000L, 51.00001, wall = 4_602_000L, receiveWall = 4_602_000L)).pending)
        assertTrue(gate.offer(sample(3_000_000_000L, 51.00002, wall = 4_603_000L, receiveWall = 4_603_000L)).trusted)
    }

    @Test
    fun reportedGpsSpeedMismatchIsRejected() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 0.0).trusted)

        assertEquals(
            "vehicle_speed_mismatch",
            gate.offer(sample(2_000_000_000L, 50.0, speedMps = 20.0), vehicleSpeedKmh = 0.0).reason
        )
    }

    @Test
    fun pairwiseImpliedGpsSpeedMismatchIsRejectedWhenReportedSpeedIsAbsent() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 0.0).trusted)

        assertEquals(
            "vehicle_speed_mismatch",
            gate.offer(sample(2_000_000_000L, 50.001, speedMps = null), vehicleSpeedKmh = 0.0).reason
        )
    }

    @Test
    fun gpsSpeedAtCanPlusFortyIsAccepted() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 60.0).trusted)

        assertTrue(gate.offer(sample(2_000_000_000L, 50.0, speedMps = 100.0 / 3.6), vehicleSpeedKmh = 60.0).trusted)
    }

    @Test
    fun missingCanSkipsOnlyNewVeto() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0, speedMps = 0.0)).trusted)

        assertTrue(gate.offer(sample(2_000_000_000L, 50.0, speedMps = 200.0)).trusted)
    }

    @Test
    fun callbackGapStartsNewGpsSpeedSequenceThenChecksFollowingPair() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 0.0).trusted)

        val firstAfterGap = gate.offer(
            sample(10_000_000_000L, 50.001, speedMps = null, receiveElapsed = 10_000_000_000L),
            vehicleSpeedKmh = 0.0
        )
        assertEquals(GpsTrustStatus.PENDING, firstAfterGap.status)
        assertEquals("recovery_pending", firstAfterGap.reason)

        assertEquals(
            "vehicle_speed_mismatch",
            gate.offer(
                sample(11_000_000_000L, 50.002, speedMps = null, receiveElapsed = 11_000_000_000L),
                vehicleSpeedKmh = 0.0
            ).reason
        )
    }

    @Test
    fun vehicleSpeedMismatchStillRequiresThreeRecoveryFixes() {
        val gate = GpsTrustGate()
        assertTrue(gate.offer(sample(1_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 0.0).trusted)
        assertEquals(
            "vehicle_speed_mismatch",
            gate.offer(sample(2_000_000_000L, 50.0, speedMps = 20.0), vehicleSpeedKmh = 0.0).reason
        )

        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(3_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 0.0).status)
        assertEquals(GpsTrustStatus.PENDING, gate.offer(sample(4_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 0.0).status)
        assertTrue(gate.offer(sample(5_000_000_000L, 50.0, speedMps = 0.0), vehicleSpeedKmh = 0.0).trusted)
    }

    @Test
    fun vehicleSpeedReferenceKeepsMaxFreshFiniteNonnegativeValue() {
        var nowMs = 1_000L
        val reference = VehicleSpeedReference(nowElapsedMs = { nowMs })
        reference.observe(20.0)
        reference.observe(80.0)
        reference.observe(-1.0)
        reference.observe(Double.NaN)
        reference.observe(Double.POSITIVE_INFINITY)

        assertEquals(80.0, reference.current())
        nowMs = 3_001L
        assertNull(reference.current())
    }

    private fun sample(
        elapsed: Long,
        latitude: Double,
        wall: Long = 1_000L + elapsed / 1_000_000L,
        receiveWall: Long = wall,
        receiveElapsed: Long = elapsed,
        isMock: Boolean = false,
        bootId: String = "boot",
        speedMps: Double? = 10.0
    ) = GpsLocationSample(
        observedAt = "2026-08-20T12:00:00Z",
        wallTimeMs = wall,
        elapsedRealtimeNanos = elapsed,
        bootId = bootId,
        segmentId = "segment",
        latitude = latitude,
        longitude = 30.0,
        accuracyM = 5.0,
        speedMps = speedMps,
        altitudeM = null,
        bearingDeg = null,
        receiveWallTimeMs = receiveWall,
        receiveElapsedRealtimeNanos = receiveElapsed,
        isMock = isMock
    )
}
