package com.bydcollector.collector

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.local.*
import com.bydcollector.collector.data.trips.*
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.maintenance.ArchiveStorageItemPhase
import com.bydcollector.collector.maintenance.ArchiveEntryStatus
import com.bydcollector.collector.ui.ArchiveStorageSnapshotCache
import com.bydcollector.collector.maintenance.DatabaseArchiveManager
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.StorageFormatCutoverCoordinator
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** Failure injection uses actual production stores, not copies of their SQL. */
internal object StorageBehaviorGate {
    fun cases(context: Context, prefix: String): List<Pair<String, () -> Unit>> = listOf(
        "main_worker_import_rollback_and_reopen" to { workerImport(context, prefix) },
        "diagnostic_event_does_not_wait_for_sqlite" to { eventDuringWriteLock(context, prefix) },
        "diagnostic_close_serializes_active_and_queued_writers" to { diagnosticClose(context, prefix) },
        "per_database_maintenance_keeps_sibling_commits_live" to { maintenanceIsolation(context, prefix) },
        "archive_storage_default_verifier_compact_families" to { archiveStorageDefaultVerifier(context, prefix) },
        "archive_cold_cache_stays_visible_during_zip" to { archiveColdCache(context, prefix) },
        "archive_busy_full_and_create_failure_preserve_evidence" to { archiveFailureSafety(context, prefix) },
        "trip_completion_atomic_close_and_exact_ack" to { tripCompletion(context, prefix) },
        "telegram_atomic_outbox_state_and_delivery" to { telegramTransactions(context, prefix) }
    )

    private fun archiveFailureSafety(context: Context, prefix: String) {
        val root = File(context.cacheDir, "${prefix}_archive_failures")
        check(root.mkdirs())
        val file = File(root, "evidence.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            check(database.enableWriteAheadLogging())
            database.execSQL("CREATE TABLE evidence(value BLOB)")
            database.execSQL("INSERT INTO evidence VALUES (X'01')")
            database.beginTransactionNonExclusive()
            try {
                database.execSQL("INSERT INTO evidence VALUES (X'02')")
                check(!StorageFormatCutoverCoordinator.checkpointDatabase(file)) {
                    "Busy checkpoint must not report success"
                }
            } finally { database.endTransaction() }
            check(count(database, "evidence") == 1L)
            // Pin the primary connection: outside a transaction this PRAGMA can
            // configure a WAL reader instead of the connection doing the insert.
            database.beginTransactionNonExclusive()
            try {
                val pages = database.rawQuery("PRAGMA page_count", null).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                val limit = pages * database.pageSize
                check(database.setMaximumSize(limit) == limit)
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            val failure = runCatching { database.execSQL("INSERT INTO evidence VALUES (zeroblob(4194304))") }.exceptionOrNull()
            check(failure is android.database.sqlite.SQLiteFullException) {
                "Expected bounded-page SQLITE_FULL, got $failure"
            }
            check(count(database, "evidence") == 1L) { "Failed write altered committed evidence" }
        }
        check(StorageFormatCutoverCoordinator.checkpointDatabase(file))
        val archived = DatabaseArchiveManager.archive(file, File(root, "archives"), "failure_gate")
        check(archived.ok && !file.exists())
        // A directory at the replacement path forces real SQLite open/create failure,
        // without filling the emulator's disk or touching any active app database.
        check(file.mkdir())
        try {
            check(runCatching { SQLiteDatabase.openOrCreateDatabase(file, null).close() }.exceptionOrNull() is SQLiteException)
            check(archived.movedFiles.all { it.isFile })
        } finally { check(file.delete()) }
        check(DatabaseArchiveManager.restore(file, archived.movedFiles))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
            check(count(restored, "evidence") == 1L)
            restored.rawQuery("PRAGMA quick_check", null).use {
                check(it.moveToFirst() && it.getString(0) == "ok")
            }
        }
    }

