package com.bydcollector.collector.data.trips

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteChunkCodecTest {
    @Test
    fun roundTripPreservesNullsRawDoubleBitsKindsAndFlags() {
        val points = listOf(
            point(0L, RoutePoint.KIND_VALID, isFirst = true, latitude = -0.0, altitudeM = Double.NaN),
            point(1L, RoutePoint.KIND_GAP, latitude = null, longitude = null, accuracyM = null),
            point(2L, RoutePoint.KIND_UNTRUSTED, isFinal = true, quality = "untrusted:raw")
        )

        val encoded = assertNotNull(RouteChunkCodec.encodeChunks(points)).single()
        val decoded = RouteChunkCodec.decodeChunk(encoded, "trip")

        assertEquals(points, decoded)
        assertEquals(java.lang.Double.doubleToRawLongBits(-0.0), java.lang.Double.doubleToRawLongBits(decoded[0].latitude!!))
        assertEquals(java.lang.Double.doubleToRawLongBits(Double.NaN), java.lang.Double.doubleToRawLongBits(decoded[0].altitudeM!!))
        assertNull(decoded[1].longitude)
        assertTrue(decoded[0].isFirst)
        assertTrue(decoded[2].isFinal)
    }

    @Test
    fun routeIsSplitAtBoundAndStreamingMatchesDecode() {
        val points = (0L until 800L).map { point(it, quality = "q-${it % 7}") }
        val chunks = assertNotNull(RouteChunkCodec.encodeChunks(points))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.uncompressedBytes <= RouteChunkCodec.MAX_UNCOMPRESSED_BYTES })
        assertTrue(chunks.all { it.compressedBytes <= RouteChunkCodec.MAX_COMPRESSED_BYTES })
        assertEquals(points, chunks.flatMap { RouteChunkCodec.decodeChunk(it, "trip") })

        val streamed = mutableListOf<RoutePoint>()
        chunks.forEach { RouteChunkCodec.forEachPoint(it, "trip", streamed::add) }
        assertEquals(points, streamed)
    }

    @Test
    fun oversizedSinglePointFallsBackToRaw() {
        val oversized = point(0L, quality = "x".repeat(RouteChunkCodec.MAX_FIELD_UTF8_BYTES + 1))
        assertNull(RouteChunkCodec.encodeChunks(listOf(oversized)))
    }

    @Test
    fun trailingCompressedDataAndMetadataMismatchAreRejected() {
        val chunk = assertNotNull(RouteChunkCodec.encodeChunks(listOf(point(0L)))).single()
        val payload = chunk.payload + byteArrayOf(0x01)
        val trailing = chunk.copy(payload = payload, compressedBytes = payload.size)
        assertFailsWith<IllegalArgumentException> { RouteChunkCodec.decodeChunk(trailing, "trip") }
        assertFailsWith<IllegalArgumentException> { RouteChunkCodec.decodeChunk(chunk.copy(lastSequence = 7L), "trip") }
        assertFailsWith<IllegalArgumentException> { RouteChunkCodec.decodeChunk(chunk, "other") }
    }

    @Test
    fun digestSerializerKeepsUtf8BytesBeyondChunkFieldBound() {
        val point = point(0L, quality = "я".repeat(RouteChunkCodec.MAX_FIELD_UTF8_BYTES + 1))
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { RouteChunkCodec.writePointForDigest(it, point) }
        assertTrue(bytes.toByteArray().contains(0xD1.toByte()))
        assertFalse(RouteChunkCodec.encodeChunks(listOf(point)) != null)
    }

    private fun point(
        sequence: Long,
        kind: String = RoutePoint.KIND_VALID,
        isFirst: Boolean = false,
        isFinal: Boolean = false,
        latitude: Double? = 50.0,
        longitude: Double? = 30.0,
        accuracyM: Double? = 1.25,
        altitudeM: Double? = 4.5,
        quality: String = "ok"
    ) = RoutePoint(
        tripId = "trip",
        sequence = sequence,
        kind = kind,
        observedAt = "2026-08-27T00:00:${sequence.toString().padStart(2, '0')}Z",
        elapsedMs = sequence * 200L,
        receiveWallTimeMs = sequence * 300L,
        bootId = if (kind == RoutePoint.KIND_GAP) null else "boot",
        segmentId = if (kind == RoutePoint.KIND_GAP) null else "segment",
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        speedKmh = if (kind == RoutePoint.KIND_GAP) null else 12.5,
        instantaneousConsumptionKwhPer100Km = if (kind == RoutePoint.KIND_GAP) null else -0.0,
        altitudeM = altitudeM,
        bearingDeg = if (kind == RoutePoint.KIND_GAP) null else 270.0,
        quality = quality,
        isFirst = isFirst,
        isFinal = isFinal
    )
}
