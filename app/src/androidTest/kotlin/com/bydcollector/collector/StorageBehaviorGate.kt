package com.bydcollector.collector

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.bydcollector.collector.data.local.*
import com.bydcollector.collector.data.trips.*
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Failure injection uses actual production stores, not copies of their SQL. */
internal object StorageBehaviorGate {
    fun cases(context: Context, prefix: String): List<Pair<String, () -> Unit>> = listOf(
        "main_worker_import_rollback_and_reopen" to { workerImport(context, prefix) },
        "diagnostic_event_does_not_wait_for_sqlite" to { eventDuringWriteLock(context, prefix) },
        "trip_completion_atomic_close_and_exact_ack" to { tripCompletion(context, prefix) },
        "telegram_atomic_outbox_state_and_delivery" to { telegramTransactions(context, prefix) }
    )

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
