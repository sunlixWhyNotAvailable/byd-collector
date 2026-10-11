package com.bydcollector.collector

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugDatabaseResolver
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.SecondaryReplayCoordinator
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.direct.SecondarySpoolActionResult
import com.bydcollector.collector.data.direct.SecondarySpoolPage
import com.bydcollector.collector.data.local.TelegramDatabaseHelper
import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripCompletionIntent
import com.bydcollector.collector.data.trips.TripCompression
import com.bydcollector.collector.data.trips.TripCompressionPhase
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.SecondaryTelemetrySpool
import com.bydcollector.collector.maintenance.DbMaintenanceCoordinator
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.telegram.handoffTripCompletions
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Narrow storage behavior gates; each case crosses a real SQLite/file boundary. */
internal object StorageBehaviorGate {
    fun cases(context: Context, prefix: String): List<Pair<String, () -> Unit>> = listOf(
        "callbackSpoolCommitsRawBeforeExactAckAndSurvivesReplay" to { spoolToSqliteReplay(context, prefix) },
        "manualArchivePreservesEvidenceAndRestartsCollectionAfterFailure" to { manualArchiveRollback(context, prefix) },
        "interruptedTripCompressionPreservesHistoryAndRoutes" to { interruptedTripCompression(context, prefix) },
        "tripCompletionCrossStoreHandoffIsIdempotentAndOrdersLocation" to { tripCompletionHandoff(context, prefix) }
    )

