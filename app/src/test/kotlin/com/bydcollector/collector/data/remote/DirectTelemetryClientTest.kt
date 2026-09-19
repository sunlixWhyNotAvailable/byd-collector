package com.bydcollector.collector.data.remote

import com.bydcollector.collector.data.direct.DirectAutoserviceField
import com.bydcollector.collector.data.direct.DirectAutoserviceSnapshot
import com.bydcollector.collector.data.direct.DirectBatchDiagnostics
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.direct.CollectorHelperProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DirectTelemetryClientTest {
    @Test
    fun replayPendingSnapshotReturnsTypedBarrierWithoutFailureMeasurement() {
        val result = directSnapshotResult(snapshot(CollectorHelperProtocol.STATUS_REPLAY_PENDING), elapsedMs = 37)

        val pending = assertIs<TelemetryReadResult.ReplayPending>(result)
        assertEquals(37, pending.elapsedMs)
        assertNotNull(pending.rawBody)
    }

    @Test
    fun genuineNotWhitelistedSnapshotRemainsFailure() {
        val result = directSnapshotResult(snapshot(CollectorHelperProtocol.STATUS_NOT_WHITELISTED), elapsedMs = 41)

        val failure = assertIs<TelemetryReadResult.Failure>(result)
        assertEquals("autoservice_snapshot_empty", failure.category)
        assertEquals(41, failure.elapsedMs)
    }

    private fun snapshot(status: Int): DirectAutoserviceSnapshot {
        val entry = DirectFidRegistry.entries.first()
        return DirectAutoserviceSnapshot(
            fields = listOf(
                DirectAutoserviceField(
                    entry = entry,
                    status = status,
                    raw = null,
                    decoded = null,
                    error = if (status == CollectorHelperProtocol.STATUS_REPLAY_PENDING) {
                        "app-gap spool pending; replay before live read"
                    } else {
                        "address is not whitelisted"
                    }
                )
            ),
            batchDiagnostics = DirectBatchDiagnostics(
                mode = "rejected",
                nativeAvailable = false,
                nativeGroupCount = 0,
                fallbackGroupCount = 0,
                fallbackReadCount = 0,
                groupFailureCount = 0,
                helperElapsedMs = 0,
                returnedCount = 1,
                error = "rejected",
                status = if (status == CollectorHelperProtocol.STATUS_REPLAY_PENDING) {
                    CollectorHelperProtocol.STATUS_REPLAY_PENDING
                } else {
                    CollectorHelperProtocol.STATUS_INVALID_REQUEST
                }
            )
        )
    }
}
