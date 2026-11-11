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
    @Test fun businessConstraintParksUntilStorageReopenedWithoutDroppingInputs() {
        val context = RuntimeEnvironment.getApplication()
        val name = "business_fault_${System.nanoTime()}.db"
        fun open() = TelemetryStore(context, TelemetryDatabaseHelper(context, name),
            operationalEventJournal = OperationalEventJournal(context))
        var store = open()
        val fault = java.util.concurrent.CountDownLatch(1)
        val retried = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val worker = com.bydcollector.collector.data.callback.CallbackNormalizationWorker(
            "business-real-sqlite-check",
            drainPage = {
                if (attempts.incrementAndGet() > 1) retried.countDown()
                val ref = store.nextPendingBusinessInput()
                if (ref != null) store.completeBusinessInput(ref,
                    BusinessInputProjection.incoming(store.loadBusinessInputDelta(ref)))
                val more = store.nextPendingBusinessInput() != null
                if (!more) completed.countDown()
                more
            },
            onFault = { fault.countDown() },
            requiresRepair = com.bydcollector.collector.data.callback.CallbackNormalizationWorker::isPersistentSqliteFault
        )
        try {
            store.insertPoll(store.openSession(), PersistedPollInput(
                timestamp = "2026-11-11T07:00:00Z", ok = true, elapsedMs = 1, requestCount = 1,
                errors = null, rawResponseBody = null,
                readings = listOf(PollReading("gearbox_1011_555745336_5", "4", "4")),
                source = com.bydcollector.collector.data.polling.PollSampleSource("gear", "boot", 1_000, "timer", 1),
                origin = PollOrigin.REPLAY), store.getActiveCatalogParameters())
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("DROP TABLE business_input_source_checkpoints")
                db.execSQL(checkNotNull(javaClass.classLoader?.getResourceAsStream("business-checkpoint-v18.sql"))
                    .bufferedReader().use { it.readText() })
                db.version = 18
            }
            val generation = store.businessInputGeneration()
            val first = assertNotNull(store.nextPendingBusinessInput())
            assertTrue(worker.start())
            assertTrue(fault.await(5, TimeUnit.SECONDS))
            assertTrue(worker.isRunning())
            store.enqueueEvaluation(BusinessInputTimerSource("later", "boot", "timer", 2,
                1_800_000_002_000, 2_000), null)
            repeat(100) { worker.signal() }
            assertFalse(retried.await(1_300, TimeUnit.MILLISECONDS), "Unrepaired CHECK must not retry")
            assertEquals(first, store.nextPendingBusinessInput())
            assertEquals(2, store.pendingBusinessInputs().size)
            assertTrue(worker.stopAndJoin(2_000))
            store.close()
            store = open()
            assertEquals(generation, store.businessInputGeneration())
            assertTrue(worker.start())
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(worker.stopAndJoin(2_000))
            assertNull(store.nextPendingBusinessInput())
            assertEquals("4", store.loadBusinessInputCheckpoint().getValue("gearbox_1011_555745336_5").reading.rawValue)
        } finally {
            worker.stopAndJoin(2_000)
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun main18UpgradePreservesAndCompletesPageAlreadyCommittedByTripsAndTelegram() {
        val context = RuntimeEnvironment.getApplication()
        val prefix = "page"
        val names = listOf("${prefix}_main.db", "${prefix}_trips.db", "${prefix}_telegram.db")
        fun main() = TelemetryStore(context, TelemetryDatabaseHelper(context, names[0]),
            operationalEventJournal = OperationalEventJournal(context))
        fun trips() = TripStore(TripDatabaseHelper(context, names[1]))
        fun telegram() = TelegramStore(context, TelegramDatabaseHelper(context, names[2]))
        try {
            val refs = main().use { store ->
                val session = store.openSession()
                store.insertPoll(session, PersistedPollInput(
                    timestamp = Instant.ofEpochMilli(1_799_999_999_000L).toString(), ok = true,
                    elapsedMs = 1, requestCount = 1, errors = null, rawResponseBody = null,
                    readings = listOf(PollReading("bodywork_power_level", "0", "0")),
                    source = com.bydcollector.collector.data.polling.PollSampleSource("before-page", "boot", 0, "timer", 0),
                    origin = PollOrigin.REPLAY), store.getActiveCatalogParameters())
                val previous = assertNotNull(store.nextPendingBusinessInput())
                store.completeBusinessInput(previous, BusinessInputProjection.incoming(store.loadBusinessInputDelta(previous)))
                store.insertPoll(session, PersistedPollInput(
                    timestamp = Instant.ofEpochMilli(1_800_000_000_000L).toString(), ok = true,
                    elapsedMs = 1, requestCount = 1, errors = null, rawResponseBody = null,
                    readings = listOf(PollReading("gearbox_1011_555745336_5", "4", "4")),
                    source = com.bydcollector.collector.data.polling.PollSampleSource("gear-page", "boot", 1_000, "timer", 1),
                    origin = PollOrigin.REPLAY), store.getActiveCatalogParameters())
                (1..4).forEach { index -> store.enqueueEvaluation(BusinessInputTimerSource(
                    "timer-$index", "boot", "timer", index.toLong(), 1_800_000_000_000L + index * 10_000L,
                    1_000L + index * 10_000L), null) }
                store.pendingBusinessInputs()
            }
            val generation = main().use { it.businessInputGeneration() }
            val checkpoint = main().use { it.loadBusinessInputCheckpoint() }
            val updates = main().use { BusinessInputProjection.incoming(it.loadBusinessInputDelta(refs.first())) }
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
            // Reopen the historical Main18 schema while Trips/Telegram already have their durable page receipts.
            context.openOrCreateDatabase(names[0], 0, null).use { db ->
                db.execSQL("INSERT INTO business_input_source_seed SELECT * FROM business_input_source_checkpoints")
                val ddl = checkNotNull(javaClass.classLoader?.getResourceAsStream("business-checkpoint-v18.sql"))
                    .bufferedReader().use { it.readText() }
                listOf("business_input_source_checkpoints", "business_input_source_seed").forEach { table ->
                    db.execSQL("ALTER TABLE $table RENAME TO ${table}_fixture")
                    db.execSQL(ddl.replace("business_input_source_checkpoints", table))
                    db.execSQL("INSERT INTO $table SELECT * FROM ${table}_fixture")
                    db.execSQL("DROP TABLE ${table}_fixture")
                    assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
                        db.execSQL("INSERT INTO $table SELECT 'gearbox_1011_555745336_5', raw_value, desc_value, " +
                            "source_kind, source_identity, source_boot_id, source_generator_id, source_sequence, source_epoch, " +
                            "source_wall_ms, source_elapsed_ms, source_poll_id, updated_at_ms FROM $table")
                    }
                }
                db.version = 18
            }
            main().use { store ->
                assertEquals(generation, store.businessInputGeneration())
                assertEquals(refs, store.pendingBusinessInputs())
                assertEquals(checkpoint, store.loadBusinessInputCheckpoint())
                assertEquals(checkpoint, store.loadBusinessInputInitialCheckpoint())
            }
            trips().use { assertEquals(page, it.pendingBusinessPage()) }
            telegram().use { store ->
                assertTrue(store.hasBusinessPageReceipt(page.key))
                assertEquals(2L, store.diagnosticSnapshot().outboxTotal)
            }
            val olderLocation = com.bydcollector.collector.location.LocationNormalizer.gap("2026-11-10T10:00:00Z", "page-gap")
            val newerLocation = com.bydcollector.collector.location.LocationNormalizer.gap("2026-11-10T10:00:01Z", "provider-gap")
            val observations = page.entries.filter { it.projectEnergy }.flatMap { EnergyTelemetryProjection.observations(it.energy!!) } + olderLocation
            val first = main().use { store ->
                store.applyNormalizedObservations(newerLocation)
                assertFailsWith<IllegalStateException> { store.completeBusinessPage("other-generation", refs, emptyMap(), emptyMap(), observations) }
                assertEquals(refs, store.pendingBusinessInputs())
                store.completeBusinessPage(generation, refs, updates, emptyMap(), observations)
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
                assertEquals("4", store.loadBusinessInputCheckpoint().getValue("gearbox_1011_555745336_5").reading.rawValue)
                assertEquals(0, store.completeBusinessPage(generation, refs, updates, emptyMap(), observations).observedCount)
                TelemetryDatabaseHelper(context, names[0]).use { helper ->
                    assertEquals(19, helper.readableDatabase.version)
                    helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM business_input_receipts", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(refs.size + 1, it.getInt(0))
                    }
                    helper.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                }
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