    private fun spoolToSqliteReplay(context: Context, prefix: String) {
        val parameters = DirectDebugParameterAsset.load(context)
        val databaseName = "${prefix}_secondary_spool_replay.db"
        val spoolRoot = File(context.cacheDir, "${prefix}_secondary_spool").apply { check(mkdirs() || isDirectory) }
        val spool = openSpoolForTest(spoolRoot, parameters.size)
        var importedStore: DirectDebugStore? = null
        try {
            val expectedRaw = 0x13572468
            val identity = SecondaryTelemetrySpool.CycleIdentity("boot-test", "helper-test", "gap-test", 1L)
            val cycle = SecondaryTelemetrySpool.Cycle(
                identity,
                DirectDebugParameterAsset.SOURCE_VERSION,
                1_790_000_000_001L,
                10_001L,
                12L,
                0,
                1,
                true,
                1,
                0,
                0,
                0,
                null,
                parameters.mapIndexed { ordinal, _ ->
                    SecondaryTelemetrySpool.Value(ordinal, 0, true, if (ordinal == 0) expectedRaw else ordinal, null)
                }
            )
            check(spool.append(cycle) == SecondaryTelemetrySpool.AppendResult.FULL)
            val expectedSecondRaw = 0x24681357
            val secondIdentity = SecondaryTelemetrySpool.CycleIdentity("boot-test", "helper-test", "gap-test", 2L)
            val secondCycle = SecondaryTelemetrySpool.Cycle(
                secondIdentity,
                DirectDebugParameterAsset.SOURCE_VERSION,
                1_790_000_000_002L,
                10_002L,
                13L,
                0,
                1,
                true,
                1,
                0,
                0,
                0,
                null,
                parameters.mapIndexed { ordinal, _ ->
                    SecondaryTelemetrySpool.Value(
                        ordinal, 0, true, if (ordinal == 0) expectedSecondRaw else ordinal, null
                    )
                }
            )
            check(spool.append(secondCycle) == SecondaryTelemetrySpool.AppendResult.DELTA)
            val descriptor = checkNotNull(spool.oldest())
            val rawBytes = readSpoolRecord(spool, descriptor)
            val record = SecondaryTelemetrySpool.Codec.decode(rawBytes)
            check(record.identity == identity && record.kind == SecondaryTelemetrySpool.Kind.FULL)
            check(record.values.first().raw == expectedRaw)
            // The public replay API exposes only oldest(); snapshot the actual queued DELTA bytes
            // so the following exact ACK can be checked against an independent pre-ACK oracle.
            val secondRawBytes = readReadyRecord(spoolRoot, secondIdentity)
            val secondRecord = SecondaryTelemetrySpool.Codec.decode(secondRawBytes)
            check(secondRecord.identity == secondIdentity && secondRecord.kind == SecondaryTelemetrySpool.Kind.DELTA)
            check(secondRecord.values.single().let { it.ordinal == 0 && it.raw == expectedSecondRaw })

            val helper = DirectDebugDatabaseHelper(context, databaseName)
            val store = DirectDebugStore(context, helper)
            importedStore = store
            val sessionId = store.openSession(parameters, parameters.size)
            val database = helper.writableDatabase
            failInserts(database, "debug_secondary_receipts")
            val failedImport = replayCoordinator(spool, store, sessionId) { descriptor ->
                ackSpoolRecord(spool, descriptor)
            }.drain()
            check(!failedImport.drained && failedImport.committedRecords == 0)
            check(count(database, "debug_direct_cycles") == 0L)
            check(count(database, "debug_direct_readings") == 0L)
            check(count(database, "debug_secondary_receipts") == 0L)
            check(sameDescriptor(spool.oldest(), descriptor)) {
                "An aborted SQLite import must leave the exact raw spool record pending"
            }
            check(readReadyRecord(spoolRoot, secondIdentity).contentEquals(secondRawBytes)) {
                "An aborted first import changed the unrelated pending DELTA bytes"
            }
            database.execSQL("DROP TRIGGER storage_gate_injected_failure")

            val failAck = AtomicInteger(1)
            val stoppedBeforeAck = replayCoordinator(spool, store, sessionId) { descriptor ->
                if (failAck.getAndSet(0) == 1) {
                    SecondarySpoolActionResult(1, error = "simulated ACK unavailable")
                } else {
                    ackSpoolRecord(spool, descriptor)
                }
            }.drain()
            check(!stoppedBeforeAck.drained && stoppedBeforeAck.committedRecords == 1) {
                "The real replay coordinator must report a committed row when its ACK cannot complete"
            }
            check(count(database, "debug_direct_cycles") == 1L)
            check(count(database, "debug_secondary_receipts") == 1L)
            check(sameDescriptor(spool.oldest(), descriptor)) {
                "The raw bytes must remain available until the post-commit ACK"
            }
            check(readReadyRecord(spoolRoot, secondIdentity).contentEquals(secondRawBytes)) {
                "A failed ACK for the FULL record changed the unrelated pending DELTA bytes"
            }
            val wrongDigest = SecondaryTelemetrySpool.Descriptor(
                descriptor.spoolOrder,
                descriptor.identity,
                descriptor.kind,
                descriptor.capturedWallMs,
                descriptor.capturedElapsedMs,
                descriptor.fileName,
                descriptor.length,
                "0".repeat(64)
            )
            check(spool.acknowledge(wrongDigest) == SecondaryTelemetrySpool.AckResult.REJECTED)
            check(sameDescriptor(spool.oldest(), descriptor)) { "A non-exact ACK released pending raw bytes" }
            store.close()
            importedStore = null

            // Simulate death after the SQLite commit and before the producer ACK.
            val reopenedHelper = DirectDebugDatabaseHelper(context, databaseName)
            DirectDebugStore(context, reopenedHelper).use { reopened ->
                val reopenedSession = reopened.openSession(parameters, parameters.size)
                val ackCalls = AtomicInteger()
                val replay = replayCoordinator(spool, reopened, reopenedSession) { pending ->
                    if (ackCalls.getAndIncrement() == 1) {
                        SecondarySpoolActionResult(1, error = "simulated second-record ACK unavailable")
                    } else {
                        ackSpoolRecord(spool, pending)
                    }
                }.drain()
                check(!replay.drained && replay.duplicateRecords == 1 && replay.committedRecords == 1) {
                    "Reopen should deduplicate/ACK the FULL, commit the DELTA, then stop on its failed ACK: $replay"
                }
                val pendingSecond = checkNotNull(spool.oldest())
                check(pendingSecond.identity == secondIdentity && pendingSecond.kind == SecondaryTelemetrySpool.Kind.DELTA) {
                    "ACK of the FULL did not leave the unrelated DELTA at the head of the spool"
                }
                check(readSpoolRecord(spool, pendingSecond).contentEquals(secondRawBytes)) {
                    "ACK of the FULL changed the exact bytes of the unrelated pending DELTA"
                }
                val pendingRecord = SecondaryTelemetrySpool.Codec.decode(readSpoolRecord(spool, pendingSecond))
                check(pendingRecord.values.single().let { it.ordinal == 0 && it.raw == expectedSecondRaw })
                val db = reopenedHelper.readableDatabase
                check(count(db, "debug_direct_cycles") == 2L)
                check(count(db, "debug_secondary_receipts") == 2L)
                db.rawQuery(
                    "SELECT r.raw_int, c.feature_names FROM debug_direct_readings r " +
                        "JOIN debug_direct_candidates c ON c.id = r.candidate_id " +
                        "WHERE c.source_key = ? ORDER BY r.id DESC LIMIT 1",
                    arrayOf(parameters.first().key)
                ).use { row ->
                    check(row.moveToFirst())
                    check(row.getInt(0) == expectedSecondRaw) { "Committed DELTA raw value changed during replay" }
                    check(row.getString(1) == parameters.first().featureNames) {
                        "Persisted field description no longer matches the source catalog"
                    }
                }

                val finalReplay = replayCoordinator(spool, reopened, reopenedSession) { pending ->
                    ackSpoolRecord(spool, pending)
                }.drain()
                check(finalReplay.drained && finalReplay.duplicateRecords == 1 && finalReplay.committedRecords == 0) {
                    "Replay should deduplicate and ACK the pending DELTA: $finalReplay"
                }
                check(spool.oldest() == null) { "Replay coordinator did not release the pending DELTA" }
                check(spool.acknowledge(pendingSecond) == SecondaryTelemetrySpool.AckResult.NOT_FOUND)
            }
        } finally {
            importedStore?.let { runCatching { it.close() } }
            spool.close()
        }
    }

