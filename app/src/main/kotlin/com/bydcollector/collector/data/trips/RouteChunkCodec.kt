package com.bydcollector.collector.data.trips

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** Bounded, deterministic, lossless route-point chunks. */
object RouteChunkCodec {
    const val MAX_UNCOMPRESSED_BYTES = 32 * 1024
    const val MAX_COMPRESSED_BYTES = 64 * 1024
    const val MAX_FIELD_UTF8_BYTES = 4 * 1024
    const val MAX_POINTS_PER_CHUNK = 1_024

    private const val MAGIC = 0x42594452 // "BYDR"
    private const val VERSION = 1
    private const val HEADER_BYTES = 4 + 1 + 4

    data class EncodedChunk(
        val tripId: String,
        val chunkIndex: Int,
        val firstSequence: Long,
        val lastSequence: Long,
        val pointCount: Int,
        val firstObservedAt: String,
        val lastObservedAt: String,
        val uncompressedBytes: Int,
        val compressedBytes: Int,
        val payload: ByteArray
    )

    /** Returns null when any point cannot fit a bounded lossless representation. */
    fun encodeChunks(points: List<RoutePoint>): List<EncodedChunk>? {
        if (points.isEmpty()) return emptyList()
        if (runCatching { validateOrder(points) }.isFailure) return null
        val chunks = ArrayList<EncodedChunk>()
        val current = ArrayList<RoutePoint>()
        var currentBytes = HEADER_BYTES
        var chunkIndex = 0
        for (point in points) {
            val pointBytes = encodedPointSize(point) ?: return null
            if (current.isNotEmpty() &&
                (current.size >= MAX_POINTS_PER_CHUNK || currentBytes + pointBytes > MAX_UNCOMPRESSED_BYTES)
            ) {
                    encodeChunk(chunkIndex++, current)?.let(chunks::add) ?: return null
                    current.clear()
                    currentBytes = HEADER_BYTES
            }
            current.add(point)
            currentBytes += pointBytes
            if (currentBytes > MAX_UNCOMPRESSED_BYTES) return null
        }
        encodeChunk(chunkIndex, current)?.let(chunks::add) ?: return null
        return chunks
    }

    fun decodeChunk(metadata: EncodedChunk, expectedTripId: String): List<RoutePoint> = buildList {
        forEachPoint(metadata, expectedTripId, ::add)
    }

    /** Encodes one bounded chunk for the streaming candidate copier. */
    internal fun encodeSingleChunk(index: Int, points: List<RoutePoint>): EncodedChunk? {
        if (points.isEmpty()) return null
        return runCatching {
            validateOrder(points)
            encodeChunk(index, points)
        }.getOrNull()
    }

