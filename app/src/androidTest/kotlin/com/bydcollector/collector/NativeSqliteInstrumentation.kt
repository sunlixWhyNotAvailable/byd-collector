package com.bydcollector.collector

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.bydcollector.collector.data.callback.CallbackDelivery
import com.bydcollector.collector.data.callback.CallbackImportResult
import com.bydcollector.collector.data.callback.CallbackRawStore
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.debug.SecondaryImportResult
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.SourceOrderedApplyResult
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.direct.SecondaryTelemetrySpool
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.direct.CallbackParcelGate
import com.bydcollector.collector.direct.CallbackValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Emulator-only behavioral gates plus an opt-in synthetic route for visual QA. */
class NativeSqliteInstrumentation : Instrumentation() {
    private val prefix = "callback_gate_${UUID.randomUUID()}"
    private val completed = mutableListOf<String>()
    private var routeFixture: String? = null
    private var listenerFixture: String? = null
    private var hintFixture = false
    private var shutdownShellFixture = false
    private var shutdownLifecycleFixture = false
    private var fixtureLanguage = "uk"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        routeFixture = arguments?.getString("routeFixture")
        listenerFixture = arguments?.getString("listenerFixture")
        hintFixture = arguments?.getString("hintFixture") == "true"
        shutdownShellFixture = arguments?.getString("shutdownShellFixture") == "true"
        shutdownLifecycleFixture = arguments?.getString("shutdownLifecycleFixture") == "true"
        fixtureLanguage = arguments?.getString("language") ?: "uk"
        start()
    }

    override fun onStart() {
        if (!isEmulator()) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Tests require an emulator") })
            return
        }
        if (hintFixture || shutdownShellFixture || shutdownLifecycleFixture) {
            val result = runCatching {
                when {
                    shutdownLifecycleFixture -> ShutdownShellBehaviorGate.startLifecycle(this)
                    shutdownShellFixture -> ShutdownShellBehaviorGate.run(this)
                    else -> HintWindowBehaviorGate.run(this)
                }
            }
            finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", result.getOrElse { it.stackTraceToString() }) })
            return
        }
        listenerFixture?.let { action ->
            try {
                val component = android.content.ComponentName(targetContext,
                    com.bydcollector.collector.system.CollectorNotificationListenerService::class.java)
                val manager = targetContext.packageManager
                val prefs = targetContext.getSharedPreferences("shutdown_listener_fixture", 0)
                val desired = when (action) {
                    "disable" -> {
                        if (!prefs.contains("previous")) check(prefs.edit().putInt("previous",
                            manager.getComponentEnabledSetting(component)).commit())
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    "restore" -> {
                        check(prefs.contains("previous")) { "No saved listener state" }
                        prefs.getInt("previous", android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    }
                    else -> error("Unknown listener fixture action")
                }
                manager.setComponentEnabledSetting(component, desired, android.content.pm.PackageManager.DONT_KILL_APP)
                check(manager.getComponentEnabledSetting(component) == desired)
                if (action == "restore") check(prefs.edit().remove("previous").commit())
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "LISTENER_FIXTURE $action state=$desired") })
            } catch (failure: Throwable) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure.stackTraceToString()) })
            }
            return
        }
        routeFixture?.let { fixture ->
            try {
                seedRouteFixture(fixture)
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "ROUTE_FIXTURE_READY $fixture") })
            } catch (failure: Throwable) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure.stackTraceToString()) })
            }
            return
        }
        val tests = SettingsBehaviorGate.cases(targetContext, prefix) +
            StorageBehaviorGate.cases(targetContext, prefix) + listOf(
            "main_compact_callback_transactions" to { mainCallbackTransactions(false) },
            "main_legacy_additive_upgrade" to { mainCallbackTransactions(true) },
            "secondary_callback_transactions" to ::secondaryCallbackTransactions,
            "secondary_old_new_full_delta_replay" to ::secondaryCatalogReplay,
            "callback_normalization_atomic_receipt" to ::normalizationReceipt,
            "callback_source_order_compact" to { sourceOrderedNormalization(false) },
            "callback_source_order_legacy" to { sourceOrderedNormalization(true) },
            "callback_android_parcel_paging_ack" to {
                CallbackParcelGate.run(File(targetContext.cacheDir, "${prefix}_parcel"))
            }
        )
        var failure: Throwable? = null
        try {
            tests.forEachIndexed { index, (name, body) ->
                val status = Bundle().apply {
                    putString("id", "InstrumentationTestRunner")
                    putString("class", this@NativeSqliteInstrumentation.javaClass.name)
                    putString("test", name)
                    putInt("numtests", tests.size)
                    putInt("current", index + 1)
                }
                sendStatus(1, status)
                body()
                completed += name
                sendStatus(0, status)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { cleanupTestFiles() }.onFailure { cleanupError ->
                if (failure == null) failure = cleanupError else failure!!.addSuppressed(cleanupError)
            }
        }
        val result = failure
        finish(if (result == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (result == null)
                "CALLBACK_ANDROID_GATE_PASS ${completed.size}/${tests.size}\n" + completed.joinToString("\n")
            else "CALLBACK_ANDROID_GATE_FAIL after ${completed.size}: ${result.stackTraceToString()}")
        })
    }

    private fun isEmulator(): Boolean = android.os.Build.MODEL.startsWith("sdk_gphone") ||
        android.os.Build.FINGERPRINT.contains("generic")

    private fun cleanupTestFiles() {
        val drained = java.util.concurrent.CountDownLatch(1)
        com.bydcollector.collector.util.sharedOperationalEventExecutor.execute { drained.countDown() }
        check(drained.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Event writer did not drain before cleanup" }
        targetContext.databaseList().filter { it.startsWith("${prefix}_") && it.endsWith(".db") }
            .forEach { check(targetContext.deleteDatabase(it)) { "Could not remove test database: $it" } }
        val root = targetContext.cacheDir.canonicalFile
        root.listFiles().orEmpty().filter { it.name.startsWith("${prefix}_") }.forEach { file ->
            check(file.canonicalFile.parentFile == root)
            check(file.deleteRecursively()) { "Could not remove test cache: $file" }
        }
    }

    /** Test-APK-only fixture; never available in the shipped app or on a vehicle. */
    private fun seedRouteFixture(fixture: String) {
        check(isEmulator()) {
            "Route fixture is restricted to an Android emulator"
        }
        require(fixture in setOf("fresh", "stale", "none", "first", "closed", "cleanup"))
        val settings = com.bydcollector.collector.service.CollectorSettings(targetContext, BydCollectorApplication.store(targetContext))
        check(!settings.isPollingEnabled() && !settings.isDebugPollingEnabled()) { "Stop emulator collectors before visual QA" }
        val id = "callback_ui_marker_gate"
        val helper = com.bydcollector.collector.data.trips.TripDatabaseHelper(targetContext)
        com.bydcollector.collector.data.trips.TripStore(helper).use { trips ->
            check(trips.openSessions().all { it.tripId == id }) { "Unrelated open emulator trip must be preserved" }
            helper.writableDatabase.delete("trip_sessions", "trip_id = ?", arrayOf(id))
            if (fixture == "cleanup") return
            settings.setUiLanguageCode(fixtureLanguage)
            val now = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
            fun iso(value: Long) = java.time.Instant.ofEpochMilli(value).toString()
            trips.upsertSession(com.bydcollector.collector.data.trips.TripSession(
                tripId = id, startedAt = iso(now - 120_000), startElapsedMs = elapsed - 120_000,
                startBootId = boot, movementObserved = true, distanceKm = 1.2, durationMs = 120_000,
                state = if (fixture == "closed") "closed" else "open",
                endedAt = if (fixture == "closed") iso(now) else null
            ))
            val locations = if (fixture == "first") listOf(50.4478 to 30.4490) else listOf(
                50.4478 to 30.4490, 50.4482 to 30.4497, 50.4486 to 30.4507,
                50.4490 to 30.4514, 50.4495 to 30.4522
            )
            locations.forEachIndexed { index, point ->
                val age = if (fixture == "stale") 60_000L else (locations.lastIndex - index) * 200L
                val none = fixture == "none"
                trips.upsertRoutePoint(com.bydcollector.collector.data.trips.RoutePoint(
                    tripId = id, sequence = index.toLong(), kind = if (none) "gap" else "valid",
                    observedAt = iso(now - age), elapsedMs = elapsed - age, receiveWallTimeMs = now - age,
                    bootId = boot, latitude = if (none) null else point.first, longitude = if (none) null else point.second,
                    accuracyM = if (none) null else 3.0, speedKmh = if (none) null else 35.0,
                    quality = if (none) "missing" else "ok", isFirst = index == 0,
                    isFinal = fixture == "closed" && index == locations.lastIndex
                ))
            }
            check(trips.verify())
        }
    }

    private fun mainCallbackTransactions(legacy: Boolean) {
        val name = "${prefix}_main_${if (legacy) "legacy" else "compact"}.db"
        if (legacy) {
            val file = targetContext.getDatabasePath(name)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                executeAsset(db, TelemetryDatabaseHelper.LEGACY_SCHEMA_ASSET)
                db.execSQL("DROP TABLE IF EXISTS raw_callback_normalization_receipts")
                db.execSQL("DROP TABLE IF EXISTS raw_callback_events")
                db.execSQL("DROP TABLE IF EXISTS raw_callback_batches")
                db.execSQL("DROP TABLE IF EXISTS poll_callback_sources")
                db.execSQL("INSERT INTO collection_sessions(id, session_type) VALUES(1, 'gate_retained')")
                db.execSQL("INSERT INTO polls(id, session_id, ts, ok, raw_response_body) VALUES(1, 1, '2026-09-21T00:00:00Z', 1, 'retained raw')")
                db.version = 10
            }
        }
        TelemetryDatabaseHelper(targetContext, name).use { helper ->
            val db = helper.writableDatabase
            check(helper.isCompactV2() != legacy)
            check(db.version == TelemetryDatabaseHelper.DATABASE_VERSION)
            if (legacy) check(text(db, "SELECT raw_response_body FROM polls WHERE id=1") == "retained raw")
            callbackTransactions(db, 1)
        }
        cachedPollProvenance(name)
        // Reopening must retain the exact receipt, not merely an in-memory duplicate cache.
        TelemetryDatabaseHelper(targetContext, name).use { helper ->
            val store = CallbackRawStore(database = { helper.writableDatabase })
            val record = callbackBatch(1)
            val duplicate = store.importBatch(record, TelemetryCallbackBatch.digest(record.encode()), CallbackDelivery.REPLAY)
            check(duplicate is CallbackImportResult.Committed && duplicate.duplicate)
            check(count(helper.readableDatabase, "raw_callback_events") == 4L)
            check(count(helper.readableDatabase, "poll_callback_sources") == 1L)
        }
    }

    private fun cachedPollProvenance(name: String) {
        val helper = TelemetryDatabaseHelper(targetContext, name)
        TelemetryStore(targetContext, helper, operationalEventJournal = OperationalEventJournal(targetContext)).use { store ->
            val session = store.openSession("callback_provenance_gate")
            val parameters = store.getActiveCatalogParameters()
            val key = com.bydcollector.collector.data.direct.DirectFidRegistry.entries
                .first { it.dev == 1001 && it.fid == 315621418 && it.tx == 5 }.key
            check(parameters.any { it.key == key })
            // Original raw event may belong to an archived database or another stream.
            val source = CallbackValueSource("older_boot", "older_helper", 2, 7, 81,
                1001, 315621418, TelemetryCallbackBatch.TYPE_INT, 2, 10000, 2000, null, "usable")
            val input = PersistedPollInput("2026-09-22T07:00:00Z", true, 10, 1, null,
                rawResponseBody = null, readings = listOf(PollReading(key, "2", callbackSource = source)))
            val db = helper.writableDatabase
            val pollsBefore = count(db, "polls")
            val valuesBefore = count(db, "poll_values")
            db.execSQL("CREATE TRIGGER gate_fail_source BEFORE INSERT ON poll_callback_sources BEGIN SELECT RAISE(ABORT, 'injected source failure'); END")
            check(runCatching { store.insertPoll(session, input, parameters) }.isFailure)
            check(count(db, "polls") == pollsBefore && count(db, "poll_values") == valuesBefore)
            db.execSQL("DROP TRIGGER gate_fail_source")
            val pollId = store.insertPoll(session, input, parameters)
            check(text(db, "SELECT helper_generation FROM poll_callback_sources WHERE poll_id=$pollId") == "older_helper")
            check(text(db, "SELECT received_elapsed_ms FROM poll_callback_sources WHERE poll_id=$pollId") == "2000")
            check(text(db, "SELECT received_wall_ms FROM poll_callback_sources WHERE poll_id=$pollId") == "10000")
            store.insertPoll(session, input.copy(readings = listOf(PollReading(key, "2"))), parameters)
            check(count(db, "poll_callback_sources") == 1L)
            check(count(db, "raw_callback_events") == 4L)
        }
    }

    private fun secondaryCallbackTransactions() {
        val name = "${prefix}_secondary_events.db"
        DirectDebugDatabaseHelper(targetContext, name).use { helper ->
            callbackTransactions(helper.writableDatabase, 2)
        }
        DirectDebugDatabaseHelper(targetContext, name).use { helper ->
            check(count(helper.readableDatabase, "raw_callback_events") == 4L)
            check(DirectDebugDatabaseHelper.isCompactV2(helper.readableDatabase))
        }
    }

    private fun callbackTransactions(db: SQLiteDatabase, stream: Int) {
        val store = CallbackRawStore(database = { db })
        val record = callbackBatch(stream)
        val digest = TelemetryCallbackBatch.digest(record.encode())
        db.execSQL("CREATE TRIGGER gate_fail_raw BEFORE INSERT ON raw_callback_events BEGIN SELECT RAISE(ABORT, 'injected raw failure'); END")
        runCatching { store.importBatch(record, digest, CallbackDelivery.LIVE) }
        check(count(db, "raw_callback_events") == 0L)
        check(count(db, "raw_callback_batches") == 0L)
        db.execSQL("DROP TRIGGER gate_fail_raw")
        val inserted = store.importBatch(record, digest, CallbackDelivery.LIVE)
        check(inserted is CallbackImportResult.Committed && !inserted.duplicate)
        check(count(db, "raw_callback_events") == 4L) // equal scalar callbacks are separate evidence
        check(text(db, "SELECT hex(raw_bytes) FROM raw_callback_events WHERE event_sequence=3") == "00FF7F")
        check(text(db, "SELECT raw_bits FROM raw_callback_events WHERE event_sequence=4") == "2143294004")
        val duplicate = store.importBatch(record, digest, CallbackDelivery.REPLAY)
        check(duplicate is CallbackImportResult.Committed && duplicate.duplicate)
        check(count(db, "raw_callback_batches") == 1L)
        val conflicting = TelemetryCallbackBatch(record.bootId, record.helperGeneration, stream,
            record.epoch, record.batchSequence, listOf(callbackEvent(1, 99)))
        check(store.importBatch(conflicting, TelemetryCallbackBatch.digest(conflicting.encode()), CallbackDelivery.REPLAY)
            is CallbackImportResult.Rejected)
        // Different batch identity but overlapping event identity must roll back the whole batch.
        val overlap = TelemetryCallbackBatch(record.bootId, record.helperGeneration, stream,
            record.epoch, 1, listOf(callbackEvent(2, 5), callbackEvent(5, 6)))
        runCatching { store.importBatch(overlap, TelemetryCallbackBatch.digest(overlap.encode()), CallbackDelivery.LIVE) }
        check(count(db, "raw_callback_events") == 4L)
        check(count(db, "raw_callback_batches") == 1L)
    }

    private fun callbackEvent(sequence: Long, value: Int = 2) = TelemetryCallbackBatch.Event(
        sequence, 1001, 315621418, TelemetryCallbackBatch.TYPE_INT, value, null,
        1_790_000_000_000L + sequence, 1000 + sequence, null, "usable")

    private fun callbackBatch(stream: Int) = TelemetryCallbackBatch("boot", "helper", stream, 1, 0,
        listOf(callbackEvent(1), callbackEvent(2), TelemetryCallbackBatch.Event(3, 1002, -123,
            TelemetryCallbackBatch.TYPE_BYTES, 0, byteArrayOf(0, -1, 127), 1_790_000_000_003L, 1003, null, "opaque"),
            TelemetryCallbackBatch.Event(4, 1002, -124, TelemetryCallbackBatch.TYPE_FLOAT, 0x7fc01234,
                null, 1_790_000_000_004L, 1004, null, "invalid")))

    private fun normalizationReceipt() {
        val name = "${prefix}_normalization.db"
        val helper = TelemetryDatabaseHelper(targetContext, name)
        TelemetryStore(targetContext, helper, operationalEventJournal = OperationalEventJournal(targetContext)).use { store ->
            store.ensureNormalizedCatalogImported()
            val record = callbackBatch(1)
            check(store.importCallbackBatch(record, TelemetryCallbackBatch.digest(record.encode()), CallbackDelivery.LIVE)
                is CallbackImportResult.Committed)
            val event = store.pendingCallbackNormalization().first()
            val observation = NormalizedObservation(NormalizedFieldCatalog.speedKmh,
                NormalizedValue(NormalizedValueType.NUMBER, number = 42.0), NormalizedQuality.OK,
                null, "speed_1013_-1807745016_7", "2026-09-22T07:00:00Z")
            val db = helper.writableDatabase
            db.execSQL("CREATE TRIGGER gate_fail_normalized BEFORE INSERT ON raw_callback_normalization_receipts BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END")
            check(runCatching { store.applyCallbackNormalization(event.id, listOf(observation)) }.isFailure)
            check(count(db, "raw_callback_normalization_receipts") == 0L)
            check(count(db, "vehicle_state_current") == 0L)
            check(count(db, "vehicle_state_history") == 0L)
            db.execSQL("DROP TRIGGER gate_fail_normalized")
            check(!store.applyCallbackNormalization(event.id, listOf(observation)).duplicate)
            check(store.applyCallbackNormalization(event.id, listOf(observation)).duplicate)
            check(count(db, "raw_callback_normalization_receipts") == 1L)
            check(count(db, "vehicle_state_history") == 1L)
            check(count(db, "raw_callback_events") == 4L)
        }
    }

    private fun sourceOrderedNormalization(legacy: Boolean) {
        val name = "${prefix}_source_order_${if (legacy) "legacy" else "compact"}.db"
        if (legacy) {
            val file = targetContext.getDatabasePath(name)
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                executeAsset(db, TelemetryDatabaseHelper.LEGACY_SCHEMA_ASSET)
                db.execSQL("DROP TABLE IF EXISTS normalized_source_inputs")
                db.version = 10
            }
        }
        val baseWall = 1_790_000_000_000L
        val normalizer = VehicleStateNormalizer()
        val key = "speed_1013_-1807745016_7"
        fun assertAppliedSource(stamp: NormalizedSourceStamp?, kind: NormalizedSourceKind, elapsed: Long) {
            check(stamp?.let {
                it.kind == kind && it.bootId == "source_boot" && it.elapsedMs == elapsed &&
                    it.wallMs == baseWall + elapsed
            } == true)
        }
        fun assertNoAppliedSource(stamp: NormalizedSourceStamp?) = check(stamp == null)
        fun rawBatch(sequence: Long, elapsed: Long, speed: Float) = TelemetryCallbackBatch(
            "source_boot", "source_helper", 1, 1, sequence,
            listOf(TelemetryCallbackBatch.Event(sequence, 1013, -1807745016,
                TelemetryCallbackBatch.TYPE_FLOAT, speed.toRawBits(), null,
                baseWall + elapsed, elapsed, null, "callback"))
        )
        fun import(store: TelemetryStore, batch: TelemetryCallbackBatch) {
            check(store.importCallbackBatch(batch, TelemetryCallbackBatch.digest(batch.encode()), CallbackDelivery.REPLAY)
                is CallbackImportResult.Committed)
        }
        val helper = TelemetryDatabaseHelper(targetContext, name)
        TelemetryStore(targetContext, helper, operationalEventJournal = OperationalEventJournal(targetContext)).use { store ->
            store.ensureNormalizedCatalogImported()
            val db = helper.writableDatabase
            import(store, rawBatch(1, 5_000, 40f))
            db.execSQL("CREATE TRIGGER gate_fail_order BEFORE INSERT ON raw_callback_normalization_receipts BEGIN SELECT RAISE(ABORT, 'receipt rollback'); END")
            check(runCatching { store.normalizePendingCallbackPage(normalizer) }.isFailure)
            check(count(db, "normalized_source_inputs") == 0L)
            check(count(db, "vehicle_state_current") == 0L)
            check(count(db, "raw_callback_normalization_receipts") == 0L)
            check(count(db, "raw_callback_events") == 1L)
            db.execSQL("DROP TRIGGER gate_fail_order")
            val initialCallback = store.normalizePendingCallbackPage(normalizer)
            check(initialCallback.processedCount == 1)
            check(initialCallback.oldestPageReceivedWallMs == baseWall + 5_000)
            assertAppliedSource(initialCallback.latestAppliedSource, NormalizedSourceKind.CALLBACK, 5_000)
            check(text(db, "SELECT value_number FROM vehicle_state_current WHERE field_key='speed_kmh'").toDouble() == 40.0)
            val emptyCallbackPage = store.normalizePendingCallbackPage(normalizer)
            check(emptyCallbackPage.processedCount == 0)
            check(emptyCallbackPage.oldestPageReceivedWallMs == null)
            assertNoAppliedSource(emptyCallbackPage.latestAppliedSource)

            val session = store.openSession("source_order_gate")
            val parameters = store.getActiveCatalogParameters()
            fun poll(elapsed: Long, value: Float): SourceOrderedApplyResult {
                val timestamp = java.time.Instant.ofEpochMilli(baseWall + elapsed).toString()
                val readings = listOf(PollReading(key, value.toRawBits().toString(), value.toString()))
                val id = store.insertPoll(session, PersistedPollInput(timestamp, true, 0, 1, null,
                    rawResponseBody = null, readings = readings), parameters)
                return store.applySourcePollNormalization(id, timestamp,
                    PollSampleSource("live:$elapsed", "source_boot", elapsed, "app_generator", elapsed), readings, normalizer)
            }
            val olderPoll = poll(3_000, 10f)
            assertNoAppliedSource(olderPoll.latestAppliedSource)
            check(text(db, "SELECT value_number FROM vehicle_state_current WHERE field_key='speed_kmh'").toDouble() == 40.0)
            val acceptedPoll = poll(6_000, 45f)
            assertAppliedSource(acceptedPoll.latestAppliedSource, NormalizedSourceKind.POLL, 6_000)
            check(text(db, "SELECT value_number FROM vehicle_state_current WHERE field_key='speed_kmh'").toDouble() == 45.0)
            val equalPollReplay = poll(6_000, 45f)
            assertNoAppliedSource(equalPollReplay.latestAppliedSource)
            import(store, rawBatch(2, 4_000, 20f))
            val olderCallback = store.normalizePendingCallbackPage(normalizer)
            check(olderCallback.processedCount == 1)
            assertNoAppliedSource(olderCallback.latestAppliedSource)
            check(text(db, "SELECT value_number FROM vehicle_state_current WHERE field_key='speed_kmh'").toDouble() == 45.0)
            import(store, rawBatch(3, 7_000, 50f))
            val acceptedCallback = store.normalizePendingCallbackPage(normalizer)
            assertAppliedSource(acceptedCallback.latestAppliedSource, NormalizedSourceKind.CALLBACK, 7_000)
            check(text(db, "SELECT value_number FROM vehicle_state_current WHERE field_key='speed_kmh'").toDouble() == 50.0)
            check(count(db, "raw_callback_normalization_receipts") == 3L)
            check(count(db, "normalized_source_inputs") == 1L)
        }
        val reopened = TelemetryDatabaseHelper(targetContext, name)
        TelemetryStore(targetContext, reopened, operationalEventJournal = OperationalEventJournal(targetContext)).use { store ->
            import(store, rawBatch(4, 2_000, 70f))
            val oldAfterReopen = store.normalizePendingCallbackPage(normalizer)
            check(oldAfterReopen.processedCount == 1)
            assertNoAppliedSource(oldAfterReopen.latestAppliedSource)
            check(text(reopened.readableDatabase, "SELECT value_number FROM vehicle_state_current WHERE field_key='speed_kmh'").toDouble() == 50.0)
            check(count(reopened.readableDatabase, "raw_callback_normalization_receipts") == 4L)
            check(count(reopened.readableDatabase, "raw_callback_events") == 4L)
        }
    }

    private fun secondaryCatalogReplay() {
        val active = DirectDebugParameterAsset.load(targetContext)
        val definitions = DirectDebugParameterAsset.loadDefinitions(targetContext)
        val excludedOrdinal = definitions.indexOfFirst { !DirectDebugParameterAsset.isRuntimeSelected(it) }
        check(excludedOrdinal >= 0)
        val name = "${prefix}_secondary_catalogs.db"
        val old = records(DirectDebugParameterAsset.LEGACY_SOURCE_VERSION, definitions.size, excludedOrdinal)
            .let { legacyRecord(it.first) to legacyRecord(it.second) }
        val current = records(DirectDebugParameterAsset.SOURCE_VERSION, active.size, 0, cachedOrdinal = 1)
        DirectDebugDatabaseHelper(targetContext, name).let { helper ->
            DirectDebugStore(targetContext, helper).use { store ->
                val session = store.openSession(active, active.size)
                checkImport(store, session, old.first, duplicate = false)
                // Simulate a released pre-callback database with a retained v1 predecessor.
                helper.writableDatabase.execSQL("DROP TABLE debug_secondary_replay_cached_flags")
            }
        }
        // Persisted legacy predecessor must still be usable after reopening with active-v2 catalog.
        DirectDebugDatabaseHelper(targetContext, name).let { helper ->
            DirectDebugStore(targetContext, helper).use { store ->
                val session = store.openSession(active, active.size)
                checkImport(store, session, old.second, duplicate = false)
                checkImport(store, session, old.second, duplicate = true)
                checkImport(store, session, current.first, duplicate = false)
                checkCachedOrdinal(helper.readableDatabase, 1)
                checkImport(store, session, current.second, duplicate = false)
                checkCachedOrdinal(helper.readableDatabase, 1)
                check(count(helper.readableDatabase, "debug_secondary_receipts") == 4L)
                val excluded = definitions[excludedOrdinal]
                helper.readableDatabase.rawQuery("""
                    SELECT r.raw_int FROM debug_direct_readings r
                    JOIN debug_direct_candidates c ON c.id=r.candidate_id
                    WHERE c.dev=? AND c.fid=? AND c.tx=? ORDER BY r.id DESC LIMIT 1
                """.trimIndent(), arrayOf(excluded.dev.toString(), excluded.fid.toString(), excluded.tx.toString())).use { row ->
                    check(row.moveToFirst() && row.getInt(0) == 77)
                }
            }
        }
    }

    private fun checkImport(store: DirectDebugStore, session: Long, bytes: ByteArray, duplicate: Boolean) {
        val record = SecondaryTelemetrySpool.Codec.decode(bytes)
        val result = store.importSecondaryRecord(session, record, TelemetryCallbackBatch.digest(bytes).uppercase())
        check(result is SecondaryImportResult.Committed && result.duplicate == duplicate) { "Secondary import failed: $result" }
    }

    private fun checkCachedOrdinal(db: SQLiteDatabase, ordinal: Int) {
        db.rawQuery("""
            SELECT f.cached_bits FROM debug_secondary_replay_cached_flags f
            JOIN debug_direct_catalog_versions c ON c.id=f.catalog_version_id
            WHERE c.source_version=?
        """.trimIndent(), arrayOf(DirectDebugParameterAsset.SOURCE_VERSION)).use {
            check(it.moveToFirst())
            check(it.getBlob(0)[ordinal / 8].toInt() and (1 shl (ordinal % 8)) != 0)
        }
    }

    private fun legacyRecord(bytes: ByteArray): ByteArray = org.json.JSONObject(String(bytes, Charsets.UTF_8))
        .put("record_version", 1).toString().toByteArray(Charsets.UTF_8)

    private fun records(catalog: String, fields: Int, changedOrdinal: Int, cachedOrdinal: Int = -1): Pair<ByteArray, ByteArray> {
        val root = File(targetContext.cacheDir, "${prefix}_${fields}_spool")
        check(root.mkdirs())
        val factory = SecondaryTelemetrySpool::class.java.getDeclaredMethod("openForTest",
            File::class.java, java.lang.Long.TYPE, Integer.TYPE).apply { isAccessible = true }
        return (factory.invoke(null, root, 32L * 1024 * 1024, fields) as SecondaryTelemetrySpool).use { spool ->
            fun cycle(sequence: Long, changed: Boolean) = SecondaryTelemetrySpool.Cycle(
                SecondaryTelemetrySpool.CycleIdentity("boot", "helper", "gap_$fields", sequence), catalog,
                1_790_000_000_000L + sequence, 1000 + sequence, 10, 0, 1, true, 1, 0, 0, 0, null,
                List(fields) { ordinal -> SecondaryTelemetrySpool.Value(ordinal, 0, true,
                    if (changed && ordinal == changedOrdinal) 77 else 2, null, ordinal == cachedOrdinal) })
            spool.append(cycle(0, false))
            spool.append(cycle(1, true))
            readAndAck(spool) to readAndAck(spool)
        }
    }

    private fun readAndAck(spool: SecondaryTelemetrySpool): ByteArray {
        val descriptor = checkNotNull(spool.oldest())
        val output = ByteArrayOutputStream()
        var offset = 0L
        while (offset < descriptor.length) {
            val bytes = spool.readSlice(descriptor, offset, SecondaryTelemetrySpool.MAX_SLICE_BYTES)
            check(bytes.isNotEmpty())
            output.write(bytes)
            offset += bytes.size
        }
        check(spool.acknowledge(descriptor) == SecondaryTelemetrySpool.AckResult.RELEASED)
        return output.toByteArray()
    }

    private fun executeAsset(db: SQLiteDatabase, asset: String) {
        val statement = StringBuilder()
        targetContext.assets.open(asset).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (!line.trim().startsWith("--")) {
                    statement.appendLine(line)
                    if (line.trim().endsWith(';')) {
                        db.execSQL(statement.toString())
                        statement.clear()
                    }
                }
            }
        }
        check(statement.isBlank())
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    private fun text(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getString(0) }
}
