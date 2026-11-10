package com.bydcollector.collector.data.local

import android.app.Application
import com.bydcollector.collector.data.energy.*
import com.bydcollector.collector.data.input.*
import com.bydcollector.collector.data.trips.*
import com.bydcollector.collector.service.*
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.telegram.TelegramCoordinator
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real SQLite and production energy/Trips calculations; no vehicle or HTTP result is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class BusinessPageRecoveryTest {
    @Test fun orderedPageReopensAcrossAllDatabases() {
        val context = RuntimeEnvironment.getApplication()
        val prefix = "page"
        val names = listOf("${prefix}_main.db", "${prefix}_trips.db", "${prefix}_telegram.db")
        fun main() = TelemetryStore(context, TelemetryDatabaseHelper(context, names[0]),
            operationalEventJournal = OperationalEventJournal(context))
        fun trips() = TripStore(TripDatabaseHelper(context, names[1]))
        fun telegram() = TelegramStore(context, TelegramDatabaseHelper(context, names[2]))
        try {
            val refs = main().use { store ->
                repeat(5) { index -> store.enqueueEvaluation(BusinessInputTimerSource(
                    "timer-$index", "boot", "timer", index.toLong(), 1_800_000_000_000L + index * 10_000L,
                    1_000L + index * 10_000L), null) }
                store.pendingBusinessInputs()
            }
            val generation = main().use { it.businessInputGeneration() }
            val page = trips().use { store ->
                val runtime = TripRuntimeCoordinator(context, store, { true }, { false }, {}, { true }, {}, { _, _, _ -> },
                    bootIdProvider = { "boot" })
                try {
                    runtime.prepareBusinessPage(false, false, {}) {
                        val buffered = BufferedEnergyStorage(store.readEnergyRuntimeRow())
                        val energy = EnergySessionCoordinator(buffered)
                        val entries = refs.mapIndexed { index, ref ->
                            val on = index != 2 && index != 4
                            val elapsed = checkNotNull(ref.acquiredElapsedMs)
                            val at = Instant.ofEpochMilli(checkNotNull(ref.acquiredWallMs)).toString()
                            val result = energy.process(EnergyReceipt(EnergyReceipt.businessIdentity(generation, ref.id), at,
                                EnergyInput("boot", elapsed, 400.0, 100.0, on, true, false),
                                businessGeneration = generation, businessInputId = ref.id,
                                evaluationBootId = "boot", evaluationElapsedMs = elapsed))
                            val projection = result.pendingProjection
                            if (projection != null) assertTrue(energy.confirmProjected(projection.snapshot.snapshotId))
                            val stamp = NormalizedSourceStamp(NormalizedSourceKind.POLL, ref.sourceIdentity, "boot", "timer",
                                index.toLong(), checkNotNull(ref.acquiredWallMs), elapsed)
                            val speedKey = "speed_1013_-1807745016_7"
                            val odometerKey = com.bydcollector.collector.data.normalized.NormalizedFieldCatalog.odometerKm.sourceKeys.single()
                            val counterKey = com.bydcollector.collector.data.normalized.NormalizedFieldCatalog.tripEnergy.sourceKeys.single()
                            val motion = BusinessInputProjection.observations(VehicleStateNormalizer(), mapOf(
                                speedKey to NormalizedSourceInput(PollReading(speedKey, "1092616192", "10.0"), stamp, null),
                                odometerKey to NormalizedSourceInput(PollReading(odometerKey, (100000 + index * 2).toString(),
                                    (100000 + index * 2).toString()), stamp, null),
                                counterKey to NormalizedSourceInput(PollReading(counterKey,
                                    java.lang.Float.floatToRawIntBits(index * 0.2f).toString(), (index * 0.2).toString()), stamp, null)))
                            val trip = runtime.onBusinessInput(generation, ref.id, ref.sourceIdentity, at,
                                listOf(PollReading("bodywork_power_level", if (on) "2" else "0", if (on) "2" else "0")),
                                motion, false, result.snapshot, stamp).get(1, TimeUnit.SECONDS)
                            BusinessPageEntry(ref.id, ref.sourceIdentity, result.snapshot, projection != null, trip)
                        }
                        buffered.commitTo(store)
                        BusinessPage(generation, "boot", entries)
                    }.get(5, TimeUnit.SECONDS)
                } finally { runtime.close() }
            }
            val completions = trips().use { store ->
                assertEquals(page, store.pendingBusinessPage())
                assertEquals(2, store.allPendingCompletions().size)
                store.allPendingCompletions().forEach {
                    assertTrue(it.session?.movementObserved == true, "Missing movement: ${it.session}")
                    assertTrue((it.session?.distanceKm ?: 0.0) > 0.1, "Missing distance: ${it.session}")
                }
                assertFalse(EnergySessionCoordinator(store).runtimeStatus().active)
                store.allPendingCompletions()
            }
            main().use { assertEquals(refs, it.pendingBusinessInputs()) }
            telegram().use { store -> main().use { eventStore ->
                val settings = CollectorSettings(context)
                settings.setTelegramEnabled(true)
                settings.setTelegramEventEnabled(com.bydcollector.collector.telegram.TelegramEventType.TRIP_SUMMARY.key, true)
                val coordinator = TelegramCoordinator(eventStore, store, settings, VehicleStateNormalizer(),
                    dispatchSend = { error("Local page must not dispatch HTTP") })
                coordinator.processBusinessPage(page.key) {
                    refs.zip(page.entries).forEachIndexed { index, (ref, entry) ->
                        val on = index != 2 && index != 4
                        coordinator.onSourcePoll(0, BusinessInputProjection.timestamp(ref), BusinessInputProjection.source(ref),
                            listOf(PollReading("bodywork_power_level", if (on) "2" else "0", if (on) "2" else "0")),
                            PollOrigin.REPLAY, "boot", entry.energy, requireCommit = true)
                        completions.filter { it.sequence == entry.trip?.completionWatermark && entry.trip.closedPowerSession }
                            .forEach { coordinator.acceptTripCompletion(it, null) }
                    }
                }
                coordinator.processBusinessPage(page.key) { error("Committed page must not execute twice") }
            }
            }
            telegram().use { store ->
                assertTrue(store.hasBusinessPageReceipt(page.key))
                completions.forEach { assertTrue(store.hasTripCompletionReceipt(it.sequence, it.identity)) }
                assertEquals(2L, store.diagnosticSnapshot().outboxTotal, store.loadTelegramRuntimeState { _, _ -> }.toJson())
            }
            val olderLocation = com.bydcollector.collector.location.LocationNormalizer.gap("2026-11-10T10:00:00Z", "page-gap")
            val newerLocation = com.bydcollector.collector.location.LocationNormalizer.gap("2026-11-10T10:00:01Z", "provider-gap")
            val observations = page.entries.filter { it.projectEnergy }.flatMap { EnergyTelemetryProjection.observations(it.energy!!) } + olderLocation
            val first = main().use { store ->
                store.applyNormalizedObservations(newerLocation)
                assertFailsWith<IllegalStateException> { store.completeBusinessPage("other-generation", refs, emptyMap(), emptyMap(), observations) }
                assertEquals(refs, store.pendingBusinessInputs())
                store.completeBusinessPage(generation, refs, emptyMap(), emptyMap(), observations)
            }
            assertTrue(first.observedCount > 0)
            TelemetryDatabaseHelper(context, names[0]).use { helper ->
                val column = if (helper.isCompactV2()) "observed_at_ms" else "observed_at"
                val value = if (helper.isCompactV2()) Instant.parse("2026-11-10T10:00:00Z").toEpochMilli().toString() else "2026-11-10T10:00:00Z"
                helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM vehicle_state_history WHERE $column=?", arrayOf(value)).use {
                    assertTrue(it.moveToFirst()); assertEquals(olderLocation.size, it.getInt(0))
                }
            }
            main().use { store ->
                val current = store.normalizedCurrentState(setOf("location"))
                assertTrue(current.isNotEmpty())
                assertTrue(current.all { it.observedAt == "2026-11-10T10:00:01Z" })
                assertEquals(refs.last().id, store.completedBusinessInputId())
                assertNull(store.nextPendingBusinessInput())
                assertEquals(0, store.completeBusinessPage(generation, refs, emptyMap(), emptyMap(), observations).observedCount)
            }
            trips().use { store ->
                assertEquals(page, store.pendingBusinessPage())
                completions.forEach { store.acknowledgeCompletion(it.sequence, it.identity) }
                store.acknowledgeBusinessPage(page.key)
            }
            trips().use { assertNull(it.pendingBusinessPage()); assertTrue(it.allPendingCompletions().isEmpty()) }
        } finally { names.forEach(context::deleteDatabase) }
    }

    @Test fun failedPageRollsBackTripsAndEnergy() {
        val context = RuntimeEnvironment.getApplication()
        val name = "page_rollback_${System.nanoTime()}.db"
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
                    store.commitBusinessPage {
                        store.upsertSession(TripSession(tripId = "rolled-back", startedAt = "2026-11-10T10:00:00Z"))
                        val energy = EnergySessionCoordinator(store)
                        energy.process(EnergyReceipt("input", "2026-11-10T10:00:00Z",
                            EnergyInput("boot", 1_000L, 400.0, 100.0, true, true, false)))
                        store.consumeBusinessInput("generation", 1L, "input") { TripBusinessInputMutation(Unit, "x".repeat(521)) }
                        BusinessPage("generation", "boot", listOf(BusinessPageEntry(1, "input", null, false, null)))
                    }
                }
            }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                assertNull(store.pendingBusinessPage())
                assertNull(store.readEnergyRuntimeRow())
                assertNull(store.businessInputCursor())
                assertNull(store.session("rolled-back"))
            }
        } finally { context.deleteDatabase(name) }
    }
}