    /** Returns the serialized size of one point, or null when bounded chunks cannot represent it. */
    internal fun encodedPointSize(point: RoutePoint): Int? = runCatching {
        validatePoint(point)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writePoint(it, point) }
        output.size()
    }.getOrNull()

    /** Decode one chunk without creating a list, enforcing all metadata bounds/order. */
    fun forEachPoint(
        metadata: EncodedChunk,
        expectedTripId: String,
        consumer: (RoutePoint) -> Unit,
    ) {
        require(metadata.tripId == expectedTripId) { "Chunk metadata trip mismatch" }
        require(metadata.chunkIndex >= 0) { "Negative chunk index" }
        require(metadata.firstSequence >= 0L) { "Negative chunk sequence" }
        require(metadata.pointCount in 1..MAX_POINTS_PER_CHUNK) { "Invalid chunk point count" }
        require(metadata.firstSequence <= Long.MAX_VALUE - metadata.pointCount + 1L) { "Chunk sequence overflow" }
        require(metadata.lastSequence == metadata.firstSequence + metadata.pointCount - 1) { "Invalid chunk sequence bounds" }
        require(metadata.uncompressedBytes in 1..MAX_UNCOMPRESSED_BYTES) { "Invalid uncompressed size" }
        require(metadata.compressedBytes in 1..MAX_COMPRESSED_BYTES) { "Invalid compressed size" }
        require(metadata.payload.size == metadata.compressedBytes) { "Compressed size mismatch" }

        val raw = decompress(metadata.payload)
        require(raw.size == metadata.uncompressedBytes) { "Uncompressed size mismatch" }
        val input = DataInputStream(ByteArrayInputStream(raw))
        try {
            require(input.readInt() == MAGIC) { "Unsupported route chunk" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported route chunk version" }
            require(input.readInt() == metadata.pointCount) { "Chunk point count mismatch" }
            var expectedSequence = metadata.firstSequence
            repeat(metadata.pointCount) {
                val point = readPoint(input)
                require(point.tripId == expectedTripId) { "Chunk trip mismatch" }
                require(point.sequence == expectedSequence) { "Chunk sequence order mismatch" }
                if (it == 0) require(point.observedAt == metadata.firstObservedAt) { "First observation bound mismatch" }
                if (it == metadata.pointCount - 1) require(point.observedAt == metadata.lastObservedAt) { "Last observation bound mismatch" }
                consumer(point)
                expectedSequence++
            }
            require(input.available() == 0) { "Trailing route chunk bytes" }
        } catch (e: EOFException) {
            throw IllegalArgumentException("Truncated route chunk", e)
        }
    }

    private fun encodeChunk(index: Int, points: List<RoutePoint>): EncodedChunk? {
        if (points.isEmpty() || points.size > MAX_POINTS_PER_CHUNK) return null
        val raw = encodeUncompressed(points)
        if (raw.size !in 1..MAX_UNCOMPRESSED_BYTES) return null
        val compressed = compress(raw)
        if (compressed.size !in 1..MAX_COMPRESSED_BYTES) return null
        val first = points.first()
        val last = points.last()
        return EncodedChunk(
            tripId = first.tripId,
            chunkIndex = index,
            firstSequence = first.sequence,
            lastSequence = last.sequence,
            pointCount = points.size,
            firstObservedAt = first.observedAt,
            lastObservedAt = last.observedAt,
            uncompressedBytes = raw.size,
            compressedBytes = compressed.size,
            payload = compressed
        )
    }

    private fun validateOrder(points: List<RoutePoint>) {
        val tripId = points.first().tripId
        require(tripId.isNotEmpty()) { "Route trip id must not be empty" }
        points.forEachIndexed { index, point ->
            validatePoint(point)
            require(point.tripId == tripId) { "Route points must share a trip" }
            if (index > 0) require(point.sequence == points[index - 1].sequence + 1) { "Route sequence is not contiguous" }
        }
    }

    private fun validatePoint(point: RoutePoint) {
        require(point.tripId.isNotEmpty()) { "Route trip id must not be empty" }
        require(point.kind == RoutePoint.KIND_VALID || point.kind == RoutePoint.KIND_GAP || point.kind == RoutePoint.KIND_UNTRUSTED) { "Unsupported route point kind" }
    }

    private fun encodeUncompressed(points: List<RoutePoint>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(VERSION)
            data.writeInt(points.size)
            points.forEach { writePoint(data, it) }
        }
        return output.toByteArray()
    }

    /** Canonical point serializer for Step 8 equality/digest checks; permits raw fallback fields. */
    internal fun writePointForDigest(data: DataOutput, point: RoutePoint) = writePoint(data, point, enforceFieldBound = false)

    private fun writePoint(data: DataOutput, point: RoutePoint, enforceFieldBound: Boolean = true) {
        writeString(data, point.tripId, enforceFieldBound)
        data.writeLong(point.sequence)
        writeString(data, point.kind, enforceFieldBound)
        writeString(data, point.observedAt, enforceFieldBound)
        writeNullableLong(data, point.elapsedMs)
        writeNullableLong(data, point.receiveWallTimeMs)
        writeNullableString(data, point.bootId, enforceFieldBound)
        writeNullableString(data, point.segmentId, enforceFieldBound)
        writeNullableDouble(data, point.latitude)
        writeNullableDouble(data, point.longitude)
        writeNullableDouble(data, point.accuracyM)
        writeNullableDouble(data, point.speedKmh)
        writeNullableDouble(data, point.instantaneousConsumptionKwhPer100Km)
        writeNullableDouble(data, point.altitudeM)
        writeNullableDouble(data, point.bearingDeg)
        writeString(data, point.quality, enforceFieldBound)
        data.writeBoolean(point.isFirst)
        data.writeBoolean(point.isFinal)
    }

    private fun readPoint(input: DataInputStream): RoutePoint = RoutePoint(
        tripId = readString(input),
        sequence = input.readLong(),
        kind = readString(input),
        observedAt = readString(input),
        elapsedMs = readNullableLong(input),
        receiveWallTimeMs = readNullableLong(input),
        bootId = readNullableString(input),
        segmentId = readNullableString(input),
        latitude = readNullableDouble(input),
        longitude = readNullableDouble(input),
        accuracyM = readNullableDouble(input),
        speedKmh = readNullableDouble(input),
        instantaneousConsumptionKwhPer100Km = readNullableDouble(input),
        altitudeM = readNullableDouble(input),
        bearingDeg = readNullableDouble(input),
        quality = readString(input),
        isFirst = input.readBoolean(),
        isFinal = input.readBoolean()
    )

    private fun writeString(data: DataOutput, value: String, enforceFieldBound: Boolean = true) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (enforceFieldBound) require(bytes.size <= MAX_FIELD_UTF8_BYTES) { "Route text field exceeds bound" }
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun writeNullableString(data: DataOutput, value: String?, enforceFieldBound: Boolean = true) {
        if (value == null) data.writeInt(-1) else writeString(data, value, enforceFieldBound)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_FIELD_UTF8_BYTES) { "Invalid route text length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return decodeUtf8(bytes)
    }

    private fun readNullableString(input: DataInputStream): String? {
        val length = input.readInt()
        if (length == -1) return null
        require(length in 0..MAX_FIELD_UTF8_BYTES) { "Invalid nullable route text length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return decodeUtf8(bytes)
    }

    private fun writeNullableLong(data: DataOutput, value: Long?) {
        data.writeBoolean(value != null)
        if (value != null) data.writeLong(value)
    }

    private fun readNullableLong(input: DataInputStream): Long? = if (input.readBoolean()) input.readLong() else null

    private fun writeNullableDouble(data: DataOutput, value: Double?) {
        data.writeBoolean(value != null)
        if (value != null) data.writeLong(java.lang.Double.doubleToRawLongBits(value))
    }

    private fun readNullableDouble(input: DataInputStream): Double? =
        if (input.readBoolean()) java.lang.Double.longBitsToDouble(input.readLong()) else null

    private fun compress(raw: ByteArray): ByteArray = ByteArrayOutputStream(raw.size).use { output ->
        DeflaterOutputStream(output).use { it.write(raw) }
        output.toByteArray()
    }

    private fun decompress(compressed: ByteArray): ByteArray {
        require(compressed.size in 1..MAX_COMPRESSED_BYTES) { "Compressed route chunk exceeds bound" }
        val inflater = Inflater()
        inflater.setInput(compressed)
        val output = ByteArrayOutputStream(minOf(MAX_UNCOMPRESSED_BYTES, compressed.size * 2))
        val buffer = ByteArray(8 * 1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    if (output.size() + count > MAX_UNCOMPRESSED_BYTES) throw IllegalArgumentException("Uncompressed route chunk exceeds bound")
                    output.write(buffer, 0, count)
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw IllegalArgumentException("Truncated route chunk compression")
                } else {
                    throw IllegalArgumentException("Invalid route chunk compression")
                }
            }
            require(inflater.remaining == 0) { "Trailing compressed route bytes" }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("Invalid UTF-8 route field", e)
    }
}