    private fun manualArchiveRollback(context: Context, prefix: String) {
        val app = context.applicationContext as BydCollectorApplication
        val filesRoot = File(context.cacheDir, "${prefix}_manual_archive_files").apply { check(mkdirs() || isDirectory) }
        val mainContext = StorageTestContext(context, filesRoot)
        val archiveRoot = File(filesRoot, "db_archive")
        check(archiveRoot.mkdirs() || archiveRoot.isDirectory)
        val settings = CollectorSettings(mainContext)
        check(settings.storageCutoverJournal() == null) { "A prior storage cutover must be resolved before this gate" }

        val initialMain = BydCollectorApplication.store(context)
        val originalSessionType = "${prefix}_archive_original"
        val resumedSessionType = "${prefix}_archive_resumed"
        val originalSessionId = initialMain.openSession(originalSessionType)
        awaitEventWriter()
        var resumedMainSessionId = 0L
        try {
            val capturedMainArchive = AtomicReference<String?>()
            val mainReopenCalls = AtomicInteger()
            var mainCollectionResumed = false
            val mainCoordinator = DbMaintenanceCoordinator(
                context = mainContext,
                settings = settings,
                application = app,
                stopRuntime = {},
                onStoreReopened = {
                    if (mainReopenCalls.getAndIncrement() == 0) {
                        capturedMainArchive.set(settings.storageCutoverJournal()?.archivePath)
                        error("test injected Main replacement-store failure")
                    }
                },
                closeDebugStore = {},
                onDebugStoreReopened = {}
            )
            val mainResult = mainCoordinator.run(DbMaintenanceOperation.ARCHIVE) {
                resumedMainSessionId = BydCollectorApplication.store(context).openSession(resumedSessionType)
                mainCollectionResumed = resumedMainSessionId > originalSessionId
            }
            awaitEventWriter()
            check(!mainResult.ok && mainResult.message.contains("test injected Main")) {
                "Manual Main archive should report the injected replacement failure: $mainResult"
            }
            check(mainCollectionResumed) { "Main collection did not restart after the archive rollback" }
            check(settings.storageCutoverJournal() == null) { "Failed Main archive left a cutover journal" }
            check(capturedMainArchive.get()?.let { !File(it).exists() } == true) {
                "Failed Main replacement left an unverified archive directory behind"
            }
            val mainFile = context.getDatabasePath(com.bydcollector.collector.data.local.TelemetryDatabaseHelper.DATABASE_NAME)
            SQLiteDatabase.openDatabase(mainFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT COUNT(*) FROM collection_sessions WHERE session_type IN (?, ?)",
                    arrayOf(originalSessionType, resumedSessionType)
                ).use { row -> check(row.moveToFirst() && row.getLong(0) == 2L) }
                db.rawQuery("PRAGMA quick_check", null).use { row -> check(row.moveToFirst() && row.getString(0) == "ok") }
            }
        } finally {
            runCatching { BydCollectorApplication.store(context) }.getOrNull()?.let { currentMain ->
                listOfNotNull(originalSessionId, resumedMainSessionId.takeIf { it > 0L }).forEach { sessionId ->
                    runCatching { currentMain.endSession(sessionId, "storage_gate_cleanup") }
                }
            }
            runCatching { awaitEventWriter() }
        }

