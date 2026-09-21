package com.bydcollector.collector.direct

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondarySpoolBinderContractTest {
    @Test
    fun secondaryTransactionsAreDistinctAndPagesLeaveRoomForBoundedMetadata() {
        assertEquals(11, CollectorHelperProtocol.PROTOCOL_VERSION)
        assertEquals(listOf(7, 8, 9), listOf(
            CollectorHelperProtocol.TX_SECONDARY_PENDING_PAGE,
            CollectorHelperProtocol.TX_SECONDARY_ACK,
            CollectorHelperProtocol.TX_SECONDARY_QUARANTINE
        ))
        assertTrue(SecondaryTelemetrySpool.MAX_SLICE_BYTES + 16 * 1024 <= SecondarySpoolBinder.MAX_REPLY_BYTES)
        assertEquals(256 * 1024, SecondarySpoolBinder.MAX_REPLY_BYTES)
    }

    @Test
    fun dispatchIsUidGuardedPassiveAndIndependentOfMainLeaseAndVendorReads() {
        val daemon = source("java/com/bydcollector/collector/direct/CollectorHelperDaemon.java")
        val uid = daemon.indexOf("Binder.getCallingUid() != appUid")
        val token = daemon.indexOf("data.enforceInterface(CollectorHelperProtocol.DESCRIPTOR)")
        val dispatch = daemon.indexOf("secondarySpoolBinder.onTransact(code, data, reply)")
        assertTrue(uid >= 0 && uid < token && token < dispatch)
        val secondary = source("java/com/bydcollector/collector/direct/SecondarySpoolBinder.java")
        assertFalse(secondary.contains("markConsumerHeartbeat"))
        assertFalse(secondary.contains("BatchEngine"))
        assertFalse(secondary.contains("spool.append("))
        assertTrue(secondary.contains("spool.readSlice(descriptor, offset, limit)"))
        assertTrue(secondary.contains("spool.acknowledge(descriptor)"))
        assertTrue(secondary.contains("spool.quarantine(descriptor, reason)"))
        assertTrue(daemon.contains("secondarySpoolBinder.close()"))
    }

    @Test
    fun clientValidatesPageIdentityBoundsAndRecyclesParcels() {
        val client = source("kotlin/com/bydcollector/collector/data/direct/DirectVehicleHelperClient.kt")
            .substringAfter("fun secondarySpoolPage(").substringBefore("fun pendingWorkerSamples(")
        assertTrue(client.contains("reply.dataSize() <= SecondarySpoolBinder.MAX_REPLY_BYTES"))
        assertTrue(client.contains("selected.sha256 == descriptor.sha256"))
        assertTrue(client.contains("selected.identity == descriptor.identity"))
        assertTrue(client.contains("returnedOffset == offset"))
        assertTrue(client.contains("data.recycle()"))
        assertTrue(client.contains("reply.recycle()"))
    }

    private fun source(relative: String): String = listOf(
        File("src/main/$relative"), File("app/src/main/$relative")
    ).first(File::isFile).readText()
}
