package com.bydcollector.collector.direct

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TelemetryCallbackBatchTest {
    private fun event(
        sequence: Long,
        type: Int = TelemetryCallbackBatch.TYPE_INT,
        raw: Int = 2,
        bytes: ByteArray? = null
    ) = TelemetryCallbackBatch.Event(sequence, 1001, 315621418, type, raw, bytes,
        1_790_000_000_000L + sequence, 1000 + sequence, null, "usable")

    private fun batch(vararg events: TelemetryCallbackBatch.Event) =
        TelemetryCallbackBatch("boot", "generation", 1, 2, 3, events.toList())

    @Test fun `round trip preserves repeated integers float bits bytes and receipt clocks`() {
        val floatBits = 0x7fc01234
        val original = batch(event(1), event(2), event(3, TelemetryCallbackBatch.TYPE_FLOAT, floatBits),
            event(4, TelemetryCallbackBatch.TYPE_BYTES, bytes = byteArrayOf(0, -1, 127)))
        val encoded = original.encode()
        val decoded = TelemetryCallbackBatch.decode(encoded)
        assertContentEquals(encoded, decoded.encode())
        assertEquals(4, decoded.events.size)
        assertEquals(floatBits, decoded.events[2].rawBits)
        assertContentEquals(byteArrayOf(0, -1, 127), decoded.events[3].rawBytes())
        assertEquals(1004L, decoded.events[3].receivedElapsedMs)
        assertNull(decoded.events[3].sourceWallMs)
        assertEquals(original.identity(), decoded.identity())
        assertEquals(TelemetryCallbackBatch.digest(encoded), TelemetryCallbackBatch.digest(decoded.encode()))
    }

    @Test fun `evidence payload cannot be changed through caller arrays`() {
        val bytes = byteArrayOf(1, 2, 3)
        val item = event(1, TelemetryCallbackBatch.TYPE_BYTES, bytes = bytes)
        bytes[0] = 99
        val returned = item.rawBytes()!!
        returned[1] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), item.rawBytes())
    }

    @Test fun `identity components cannot collide through delimiter injection`() {
        val a = TelemetryCallbackBatch("a:b", "c", 1, 2, 3, listOf(event(1)))
        val b = TelemetryCallbackBatch("a", "b:c", 1, 2, 3, listOf(event(1)))
        assertNotEquals(a.identity(), b.identity())
        assertNotEquals(a.eventIdentity(a.events.single()), b.eventIdentity(b.events.single()))
    }

    @Test fun `invalid count order type and native payload are rejected`() {
        assertFailsWith<IllegalArgumentException> { batch() }
        assertFailsWith<IllegalArgumentException> { batch(event(2), event(1)) }
        assertFailsWith<IllegalArgumentException> { batch(event(1), event(1)) }
        assertFailsWith<IllegalArgumentException> { batch(*(0..512).map { event(it.toLong()) }.toTypedArray()) }
        assertFailsWith<IllegalArgumentException> { event(1, 4) }
        assertFailsWith<IllegalArgumentException> { event(1, TelemetryCallbackBatch.TYPE_BYTES) }
        assertFailsWith<IllegalArgumentException> { event(1, bytes = byteArrayOf(1)) }
    }

    @Test fun `truncated trailing malformed and over-cap wire records are rejected`() {
        val encoded = batch(event(1)).encode()
        assertFailsWith<IOException> { TelemetryCallbackBatch.decode(encoded.copyOf(encoded.size - 1)) }
        assertFailsWith<IOException> { TelemetryCallbackBatch.decode(encoded + byteArrayOf(0)) }
        assertFailsWith<IOException> { TelemetryCallbackBatch.decode(encoded.clone().also { it[0] = 0 }) }
        assertFailsWith<IOException> { TelemetryCallbackBatch.decode(ByteArray(TelemetryCallbackBatch.MAX_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> {
            batch(event(1, TelemetryCallbackBatch.TYPE_BYTES,
                bytes = ByteArray(TelemetryCallbackBatch.MAX_BYTES - 2048)), event(2, TelemetryCallbackBatch.TYPE_BYTES,
                bytes = ByteArray(2048)))
        }
    }

    @Test fun `known source timestamp stays distinct from local receipt`() {
        val item = TelemetryCallbackBatch.Event(1, 1001, 10, TelemetryCallbackBatch.TYPE_INT, 5,
            null, 2000, 1000, 1500L, "usable")
        val decoded = TelemetryCallbackBatch.decode(batch(item).encode()).events.single()
        assertEquals(1500L, decoded.sourceWallMs)
        assertEquals(2000L, decoded.receivedWallMs)
    }
}