        // Exercise the same coordinator rollback against an isolated real Secondary SQLite file.
        val debugDatabaseRoot = File(filesRoot, "isolated_databases").apply { check(mkdirs() || isDirectory) }
        val debugContext = StorageTestContext(context, filesRoot, debugDatabaseRoot, applicationContextIsSelf = true)
        val debugSettings = CollectorSettings(debugContext)
        val priorArchiveStoragePending = debugSettings.isCutoverArchiveStoragePending()
        check(debugSettings.storageCutoverJournal() == null)
        val parameters = DirectDebugParameterAsset.load(debugContext)
        val debugStoreRef = AtomicReference<DirectDebugStore?>()
        var originalDebugSessionId = 0L
        var resumedDebugSessionId = 0L
        var retryDebugSessionId = 0L
        var retryArchiveStarted = false
        try {
        val initialDebug = DirectDebugStore(debugContext, DirectDebugDatabaseHelper(debugContext))
        debugStoreRef.set(initialDebug)
        originalDebugSessionId = initialDebug.openSession(parameters, parameters.size)
        val debugDatabaseFile = DirectDebugDatabaseResolver.databaseFile(debugContext)
        val debugArchivePath = AtomicReference<String?>()
        val debugReopenCalls = AtomicInteger()
        var debugCollectionResumed = false
        val debugCoordinator = DbMaintenanceCoordinator(
            context = debugContext,
            settings = debugSettings,
            application = app,
            stopRuntime = {},
            onStoreReopened = {},
            closeDebugStore = { debugStoreRef.getAndSet(null)?.close() },
            onDebugStoreReopened = { reopened ->
                debugStoreRef.set(reopened)
                if (debugReopenCalls.getAndIncrement() == 0) {
                    debugArchivePath.set(debugSettings.storageCutoverJournal()?.archivePath)
                    error("test injected Secondary replacement-store failure")
                }
            }
        )
        val debugResult = debugCoordinator.run(DbMaintenanceOperation.DEBUG_ARCHIVE) {
            val reopened = checkNotNull(debugStoreRef.get())
            resumedDebugSessionId = reopened.openSession(parameters, parameters.size)
            debugCollectionResumed = resumedDebugSessionId > originalDebugSessionId
        }
        check(!debugResult.ok && debugResult.message.contains("test injected Secondary")) {
            "Manual Secondary archive should report the injected replacement failure: $debugResult"
        }
        check(debugCollectionResumed) { "Secondary collection did not restart after archive rollback" }
        check(debugSettings.storageCutoverJournal() == null) { "Failed Secondary archive left a cutover journal" }
        check(debugArchivePath.get()?.let { !File(it).exists() } == true) {
            "Failed Secondary replacement left an unverified archive directory behind"
        }
        SQLiteDatabase.openDatabase(debugDatabaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM debug_direct_sessions WHERE id IN (?, ?)",
                arrayOf(originalDebugSessionId.toString(), resumedDebugSessionId.toString())
            ).use { row -> check(row.moveToFirst() && row.getLong(0) == 2L) }
            db.rawQuery("PRAGMA quick_check", null).use { row -> check(row.moveToFirst() && row.getString(0) == "ok") }
        }

