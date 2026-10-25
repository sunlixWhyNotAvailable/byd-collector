package com.bydcollector.collector.influx

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfluxBatchSizingPolicyTest {
    private val unknownPending = InfluxPendingSummary(rows = -1L, oldestObservedAt = null, complete = false)

    @Test fun knownCountsAndUnknownStartChooseExpectedLimits() {
        assertEquals(300, influxBatchSizing(InfluxPendingSummary(4_999L, null), null).limit)
        assertEquals(5_000, influxBatchSizing(InfluxPendingSummary(5_000L, null), null).limit)

        val unknownStart = influxBatchSizing(unknownPending, null)
        assertEquals(300, unknownStart.limit)
        assertEquals("unknown_start", unknownStart.reason)
        assertEquals("unknown", influxModeFor(unknownPending))
    }

    @Test fun fullCleanUnknownBatchesGrowAndShortBatchResets() {
        var size = influxBatchSizing(unknownPending, null).limit
        for (expected in listOf(600, 1_200, 2_400, 4_800, 5_000, 5_000)) {
            size = influxBatchSizeAfterCleanAck(unknownPending, size, requestedLimit = size, actualRows = size)
            assertEquals(expected, size)
        }

        assertEquals(
            300,
            influxBatchSizeAfterCleanAck(unknownPending, currentBatchSize = size, requestedLimit = size, actualRows = 100)
        )
    }

    @Test fun transientFailureStepsDownUnknownAndKnownBacklogBatches() {
        assertEquals(2_500, influxBatchSizeAfterTransientFailure(unknownPending, 5_000))
        assertEquals(1_250, influxBatchSizeAfterTransientFailure(unknownPending, 2_500))
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

    @Test fun serverCeilingHalvesActualRejectedRowsAndCanFallBelowRealtimeFloor() {
        assertEquals(2_500, influxBatchLimitAfterOversize(5_000, 5_000))
        assertEquals(150, influxBatchLimitAfterOversize(5_000, 300))
        assertEquals(75, influxBatchLimitAfterOversize(150, 150))
        assertEquals(1, influxBatchLimitAfterOversize(75, 3))
        assertEquals(1, influxBatchLimitAfterOversize(1, 1))
    }

    @Test fun payloadLimitCountsUtf8EscapesAndNewlinesWithoutSkippingRows() {
        val config = InfluxConfig(true, "127.0.0.1", 8086, "test", null, null, "state", setOf("battery"))
        val rows = (1L..3L).map { id ->
            InfluxPendingHistoryRow(id, "label", "battery", "TEXT", "温度: \"заряд\" 🌡", null, null,
                "OK", null, null, "", "2026-10-25T00:00:00Z", "2026-10-25T00:00:00Z")
        }
        val firstTwo = rows.take(2).map { InfluxLineProtocol.toLine(it, config) }
        val exactBytes = firstTwo.joinToString("\n").toByteArray(Charsets.UTF_8).size
        val first = InfluxLineProtocol.boundedPayload(rows, config, exactBytes)
        assertEquals(firstTwo, first.lines)
        assertEquals(exactBytes, first.bytes)
        assertNull(first.oversizedPointBytes)
        val rest = InfluxLineProtocol.boundedPayload(rows.drop(first.lines.size), config, exactBytes)
        assertEquals(rows.map { InfluxLineProtocol.toLine(it, config) }, first.lines + rest.lines)

        val shorter = InfluxLineProtocol.boundedPayload(rows, config, exactBytes - 1)
        assertEquals(firstTwo.take(1), shorter.lines)
        val singleBytes = firstTwo.first().toByteArray(Charsets.UTF_8).size
        val tooLarge = InfluxLineProtocol.boundedPayload(rows, config, singleBytes - 1)
        assertTrue(tooLarge.lines.isEmpty())
        assertEquals(0, tooLarge.bytes)
        assertEquals(singleBytes, tooLarge.oversizedPointBytes)
    }
}
