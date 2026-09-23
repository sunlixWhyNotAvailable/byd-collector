package com.bydcollector.collector.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecondarySpoolBinderContractTest {
    @Test
    fun secondaryTransactionsAreDistinctAndPagesLeaveRoomForBoundedMetadata() {
        assertEquals(13, CollectorHelperProtocol.PROTOCOL_VERSION)
        assertEquals(listOf(7, 8, 9), listOf(
            CollectorHelperProtocol.TX_SECONDARY_PENDING_PAGE,
            CollectorHelperProtocol.TX_SECONDARY_ACK,
            CollectorHelperProtocol.TX_SECONDARY_QUARANTINE
        ))
        assertTrue(SecondaryTelemetrySpool.MAX_SLICE_BYTES + 16 * 1024 <= SecondarySpoolBinder.MAX_REPLY_BYTES)
        assertEquals(256 * 1024, SecondarySpoolBinder.MAX_REPLY_BYTES)
    }

}
