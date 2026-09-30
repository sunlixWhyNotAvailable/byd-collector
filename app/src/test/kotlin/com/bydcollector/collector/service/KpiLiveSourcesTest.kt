package com.bydcollector.collector.service

import com.bydcollector.collector.data.direct.*
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import org.junit.Assert.*
import org.junit.Test

class KpiLiveSourcesTest {
    private val soc = entry("statistic_1014_1145045040_5")
    private val voltage = entry("charging_charge_battery_volt")
    private val current = entry("charging_charge_current")
    private val ready = CollectorHelperProtocol.KPI_LISTENER_READY

    @Test fun `burst is latest per source and cached mailbox cannot renew freshness`() {
        val buffer = buffer()
        val freshness = KpiFreshness("boot", 3_000)
        repeat(140) { n ->
            val time = 10_000L + n
            val update = buffer.acceptMailbox(mailbox(raw(soc, n % 100, time, n + 1L)), time)
            assertEquals(n == 0, update.cadenceChanged)
            freshness.accept(update.observations, time)
        }
        assertEquals(1, buffer.sourceCount())
        assertEquals(39.0, freshness.freshObservations(10_139).single { it.field.fieldKey == "soc" }.value?.number)
        val replay = buffer.acceptMailbox(mailbox(raw(soc, 39, 10_139, 140)), 12_000)
        assertTrue(replay.observations.isEmpty())
        assertEquals(0L, freshness.remainingMs(13_139))
        assertTrue(freshness.freshObservations(13_139).isEmpty())
    }

    @Test fun `getter started before callback cannot overwrite it but later real getter can`() {
        val buffer = buffer()
        buffer.acceptMailbox(mailbox(raw(soc, 80, 10_100)), 10_100)
        assertTrue(poll(buffer, soc, 75, 10_000, 10_500).observations.isEmpty())
        assertTrue(poll(buffer, soc, 75, 10_100, 10_500).observations.isEmpty())
        val later = poll(buffer, soc, 79, 11_000, 11_200).observations.single { it.field.fieldKey == "soc" }
        assertEquals(79.0, later.value?.number)
        assertEquals(11_000L, later.sourceStamp?.elapsedMs)
        assertTrue(poll(buffer, soc, 70, 11_001, 14_001).observations.isEmpty())
    }

    @Test fun `invalid raw types sentinels and failed getters never prove or refresh a source`() {
        val buffer = buffer()
        val wrongType = raw(soc, 80, 10_000).copy(nativeType = TelemetryCallbackBatch.TYPE_FLOAT)
        assertTrue(buffer.acceptMailbox(mailbox(wrongType), 10_000).observations.isEmpty())
        assertFalse(buffer.callbackProven(soc.key))
        listOf(65_535.0f, Float.NaN, Float.POSITIVE_INFINITY, -1.0f).forEach { invalid ->
            assertTrue(buffer.acceptMailbox(mailbox(raw(current, invalid.toBits(), 10_000)), 10_000).observations.isEmpty())
            assertTrue(poll(buffer, current, invalid.toBits(), 10_000, 10_001).observations.isEmpty())
        }
        assertTrue(poll(buffer, soc, -10_011, 10_000, 10_001).observations.isEmpty())
        assertTrue(buffer.acceptPoll(listOf(soc to DirectHelperReadResult(-1, 90)),
            10_000, 10_000, 10_001, "worker").observations.isEmpty())
        assertTrue(buffer.acceptPoll(listOf(soc to DirectHelperReadResult(0, 90, callbackCached = true)),
            10_000, 10_000, 10_001, "worker").observations.isEmpty())
        assertEquals(0, buffer.sourceCount())
    }

    @Test fun `composite uses oldest real contributor and generation replacement clears sources`() {
        val buffer = buffer()
        buffer.acceptMailbox(mailbox(raw(voltage, 400, 10_000)), 10_000)
        val update = buffer.acceptMailbox(mailbox(raw(current, 10.0f.toBits(), 10_100, 2)), 10_100)
        val power = update.observations.single { it.field.fieldKey == "battery_discharge_power_kw" }
        assertEquals(10_000L, power.sourceStamp?.elapsedMs)
        assertNotNull(power.value?.number)
        val oldEpoch = buffer.transportEpoch()
        assertTrue(buffer.acceptSubscription("boot", "replacement", ready).helperGenerationChanged)
        assertTrue(buffer.acceptPoll(listOf(voltage to DirectHelperReadResult(0, 400)),
            10_200, 10_200, 10_300, "worker", expectedTransportEpoch = oldEpoch).observations.isEmpty())
        assertEquals(0, buffer.sourceCount())
        assertFalse(buffer.callbackProven(voltage.key))
        assertTrue(buffer.resetTransport())
        assertFalse(buffer.resetTransport())
    }

    @Test fun `one second fallback two second reconciliation and forced entry seed`() {
        val cadence = KpiPollCadence()
        val rows = listOf(soc, current)
        val proven: (String) -> Boolean = { it == current.key }
        assertEquals(rows, cadence.due(rows, 10_000, true, true, true, proven))
        cadence.markStarted(rows, 10_000)
        assertEquals(listOf(soc), cadence.due(rows, 11_000, true, true, true, proven))
        assertEquals(rows, cadence.due(rows, 11_000, true, false, true, proven))
        assertEquals(rows, cadence.due(rows, 11_000, true, true, false, proven))
        assertTrue(cadence.due(rows, 11_000, false, false, true, proven).isEmpty())
        assertEquals(rows, cadence.due(rows, 12_000, true, true, true, proven))
        assertEquals(rows, cadence.due(rows, 10_001, true, true, true, proven, forceSeed = true))
        assertEquals(500L, cadence.nextDelayMs(rows, 10_500, true, true, true, proven))
        cadence.clear()
        assertEquals(0L, cadence.nextDelayMs(rows, 10_500, true, true, true, proven))
    }

    private fun buffer() = KpiLiveSourceBuffer(bootId = "boot").apply {
        acceptSubscription("boot", "helper", ready)
    }
    private fun entry(key: String) = DirectFidRegistry.entries.single { it.key == key }
    private fun raw(entry: DirectFidEntry, bits: Int, elapsed: Long, sequence: Long = 1) = DirectKpiRawValue(
        entry.tx, entry.dev, entry.fid, 0,
        if (entry.tx == DirectFidRegistry.TX_GET_FLOAT) TelemetryCallbackBatch.TYPE_FLOAT else TelemetryCallbackBatch.TYPE_INT,
        bits, null, elapsed, elapsed, sequence)
    private fun mailbox(vararg values: DirectKpiRawValue) = DirectKpiMailboxSnapshot(
        0, 1, "boot", "helper", values.maxOfOrNull { it.sequence } ?: 0, ready, values = values.toList())
    private fun poll(buffer: KpiLiveSourceBuffer, entry: DirectFidEntry, bits: Int, started: Long, ended: Long) =
        buffer.acceptPoll(listOf(entry to DirectHelperReadResult(0, bits)), started, started, ended, "worker")
}
