package com.bydcollector.collector.direct

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackValueSourceTest {
    @Test fun `cache provenance roundtrips original clocks and exact float bits`() {
        val source = source()
        val decoded = decode(encode(source))!!
        assertEquals(source.bootId, decoded.bootId)
        assertEquals(source.helperGeneration, decoded.helperGeneration)
        assertEquals(2, decoded.stream)
        assertEquals(7L, decoded.epoch)
        assertEquals(81L, decoded.eventSequence)
        assertEquals(10000L, decoded.receivedWallMs)
        assertEquals(2000L, decoded.receivedElapsedMs)
        assertNull(decoded.sourceWallMs)
        assertEquals(0x7fc01234, decoded.rawBits)
        assertTrue(decoded.matches(7, 1013, 42, 0x7fc01234))
        assertFalse(decoded.matches(5, 1013, 42, 0x7fc01234))
        assertFalse(decoded.matches(7, 1013, 43, 0x7fc01234))
        assertFalse(decoded.matches(7, 1013, 42, 0))
    }

    @Test fun `null means a genuine getter not synthetic callback provenance`() {
        assertNull(decode(encode(null)))
    }

    @Test fun `malformed marker truncated input and bytes type cannot become scalar cache`() {
        assertFailsWith<IOException> { decode(byteArrayOf(2)) }
        assertFailsWith<IOException> { decode(encode(source()).dropLast(1).toByteArray()) }
        assertFailsWith<IllegalArgumentException> {
            CallbackValueSource("boot", "generation", 1, 0, 0, 1, 2,
                TelemetryCallbackBatch.TYPE_BYTES, 0, 1, 1, null, "ok")
        }
    }

    private fun source() = CallbackValueSource("boot", "generation", 2, 7, 81, 1013, 42,
        TelemetryCallbackBatch.TYPE_FLOAT, 0x7fc01234, 10000, 2000, null, "ok")

    private fun encode(value: CallbackValueSource?): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { CallbackValueSource.writeNullable(it, value) }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): CallbackValueSource? = DataInputStream(ByteArrayInputStream(bytes)).use {
        CallbackValueSource.readNullable(it).also { _ -> assertEquals(0, it.available()) }
    }
}
