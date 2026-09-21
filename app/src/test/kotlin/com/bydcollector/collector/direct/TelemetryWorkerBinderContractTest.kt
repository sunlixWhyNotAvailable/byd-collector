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
    fun protocolExposesBoundedSpoolAndExactOwnerStopWithAppGapFallback() {
        val daemon = source("com/bydcollector/collector/direct/CollectorHelperDaemon.java")
        val runtime = source("com/bydcollector/collector/direct/HelperDualStreamRuntime.java")
        val state = source("com/bydcollector/collector/direct/HelperStreamRuntimeState.java")
        val client = source("com/bydcollector/collector/data/direct/DirectVehicleHelperClient.kt")
        val stopEndpoint = daemon.substringAfter("if (code == CollectorHelperProtocol.TX_STOP_OWNER)")
        val liveBatchEndpoint = daemon.substringAfter("if (code == CollectorHelperProtocol.TX_READ_BATCH)")
            .substringBefore("if (code == CollectorHelperProtocol.TX_WORKER_PENDING)")
            .substringBefore("return true;")
        val stopClient = client.substringAfter("fun requestStop(ownerMode: DirectHelperOwnerMode)")
            .substringBefore("private fun readWorkerSample")

        assertEquals(11, CollectorHelperProtocol.PROTOCOL_VERSION)
        assertEquals(-915, CollectorHelperProtocol.STATUS_REPLAY_PENDING)
        assertEquals("spool", CollectorHelperProtocol.SPOOL_MODE_ARG)
        assertEquals(100, CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES)
        assertEquals(128, CollectorHelperProtocol.MAX_WORKER_FIELD_COUNT)
        assertTrue(daemon.contains("Binder.getCallingUid() != appUid"))
        assertTrue(daemon.contains("code == CollectorHelperProtocol.TX_WORKER_PENDING"))
        assertTrue(daemon.contains("code == CollectorHelperProtocol.TX_WORKER_ACK"))
        assertTrue(client.contains("binder.transact(CollectorHelperProtocol.TX_WORKER_PENDING"))
        assertTrue(client.contains("binder.transact(CollectorHelperProtocol.TX_WORKER_ACK"))
        assertTrue(client.contains("status = batchStatus"))
        assertTrue(stopEndpoint.contains("boolean accepted = expectedOwnerMode == actualOwnerMode"))
        assertTrue(stopEndpoint.contains("if (accepted)"))
        assertTrue(stopEndpoint.contains("mainHandler.postDelayed"))
        assertTrue(stopEndpoint.indexOf("if (accepted)") < stopEndpoint.indexOf("mainHandler.postDelayed"))
        assertTrue(stopEndpoint.contains("mainHandler.getLooper().quitSafely()"))
        assertTrue(runtime.contains("POLL_INTERVAL_MS = 500L"))
        assertTrue(state.contains("LEASE_MS = 2_000L"))
        assertFalse(daemon.contains("markConsumerHeartbeat"))
        assertTrue(runtime.contains("mainSpool.append(CollectorHelperDaemon.workerSample("))
        assertTrue(runtime.contains("if (!mainSpool.canAppend())"))
        assertTrue(runtime.contains("ownerHandler.removeCallbacks(tick)"))
        assertTrue(state.contains("state.leaseExpiresMs = saturatedAdd(nowMs, LEASE_MS)"))
        assertTrue(liveBatchEndpoint.contains("runtime.readMain(controllerToken, mainEpoch, rows)"))
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
            DirectHelperOwnerMode.APP_GAP_SPOOL,
            DirectHelperOwnerMode.fromProtocolValue(CollectorHelperProtocol.OWNER_MODE_APP_GAP_SPOOL)
        )
        assertNull(DirectHelperOwnerMode.fromProtocolValue(-1))
        assertTrue(DirectHelperStopResult(CollectorHelperProtocol.STATUS_OK, accepted = true).ok)
        assertFalse(DirectHelperStopResult(CollectorHelperProtocol.STATUS_OK, accepted = false).ok)
        assertTrue(daemon.contains("final boolean spoolMode = args.length == 3"))
        assertFalse(daemon.contains("WorkerPollLoop"))
        assertTrue(runtime.contains("mainSpool.append(CollectorHelperDaemon.workerSample("))
        assertTrue(daemon.contains("OWNER_MODE_APP_GAP_SPOOL"))
        assertTrue(daemon.contains("samples = workerSpool.pending(data.readInt(), replaySampleValidator)"))
        assertTrue(runtime.contains("state.fallbackAllowed(CollectorHelperProtocol.STREAM_MAIN"))
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
