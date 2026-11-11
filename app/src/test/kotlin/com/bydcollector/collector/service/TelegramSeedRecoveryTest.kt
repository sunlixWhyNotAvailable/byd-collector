package com.bydcollector.collector.service

import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectGetLineage
import com.bydcollector.collector.data.input.BusinessContinuityState
import com.bydcollector.collector.data.input.BusinessInputContinuity
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.TelegramDatabaseHelper
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.data.input.*
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.telegram.TelegramEventType
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramSeedRecoveryTest {
    private fun lineage(boot: String, generation: String, time: Long) = DirectGetLineage(
        boot, generation, 1, 1, "read:$boot:$generation:$time", time,
        100000 + time, time, 100000 + time, time, -1, -1, false)

    @Test fun allConsumerKeysRecoverAcrossBootAndPersistedLossWithoutNewCallbacks() {
        val keys = TelegramOrderedInput.rawSourceKeys
        val old = lineage("old", "old-helper", 100)
        val fresh = lineage("new", "new-helper", 200)
        fun readings(source: DirectGetLineage) = keys.map { key ->
            val entry = DirectFidRegistry.entries.first { it.key == key }
            PollReading(key, if (entry.tx == 7) 1f.toRawBits().toString() else "1", getLineage = source)
        }
        val seeded = TelegramOrderedInput.merge(emptyMap(), readings(old), old.sourceStamp(), 1, "old")
        assertTrue(seeded.cursors.values.all { it.valid })
        val lost = old.sourceStamp().copy(identity = "lost", wallMs = 100150, elapsedMs = 150)
        val loss = keys.associateWith { BusinessInputContinuity(BusinessContinuityState.LOST,
            lost, null, null, null, false, "source_lost") }
        val invalidated = TelegramOrderedInput.merge(seeded.cursors, emptyList(), lost, 2, "old", loss)
        val persisted = TelegramOrderedInput.decodeCursors(TelegramOrderedInput.encodeCursors(invalidated.cursors))
        val available = keys.associateWith { BusinessInputContinuity(BusinessContinuityState.AVAILABLE,
            fresh.sourceStamp().copy(identity = "available", elapsedMs = 201), "get", fresh.readIdentity,
            fresh.recordSequence, true, "initial_get") }
        val result = TelegramOrderedInput.merge(persisted, readings(fresh), fresh.sourceStamp(), 3, "new", available)
        assertEquals(keys, result.cursors.filterValues { it.valid && it.stamp.bootId == "new" }.keys)
        assertEquals(0, result.stats.invalid)
        val reopened = TelegramOrderedInput.decodeCursors(TelegramOrderedInput.encodeCursors(result.cursors))
        assertEquals(result.cursors, reopened)
        val stale = TelegramOrderedInput.merge(reopened, readings(old), old.sourceStamp(), 4, "new", loss)
        assertEquals(reopened, stale.cursors)
    }

    @Test fun pendingSeedRequiresMatchingOwnerAndCannotUndoANewerLoss() {
        val key = "statistic_1014_1145045040_5"
        val seed = lineage("boot", "helper", 200)
        val boundary = seed.sourceStamp().copy(identity = "available", elapsedMs = 201)
        val update = BusinessInputContinuity(BusinessContinuityState.AVAILABLE, boundary,
            "get", seed.readIdentity, seed.recordSequence, true, "initial_get")
        val waiting = TelegramOrderedInput.merge(emptyMap(), emptyList(), boundary, 1, "boot", mapOf(key to update))
        val wrong = seed.copy(helperGeneration = "other")
        val rejected = TelegramOrderedInput.merge(waiting.cursors,
            listOf(PollReading(key, "72", getLineage = wrong)), wrong.sourceStamp(), 2, "boot")
        assertFalse(rejected.cursors.getValue(key).valid)
        assertEquals(1, rejected.stats.rejectionReasons["valid_value_behind_continuity"])
        val restored = TelegramOrderedInput.merge(waiting.cursors,
            listOf(PollReading(key, "72", getLineage = seed)), seed.sourceStamp(), 3, "boot")
        assertTrue(restored.cursors.getValue(key).valid)
        val unrelatedDeath = update.copy(state = BusinessContinuityState.LOST, seedValid = false,
            stamp = boundary.copy(identity = "app-loss", elapsedMs = 400, generatorId = "app-source-loss:boot"),
            helperGeneration = "other", stream = 1)
        val retained = TelegramOrderedInput.merge(restored.cursors, emptyList(), unrelatedDeath.stamp, 4, "boot",
            mapOf(key to unrelatedDeath))
        assertEquals(restored.cursors, retained.cursors)
        val loss = update.copy(state = BusinessContinuityState.LOST, seedValid = false,
            stamp = boundary.copy(identity = "lost-later", elapsedMs = 300))
        val invalid = TelegramOrderedInput.merge(restored.cursors, emptyList(), loss.stamp, 4, "boot", mapOf(key to loss))
        val late = TelegramOrderedInput.merge(invalid.cursors,
            listOf(PollReading(key, "72", getLineage = seed)), seed.sourceStamp(), 5, "boot", mapOf(key to update))
        assertFalse(late.cursors.getValue(key).valid)
    }

    @Test fun retainedHistoryRepairsOnlyActiveGroupAndPersistsWithoutRewritingOutbox() {
        val config = TelegramEventConfig(setOf(TelegramEventType.TRIP_SUMMARY), 5, 12.0, 60000, 30000)
        val normalizer = VehicleStateNormalizer()
        val soc = "statistic_1014_1145045040_5"
        val odo = "statistic_1014_1246765072_5"
        val gear = "gearbox_1011_555745336_5"
        val energy = "statistic_statistic_this_trip_total_elec_consumption"
        fun delta(time: Long, vararg values: Pair<String, String>): BusinessInputDelta {
            val get = lineage("boot", "helper", time)
            val kind = if (values.isEmpty()) BusinessInputKind.TIMER else BusinessInputKind.GET
            return BusinessInputDelta(BusinessInputRef(time + 1, kind, null, values.map { it.first },
                get.readIdentity, "boot", "helper", time, 1, 100000 + time, time, 1, time,
                BusinessInputOrigin.REPLAY, true, "ok", null, null),
                readings = values.map { value ->
                    val entry = DirectFidRegistry.entries.first { it.key == value.first }
                    PollReading(value.first, value.second,
                        com.bydcollector.collector.data.direct.DirectValueDecoders.decode(entry, value.second.toInt()),
                        getLineage = get)
                })
        }
        val history = listOf(delta(0, gear to "1", soc to "72", odo to "1000"),
            delta(100, gear to "4", energy to 10f.toRawBits().toString()),
            delta(500, soc to "71", odo to "1010", energy to 11f.toRawBits().toString()),
            delta(1000, gear to "1", odo to "1020"), delta(31000))
        val broken = TelegramEventEngine()
        var summary: TelegramDetectedEvent? = null
        history.forEachIndexed { index, full ->
            // The original consumer missed the initial odometer/SOC; Main retained both.
            val d = if (index == 0) full.copy(readings = full.readings.filter { it.rawKey == gear }) else full
            val result = if (d.ref.kind == BusinessInputKind.TIMER)
                broken.onDetectorTimer(config, d.ref.acquiredWallMs!!, "boot", d.ref.acquiredElapsedMs!!, normalizer)
            else broken.onSourcePoll(d.ref.pollId!!, BusinessInputProjection.timestamp(d.ref),
                BusinessInputProjection.source(d.ref), d.readings, PollOrigin.REPLAY, "boot", config,
                d.ref.acquiredWallMs!!, normalizer)
            result.events.firstOrNull { it.type == TelegramEventType.TRIP_SUMMARY }?.let { summary = it }
        }
        assertFalse(broken.state.bootTotalDistanceComplete)
        assertNull(broken.state.bootStartSoc)
        val original = TelegramEventState.fromJson(JSONObject(broken.state.toJson()).apply {
            remove("initialSeedRecoveryVersion")
        }.toString())
        val repaired = TelegramHistoryRepair.recover(original, config, normalizer, true, history.asSequence())
        assertEquals("matching_active_group", repaired.reason)
        assertTrue(repaired.state.bootTotalDistanceComplete)
        assertEquals(2.0, repaired.state.bootTotalDistanceKm, 1e-6)
        assertEquals(72.0, repaired.state.bootStartSoc)
        assertEquals(original.pendingPowerOffLocationTripId, repaired.state.pendingPowerOffLocationTripId)
        val closed = TelegramHistoryRepair.recover(original, config, normalizer, false, history.asSequence())
        assertFalse(closed.state.bootTotalDistanceComplete)
        val absent = TelegramHistoryRepair.recover(original, config, normalizer, true, emptySequence())
        assertFalse(absent.state.bootTotalDistanceComplete)
        assertNull(absent.state.bootStartSoc)
        val context = RuntimeEnvironment.getApplication()
        val name = "seed-history-repair.db"
        context.deleteDatabase(name)
        var store = TelegramStore(context, TelegramDatabaseHelper(context, name))
        try {
            val report = assertNotNull(summary)
            store.commitTelegramEvents(listOf(TelegramOutboxMessage(report.dedupeKey, report.type.key,
                report.variables.toString())), original.toJson())
            val queued = assertNotNull(store.telegramMessageByDedupeKey(report.dedupeKey))
            store.saveTelegramRuntimeState(repaired.state.toJson())
            store.close()
            store = TelegramStore(context, TelegramDatabaseHelper(context, name))
            val reopened = TelegramEventState.fromJson(store.telegramRuntimeState())
            assertEquals(repaired.state, reopened)
            assertEquals(queued, store.telegramMessageByDedupeKey(report.dedupeKey))
            assertEquals(reopened, TelegramHistoryRepair.recover(reopened, config, normalizer, true,
                history.asSequence()).state)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