    private fun archiveStorageDefaultVerifier(context: Context, prefix: String) {
        val testRoot = File(context.cacheDir, "${prefix}_archive_default_verifier")
        val archiveRoot = File(testRoot, "archives")
        check(archiveRoot.mkdirs() || archiveRoot.isDirectory)
        val mainActive = File(testRoot, "${prefix}_main_active.db")
        val secondaryActive = File(testRoot, DirectDebugDatabaseHelper.DATABASE_NAME)
        createCompactArchiveFixture(context, mainActive, main = true)
        createCompactArchiveFixture(context, secondaryActive, main = false)
        val activeContentBefore = listOf(mainActive, secondaryActive)
            .associate { it.absolutePath to sqliteFileSetDigest(it) }

        // No verifier override: exercise ArchiveStorageManager's production default against
        // compact schemas created by the real SQLiteOpenHelper implementations.
        val manager = ArchiveStorageManager(
            archiveRoot = archiveRoot,
            mainDatabaseFile = mainActive,
            debugDatabaseFile = secondaryActive,
            tripsDatabaseFile = File(testRoot, "${prefix}_trips.db")
        )
        val validMain = File(archiveRoot, "${ArchiveStorageManager.MAIN_ARCHIVE_PREFIX}${prefix}_valid")
        val validSecondary = File(archiveRoot, "${ArchiveStorageManager.DEBUG_ARCHIVE_PREFIX}${prefix}_valid")
        copySqliteFileSet(mainActive, validMain)
        copySqliteFileSet(secondaryActive, validSecondary)

        for (rawArchive in listOf(validMain, validSecondary)) {
            check(manager.compressRawArchiveDirectory(rawArchive)) {
                "Default verifier rejected compact archive ${rawArchive.name}"
            }
            check(!rawArchive.exists()) { "Verified raw archive was not deleted: ${rawArchive.name}" }
            val zip = File(archiveRoot, "${rawArchive.name}.zip")
            check(zip.isFile) { "Verified archive ZIP is missing: ${rawArchive.name}" }
            ZipFile(zip).use { archive ->
                val marker = checkNotNull(archive.getEntry(ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER))
                val state = archive.getInputStream(marker).bufferedReader(Charsets.UTF_8).use { it.readLine() }
                check(state == "state=PASSED") { "ZIP did not retain the successful audit marker: ${rawArchive.name}" }
            }
        }

        val invalidMain = File(archiveRoot, "${ArchiveStorageManager.MAIN_ARCHIVE_PREFIX}${prefix}_invalid")
        val invalidSecondary = File(archiveRoot, "${ArchiveStorageManager.DEBUG_ARCHIVE_PREFIX}${prefix}_invalid")
        copySqliteFileSet(mainActive, invalidMain)
        copySqliteFileSet(secondaryActive, invalidSecondary)
        for ((rawArchive, databaseName) in listOf(
            invalidMain to mainActive.name,
            invalidSecondary to secondaryActive.name
        )) {
            File(rawArchive, databaseName).writeText("not a SQLite database", Charsets.UTF_8)
            check(!manager.compressRawArchiveDirectory(rawArchive)) {
                "Default verifier accepted unrecognized archive ${rawArchive.name}"
            }
            check(rawArchive.isDirectory) { "Failed raw archive was removed: ${rawArchive.name}" }
            check(!File(archiveRoot, "${rawArchive.name}.zip").exists()) {
                "Failed archive produced a ZIP: ${rawArchive.name}"
            }
            val marker = File(rawArchive, ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER)
            val markerText = marker.readText(Charsets.UTF_8)
            check("state=FAILED" in markerText && "error=archive_verification_unavailable:format" in markerText) {
                "Failed archive marker did not preserve the verifier result: ${rawArchive.name}"
            }
        }

        assertArchiveFixtureEvidence(mainActive, main = true)
        assertArchiveFixtureEvidence(secondaryActive, main = false)
        val activeContentAfter = listOf(mainActive, secondaryActive)
            .associate { it.absolutePath to sqliteFileSetDigest(it) }
        check(activeContentAfter == activeContentBefore) { "Archive verification changed an active database file set" }
    }

