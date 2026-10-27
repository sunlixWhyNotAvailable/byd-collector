package com.bydcollector.collector.service

import com.bydcollector.collector.data.direct.DirectKpiMailboxSnapshot
import com.bydcollector.collector.data.direct.DirectKpiRawValue
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.direct.CollectorHelperProtocol
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KpiCapturedSourceTest {
    @Test
    fun capturedCurrentStaysValidThroughQuietTimeButLossRejectsOldReplay() {
        val fixture = javaClass.getResourceAsStream(
            "/com/bydcollector/collector/data/input/callback-batch-20260922.json"
        )!!.bufferedReader().use { JSONObject(it.readText()) }
        val boot = fixture.getJSONObject("batch").getString("boot_id")
        val generation = fixture.getJSONObject("batch").getString("helper_generation")
        val events = fixture.getJSONArray("events")
        val currents = (0 until events.length()).map(events::getJSONObject)
            .filter { it.getInt("fid") == 1145045016 }
        val first = currents.first()
        val next = currents.last()
        val buffer = KpiLiveSourceBuffer(bootId = boot)
        val held = KpiFreshness(boot)
        fun captured(event: JSONObject, order: Long, epoch: Long) = DirectKpiRawValue(
            tx = 7, dev = event.getInt("device"), fid = event.getInt("fid"),
            status = CollectorHelperProtocol.STATUS_OK, nativeType = event.getInt("native_type"),
            rawBits = event.getInt("raw_bits"), bytes = null,
            observedWallMs = event.getLong("received_wall_ms"),
            observedElapsedMs = event.getLong("received_elapsed_ms"), sequence = order,
            sourceKind = 1, sourceEpoch = epoch, sourceRecordSequence = order, quality = "callback_usable"
        )
        fun accept(value: DirectKpiRawValue, now: Long): KpiSourceUpdate {
            val update = buffer.acceptMailbox(DirectKpiMailboxSnapshot(
                status = CollectorHelperProtocol.STATUS_OK, subscriptionId = 1,
                helperBootId = boot, helperGeneration = generation, sequence = value.sequence,
                listenerStatus = CollectorHelperProtocol.KPI_LISTENER_READY, values = listOf(value)
            ), now)
            held.clear(update.invalidatedFieldKeys)
            held.accept(update.observations, now)
            return update
        }
        // Original vehicle raw values/clocks; only KPI envelope order and loss are controlled inputs.
        val original = captured(first, 1, 1)
        val now = next.getLong("received_elapsed_ms") + 60_000
        accept(original, now)
        val current = held.observations().single { it.field.fieldKey == "charge_current_a" }
        assertEquals(0.9, current.value.number!!, 0.0001)
        assertEquals(NormalizedSourceKind.CALLBACK, current.sourceStamp!!.kind)
        assertEquals(original.observedElapsedMs, current.sourceStamp!!.elapsedMs)
        assertTrue(accept(original, now + 60_000).observations.isEmpty())
        assertEquals(current, held.observations().single { it.field.fieldKey == "charge_current_a" })

        val lost = original.copy(status = CollectorHelperProtocol.STATUS_READ_ERROR, rawBits = null,
            sequence = 2, sourceKind = 3, sourceEpoch = 2, sourceRecordSequence = 2,
            quality = "source_lost", lossReason = "listener_unavailable",
            observedElapsedMs = original.observedElapsedMs + 1)
        accept(lost, now)
        assertTrue(held.observations().none { it.field.fieldKey == "charge_current_a" })
        accept(original, now)
        assertTrue(held.observations().none { it.field.fieldKey == "charge_current_a" })
        accept(captured(next, 3, 2), now)
        assertEquals(1.0, held.observations().single { it.field.fieldKey == "charge_current_a" }.value.number)
    }
}
