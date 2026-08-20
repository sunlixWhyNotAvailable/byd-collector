package com.bydcollector.collector.direct

import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectHelperStopResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryWorkerBinderContractTest {
    @Test
    fun protocolExposesBoundedSpoolAndExactOwnerStopWithOptInWorkerPolling() {
        val daemon = source("com/bydcollector/collector/direct/CollectorHelperDaemon.java")
        val client = source("com/bydcollector/collector/data/direct/DirectVehicleHelperClient.kt")
        val stopEndpoint = daemon.substringAfter("if (code == CollectorHelperProtocol.TX_STOP_OWNER)")
            .substringBefore("return true;")
        val stopClient = client.substringAfter("fun requestStop(ownerMode: DirectHelperOwnerMode)")
            .substringBefore("private fun readWorkerSample")

        assertEquals(7, CollectorHelperProtocol.PROTOCOL_VERSION)
        assertEquals(100, CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES)
        assertEquals(128, CollectorHelperProtocol.MAX_WORKER_FIELD_COUNT)
        assertTrue(daemon.contains("Binder.getCallingUid() != appUid"))
        assertTrue(daemon.contains("code == CollectorHelperProtocol.TX_WORKER_PENDING"))
        assertTrue(daemon.contains("code == CollectorHelperProtocol.TX_WORKER_ACK"))
        assertTrue(client.contains("binder.transact(CollectorHelperProtocol.TX_WORKER_PENDING"))
        assertTrue(client.contains("binder.transact(CollectorHelperProtocol.TX_WORKER_ACK"))
        assertTrue(stopEndpoint.contains("boolean accepted = expectedOwnerMode == actualOwnerMode"))
        assertTrue(stopEndpoint.contains("if (accepted)"))
        assertTrue(stopEndpoint.contains("mainHandler.postDelayed"))
        assertTrue(stopEndpoint.indexOf("if (accepted)") < stopEndpoint.indexOf("mainHandler.postDelayed"))
        assertTrue(stopEndpoint.contains("mainHandler.getLooper().quitSafely()"))
        assertTrue(daemon.contains("ACTIVE_INTERVAL_MS = 500L"))
        assertTrue(daemon.contains("DETACHED_INTERVAL_MS = 5_000L"))
        assertTrue(daemon.contains("CONSUMER_LEASE_MS = 2_000L"))
        assertTrue(daemon.contains("workerPollLoop.markConsumerHeartbeat()"))
        assertTrue(daemon.contains("boolean appended = spool.append"))
        assertTrue(daemon.contains("if (!spool.canAppend())"))
        assertTrue(daemon.contains("handler.removeCallbacks(this)"))
        assertTrue(daemon.contains("now - previous > CONSUMER_LEASE_MS"))
        assertTrue(stopEndpoint.contains("}, 100L);"))
        assertTrue(stopClient.contains("data.writeInt(ownerMode.protocolValue)"))
        assertTrue(stopClient.contains("binder.transact(CollectorHelperProtocol.TX_STOP_OWNER"))
        assertTrue(stopClient.contains("require((status == CollectorHelperProtocol.STATUS_OK) == accepted)"))
        assertTrue(stopClient.indexOf("data.writeInt(ownerMode.protocolValue)") < stopClient.indexOf("TX_STOP_OWNER"))
        assertTrue(client.contains("fieldIndex == expectedIndex"))
        assertTrue(client.contains("override fun ownerMode(): DirectHelperOwnerMode?"))
        assertEquals(
            DirectHelperOwnerMode.APP,
            DirectHelperOwnerMode.fromProtocolValue(CollectorHelperProtocol.OWNER_MODE_APP)
        )
        assertEquals(
            DirectHelperOwnerMode.AUTONOMOUS_WORKER,
            DirectHelperOwnerMode.fromProtocolValue(CollectorHelperProtocol.OWNER_MODE_AUTONOMOUS_WORKER)
        )
        assertNull(DirectHelperOwnerMode.fromProtocolValue(-1))
        assertTrue(DirectHelperStopResult(CollectorHelperProtocol.STATUS_OK, accepted = true).ok)
        assertFalse(DirectHelperStopResult(CollectorHelperProtocol.STATUS_OK, accepted = false).ok)
        assertTrue(daemon.contains("final boolean workerMode = args.length == 3"))
        assertTrue(daemon.contains("? new WorkerPollLoop("))
        assertTrue(daemon.contains("spool.append(workerSample("))
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
