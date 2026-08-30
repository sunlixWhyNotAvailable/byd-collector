package com.bydcollector.collector.data.trips

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripRouteDiagnosticTest {
    @Test
    fun accumulatorPreservesFinalAndLatestValidProofForCompressedRoute() {
        val points = listOf(
            point(0L, kind = RoutePoint.KIND_VALID, isFinal = false),
            point(1L, kind = RoutePoint.KIND_GAP, isFinal = false),
            point(2L, kind = RoutePoint.KIND_UNTRUSTED, isFinal = false),
            point(3L, kind = RoutePoint.KIND_VALID, isFinal = true)
        )
        val chunks = requireNotNull(RouteChunkCodec.encodeChunks(points))
        val accumulator = TripRouteDiagnosticAccumulator("trip")
        chunks.forEach { chunk ->
            RouteChunkCodec.forEachPoint(chunk, "trip", accumulator::accept)
        }
        val evidence = accumulator.finish("chunks")

        assertEquals("chunks", evidence.storage)
        assertEquals(4L, evidence.pointCount)
        assertEquals(2L, evidence.validCount)
        assertEquals(1L, evidence.gapCount)
        assertEquals(1L, evidence.untrustedCount)
        assertEquals(1L, evidence.finalMarkerCount)
        assertEquals(3L, evidence.finalPoint?.sequence)
        assertEquals(3L, evidence.latestValidPoint?.sequence)
        assertTrue(evidence.finalIsLatestValid)
        assertTrue(evidence.sequenceContiguous)
        assertTrue(evidence.finalPoint?.hasCoordinate == true)
    }

    @Test
    fun sequenceContinuityRequiresThePersistedZeroOrigin() {
        val accumulator = TripRouteDiagnosticAccumulator("trip")
        accumulator.accept(point(5L, kind = RoutePoint.KIND_VALID, isFinal = true))
        assertFalse(accumulator.finish("raw").sequenceContiguous)
    }

    private fun point(sequence: Long, kind: String, isFinal: Boolean): RoutePoint = RoutePoint(
        tripId = "trip",
        sequence = sequence,
        kind = kind,
        observedAt = "2026-08-30T00:00:0${sequence}Z",
        latitude = if (kind == RoutePoint.KIND_GAP) null else 50.0 + sequence,
        longitude = if (kind == RoutePoint.KIND_GAP) null else 30.0 + sequence,
        quality = if (kind == RoutePoint.KIND_UNTRUSTED) "untrusted:test" else kind,
        isFinal = isFinal
    )
}
