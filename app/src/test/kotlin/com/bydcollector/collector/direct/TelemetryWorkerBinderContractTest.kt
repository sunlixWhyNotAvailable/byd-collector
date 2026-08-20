package com.bydcollector.collector.direct

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryWorkerBinderContractTest {
    @Test
    fun protocolExposesBoundedPendingAndPostCommitAckWithoutStartingAPoller() {
        val daemon = source("com/bydcollector/collector/direct/CollectorHelperDaemon.java")
        val client = source("com/bydcollector/collector/data/direct/DirectVehicleHelperClient.kt")

        assertEquals(5, CollectorHelperProtocol.PROTOCOL_VERSION)
        assertEquals(100, CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES)
        assertEquals(128, CollectorHelperProtocol.MAX_WORKER_FIELD_COUNT)
        assertTrue(daemon.contains("Binder.getCallingUid() != appUid"))
        assertTrue(daemon.contains("code == CollectorHelperProtocol.TX_WORKER_PENDING"))
        assertTrue(daemon.contains("code == CollectorHelperProtocol.TX_WORKER_ACK"))
        assertTrue(client.contains("binder.transact(CollectorHelperProtocol.TX_WORKER_PENDING"))
        assertTrue(client.contains("binder.transact(CollectorHelperProtocol.TX_WORKER_ACK"))
        assertTrue(client.contains("fieldIndex == expectedIndex"))
        assertFalse(daemon.contains("workerSpool.append("))
        assertFalse(daemon.contains("TX_WRITE"))
        assertFalse(daemon.contains("sendCmd"))
        assertFalse(daemon.contains("setXD"))
        assertFalse(daemon.contains("setTrigger"))
        assertFalse(daemon.contains("wakeUpMcu"))
        assertFalse(daemon.contains("setAction"))
    }

    private fun source(path: String): String = listOf(
        File("app/src/main/java/$path"),
        File("src/main/java/$path"),
        File("app/src/main/kotlin/$path"),
        File("src/main/kotlin/$path")
    ).firstOrNull(File::isFile)?.readText() ?: error("Missing source: $path")
}
