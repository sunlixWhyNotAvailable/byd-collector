package com.bydcollector.collector.keepalive

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbKeepAliveTransportTest {
    @Test
    fun parsesRecordedUsbOnParcelAsBinderAcknowledgement() {
        val result = UsbKeepAliveTransport.parseUsbOnReply(
            "Result: Parcel(00000000 00000000   '........')\n"
        )

        assertTrue(result.valid)
        assertEquals(0, result.value)
        assertEquals(0L, result.exceptionCode)
        assertEquals(0L, result.returnedInt)
    }

    @Test
    fun acceptsOnlyDocumentedAccAndPowerRanges() {
        for (state in 0..1) {
            val result = UsbKeepAliveTransport.parseAccReply(parcel("00000000", hex(state)))
            assertTrue(result.valid)
            assertEquals(state, result.value)
        }
        for (level in 0..4) {
            val result = UsbKeepAliveTransport.parseBodyworkPowerLevelReply(parcel("00000000", hex(level)))
            assertTrue(result.valid)
            assertEquals(level, result.value)
        }

        val outOfRange = UsbKeepAliveTransport.parseBodyworkPowerLevelReply(parcel("00000000", "00000005"))
        assertFalse(outOfRange.valid)
        assertEquals(UsbKeepAliveTransport.INVALID_VALUE, outOfRange.value)
        assertEquals("value_out_of_range", outOfRange.error)
    }

    @Test
    fun malformedExceptionAndSentinelRepliesStayUnavailable() {
        val malformed = UsbKeepAliveTransport.parseAccReply("Result: Parcel(00000000 00000000 '........') extra")
        assertFalse(malformed.valid)
        assertEquals(UsbKeepAliveTransport.INVALID_VALUE, malformed.value)
        assertEquals("malformed_parcel", malformed.error)

        val exception = UsbKeepAliveTransport.parseAccReply(parcel("00000001", "00000000"))
        assertFalse(exception.valid)
        assertEquals(UsbKeepAliveTransport.INVALID_VALUE, exception.value)
        assertEquals(1L, exception.exceptionCode)
        assertEquals(0L, exception.returnedInt)
        assertEquals("binder_exception", exception.error)

        val sentinel = UsbKeepAliveTransport.parseAccReply(parcel("00000000", "FFFFFFFF"))
        assertFalse(sentinel.valid)
        assertEquals(UsbKeepAliveTransport.INVALID_VALUE, sentinel.value)
        assertEquals("value_out_of_range", sentinel.error)
    }

    @Test
    fun nonzeroUsbServiceResultIsNotAValidAcknowledgement() {
        val result = UsbKeepAliveTransport.parseUsbOnReply(parcel("00000000", "00000001"))

        assertFalse(result.valid)
        assertEquals(UsbKeepAliveTransport.INVALID_VALUE, result.value)
        assertEquals(1L, result.returnedInt)
        assertEquals("nonzero_service_result", result.error)
    }

    private fun parcel(exception: String, returnedInt: String) =
        "Result: Parcel($exception $returnedInt '........')"

    private fun hex(value: Int) = "%08X".format(value)
}