        // Retry successfully against the isolated Secondary database, not the app's live Main DB.
        val restoredStore = checkNotNull(debugStoreRef.get())
        restoredStore.endSession(originalDebugSessionId, "storage_gate_retry_archive")
        restoredStore.endSession(resumedDebugSessionId, "storage_gate_retry_archive")
        retryArchiveStarted = true
        val retryCoordinator = DbMaintenanceCoordinator(
            context = debugContext,
            settings = debugSettings,
            application = app,
            stopRuntime = {},
            onStoreReopened = {},
            closeDebugStore = { debugStoreRef.getAndSet(null)?.close() },
            onDebugStoreReopened = { reopened -> debugStoreRef.set(reopened) }
        )
        val retryResult = retryCoordinator.run(DbMaintenanceOperation.DEBUG_ARCHIVE) {
            retryDebugSessionId = checkNotNull(debugStoreRef.get()).openSession(parameters, parameters.size)
        }
        check(retryResult.ok && !retryResult.archivePath.isNullOrBlank()) {
            "Retrying Secondary archive after rollback should succeed: $retryResult"
        }
        val retriedArchiveDatabase = File(retryResult.archivePath!!, debugDatabaseFile.name)
        check(retriedArchiveDatabase.isFile) { "Successful retry did not preserve the archived SQLite file" }
        SQLiteDatabase.openDatabase(retriedArchiveDatabase.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM debug_direct_sessions WHERE id IN (?, ?)",
                arrayOf(originalDebugSessionId.toString(), resumedDebugSessionId.toString())
            ).use { row -> check(row.moveToFirst() && row.getLong(0) == 2L) {
                "Successful archive retry lost the original or resumed collection session"
            } }
            db.rawQuery("PRAGMA quick_check", null).use { row -> check(row.moveToFirst() && row.getString(0) == "ok") }
        }
        } finally {
            debugStoreRef.getAndSet(null)?.let { activeDebugStore ->
                val sessionIds = when {
                    retryDebugSessionId > 0L -> listOf(retryDebugSessionId)
                    retryArchiveStarted -> emptyList()
                    else -> listOf(originalDebugSessionId, resumedDebugSessionId)
                }
                sessionIds.filter { it > 0L }.forEach { sessionId ->
                    runCatching { activeDebugStore.endSession(sessionId, "storage_gate_cleanup") }
                }
                runCatching { activeDebugStore.close() }
            }
            check(runCatching {
                debugSettings.setCutoverArchiveStoragePending(priorArchiveStoragePending)
                true
            }.getOrDefault(false)) { "Could not restore archive-storage verification state" }
        }
    }

    private fun interruptedTripCompression(context: Context, prefix: String) {
        val databaseName = "${prefix}_trip_compression.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val tripId = "${prefix}_history_trip"
        val start = Instant.parse("2026-09-22T07:00:00Z")
        val points = (0 until 4_096).map { sequence ->
            RoutePoint(
                tripId = tripId,
                sequence = sequence.toLong(),
                observedAt = start.plusSeconds(sequence * 2L).toString(),
                elapsedMs = sequence * 2_000L,
                bootId = "compression-test-boot",
                segmentId = "compression-test-segment",
                latitude = 50.0 + (sequence % 64) * 0.00001,
                longitude = 30.0 + (sequence % 97) * 0.00001,
                accuracyM = 4.0,
                speedKmh = 20.0 + (sequence % 10),
                quality = "ok",
                isFirst = sequence == 0,
                isFinal = sequence == 4_095
            )
        }
        val expectedSession = TripSession(
            tripId = tripId,
            state = TripSession.STATE_CLOSED,
            startedAt = start.toString(),
            endedAt = start.plusSeconds(8_190).toString(),
            movementObserved = true,
            startSoc = 82.0,
            endSoc = 71.0,
            startOdometerKm = 5_100.0,
            lastOdometerKm = 5_222.5,
            durationMs = 8_190_000L,
            distanceKm = 122.5,
            energyKwh = 19.4,
            dischargedKwh = 20.1,
            regeneratedKwh = 0.7,
            netKwh = 19.4,
            energyCoveredMs = 8_000_000L,
            energyUncoveredMs = 190_000L,
            energyPartial = true,
            energyObservedAt = start.plusSeconds(8_190).toString(),
            telegramEligible = true
        )
        val helper = TripDatabaseHelper(context, databaseName)
        val store = TripStore(helper)
        var expectedRoute: List<RoutePoint> = emptyList()
        try {
        store.validateStartup(recoveredAfterReplacement = false)
        store.upsertSession(expectedSession)
        store.replaceRouteRaw(tripId, points)
        expectedRoute = store.queryRoutePoints(tripId)
        check(expectedRoute == points)

        val observedPhases = mutableListOf<TripCompressionPhase>()
        val interrupted = runCatching {
            TripCompression(
                context,
                store,
                onPhase = { phase ->
                    observedPhases += phase
                    if (phase == TripCompressionPhase.CANDIDATE_MOVED) error("test interrupted replacement")
                }
            ).run()
        }.exceptionOrNull()
        check(interrupted?.message.orEmpty().contains("test interrupted replacement")) {
            "Compression did not reach the interrupted candidate replacement: $interrupted"
        }
        check(TripCompressionPhase.CANDIDATE_MOVED in observedPhases)
        check(store.verify() && store.verifyForeignKeys())
        check(store.session(tripId) == expectedSession)
        check(store.queryRoutePoints(tripId) == expectedRoute) {
            "Interrupted candidate replacement changed route evidence"
        }
        } finally {
            runCatching { store.close() }
        }

        // Recreate the durable PREPARED rename boundary: corrupt active candidate, valid backup.
        TripCompression.prepareClosedDatabaseForMove(databaseFile)
        val backup = File(databaseFile.parentFile, databaseFile.name + ".compression-backup.db")
        val journal = File(databaseFile.parentFile, databaseFile.name + ".compression-journal")
        check(!backup.exists() && !journal.exists()) { "Compression rollback left replacement files behind" }
        Files.copy(databaseFile.toPath(), backup.toPath())
        FileOutputStream(journal).use { output ->
            output.write("PREPARED".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        FileOutputStream(databaseFile, false).use { output ->
            output.write("corrupt candidate".toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }

        check(TripCompression.recoverBeforeOpen(context, databaseName)) {
            "Trips startup did not recognize the interrupted replacement"
        }
        check(journal.isFile && journal.readText(Charsets.US_ASCII) == "PREPARED") {
            "Recovery evidence must remain until the restored SQLite file is validated"
        }
        TripStore(TripDatabaseHelper(context, databaseName)).use { recovered ->
            recovered.validateStartup(recoveredAfterReplacement = true)
            check(recovered.verify() && recovered.verifyForeignKeys())
            check(recovered.session(tripId) == expectedSession)
            check(recovered.queryRoutePoints(tripId) == expectedRoute) {
                "Recovery selected the corrupted candidate over the valid original"
            }
        }
        TripCompression.completeRecoveryAfterValidation(context, databaseName)
        check(!journal.exists() && !backup.exists()) { "Validated recovery did not finalize replacement cleanup" }
    }

    private fun tripCompletionHandoff(context: Context, prefix: String) {
        val tripDatabaseName = "${prefix}_trip_completion_handoff.db"
        val telegramDatabaseName = "${prefix}_telegram_completion_handoff.db"
        val opened = TripSession("${prefix}_trip", startedAt = "2026-09-22T07:00:00Z")
        val closed = opened.copy(
            state = TripSession.STATE_CLOSED,
            endedAt = "2026-09-22T07:05:00Z",
            movementObserved = true,
            startSoc = 80.0,
            endSoc = 78.0,
            distanceKm = 10.0,
            energyKwh = 1.8,
            telegramEligible = true
        )
        val created = TripStore(TripDatabaseHelper(context, tripDatabaseName)).use { trips ->
            trips.upsertSession(opened)
            checkNotNull(trips.closeSessionWithCompletion(
                closed,
                TripCompletionIntent(identity = "completion:${prefix}", observedAt = checkNotNull(closed.endedAt), session = closed)
            ))
        }
        val summaryKey = "trip-summary:${created.identity}"
        val locationKey = "trip-location:${created.identity}"
        val events = listOf(
            TelegramOutboxMessage(summaryKey, "trip-summary", "summary for ${created.identity}"),
            TelegramOutboxMessage(
                locationKey,
                "trip-location",
                "location for ${created.identity}",
                waitsForSummaryKey = summaryKey
            )
        )

        // A failure while writing Telegram's receipt rolls back its outbox and state together.
        TripStore(TripDatabaseHelper(context, tripDatabaseName)).use { trips ->
            val telegramHelper = TelegramDatabaseHelper(context, telegramDatabaseName)
            TelegramStore(context, telegramHelper).use { telegram ->
                val database = telegramHelper.writableDatabase
                failInserts(database, "telegram_trip_completion_receipt")
                expectInjectedFailure {
                    handoffTripCompletions(
                        created.sequence,
                        pending = { trips.pendingCompletions(created.sequence) },
                        accept = { intent -> acceptCompletion(telegram, events, intent) },
                        acknowledge = { intent -> trips.acknowledgeCompletion(intent.sequence, intent.identity) },
                        afterAck = {}
                    )
                }
                check(count(database, "telegram_outbox") == 0L)
                check(telegram.telegramRuntimeState() == null)
                check(!telegram.hasTripCompletionReceipt(created.sequence, created.identity))
                check(trips.pendingCompletions(created.sequence) == listOf(created))
                database.execSQL("DROP TRIGGER storage_gate_injected_failure")
            }
        }

        // Commit the Telegram acceptance, then stop before the Trips ACK just as a process can.
        TripStore(TripDatabaseHelper(context, tripDatabaseName)).use { trips ->
            val telegramHelper = TelegramDatabaseHelper(context, telegramDatabaseName)
            TelegramStore(context, telegramHelper).use { telegram ->
                var simulatedStop = false
                val failure = runCatching {
                    handoffTripCompletions(
                        created.sequence,
                        pending = { trips.pendingCompletions(created.sequence) },
                        accept = { intent ->
                            val accepted = acceptCompletion(telegram, events, intent)
                            if (!simulatedStop) {
                                simulatedStop = true
                                error("simulated stop after durable Telegram acceptance")
                            }
                            accepted
                        },
                        acknowledge = { intent -> trips.acknowledgeCompletion(intent.sequence, intent.identity) },
                        afterAck = {}
                    )
                }.exceptionOrNull()
                check(failure?.message.orEmpty().contains("simulated stop"))
                check(telegram.hasTripCompletionReceipt(created.sequence, created.identity))
                check(count(telegramHelper.readableDatabase, "telegram_outbox") == 2L)
                check(trips.pendingCompletions(created.sequence) == listOf(created)) {
                    "Trips intent must remain durable until its exact acknowledgement"
                }
            }
        }

        // Reopen both physical SQLite databases. Receipt dedupe accepts once; Trips then ACKs.
        TripStore(TripDatabaseHelper(context, tripDatabaseName)).use { trips ->
            val telegramHelper = TelegramDatabaseHelper(context, telegramDatabaseName)
            TelegramStore(context, telegramHelper).use { telegram ->
                check(!trips.acknowledgeCompletion(created.sequence, "wrong-identity"))
                handoffTripCompletions(
                    created.sequence,
                    pending = { trips.pendingCompletions(created.sequence) },
                    accept = { intent -> acceptCompletion(telegram, events, intent) },
                    acknowledge = { intent -> trips.acknowledgeCompletion(intent.sequence, intent.identity) },
                    afterAck = {}
                )
                check(trips.pendingCompletions(created.sequence).isEmpty())
                check(telegram.hasTripCompletionReceipt(created.sequence, created.identity))
                check(count(telegramHelper.readableDatabase, "telegram_outbox") == 2L) {
                    "Reopen/retry duplicated or lost a Telegram message"
                }
                check(telegram.telegramMessageByDedupeKey(summaryKey) != null)
                val blockedLocation = checkNotNull(telegram.telegramMessageByDedupeKey(locationKey))
                check(blockedLocation.waitsForSummaryKey == summaryKey)
                check(telegram.oldestDueTelegramMessage(2_000L)?.dedupeKey == summaryKey)

                val summary = checkNotNull(telegram.telegramMessageByDedupeKey(summaryKey))
                telegram.markTelegramDelivered(summary.id, "summary-delivered", 2_000L)
                val location = checkNotNull(telegram.oldestDueTelegramMessage(2_001L))
                check(location.dedupeKey == locationKey && location.waitsForSummaryKey == null) {
                    "Location dependency was not released by the durable summary delivery receipt"
                }
                check(telegram.telegramRuntimeState() == "summary-delivered")
            }
        }
    }

    private fun acceptCompletion(
        telegram: TelegramStore,
        events: List<TelegramOutboxMessage>,
        intent: TripCompletionIntent
    ) = telegram.commitTelegramEvents(
        messages = events,
        stateJson = "state:${intent.identity}",
        nowMs = 1_000L,
        completionSequence = intent.sequence,
        completionIdentity = intent.identity
    )

    private fun replayCoordinator(
        spool: SecondaryTelemetrySpool,
        store: DirectDebugStore,
        sessionId: Long,
        acknowledge: (SecondaryTelemetrySpool.Descriptor) -> SecondarySpoolActionResult
    ) = SecondaryReplayCoordinator(
        fetchPage = { requested, offset, limit ->
            val descriptor = requested ?: spool.oldest()
            if (descriptor == null) {
                SecondarySpoolPage(CollectorHelperProtocol.STATUS_OK, null, 0L, byteArrayOf())
            } else {
                SecondarySpoolPage(
                    CollectorHelperProtocol.STATUS_OK,
                    descriptor,
                    offset,
                    spool.readSlice(descriptor, offset, limit)
                )
            }
        },
        acknowledge = acknowledge,
        quarantine = { descriptor, reason ->
            SecondarySpoolActionResult(
                CollectorHelperProtocol.STATUS_OK,
                affected = spool.quarantine(descriptor, reason)
            )
        },
        importRecord = { record, digest -> store.importSecondaryRecord(sessionId, record, digest) }
    )

    private fun ackSpoolRecord(
        spool: SecondaryTelemetrySpool,
        descriptor: SecondaryTelemetrySpool.Descriptor
    ): SecondarySpoolActionResult = when (spool.acknowledge(descriptor)) {
        SecondaryTelemetrySpool.AckResult.RELEASED ->
            SecondarySpoolActionResult(CollectorHelperProtocol.STATUS_OK, affected = 1)
        SecondaryTelemetrySpool.AckResult.NOT_FOUND -> SecondarySpoolActionResult(1, error = "record not found")
        SecondaryTelemetrySpool.AckResult.REJECTED -> SecondarySpoolActionResult(1, error = "exact ACK rejected")
    }

    private fun openSpoolForTest(root: File, fieldCount: Int): SecondaryTelemetrySpool {
        val factory = SecondaryTelemetrySpool::class.java.getDeclaredMethod(
            "openForTest",
            File::class.java,
            java.lang.Long.TYPE,
            Integer.TYPE
        ).apply { isAccessible = true }
        return factory.invoke(null, root, 32L * 1024 * 1024, fieldCount) as SecondaryTelemetrySpool
    }

    private fun readSpoolRecord(
        spool: SecondaryTelemetrySpool,
        descriptor: SecondaryTelemetrySpool.Descriptor
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        var offset = 0L
        while (offset < descriptor.length) {
            val page = spool.readSlice(descriptor, offset, SecondaryTelemetrySpool.MAX_SLICE_BYTES)
            check(page.isNotEmpty()) { "Spool returned an empty page before end of record" }
            bytes.write(page)
            offset += page.size
        }
        check(offset == descriptor.length)
        return bytes.toByteArray()
    }

    private fun readReadyRecord(
        root: File,
        identity: SecondaryTelemetrySpool.CycleIdentity
    ): ByteArray {
        val readyFiles = checkNotNull(root.listFiles { file -> file.isFile && file.name.endsWith(".ready") }) {
            "Could not list pending secondary spool records"
        }
        val matches = readyFiles.mapNotNull { file ->
            val bytes = file.readBytes()
            bytes.takeIf {
                runCatching { SecondaryTelemetrySpool.Codec.decode(it).identity == identity }.getOrDefault(false)
            }
        }
        check(matches.size == 1) { "Expected exactly one ready spool record for ${identity.sequence}, got ${matches.size}" }
        return matches.single()
    }

    private fun sameDescriptor(
        actual: SecondaryTelemetrySpool.Descriptor?,
        expected: SecondaryTelemetrySpool.Descriptor
    ): Boolean = actual?.let {
        it.identity == expected.identity && it.fileName == expected.fileName &&
            it.length == expected.length && it.sha256 == expected.sha256
    } == true

    private class StorageTestContext(
        base: Context,
        private val filesDirectory: File,
        private val databaseDirectory: File? = null,
        private val applicationContextIsSelf: Boolean = false
    ) : ContextWrapper(base) {
        override fun getFilesDir(): File = filesDirectory

        override fun getDatabasePath(name: String): File {
            val root = databaseDirectory ?: return super.getDatabasePath(name)
            check(root.mkdirs() || root.isDirectory)
            return File(root, name)
        }

        override fun getApplicationContext(): Context =
            if (applicationContextIsSelf) this else super.getApplicationContext()
    }

    private fun awaitEventWriter() {
        repeat(2) {
            val drained = CountDownLatch(1)
            sharedOperationalEventExecutor.execute { drained.countDown() }
            check(drained.await(5, TimeUnit.SECONDS)) { "Operational event writer did not drain" }
        }
    }

    private fun failInserts(db: SQLiteDatabase, table: String) {
        db.execSQL(
            "CREATE TRIGGER storage_gate_injected_failure BEFORE INSERT ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'test injected failure'); END"
        )
    }

    private fun expectInjectedFailure(run: () -> Unit) {
        val error = runCatching(run).exceptionOrNull()
        check(error is SQLiteException && error.message.orEmpty().contains("test injected failure")) {
            "Expected injected SQLite failure, got $error"
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst()); it.getLong(0) }

}