    private fun archiveColdCache(context: Context, prefix: String) {
        val provider = com.bydcollector.collector.ui.DashboardStateProvider(
            context, { error("Initial dashboard must not read telemetry SQLite") },
            com.bydcollector.collector.service.CollectorSettings(context)
        )
        check(provider.loadInitial().archiveStorageScanPending) {
            "Cold dashboard must not claim the archive directory is empty before its first scan"
        }
        val root = File(context.cacheDir, "${prefix}_archive_cold_cache")
        val archives = File(root, "archives").apply { check(mkdirs()) }
        val main = File(root, TelemetryDatabaseHelper.DATABASE_NAME)
        val secondary = File(root, DirectDebugDatabaseHelper.DATABASE_NAME)
        createCompactArchiveFixture(context, main, main = true)
        val raw = File(archives, "bydcollector_telemetry_20260928_120000")
        copySqliteFileSet(main, raw)
        val manager = ArchiveStorageManager(archives, main, secondary)
        val cache = ArchiveStorageSnapshotCache(archives, main, secondary)
        val zipStarted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val worker = thread(name = "native-archive-cold-cache") {
            try {
                check(manager.compressRawArchiveDirectory(raw) { status ->
                    if (status.phase == ArchiveStorageItemPhase.CREATING_ZIP) {
                        zipStarted.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        fun loaded() = run {
            val deadline = android.os.SystemClock.elapsedRealtime() + 3_000L
            var snapshot = cache.snapshot(1024L * 1024L, includeDetails = true)
            while (snapshot.pending && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(10L)
                snapshot = cache.snapshot(1024L * 1024L, includeDetails = true)
            }
            check(!snapshot.pending && snapshot.error == null)
            snapshot.snapshot
        }
        try {
            check(zipStarted.await(3, TimeUnit.SECONDS))
            val during = loaded()
            check(during.entries.single().id == raw.name)
            check(during.entries.single().status == ArchiveEntryStatus.RAW_DIRECTORY)
            check(during.mainDatabaseSizeBytes > 0L)
            resume.countDown()
            worker.join(5_000L)
            check(!worker.isAlive)
            failure.get()?.let { throw it }
            cache.invalidate()
            val after = loaded()
            check(after.entries.single().id == "${raw.name}.zip")
            check(after.entries.single().status == ArchiveEntryStatus.COMPRESSED_ZIP)
            check(after.mainDatabaseSizeBytes == during.mainDatabaseSizeBytes)
        } finally {
            resume.countDown()
            worker.join(5_000L)
            cache.close()
        }
    }

    private fun createCompactArchiveFixture(context: Context, databaseFile: File, main: Boolean) {
        check(databaseFile.parentFile?.mkdirs() == true || databaseFile.parentFile?.isDirectory == true)
        if (main) {
            val helper = TelemetryDatabaseHelper(context, databaseFile.absolutePath)
            try {
                val database = helper.writableDatabase
                check(TelemetryDatabaseHelper.isCompactV2(database)) { "Main archive fixture is not compact v2" }
                database.execSQL(
                    "INSERT INTO collector_events(ts, category, message, detail) VALUES (?, ?, ?, ?)",
                    arrayOf("2026-09-27T00:00:00Z", "archive_gate_fixture", "active evidence", databaseFile.name)
                )
            } finally { helper.close() }
        } else {
            val helper = DirectDebugDatabaseHelper(context, databaseFile.absolutePath)
            try {
                val database = helper.writableDatabase
                check(DirectDebugDatabaseHelper.isCompactV2(database)) { "Secondary archive fixture is not compact v2" }
                database.execSQL(
                    "INSERT INTO debug_direct_catalog_versions(source_version) VALUES (?)",
                    arrayOf(databaseFile.name)
                )
            } finally { helper.close() }
        }
    }

    private fun copySqliteFileSet(source: File, rawArchive: File) {
        check(rawArchive.mkdirs() || rawArchive.isDirectory)
        val files = DatabaseArchiveManager.sidecarFiles(source).filter { it.isFile }
        check(source in files) { "Compact archive fixture database is missing: $source" }
        files.forEach { file -> check(file.copyTo(File(rawArchive, file.name)).isFile) }
    }

    private fun sqliteFileSetDigest(databaseFile: File): Map<String, String> =
        DatabaseArchiveManager.sidecarFiles(databaseFile)
            .filter { it.isFile }
            .associate { file ->
                file.name to MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
            }

    private fun assertArchiveFixtureEvidence(databaseFile: File, main: Boolean) {
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val table = if (main) "collector_events" else "debug_direct_catalog_versions"
            val column = if (main) "detail" else "source_version"
            check(count(database, table) == 1L) { "Active database evidence changed: $databaseFile" }
            database.rawQuery("SELECT $column FROM $table", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == databaseFile.name) {
                    "Active database evidence changed: $databaseFile"
                }
            }
        }
    }

    private fun maintenanceIsolation(context: Context, prefix: String) {
        val application = context.applicationContext as BydCollectorApplication
        val mainFile = File(context.cacheDir, "${prefix}_main_gate.db")
        val secondaryFile = File(context.cacheDir, "${prefix}_secondary_gate.db")
        SQLiteDatabase.openOrCreateDatabase(mainFile, null).use { main ->
            SQLiteDatabase.openOrCreateDatabase(secondaryFile, null).use { secondary ->
                main.execSQL("CREATE TABLE evidence(value INTEGER)")
                secondary.execSQL("CREATE TABLE evidence(value INTEGER)")
                for (operation in DbMaintenanceOperation.entries) {
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val failure = AtomicReference<Throwable?>(null)
                    val writer = thread(name = "native-maintenance-gate") {
                        try {
                            check(application.tryWithExclusiveDatabaseMaintenance(operation, 2_000L) {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                true
                            } == true)
                        } catch (error: Throwable) { failure.set(error) }
                    }
                    try {
                        check(entered.await(3, TimeUnit.SECONDS))
                        if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) {
                            check(application.trySecondaryDatabaseRead { true } == null)
                            application.withDatabaseRead { main.execSQL("INSERT INTO evidence VALUES (1)") }
                        } else {
                            check(application.tryDatabaseRead { true } == null)
                            application.withSecondaryDatabaseRead { secondary.execSQL("INSERT INTO evidence VALUES (1)") }
                        }
                    } finally {
                        release.countDown()
                        writer.join(3_000L)
                    }
                    check(!writer.isAlive)
                    failure.get()?.let { throw it }
                }
                check(count(main, "evidence") == 1L && count(secondary, "evidence") == 1L)
            }
        }
    }

    private fun workerImport(context: Context, prefix: String) {
        val name = "${prefix}_worker_import.db"
        val identity = TelemetryWorkerSampleIdentity("boot", "helper", 1)
        var pollId = 0L
        val input = PersistedPollInput("2026-09-22T07:00:00Z", true, 1, 1, null,
            rawResponseBody = null, readings = emptyList())
        val helper = TelemetryDatabaseHelper(context, name)
        TelemetryStore(context, helper, operationalEventJournal = journal(context, prefix)).use { store ->
            val session = store.openSession("worker_import_test")
            val parameters = store.getActiveCatalogParameters()
            val db = helper.writableDatabase
            failInserts(db, "telemetry_worker_imports")
            injectedFailure { store.insertWorkerPoll(session, identity, input, parameters) }
            check(count(db, "polls") == 0L && count(db, "poll_values") == 0L)
            check(count(db, "telemetry_worker_imports") == 0L)
            db.execSQL("DROP TRIGGER injected_failure")
            val inserted = store.insertWorkerPoll(session, identity, input, parameters)
            check(inserted.inserted)
            pollId = inserted.pollId
            val duplicate = store.insertWorkerPoll(session, identity, input, parameters)
            check(!duplicate.inserted && duplicate.pollId == pollId)
            check(count(db, "polls") == 1L && count(db, "telemetry_worker_imports") == 1L)
            awaitEventWriter()
        }
        val reopened = TelemetryDatabaseHelper(context, name)
        TelemetryStore(context, reopened, operationalEventJournal = journal(context, prefix)).use { store ->
            val result = store.insertWorkerPoll(1L, identity, input, store.getActiveCatalogParameters())
            check(!result.inserted && result.pollId == pollId)
            check(count(reopened.readableDatabase, "polls") == 1L)
        }
    }

    private fun eventDuringWriteLock(context: Context, prefix: String) {
        val helper = TelemetryDatabaseHelper(context, "${prefix}_event_lock.db")
        val journal = journal(context, prefix)
        TelemetryStore(context, helper, operationalEventJournal = journal).use { store ->
            val db = helper.writableDatabase
            val returned = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            db.beginTransaction()
            val caller = thread(name = "event-caller-test") {
                try { store.recordEvent("test_locked_database", "retained event", "full detail") }
                catch (error: Throwable) { failure.set(error) }
                finally { returned.countDown() }
            }
            val returnedWhileLocked: Boolean
            try {
                returnedWhileLocked = returned.await(3, TimeUnit.SECONDS)
            } finally {
                db.endTransaction()
                caller.join(5_000)
            }
            check(!caller.isAlive) { "Event caller did not finish after releasing SQLite" }
            check(failure.get() == null) { "Event caller failed: ${failure.get()}" }
            check(returnedWhileLocked) { "recordEvent waited for the SQLite writer; ANR regression" }
            val snapshot = File(context.cacheDir, "${prefix}_journal_snapshot")
            check(journal.snapshotTo(snapshot) > 0)
            check(snapshot.listFiles().orEmpty().any { it.readText().contains("full detail") })
            awaitEventWriter()
            check(count(db, "collector_events") == 1L)
        }
    }

    private fun diagnosticClose(context: Context, prefix: String) {
        val name = "${prefix}_diagnostic_close.db"
        val journal = journal(context, "${prefix}_diagnostic_close")
        val helper = TelemetryDatabaseHelper(context, name)
        val store = TelemetryStore(context, helper, operationalEventJournal = journal)
        val db = helper.writableDatabase
        val closeStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        var transactionOpen = false
        var closer: Thread? = null
        var monitorProbe: Thread? = null
        try {
            db.beginTransaction()
            transactionOpen = true
            store.recordEvent("diagnostic_close_active", "active writer", "active detail")
            // The async event writer holds the store monitor while SQLite is blocked;
            // closing must wait for that committed write, not close the helper under it.
            val activeWriterProbe = awaitStoreMonitorContention(store, 5_000L)
            monitorProbe = activeWriterProbe
            val closeThread = thread(name = "diagnostic-store-close") {
                closeStarted.countDown()
                try { store.close() }
                catch (error: Throwable) { closeFailure.set(error) }
                finally { closeReturned.countDown() }
            }
            closer = closeThread
            check(closeStarted.await(1, TimeUnit.SECONDS)) { "Store closer did not start" }
            check(!closeReturned.await(100, TimeUnit.MILLISECONDS)) {
                "Store closed while an event writer held its monitor"
            }
            db.endTransaction()
            transactionOpen = false
            check(closeReturned.await(5, TimeUnit.SECONDS)) { "Store close did not finish after event writer" }
            closeThread.join(5_000)
            activeWriterProbe.join(5_000)
            check(!closeThread.isAlive && !activeWriterProbe.isAlive) { "Store close or monitor probe did not finish" }
            check(closeFailure.get() == null) { "Store close failed: ${closeFailure.get()}" }
            awaitEventWriter()
            val reopened = TelemetryDatabaseHelper(context, name)
            try {
                val detail = reopened.readableDatabase.rawQuery(
                    "SELECT detail FROM collector_events WHERE category = ?",
                    arrayOf("diagnostic_close_active")
                ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
                check(detail == "active detail") { "Active event writer was not persisted before close" }
            } finally { reopened.close() }
            val snapshot = File(context.cacheDir, "${prefix}_diagnostic_close_snapshot")
            check(journal.snapshotTo(snapshot) > 0)
            check(snapshot.listFiles().orEmpty().any { it.readText().contains("active detail") })
        } finally {
            if (transactionOpen) runCatching { db.endTransaction() }
            closer?.join(5_000)
            monitorProbe?.join(5_000)
            runCatching { store.close() }
        }

        val queuedName = "${prefix}_diagnostic_queued_close.db"
        val queuedJournal = journal(context, "${prefix}_diagnostic_queued_close")
        val queuedHelper = TelemetryDatabaseHelper(context, queuedName)
        val queuedStore = TelemetryStore(context, queuedHelper, operationalEventJournal = queuedJournal)
        queuedHelper.writableDatabase
        // Hold the exact monitor used by event SQL so close wins before this queued write.
        synchronized(queuedStore) {
            queuedStore.recordEvent("diagnostic_close_queued", "queued after close", "journal only")
            queuedStore.close()
        }
        awaitEventWriter()
        val reopenedQueued = TelemetryDatabaseHelper(context, queuedName)
        try {
            check(count(reopenedQueued.readableDatabase, "collector_events") == 0L) {
                "Queued event SQL reopened or mutated the archived database"
            }
            val snapshot = File(context.cacheDir, "${prefix}_diagnostic_queued_snapshot")
            check(queuedJournal.snapshotTo(snapshot) > 0)
            check(snapshot.listFiles().orEmpty().any { it.readText().contains("journal only") }) {
                "Synchronous diagnostic journal did not retain the event"
            }
        } finally { reopenedQueued.close() }
    }

    private fun awaitStoreMonitorContention(store: TelemetryStore, timeoutMs: Long): Thread {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val attempted = CountDownLatch(1)
            val acquired = CountDownLatch(1)
            val probe = thread(name = "diagnostic-store-monitor-probe", isDaemon = true) {
                attempted.countDown()
                synchronized(store) { Unit }
                acquired.countDown()
            }
            check(attempted.await(1, TimeUnit.SECONDS)) { "Store monitor probe did not start" }
            while (probe.isAlive && System.nanoTime() < deadline) {
                if (probe.state == Thread.State.BLOCKED) return probe
                if (acquired.count == 0L) break
                Thread.yield()
            }
            probe.join(20)
        }
        error("Event SQL writer did not hold the store monitor while SQLite was locked")
    }

    private fun tripCompletion(context: Context, prefix: String) {
        val name = "${prefix}_trip_completion.db"
        val open = TripSession("trip", startedAt = "2026-09-22T07:00:00Z")
        val closed = open.copy(state = TripSession.STATE_CLOSED, endedAt = "2026-09-22T07:05:00Z")
        val intent = TripCompletionIntent(identity = "completion", observedAt = closed.endedAt!!, session = closed)
        var sequence = 0L
        val helper = TripDatabaseHelper(context, name)
        TripStore(helper).use { store ->
            store.upsertSession(open)
            failInserts(helper.writableDatabase, "trip_completion_outbox")
            injectedFailure { store.closeSessionWithCompletion(closed, intent) }
            check(store.session(open.tripId)?.state == TripSession.STATE_OPEN)
            check(store.completionWatermark() == 0L && store.pendingCompletions(0).isEmpty())
            helper.writableDatabase.execSQL("DROP TRIGGER injected_failure")
            val committed = checkNotNull(store.closeSessionWithCompletion(closed, intent))
            sequence = committed.sequence
            check(store.closeSessionWithCompletion(closed, intent) == committed)
            check(store.pendingCompletions(store.completionWatermark()) == listOf(committed))
        }
        TripStore(TripDatabaseHelper(context, name)).use { store ->
            check(store.session(open.tripId)?.state == TripSession.STATE_CLOSED)
            check(!store.acknowledgeCompletion(sequence, "wrong-identity"))
            check(store.pendingCompletions(sequence).single().identity == intent.identity)
            check(store.acknowledgeCompletion(sequence, intent.identity))
            check(!store.acknowledgeCompletion(sequence, intent.identity))
            check(store.completionWatermark() == sequence)
            val next = store.closeSessionWithCompletion(null, intent.copy(identity = "next"))!!
            check(next.sequence > sequence)
        }
    }

    private fun telegramTransactions(context: Context, prefix: String) {
        val name = "${prefix}_telegram.db"
        val messages = listOf(
            TelegramOutboxMessage("summary", "trip-summary", "summary payload"),
            TelegramOutboxMessage("location", "trip-location", "location payload", waitsForSummaryKey = "summary")
        )
        val helper = TelegramDatabaseHelper(context, name)
        TelegramStore(context, helper).use { store ->
            val db = helper.writableDatabase
            failInserts(db, "telegram_trip_completion_receipt")
            injectedFailure { store.commitTelegramEvents(messages, "old-state", 1000L, 1L, "completion") }
            check(count(db, "telegram_outbox") == 0L && store.telegramRuntimeState() == null)
            check(!store.hasTripCompletionReceipt(1L, "completion"))
            db.execSQL("DROP TRIGGER injected_failure")
            check(store.commitTelegramEvents(messages, "old-state", 1000L, 1L, "completion").all { it.inserted })
            check(store.commitTelegramEvents(messages, "ignored-state", 1000L, 1L, "completion").isEmpty())
            check(store.telegramRuntimeState() == "old-state")
        }
        val reopened = TelegramDatabaseHelper(context, name)
        TelegramStore(context, reopened).use { store ->
            val db = reopened.writableDatabase
            check(store.hasTripCompletionReceipt(1L, "completion"))
            check(count(db, "telegram_outbox") == 2L)
            val summary = checkNotNull(store.telegramMessageByDedupeKey("summary"))
            failInserts(db, "telegram_runtime_state")
            injectedFailure { store.markTelegramDelivered(summary.id, "new-state", 2000L) }
            check(store.telegramMessageByDedupeKey("summary") != null)
            check(store.telegramMessageByDedupeKey("location")?.waitsForSummaryKey == "summary")
            check(store.telegramRuntimeState() == "old-state")
            db.execSQL("DROP TRIGGER injected_failure")
            store.markTelegramDelivered(summary.id, "new-state", 2000L)
            check(store.telegramMessageByDedupeKey("summary") == null)
            check(store.telegramMessageByDedupeKey("location")?.waitsForSummaryKey == null)
            check(store.oldestDueTelegramMessage(2000L, null)?.dedupeKey == "location")
            check(store.telegramRuntimeState() == "new-state")
        }
    }

    private fun journal(context: Context, prefix: String) =
        OperationalEventJournal(File(context.cacheDir, "${prefix}_journal"))

    private fun awaitEventWriter() {
        val drained = CountDownLatch(1)
        sharedOperationalEventExecutor.execute { drained.countDown() }
        check(drained.await(5, TimeUnit.SECONDS)) { "Event writer did not drain" }
    }

    private fun failInserts(db: SQLiteDatabase, table: String) {
        db.execSQL("CREATE TRIGGER injected_failure BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'test injected failure'); END")
    }

    private fun injectedFailure(run: () -> Unit) {
        val error = runCatching(run).exceptionOrNull()
        check(error is SQLiteException && error.message.orEmpty().contains("test injected failure")) {
            "Expected injected SQLite failure, got $error"
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst()); it.getLong(0) }
}
