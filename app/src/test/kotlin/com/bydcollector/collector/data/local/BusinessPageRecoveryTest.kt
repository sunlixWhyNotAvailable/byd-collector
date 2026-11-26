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
    @Test fun archivedRecoveryCannotEvictAnAlreadyFullOutbox() {
        val context = RuntimeEnvironment.getApplication()
        val name = "recovery-capacity.db"
        val page = BusinessPage("capacity-generation", "boot", listOf(BusinessPageEntry(1, "input", null, false, null)))
        try {
            TelegramStore(context, TelegramDatabaseHelper(context, name)).use { store ->
                store.archiveUnconfirmedBusinessPage(page, emptyList())
                store.commitTelegramEvents((1..TelegramDatabaseHelper.MAX_PENDING.toInt()).map {
                    TelegramOutboxMessage("queued-$it", "capacity-input", "original $it")
                }, null)
                assertFailsWith<IllegalStateException> {
                    store.completeBusinessRecovery(page.key,
                        listOf(TelegramOutboxMessage("recovered", "capacity-input", "archived message")),
                        emptyList(), emptyMap(), System.currentTimeMillis(), emptySet())
                }
            }
            TelegramStore(context, TelegramDatabaseHelper(context, name)).use { store ->
                assertEquals(TelegramDatabaseHelper.MAX_PENDING, store.diagnosticSnapshot().outboxTotal)
                assertEquals("original 1", store.telegramMessageByDedupeKey("queued-1")?.payload)
                assertEquals(page.encode(), assertNotNull(store.nextBusinessRecovery(Long.MAX_VALUE)).pagePayload)
                assertNull(store.telegramMessageByDedupeKey("recovered"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun telegramV8RecoveryArchiveSurvivesAdditiveUpgrade() {
        val context = RuntimeEnvironment.getApplication()
        val name = "telegram-v8-recovery.db"
        val intent = TripCompletionIntent(17, "historical-completion", "2026-11-25T10:00:00Z")
        val payload = org.json.JSONArray().put(org.json.JSONObject(TripCompletionIntentCodec.encode(intent))).toString()
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                // Actual v8 table definitions; no terminal delivery result is manufactured.
                db.execSQL("CREATE TABLE telegram_unconfirmed_business_recovery " +
                    "(page_key TEXT PRIMARY KEY, page_payload TEXT NOT NULL, completions_json TEXT NOT NULL)")
                db.execSQL("CREATE TABLE telegram_conflicting_delivery (dedupe_key TEXT PRIMARY KEY NOT NULL, " +
                    "attempt_id TEXT NOT NULL, owner_id TEXT NOT NULL, sequence INTEGER NOT NULL, event_type TEXT NOT NULL, " +
                    "payload TEXT, key_json TEXT NOT NULL, diagnostic TEXT NOT NULL, archived_at_ms INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE telegram_delivery_attempt (id INTEGER PRIMARY KEY CHECK (id=1), " +
                    "owner_id TEXT NOT NULL, attempt_id TEXT NOT NULL UNIQUE, sequence INTEGER NOT NULL CHECK(sequence>0), " +
                    "outbox_id INTEGER NOT NULL, dedupe_key TEXT NOT NULL, event_type TEXT NOT NULL, request_digest TEXT NOT NULL, " +
                    "destination_digest TEXT NOT NULL, dedupe_ref TEXT NOT NULL, bot_scope TEXT NOT NULL, started_at_ms INTEGER NOT NULL, " +
                    "local_settled INTEGER NOT NULL DEFAULT 0 CHECK(local_settled IN (0,1)))")
                db.execSQL("CREATE TABLE telegram_trip_completion_receipt (id INTEGER PRIMARY KEY CHECK(id=1), " +
                    "sequence INTEGER NOT NULL, identity TEXT NOT NULL)")
                db.execSQL("INSERT INTO telegram_unconfirmed_business_recovery VALUES(?, ?, ?)",
                    arrayOf("old-page", "original-page-data", payload))
                db.execSQL("INSERT INTO telegram_trip_completion_receipt VALUES(1, ?, ?)", arrayOf(intent.sequence, intent.identity))
                db.version = 8
            }
            repeat(2) {
                TelegramStore(context, TelegramDatabaseHelper(context, name)).use { store ->
                    val recovered = assertNotNull(store.nextBusinessRecovery(Long.MAX_VALUE))
                    assertEquals("original-page-data", recovered.pagePayload)
                    assertEquals(payload, recovered.completionsJson)
                    assertNull(recovered.beforeStateJson)
                    assertTrue(store.hasTripCompletionReceipt(intent.sequence, intent.identity))
                    assertNull(store.activeTelegramDeliveryAttempt())
                    store.ensureDeliveryDurable()
                    val parseError = assertFailsWith<org.json.JSONException> {
                        org.json.JSONObject("{\"payload\":\"private archived report\", broken")
                    }
                    store.failBusinessRecovery(recovered.pageKey, parseError, false, System.currentTimeMillis())
                    val diagnostic = store.recoveryDiagnosticLines().joinToString("\n")
                    assertContains(diagnostic, "telegram_sqlite_synchronous=2")
                    assertContains(diagnostic, "\"before_state_saved\":false")
                    assertFalse(diagnostic.contains("original-page-data"))
                    assertFalse(diagnostic.contains(intent.identity))
                    assertFalse(diagnostic.contains("private archived report"))
                    assertContains(diagnostic, "org.json.JSONException")
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    @Config(sdk = [26, 29])
    fun handoffDatabasesKeepFullSyncAcrossReopen() {
        val context = RuntimeEnvironment.getApplication()
        val telegramName = "durable-telegram.db"
        val tripsName = "durable-trips.db"
        try {
            repeat(2) {
                val telegram = TelegramDatabaseHelper(context, telegramName)
                try {
                    val db = telegram.writableDatabase
                    db.rawQuery("PRAGMA synchronous", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(2, cursor.getInt(0))
                    }
                    DurableSqlite.barrier(db)
                } finally { telegram.close() }
                val trips = TripDatabaseHelper(context, tripsName)
                try {
                    val db = trips.writableDatabase
                    db.rawQuery("PRAGMA synchronous", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(2, cursor.getInt(0))
                    }
                    DurableSqlite.barrier(db)
                } finally { trips.close() }
            }
        } finally {
            context.deleteDatabase(telegramName)
            context.deleteDatabase(tripsName)
        }
    }

    @Test fun completedPageWithoutTelegramReceiptIsArchivedBeforeConsumingExactTripsHandoff() {
        val context = RuntimeEnvironment.getApplication()
        val tripsName = "unconfirmed-page-trips.db"
        val telegramName = "unconfirmed-page-telegram.db"
        val old = TripCompletionIntent(1, "old", "2026-11-25T10:00:00Z")
        val newer = TripCompletionIntent(2, "newer", "2026-11-25T11:00:00Z")
        val page = BusinessPage("generation", "boot", listOf(
            BusinessPageEntry(5, "input", null, false, TripBusinessResult(
                consumed = true, priorCompletionWatermark = 0, completionWatermark = 1,
                powerSessionId = null, confirmedPowerOn = false, closedPowerSession = true))
        ))
        try {
            TripStore(TripDatabaseHelper(context, tripsName)).use { trips ->
                trips.replaceCompletionState(2, listOf(old, newer))
                trips.replaceBusinessPage(page)
            }
            TelegramStore(context, TelegramDatabaseHelper(context, telegramName)).use {
                it.archiveUnconfirmedBusinessPage(page, listOf(old))
            }
            TripStore(TripDatabaseHelper(context, tripsName)).use { trips ->
                assertFailsWith<IllegalStateException> { trips.acknowledgeBusinessPage("wrong", listOf(old)) }
                assertEquals(listOf(old, newer), trips.allPendingCompletions())
                assertEquals(page, trips.pendingBusinessPage())
                TelegramStore(context, TelegramDatabaseHelper(context, telegramName)).use { telegram ->
                    telegram.archiveUnconfirmedBusinessPage(page, listOf(old))
                    assertFalse(telegram.hasBusinessPageReceipt(page.key))
                    assertEquals(0L, telegram.diagnosticSnapshot().outboxTotal)
                }
                trips.acknowledgeBusinessPage(page.key, listOf(old))
                assertNull(trips.pendingBusinessPage())
                assertEquals(listOf(newer), trips.allPendingCompletions())
            }
            context.openOrCreateDatabase(telegramName, 0, null).use { db ->
                db.rawQuery("SELECT page_payload, completions_json FROM telegram_unconfirmed_business_recovery", null)
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(page, BusinessPage.decode(cursor.getString(0)))
                        val archived = org.json.JSONArray(cursor.getString(1))
                        assertEquals(1, archived.length())
                        assertEquals(old, TripCompletionIntentCodec.decode(archived.getJSONObject(0).toString()))
                    }
            }
        } finally {
            context.deleteDatabase(tripsName)
            context.deleteDatabase(telegramName)
        }
    }
    @Test fun expiredFinalCountersRemainUnknownInStoredStationaryCompletion() {
        val context = RuntimeEnvironment.getApplication()
        val name = "missing_endpoint.db"
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val runtime = TripRuntimeCoordinator(context, store, { true }, { false }, {}, { true }, {}, { _, _, _ -> },
                    bootIdProvider = { "boot" })
                val energy = EnergySessionCoordinator(BufferedEnergyStorage(null))
                val odo = "statistic_1014_1246765072_5"
                val soc = "statistic_1014_1145045040_5"
                val seed = NormalizedSourceStamp(NormalizedSourceKind.POLL, "seed", "boot", "probe", 1, 100001, 1)
                val counters = mapOf(odo to NormalizedSourceInput(PollReading(odo, "1000", "1000"), seed, 1),
                    soc to NormalizedSourceInput(PollReading(soc, "75", "75"), seed, 1))
                try {
                    listOf(1L, 60001L).forEachIndexed { index, time ->
                        val on = index == 0
                        val at = Instant.ofEpochMilli(100000 + time).toString()
                        val id = index + 1L
                        val result = energy.process(EnergyReceipt(EnergyReceipt.businessIdentity("endpoints", id), at,
                            EnergyInput("boot", time, 400.0, 100.0, on, true, false, EnergyIntervalContinuity(0)),
                            businessGeneration = "endpoints", businessInputId = id))
                        result.pendingProjection?.let { assertTrue(energy.confirmProjected(it.snapshot.snapshotId)) }
                        val observations = BusinessInputProjection.observations(VehicleStateNormalizer(),
                            BusinessInputProjection.freshInputs(counters, "boot", time))
                        runtime.onBusinessInput("endpoints", id, "sample:$id", at,
                            listOf(PollReading("bodywork_power_level", if (on) "2" else "0", if (on) "2" else "0")),
                            observations, false, result.snapshot, seed.copy(identity = "sample:$id", sequence = id,
                                wallMs = 100000 + time, elapsedMs = time)).get(1, TimeUnit.SECONDS)
                    }
                } finally { runtime.close() }
            }
            TripStore(TripDatabaseHelper(context, name)).use { reopened ->
                val completion = reopened.allPendingCompletions().single()
                val closed = assertNotNull(completion.session)
                assertEquals(100.0, closed.lastOdometerKm)
                assertNull(closed.distanceKm)
                assertNull(closed.endSoc)
                assertNull(completion.odometerKm)
                assertEquals(false, closed.counterCoverage?.odometerEndKnown)
                assertEquals(false, closed.counterCoverage?.socEndKnown)
                assertTrue("odometer_end_missing" in closed.quality)
                val config = TelegramEventConfig(setOf(com.bydcollector.collector.telegram.TelegramEventType.TRIP_SUMMARY),
                    5, 12.0, 60000, 5000)
                val report = TelegramEventEngine().onPowerOffConfirmed(config,
                    TelegramPowerOffSnapshot(energySnapshot = completion.energySnapshot, session = closed,
                        completionIdentity = completion.identity), nowMs = 160001).events.single()
                assertEquals("n/a", report.variables["total_distance_km"])
                assertEquals("n/a", report.variables["total_soc_end"])
                val legacyCoverage = assertNotNull(closed.counterCoverage).toJson().apply {
                    remove("odometer_end"); remove("soc_end")
                }
                val legacy = closed.copy(distanceKm = 0.0, endSoc = 75.0, quality = TripSession.QUALITY_OK,
                    counterCoverage = TripCounterCoverage.fromJson(legacyCoverage))
                val oldReport = TelegramEventEngine().onPowerOffConfirmed(config,
                    TelegramPowerOffSnapshot(100.0, 75.0, energySnapshot = completion.energySnapshot,
                        session = legacy, completionIdentity = completion.identity), nowMs = 160001).events.single()
                assertEquals("n/a", oldReport.variables["total_distance_km"])
                assertEquals("n/a", oldReport.variables["total_soc_end"])
            }
        } finally { context.deleteDatabase(name) }
    }

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

    @Test fun checkpointUpgradesPreserveAndCompletePageAlreadyCommittedByTripsAndTelegram() {
        listOf(18, 19).forEach(::upgradePreservesAndCompletesPage)
    }

    private fun upgradePreservesAndCompletesPage(oldVersion: Int) {
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
                                EnergyInput("boot", elapsed, 400.0, 100.0, on, true, false, EnergyIntervalContinuity(0)),
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
                                "statistic_1014_1145045040_5" to NormalizedSourceInput(
                                    PollReading("statistic_1014_1145045040_5", "75", "75"), stamp, null),
                                speedKey to NormalizedSourceInput(PollReading(speedKey,
                                    (if (index < 3) 10f else 0f).toRawBits().toString(), if (index < 3) "10.0" else "0.0"), stamp, null),
                                odometerKey to NormalizedSourceInput(PollReading(odometerKey, (100000 + minOf(index, 3) * 2).toString(),
                                    (100000 + minOf(index, 3) * 2).toString()), stamp, null),
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
                val closed = store.allPendingCompletions().map { requireNotNull(it.session) }
                assertTrue(closed.first().movementObserved)
                assertTrue(requireNotNull(closed.first().distanceKm) > 0.1)
                assertFalse(closed.last().movementObserved)
                assertEquals(0.0, closed.last().distanceKm)
                assertEquals(true, closed.last().counterCoverage?.odometerEndKnown)
                assertFalse(EnergySessionCoordinator(store).runtimeStatus().active)
                store.allPendingCompletions()
            }
            main().use { assertEquals(refs, it.pendingBusinessInputs()) }
            val recoveredName = "page_recovered_$oldVersion.db"
            try {
                val settings = CollectorSettings(context)
                settings.setTelegramEnabled(true)
                settings.setTelegramEventEnabled(com.bydcollector.collector.telegram.TelegramEventType.TRIP_SUMMARY.key, true)
                val dispatched = mutableListOf<Any>()
                val liveState = TelegramStore(context, TelegramDatabaseHelper(context, recoveredName)).use { recovered ->
                    main().use { eventStore ->
                        val coordinator = TelegramCoordinator(eventStore, recovered, settings, VehicleStateNormalizer(),
                            dispatchSend = { dispatched += it })
                        coordinator.onSourcePoll(1, "2027-01-16T08:00:00Z",
                            com.bydcollector.collector.data.polling.PollSampleSource("new-session", "new-boot", 100),
                            listOf(PollReading("bodywork_power_level", "2", "2")), PollOrigin.LIVE, "new-boot")
                        recovered.archiveUnconfirmedBusinessPage(page, completions)
                        assertNotNull(recovered.telegramRuntimeState())
                    }
                }
                TelegramStore(context, TelegramDatabaseHelper(context, recoveredName)).use { recovered ->
                    main().use { eventStore ->
                        val coordinator = TelegramCoordinator(eventStore, recovered, settings, VehicleStateNormalizer(),
                            dispatchSend = { dispatched += it })
                        coordinator.recoverOneBusinessPage()
                        val count = recovered.diagnosticSnapshot().outboxTotal
                        assertTrue(count > 0)
                        assertEquals(liveState, recovered.telegramRuntimeState())
                        completions.forEach { assertTrue(recovered.hasTripCompletionReceipt(it.sequence, it.identity)) }
                        coordinator.recoverOneBusinessPage()
                        assertEquals(count, recovered.diagnosticSnapshot().outboxTotal)
                        assertNull(recovered.businessRecoveryDeadline())
                        assertTrue(dispatched.isEmpty())
                    }
                }
            } finally { context.deleteDatabase(recoveredName) }
            telegram().use { store -> main().use { eventStore ->
                val settings = CollectorSettings(context)
                settings.setTelegramEnabled(true)
                settings.setTelegramEventEnabled(com.bydcollector.collector.telegram.TelegramEventType.TRIP_SUMMARY.key, true)
                val coordinator = TelegramCoordinator(eventStore, store, settings, VehicleStateNormalizer(),
                    dispatchSend = { error("Local page must not dispatch HTTP") })
                val applyPage = {
                    refs.zip(page.entries).forEachIndexed { index, (ref, entry) ->
                        val on = index != 2 && index != 4
                        coordinator.onSourcePoll(0, BusinessInputProjection.timestamp(ref), BusinessInputProjection.source(ref),
                            listOf(PollReading("bodywork_power_level", if (on) "2" else "0", if (on) "2" else "0")),
                            PollOrigin.REPLAY, "boot", entry.energy, requireCommit = true,
                            startedPowerSessionId = entry.trip?.takeIf { it.confirmedPowerOn }?.powerSessionId)
                        completions.filter { it.sequence == entry.trip?.completionWatermark && entry.trip.closedPowerSession }
                            .forEach { coordinator.acceptTripCompletion(it, null) }
                    }
                }
                context.openOrCreateDatabase(names[2], 0, null).use { db ->
                    db.execSQL("CREATE TRIGGER reject_final BEFORE INSERT ON telegram_business_page_receipt " +
                        "BEGIN SELECT RAISE(ABORT, 'final receipt write rejected'); END")
                }
                assertFailsWith<android.database.sqlite.SQLiteException> { coordinator.processBusinessPage(page.key, applyPage) }
                assertFalse(store.hasBusinessPageReceipt(page.key))
                assertEquals(0L, store.diagnosticSnapshot().outboxTotal)
                completions.forEach { assertFalse(store.hasTripCompletionReceipt(it.sequence, it.identity)) }
                context.openOrCreateDatabase(names[2], 0, null).use { it.execSQL("DROP TRIGGER reject_final") }
                coordinator.processBusinessPage(page.key, applyPage)
                coordinator.processBusinessPage(page.key) { error("Committed page must not execute twice") }
            }
            }
            telegram().use { store ->
                assertTrue(store.hasBusinessPageReceipt(page.key))
                completions.forEach { assertTrue(store.hasTripCompletionReceipt(it.sequence, it.identity)) }
                assertEquals(2L, store.diagnosticSnapshot().outboxTotal, store.loadTelegramRuntimeState { _, _ -> }.toJson())
                val parked = assertNotNull(store.telegramMessageByDedupeKey("${completions.last().identity}:final_summary"))
                assertTrue(parked.payload.contains("0 км") || parked.payload.contains("0 km"))
                assertFalse(parked.payload.contains("n/a"), parked.payload)
            }
            // Reopen the historical schema while Trips/Telegram already have their durable page receipts.
            context.openOrCreateDatabase(names[0], 0, null).use { db ->
                db.execSQL("INSERT INTO business_input_source_seed SELECT * FROM business_input_source_checkpoints")
                val ddl = checkNotNull(javaClass.classLoader?.getResourceAsStream("business-checkpoint-v$oldVersion.sql"))
                    .bufferedReader().use { it.readText() }
                listOf("business_input_source_checkpoints", "business_input_source_seed").forEach { table ->
                    db.execSQL("ALTER TABLE $table RENAME TO ${table}_fixture")
                    db.execSQL(ddl.replace("business_input_source_checkpoints", table))
                    db.execSQL("INSERT INTO $table SELECT * FROM ${table}_fixture")
                    db.execSQL("DROP TABLE ${table}_fixture")
                    assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
                        val rejectedKey = if (oldVersion == 18) "gearbox_1011_555745336_5" else "future_checkpoint_key"
                        db.execSQL("INSERT INTO $table SELECT '$rejectedKey', raw_value, desc_value, " +
                            "source_kind, source_identity, source_boot_id, source_generator_id, source_sequence, source_epoch, " +
                            "source_wall_ms, source_elapsed_ms, source_poll_id, updated_at_ms FROM $table")
                    }
                }
                db.version = oldVersion
            }
            main().use { store ->
                assertEquals(generation, store.businessInputGeneration())
                assertEquals(refs, store.pendingBusinessInputs())
                assertEquals(checkpoint, store.loadBusinessInputCheckpoint())
                assertEquals(checkpoint, store.loadBusinessInputInitialCheckpoint())
                val unknown = updates.values.first().let { it.copy(reading = it.reading.copy(rawKey = "future_checkpoint_key")) }
                assertFailsWith<IllegalArgumentException> {
                    store.completeBusinessInput(refs.first(), mapOf("future_checkpoint_key" to unknown))
                }
                assertEquals(refs, store.pendingBusinessInputs())
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
                    assertEquals(20, helper.readableDatabase.version)
                    assertCheckpointKeyConstraints(helper.writableDatabase)
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

    @Test fun freshCheckpointTablesKeepIntegrityWithoutFreezingTheKeyCatalog() {
        val context = RuntimeEnvironment.getApplication()
        val name = "checkpoint_integrity_${System.nanoTime()}.db"
        try {
            TelemetryDatabaseHelper(context, name).use { assertCheckpointKeyConstraints(it.writableDatabase) }
        } finally { context.deleteDatabase(name) }
    }

    private fun assertCheckpointKeyConstraints(db: android.database.sqlite.SQLiteDatabase) {
        db.beginTransaction()
        try {
            listOf("business_input_source_checkpoints", "business_input_source_seed").forEach { table ->
                fun insert(key: String?, kind: String = "poll", identity: String? = "integrity-input") = db.execSQL(
                    "INSERT INTO $table(source_key, source_kind, source_identity, source_boot_id, " +
                        "source_wall_ms, source_elapsed_ms, updated_at_ms) VALUES (?, ?, ?, 'boot', 1, 1, 1)",
                    arrayOf(key, kind, identity)
                )
                insert("future_checkpoint_key")
                assertFailsWith<android.database.sqlite.SQLiteConstraintException> { insert("future_checkpoint_key") }
                assertFailsWith<android.database.sqlite.SQLiteConstraintException> { insert(null) }
                assertFailsWith<android.database.sqlite.SQLiteConstraintException> { insert("bad_kind", kind = "invalid") }
                assertFailsWith<android.database.sqlite.SQLiteConstraintException> { insert("no_identity", identity = null) }
            }
        } finally { db.endTransaction() }
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
