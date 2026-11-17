package com.bydcollector.collector.data.energy

import android.app.Application
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.data.input.*
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.*
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.service.*
import com.bydcollector.collector.telegram.TelegramEventType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class EnergySessionCoordinatorReplayTest {
    @Test fun stationaryFirstReportRequiresMeasuredEnergyOrSocThreshold() {
        val base = 1_793_000_000_000L
        val config = TelegramEventConfig(setOf(TelegramEventType.TRIP_SUMMARY), 5, 12.0, 60_000, 5_000)
        fun run(duration: Long, endSoc: Double): List<TelegramDetectedEvent> {
            val energy = EnergySessionCoordinator(BufferedEnergyStorage(null))
            val telegram = TelegramEventEngine()
            val normalizer = VehicleStateNormalizer()
            val seed = listOf(PollReading("gearbox_1011_555745336_5", "1", "1"),
                PollReading("statistic_1014_1246765072_5", "1000", "1000"),
                PollReading("statistic_1014_1145045040_5", "75", "75"))
            fun feed(id: Long, ms: Long, on: Boolean): EnergySnapshot {
                val at = java.time.Instant.ofEpochMilli(base + ms).toString()
                val result = energy.process(EnergyReceipt("threshold:$id", at,
                    EnergyInput("boot", ms, 400.0, 100.0, on, true, false, EnergyIntervalContinuity(0))))
                confirm(energy, result)
                telegram.onSourcePoll(id, at, PollSampleSource("threshold:$id", "boot", ms, "test", id),
                    if (id == 1L) seed else emptyList(), PollOrigin.REPLAY, "boot", config, base + ms, normalizer,
                    result.snapshot, ms, startedPowerSessionId = result.snapshot?.powerSessionId
                        .takeIf { result.transition == EnergySessionTransition.STARTED })
                return requireNotNull(result.snapshot)
            }
            feed(1, 0, false)
            feed(2, 1, true)
            val final = feed(3, duration + 1, false)
            return telegram.onPowerOffConfirmed(config,
                TelegramPowerOffSnapshot(100.0, endSoc, energySnapshot = final), nowMs = base + duration + 1).events
        }
        assertTrue(run(9_000, 75.0).isEmpty(), "exactly 0.1kWh does not exceed the energy threshold")
        assertEquals(1, run(10_000, 75.0).size)
        assertEquals(1, run(1_000, 74.0).size, "one percent SOC independently admits the first report")
    }

    @Test fun qualifiedIntervalsMatchLiveTimersAndSparseReplayAcrossSqliteReopen() {
        val context = RuntimeEnvironment.getApplication()
        val base = 1_793_000_000_000L
        fun run(timers: Boolean): EnergySnapshot {
            val name = "held_energy_$timers.db"
            var store = TripStore(TripDatabaseHelper(context, name))
            var engine = EnergySessionCoordinator(store)
            var telegram = TelegramEventEngine()
            val config = TelegramEventConfig(setOf(TelegramEventType.TRIP_SUMMARY), 5, 12.0, 60_000, 5_000)
            var inputs = emptyMap<String, NormalizedSourceInput>()
            var continuity = emptyMap<String, BusinessInputContinuity>()
            val normalizer = VehicleStateNormalizer()
            var id = 0L
            fun feed(ms: Long, vararg values: Pair<String, String>): EnergySnapshot {
                id++
                val stamp = normalizedCallbackSourceStamp("boot", "helper", 1, 1, id, base + ms, ms, 1)
                val updates = values.associate { (key, value) ->
                    val entry = com.bydcollector.collector.data.direct.DirectFidRegistry.entries.first { it.key == key }
                    val callback = com.bydcollector.collector.direct.CallbackValueSource("boot", "helper", 1, 1, 1,
                        id, entry.dev, entry.fid, if (entry.tx == 7) 2 else 1, value.toInt(), base + ms, ms, null, "callback")
                    key to NormalizedSourceInput(PollReading(key, value, value, callback), stamp, null)
                }
                val states = updates.mapValues { BusinessInputContinuity(BusinessContinuityState.AVAILABLE,
                    stamp, "callback", stamp.identity, id, true, "callback_value") }
                val ref = BusinessInputRef(id, if (values.isEmpty()) BusinessInputKind.TIMER else BusinessInputKind.CALLBACK,
                    null, updates.keys.toList(), stamp.identity, "boot", "helper", id, 1, base + ms, ms,
                    null, null, BusinessInputOrigin.REPLAY, true, "ok", null, null)
                val delta = BusinessInputDelta(ref, sourceInputs = updates, continuityUpdates = states)
                val proof = EnergyTelemetryProjection.intervalContinuity(inputs, continuity, delta)
                val merged = BusinessInputReducer.merge(inputs, updates, "boot")
                continuity = BusinessInputReducer.mergeContinuity(continuity, states, "boot", merged.inputs).continuity
                inputs = merged.inputs
                val fresh = BusinessInputProjection.freshInputs(inputs, "boot", ms, continuity)
                val receipt = EnergyTelemetryProjection.receipt(PollSampleSource(EnergyReceipt.businessIdentity(name, id),
                    "boot", ms, "helper", id), java.time.Instant.ofEpochMilli(base + ms).toString(),
                    fresh.values.map { it.reading }, BusinessInputProjection.observations(normalizer, fresh), proof)
                    .copy(businessGeneration = name, businessInputId = id)
                val result = engine.process(receipt)
                confirm(engine, result)
                telegram.onSourcePoll(id, receipt.observedAt, PollSampleSource(stamp.identity, "boot", ms, "helper", id),
                    updates.values.map { it.reading }, PollOrigin.REPLAY, "boot", config, base + ms, normalizer,
                    result.snapshot, ms, states,
                    startedPowerSessionId = result.snapshot?.powerSessionId.takeIf { result.transition == EnergySessionTransition.STARTED })
                if (result.transition == EnergySessionTransition.STOPPED) {
                    val completed = telegram.onPowerOffConfirmed(config,
                        TelegramPowerOffSnapshot(100.0, 75.0, energySnapshot = result.snapshot), nowMs = base + ms)
                    val report = completed.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
                    assertTrue(report.omitCurrent)
                    assertEquals("0", report.variables["total_distance_km"])
                    assertEquals("0.33", report.variables["total_discharged_kwh"])
                    assertEquals("0.17", report.variables["total_regenerated_kwh"])
                    assertEquals("0.17", report.variables["total_net_kwh"])
                    assertEquals("-", report.variables["total_net_kwh_per_100km"])
                    assertEquals("0:02", report.variables["total_duration"])
                    com.bydcollector.collector.telegram.TelegramTemplateLanguage.entries.forEach { language ->
                        val text = requireNotNull(com.bydcollector.collector.telegram.renderTelegramPayload(report, null, language).text)
                        assertFalse(text.contains("n/a"), text)
                        assertTrue(text.contains("- кВт·год/100 км") || text.contains("- kWh/100 km"), text)
                    }
                    assertTrue(telegram.onPowerOffConfirmed(config, nowMs = base + ms + 1).events.isEmpty())
                }
                if (!timers && ms == 61_000L) {
                    store.close()
                    store = TripStore(TripDatabaseHelper(context, name))
                    engine = EnergySessionCoordinator(store)
                    telegram = TelegramEventEngine(TelegramEventState.fromJson(telegram.state.toJson()))
                    val repeated = engine.process(receipt)
                    assertTrue(repeated.duplicate)
                    assertEquals(result.snapshot, repeated.snapshot)
                    confirm(engine, repeated)
                    val boundary = assertNotNull(engine.currentSnapshot()?.atBoundary("boot", 31_000L))
                    assertEquals(1.0 / 6.0, assertNotNull(boundary.dischargedKwh), 1e-10)
                }
                return assertNotNull(result.snapshot)
            }
            try {
                feed(0, "bodywork_power_level" to "0", "charging_1009_876609586_5" to "1",
                    "charging_charge_battery_volt" to "400", "charging_charge_current" to "50",
                    "gearbox_1011_555745336_5" to "1", "statistic_1014_1246765072_5" to "1000",
                    "statistic_1014_1145045040_5" to "75")
                feed(1_000, "bodywork_power_level" to "2")
                if (timers) for (ms in 2_000L..60_000L step 1_000L) feed(ms)
                feed(61_000, "charging_charge_current" to "-25")
                if (timers) for (ms in 62_000L..120_000L step 1_000L) feed(ms)
                return feed(121_000, "bodywork_power_level" to "0")
            } finally { store.close(); context.deleteDatabase(name) }
        }
        val live = run(true)
        val replay = run(false)
        assertEquals(1.0 / 3.0, assertNotNull(replay.dischargedKwh), 1e-10)
        assertEquals(1.0 / 6.0, assertNotNull(replay.regeneratedKwh), 1e-10)
        assertEquals(assertNotNull(live.netKwh), assertNotNull(replay.netKwh), 1e-10)
        assertEquals(120_000L, replay.energyCoveredMs)
        assertEquals(0L, replay.energyUncoveredMs)
        assertFalse(replay.energyPartial)
    }

    @Test
    fun sparseGearAndTimersUseCalculatedBoundariesAcrossTwoLegsAndReload() {
        val context = RuntimeEnvironment.getApplication()
        val name = "sparse_leg_energy.db"
        val base = 1_793_000_000_000L
        val normalizer = VehicleStateNormalizer()
        val config = TelegramEventConfig(TelegramEventType.entries.toSet(), 5, 12.0, 60_000, 5_000)
        val gear = "gearbox_1011_555745336_5"
        val odo = "statistic_1014_1246765072_5"
        var engine = TelegramEventEngine()
        var inputs = emptyMap<String, NormalizedSourceInput>()
        var id = 0L
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val energy = EnergySessionCoordinator(store)
                fun feed(ms: Long, vararg values: Pair<String, String>): TelegramEventResult {
                    id++
                    val source = PollSampleSource("raw-$id", "boot", ms, "test", id)
                    val at = java.time.Instant.ofEpochMilli(base + ms).toString()
                    val stamp = NormalizedSourceStamp(NormalizedSourceKind.POLL, source.identity, "boot", "test", id, base + ms, ms)
                    val readings = values.map { (key, raw) -> PollReading(key, raw,
                        if (key == "charging_charge_current") Float.fromBits(raw.toInt()).toString() else raw) }
                    val merge = BusinessInputReducer.merge(inputs, readings.associate { it.rawKey to NormalizedSourceInput(it, stamp, null) }, "boot")
                    inputs = merge.inputs
                    val fresh = BusinessInputProjection.freshInputs(inputs, "boot", ms)
                    val kind = if (values.isEmpty()) BusinessInputKind.TIMER else BusinessInputKind.GET
                    val result = if (BusinessInputProjection.needsEnergyCheckpoint(kind, false, merge.updates.keys, energy.runtimeStatus().active)) {
                        energy.process(EnergyTelemetryProjection.receipt(source.copy(identity = EnergyReceipt.businessIdentity("sparse", id)),
                            at, fresh.values.map { it.reading }, BusinessInputProjection.observations(normalizer, fresh))
                            .copy(businessGeneration = "sparse", businessInputId = id))
                    } else null
                    result?.pendingProjection?.let { assertTrue(energy.confirmProjected(it.snapshot.snapshotId)) }
                    return if (kind == BusinessInputKind.TIMER)
                        engine.onDetectorTimer(config, base + ms, "boot", ms, normalizer, result?.snapshot)
                    else engine.onSourcePoll(id, at, source, readings, PollOrigin.REPLAY, "boot", config,
                        base + ms, normalizer, result?.snapshot, ms,
                        startedPowerSessionId = result?.snapshot?.powerSessionId.takeIf { result?.transition == EnergySessionTransition.STARTED })
                }
                feed(0, "bodywork_power_level" to "0", "charging_1009_876609586_5" to "1",
                    "charging_charge_battery_volt" to "400", "charging_charge_current" to 100f.toRawBits().toString(),
                    gear to "1", odo to "1000", "statistic_1014_1145045040_5" to "80")
                feed(100, "bodywork_power_level" to "2")
                feed(200, gear to "4")
                assertEquals(200L, assertNotNull(engine.state.tripStartEnergyPoint).sourceElapsedMs)
                feed(1_000); feed(2_000)
                feed(2_100, gear to "1", odo to "1004")
                val parked = assertNotNull(engine.state.tripEndEnergyPoint)
                assertEquals(2_100L, parked.sourceElapsedMs)
                engine = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
                for (ms in 3_000L..7_000L step 1_000) feed(ms)
                assertEquals(parked, engine.state.tripEndEnergyPoint)
                val first = feed(7_100).events.single { it.type == TelegramEventType.TRIP_SUMMARY }
                assertEquals("0.02", first.variables["trip_discharged_kwh"])
                assertEquals("0.02", first.variables["total_discharged_kwh"])
                assertFalse(first.omitCurrent, "parking before departure belongs only to the total")
                feed(7_200, gear to "4")
                feed(8_200)
                feed(9_200, gear to "1", odo to "1014")
                for (ms in 10_200L..13_200L step 1_000) feed(ms)
                // Delivery is late; both blocks still end at P (9_200), not at the report deadline.
                val second = feed(14_700).events.single { it.type == TelegramEventType.TRIP_SUMMARY }
                assertEquals("0.02", second.variables["trip_discharged_kwh"])
                assertEquals("0.1", second.variables["total_discharged_kwh"])
                assertTrue(feed(15_200).events.none { it.type == TelegramEventType.TRIP_SUMMARY })
                feed(16_200, "bodywork_power_level" to "0")
                val beforeOff = engine.state.toJson()
                val final = engine.onPowerOffConfirmed(config,
                    TelegramPowerOffSnapshot(101.4, 80.0, energySnapshot = energy.currentSnapshot()), nowMs = base + 16_200)
                    .events.single { it.type == TelegramEventType.TRIP_SUMMARY }
                assertTrue(final.omitCurrent)
                assertEquals("1.4", final.variables["total_distance_km"])
                assertEquals("0.18", final.variables["total_discharged_kwh"])
                assertEquals(base + 16_200, final.occurredAtMs)
                val fix = TelegramLocationSnapshot(1.0, 1.0, java.time.Instant.ofEpochMilli(base + 9_200).toString(),
                    7, "", "https://maps.google.com/?q=1,1", "")
                listOf(false, true).forEach { send ->
                    val restored = TelegramEventEngine(TelegramEventState.fromJson(beforeOff))
                    val report = restored.onPowerOffConfirmed(config.copy(sendLocation = send,
                        navigatorMask = com.bydcollector.collector.telegram.TelegramNavigatorMask.GOOGLE),
                        TelegramPowerOffSnapshot(101.4, 80.0, energySnapshot = energy.currentSnapshot()),
                        location = fix, nowMs = base + 16_200).events.single()
                    assertEquals(final.dedupeKey, report.dedupeKey)
                    val text = requireNotNull(com.bydcollector.collector.telegram.renderTelegramPayload(report, null,
                        com.bydcollector.collector.telegram.TelegramTemplateLanguage.UK).text)
                    assertEquals(send, text.contains("\n\nGoogle:"))
                    assertFalse(report.locationOnly)
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun coveredBoundaryReconstructionUsesThePersistedIntegralWithoutExtrapolation() {
        val context = RuntimeEnvironment.getApplication()
        val name = "energy_boundary.db"
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val energy = EnergySessionCoordinator(store)
                confirm(energy, energy.process(receipt("boundary", 1, 0, 0, false)))
                confirm(energy, energy.process(receipt("boundary", 2, 100, 100, true)))
                val result = energy.process(receipt("boundary", 3, 1_100, 1_100, true))
                confirm(energy, result)
            }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val snapshot = assertNotNull(EnergySessionCoordinator(store).currentSnapshot())
                val boundary = assertNotNull(snapshot.atBoundary("energy-replay-boot", 600))
                assertEquals(500L, boundary.energyCoveredMs)
                assertEquals(40.0 * 0.5 / 3_600.0, assertNotNull(boundary.dischargedKwh), 1e-12)
                assertNull(snapshot.atBoundary("energy-replay-boot", 99))
                assertNull(snapshot.atBoundary("energy-replay-boot", 1_101))
                assertNull(snapshot.atBoundary("another-boot", 600))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun checkpointCacheFollowsPersistedChanges() {
        val context = RuntimeEnvironment.getApplication()
        val name = "cache.db"
        val store = TripStore(TripDatabaseHelper(context, name))
        try {
            val first = EnergySessionCoordinator(store)
            val second = EnergySessionCoordinator(store)
            confirm(first, first.process(receipt("cache", 1L, 100L, 100L, false)))
            assertFalse(second.runtimeStatus().active)
            val started = first.process(receipt("cache", 2L, 200L, 200L, true))
            assertTrue(second.runtimeStatus().active)
            assertEquals(started.pendingProjection, second.pendingProjection())
            confirm(second, started)
            assertNull(first.pendingProjection())
            val stopped = second.process(receipt("cache", 3L, 300L, 300L, false))
            confirm(second, stopped)
            assertFalse(first.runtimeStatus().active)
            assertEquals(3L, first.runtimeStatus().lastBusinessInputId)
            assertEquals(stopped.snapshot, first.currentSnapshot())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun businessCursorAndEvaluationClockSurviveActualSqliteReopen() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "energy_replay_${System.nanoTime()}.db"
        val generation = "energy-replay-generation"
        var store: TripStore? = TripStore(TripDatabaseHelper(context, databaseName))
        try {
            var energy = EnergySessionCoordinator(requireNotNull(store))
            val initialOff = energy.process(receipt(generation, 1L, 100L, 500L, false))
            assertEquals(EnergySessionTransition.NONE, initialOff.transition)
            confirm(energy, initialOff)

            val started = energy.process(receipt(generation, 2L, 100L, 1_000L, true))
            assertEquals(EnergySessionTransition.STARTED, started.transition)
            assertEquals(1_000L, started.snapshot?.sourceElapsedMs)
            assertFalse(requireNotNull(started.snapshot).energyPartial)
            confirm(energy, started)

            val evaluated = energy.process(receipt(generation, 3L, 100L, 2_000L, true))
            val evaluatedSnapshot = assertNotNull(evaluated.snapshot)
            assertEquals(2_000L, evaluatedSnapshot.sourceElapsedMs)
            assertEquals(1_000L, evaluatedSnapshot.energyCoveredMs)
            assertFalse(evaluatedSnapshot.energyPartial)
            confirm(energy, evaluated)

            store?.close()
            store = TripStore(TripDatabaseHelper(context, databaseName))
            energy = EnergySessionCoordinator(requireNotNull(store))

            val prefix = energy.process(receipt(generation, 1L, 50L, 2_100L, false))
            assertTrue(prefix.stale)
            assertNull(prefix.snapshot)
            assertTrue(energy.runtimeStatus().active)

            val repeat = energy.process(receipt(generation, 3L, 100L, 2_000L, true))
            assertTrue(repeat.duplicate)
            assertEquals(evaluatedSnapshot, repeat.snapshot)
            confirm(energy, repeat)

            val sameMillisecond = energy.process(receipt(generation, 4L, 100L, 2_000L, true, current = 200.0))
            val sameMillisecondSnapshot = assertNotNull(sameMillisecond.snapshot)
            assertEquals(2_000L, sameMillisecondSnapshot.sourceElapsedMs)
            assertEquals(1_000L, sameMillisecondSnapshot.energyCoveredMs)
            assertFalse(sameMillisecondSnapshot.energyPartial)
            confirm(energy, sameMillisecond)

            val stopped = energy.process(
                receipt(generation, 5L, 50L, 2_000L, false, lateRelevantSource = true)
            )
            val stoppedSnapshot = assertNotNull(stopped.snapshot)
            assertEquals(EnergySessionTransition.STOPPED, stopped.transition)
            assertFalse(stoppedSnapshot.active)
            assertEquals(2_000L, stoppedSnapshot.sourceElapsedMs)
            assertTrue(stoppedSnapshot.energyPartial)
            confirm(energy, stopped)

            val row = assertNotNull(store?.readEnergyRuntimeRow())
            val state = EnergyStateCodec.decodeState(row.stateJson)
            assertEquals(50L, state.lastSourceElapsedMs)
            assertEquals(2_000L, state.lastEvaluationElapsedMs)
            assertEquals(5L, state.lastBusinessInputId)

            store?.close()
            store = TripStore(TripDatabaseHelper(context, databaseName))
            energy = EnergySessionCoordinator(requireNotNull(store))
            val olderPrefix = energy.process(receipt(generation, 4L, 100L, 2_500L, true))
            assertTrue(olderPrefix.stale)
            assertNull(olderPrefix.snapshot)
            val repeatedStop = energy.process(
                receipt(generation, 5L, 50L, 2_000L, false, lateRelevantSource = true)
            )
            assertTrue(repeatedStop.duplicate)
            assertEquals(stoppedSnapshot, repeatedStop.snapshot)
            confirm(energy, repeatedStop)
            assertFalse(energy.runtimeStatus().active)
            assertEquals(5L, energy.runtimeStatus().lastBusinessInputId)
        } finally {
            store?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun receipt(
        generation: String,
        inputId: Long,
        sourceElapsedMs: Long,
        evaluationElapsedMs: Long,
        powerOn: Boolean,
        current: Double = 100.0,
        lateRelevantSource: Boolean = false
    ) = EnergyReceipt(
        sourceIdentity = EnergyReceipt.businessIdentity(generation, inputId),
        observedAt = "2026-10-26T10:00:00Z",
        input = EnergyInput(
            bootId = "energy-replay-boot",
            elapsedMs = sourceElapsedMs,
            voltage = 400.0,
            current = current,
            powerOn = powerOn,
            gunDisconnected = true,
            externalCharging = false
        ),
        businessGeneration = generation,
        businessInputId = inputId,
        evaluationBootId = "energy-replay-boot",
        evaluationElapsedMs = evaluationElapsedMs,
        lateRelevantSource = lateRelevantSource
    )

    private fun confirm(coordinator: EnergySessionCoordinator, result: EnergyProcessResult) {
        result.pendingProjection?.let { assertTrue(coordinator.confirmProjected(it.snapshot.snapshotId)) }
    }
}
