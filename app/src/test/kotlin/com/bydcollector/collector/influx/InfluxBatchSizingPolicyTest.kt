package com.bydcollector.collector.influx

import org.junit.Test
import kotlin.test.assertEquals

class InfluxBatchSizingPolicyTest {
    private val unknownPending = InfluxPendingSummary(rows = -1L, oldestObservedAt = null, complete = false)

    @Test fun knownCountsAndUnknownStartChooseExpectedLimits() {
        assertEquals(300, influxBatchSizing(InfluxPendingSummary(4_999L, null), null).limit)
        assertEquals(2_000, influxBatchSizing(InfluxPendingSummary(5_000L, null), null).limit)

        val unknownStart = influxBatchSizing(unknownPending, null)
        assertEquals(300, unknownStart.limit)
        assertEquals("unknown_start", unknownStart.reason)
        assertEquals("unknown", influxModeFor(unknownPending))
    }

    @Test fun fullCleanUnknownBatchesGrowAndShortBatchResets() {
        var size = influxBatchSizing(unknownPending, null).limit
        for (expected in listOf(600, 1_200, 2_000, 2_000)) {
            size = influxBatchSizeAfterCleanAck(unknownPending, size, requestedLimit = size, actualRows = size)
            assertEquals(expected, size)
        }

        assertEquals(
            300,
            influxBatchSizeAfterCleanAck(unknownPending, currentBatchSize = size, requestedLimit = size, actualRows = 100)
        )
    }

    @Test fun transientFailureStepsDownUnknownAndKnownBacklogBatches() {
        val reducedLimit = influxBatchSizeAfterTransientFailure(unknownPending, 2_000)
        assertEquals(1_000, reducedLimit)
        assertEquals(
            "transient_failure_reduced",
            influxBatchSizing(unknownPending, reducedLimit, "transient_failure_reduced").reason
        )
        assertEquals(500, influxBatchSizeAfterTransientFailure(unknownPending, 1_000))
        assertEquals(300, influxBatchSizeAfterTransientFailure(unknownPending, 500))
        assertEquals(300, influxBatchSizeAfterTransientFailure(InfluxPendingSummary(4_999L, null), 2_000))
    }

    @Test fun knownPendingModeRemainsDistinctFromUnknown() {
        assertEquals("realtime", influxModeFor(InfluxPendingSummary(4_999L, null)))
        assertEquals("catch_up", influxModeFor(InfluxPendingSummary(5_000L, null)))
        assertEquals("unknown", influxModeFor(unknownPending))
    }
}
