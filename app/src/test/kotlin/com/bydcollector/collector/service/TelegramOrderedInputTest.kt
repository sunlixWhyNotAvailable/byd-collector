package com.bydcollector.collector.service

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramOrderedInputTest {
    @Test
    fun callbackRepeatConflictAndOldReplayCannotAdvancePersistedInput() {
        val first = merge(emptyMap(), listOf(gun(1, 1, 100)), stamp(100))
        assertEquals(1, first.stats.seeded)
        assertTrue(first.freshSourceKeys.isEmpty(), "legacy cursors seed without confirming a candidate")
        val disconnected = merge(first.cursors, listOf(gun(0, 2, 200)), stamp(200))
        assertTrue(disconnected.hasFresh("charge_gun_connected_raw"))
        val restored = TelegramOrderedInput.decodeCursors(TelegramOrderedInput.encodeCursors(disconnected.cursors))
        assertEquals(disconnected.cursors, restored)

        val cachedAgain = merge(restored, listOf(gun(0, 2, 200)), stamp(900))
        assertEquals(1, cachedAgain.stats.repeated)
        assertTrue(cachedAgain.freshSourceKeys.isEmpty())
        assertEquals(restored, cachedAgain.cursors)
        val conflicting = merge(restored, listOf(gun(1, 2, 200)), stamp(950))
        assertEquals(1, conflicting.stats.conflictingEqual)
        assertEquals("0", conflicting.inputs.getValue(GUN).reading.rawValue)
        assertEquals(restored, conflicting.cursors)
        val older = merge(restored, listOf(gun(1, 1, 100)), stamp(1_000))
        assertEquals(1, older.stats.older)
        assertTrue(older.freshSourceKeys.isEmpty())

        // A real getter confirmation may repeat the value, but not the acquisition identity.
        val getter = merge(restored, listOf(PollReading(GUN, "0")), stamp(300))
        assertTrue(getter.hasFresh("charge_gun_connected_raw"))
        assertEquals(300L, getter.sourceStamp("charge_gun_connected_raw")?.wallMs)
        assertEquals(restored, disconnected.cursors, "merge must not mutate the committed snapshot")
        val invalid = gun(1, 3, 400, fid = 42)
        assertEquals(1, merge(restored, listOf(invalid), stamp(400)).stats.invalid)
    }

    @Test
    fun trustedBootAndElapsedOrderingSurviveWallClockAndGenerationResets() {
        val previous = merge(emptyMap(), listOf(PollReading(GUN, "1")),
            stamp(500, boot = "old", wall = 9_000), currentBoot = "old")
        val current = merge(previous.cursors, listOf(PollReading(GUN, "0")),
            stamp(10, boot = "new", wall = 1_000), currentBoot = "new")
        assertTrue(current.hasFresh("charge_gun_connected_raw"))
        val replay = merge(current.cursors, listOf(PollReading(GUN, "1")),
            stamp(900, boot = "old", wall = 50_000), currentBoot = "new")
        assertEquals(1, replay.stats.older)
        assertEquals(current.cursors, replay.cursors)
        val nextGeneration = merge(current.cursors, listOf(PollReading(GUN, "0")),
            stamp(11, boot = "new", wall = 900, generation = "replacement"), currentBoot = "new")
        assertTrue(nextGeneration.hasFresh("charge_gun_connected_raw"))
        val tied = merge(nextGeneration.cursors, listOf(PollReading(GUN, "1")),
            stamp(11, boot = "new", wall = 900, generation = "other"), currentBoot = "new")
        assertEquals(1, tied.stats.incomparable)
        assertEquals(nextGeneration.cursors, tied.cursors)
    }

    @Test
    fun rangeInputsRequireCurrentBootAndIncludeCumulativeEnergy() {
        val cumulativeEnergyKey = "statistic_total_elec_consumption"
        assertTrue(TelegramOrderedInput.rawSourceKeys.contains(cumulativeEnergyKey))
        val oldBoot = merge(
            emptyMap(), listOf(PollReading(cumulativeEnergyKey, "500")),
            stamp(100, boot = "old"), currentBoot = "old"
        )
        assertTrue(oldBoot.hasCurrentBoot("cumulative_energy_kwh"))

        val afterRestart = merge(
            oldBoot.cursors, emptyList(), stamp(1, boot = "new"), currentBoot = "new"
        )
        assertFalse(afterRestart.hasCurrentBoot("cumulative_energy_kwh"))
    }

    @Test
    fun sparsePowerRetainsBothRawContributorsButRejectsMixedBootComposition() {
        val voltage = "charging_charge_battery_volt"
        val current = "charging_charge_current"
        val old = merge(emptyMap(), listOf(
            PollReading(voltage, 400f.toRawBits().toString()),
            PollReading(current, 10f.toRawBits().toString())
        ), stamp(100, boot = "old"), currentBoot = "old")
        assertTrue(old.hasSingleBoot("battery_charge_power_kw"))
        val half = merge(old.cursors, listOf(PollReading(voltage, 401f.toRawBits().toString())),
            stamp(10, boot = "new"), currentBoot = "new")
        assertTrue(half.hasFresh("battery_charge_power_kw"))
        assertFalse(half.hasSingleBoot("battery_charge_power_kw"))
        assertNull(half.sourceStamp("battery_charge_power_kw"))
        assertEquals(old.inputs.getValue(current), half.inputs.getValue(current))
        val complete = merge(half.cursors, listOf(PollReading(current, 11f.toRawBits().toString())),
            stamp(20, boot = "new"), currentBoot = "new")
        assertTrue(complete.hasSingleBoot("battery_charge_power_kw"))
        assertEquals(20L, complete.sourceStamp("battery_charge_power_kw")?.wallMs)
        assertEquals(half.inputs.getValue(voltage), complete.inputs.getValue(voltage))
        val json = TelegramOrderedInput.encodeCursors(complete.cursors)
        json.put(org.json.JSONObject().put("key", "unrelated"))
        assertEquals(complete.cursors, TelegramOrderedInput.decodeCursors(json))
    }

    private fun merge(
        previous: Map<String, TelegramRawSourceCursor>,
        readings: List<PollReading>,
        stamp: NormalizedSourceStamp,
        currentBoot: String = "boot"
    ) = TelegramOrderedInput.merge(previous, readings, stamp, 1L, currentBoot)

    private fun stamp(elapsed: Long, boot: String = "boot", wall: Long = elapsed, generation: String = "gen") =
        NormalizedSourceStamp(NormalizedSourceKind.POLL, "$boot:$generation:$elapsed", boot,
            generation, elapsed, wall, elapsed)

    private fun gun(raw: Int, sequence: Long, elapsed: Long, fid: Int = 876609586) = PollReading(
        GUN, raw.toString(), "原始描述", callbackSource = CallbackValueSource(
            "boot", "gen", 1, 1, sequence, 1009, fid,
            TelemetryCallbackBatch.TYPE_INT, raw, elapsed, elapsed, null, "callback"
        )
    )

    private companion object {
        const val GUN = "charging_1009_876609586_5"
    }
}
