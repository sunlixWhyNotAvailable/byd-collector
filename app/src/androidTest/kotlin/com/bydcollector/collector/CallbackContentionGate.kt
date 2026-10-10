package com.bydcollector.collector

import android.content.Context
import com.bydcollector.collector.data.callback.CallbackDelivery
import com.bydcollector.collector.data.callback.CallbackImportResult
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Native SQLite contention checks for the callback normalization read path. */
internal object CallbackContentionGate {
    fun cases(context: Context, prefix: String): List<Pair<String, () -> Unit>> = listOf(
        "pending_callback_normalization_reads_during_writer_reservation" to {
            pendingNormalizationReadDuringWriterReservation(context, prefix)
        },
        "concurrent_callback_imports_are_not_skipped_by_pending_read_fence" to {
            concurrentImportsAreNotSkippedByPendingReadFence(context, prefix)
        }
    )

    private fun pendingNormalizationReadDuringWriterReservation(context: Context, prefix: String) {
        val helper = TelemetryDatabaseHelper(context, "${prefix}_callback_pending_read.db")
        TelemetryStore(
            context,
            helper,
            operationalEventJournal = OperationalEventJournal(context)
        ).use { store ->
            val batch = TelemetryCallbackBatch(
                "pending-read-boot",
                "pending-read-helper",
                MAIN_STREAM,
                1,
                1,
                listOf(
                    TelemetryCallbackBatch.Event(
                        1,
                        1013,
                        -1807745016,
                        TelemetryCallbackBatch.TYPE_FLOAT,
                        40f.toRawBits(),
                        null,
                        1_790_000_000_001L,
                        1001L,
                        null,
                        "callback"
                    )
                )
            )
            check(store.importCallbackBatch(batch, TelemetryCallbackBatch.digest(batch.encode()), CallbackDelivery.LIVE)
                is CallbackImportResult.Committed)

            val database = helper.writableDatabase
            // A private fixture WAL lets SQLite provide a reader connection while
            // the writer reservation is held; production journal mode is unchanged.
            check(database.enableWriteAheadLogging()) { "callback read gate requires fixture WAL support" }
            val completed = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            database.beginTransactionNonExclusive()
            val reader = thread(name = "callback-pending-read-gate") {
                try {
                    val page = store.pendingCallbackNormalization()
                    check(page.single().eventSequence == 1L) { "pending callback page changed while reading" }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    completed.countDown()
                }
            }

            val returnedWhileWriterReserved = try {
                completed.await(3, TimeUnit.SECONDS)
            } finally {
                database.endTransaction()
            }
            reader.join(5_000)
            check(!reader.isAlive) { "pending normalization reader did not finish after writer release" }
            failure.get()?.let { throw AssertionError("pending normalization read failed", it) }
            check(returnedWhileWriterReserved) {
                "pending normalization waited for a writer reservation instead of using a read query"
            }
        }
    }

    private fun concurrentImportsAreNotSkippedByPendingReadFence(context: Context, prefix: String) {
        val helper = TelemetryDatabaseHelper(context, "${prefix}_callback_pending_fence.db")
        TelemetryStore(
            context,
            helper,
            operationalEventJournal = OperationalEventJournal(context)
        ).use { store ->
            val database = helper.writableDatabase
            check(database.enableWriteAheadLogging()) { "callback fence gate requires fixture WAL support" }
            val start = CountDownLatch(1)
            val writerDone = CountDownLatch(1)
            val stopReader = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>()
            val observedSequences = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
            val writer = thread(name = "callback-pending-fence-writer") {
                try {
                    start.await()
                    for (sequence in 1L..EVENT_COUNT) {
                        val batch = TelemetryCallbackBatch(
                            "pending-fence-boot",
                            "pending-fence-helper",
                            MAIN_STREAM,
                            1,
                            sequence,
                            listOf(
                                TelemetryCallbackBatch.Event(
                                    sequence,
                                    1013,
                                    -1807745016,
                                    TelemetryCallbackBatch.TYPE_FLOAT,
                                    (sequence.toFloat()).toRawBits(),
                                    null,
                                    1_790_000_100_000L + sequence,
                                    2_000L + sequence,
                                    null,
                                    "callback"
                                )
                            )
                        )
                        val imported = store.importCallbackBatch(
                            batch,
                            TelemetryCallbackBatch.digest(batch.encode()),
                            CallbackDelivery.LIVE
                        )
                        check(imported is CallbackImportResult.Committed) { "callback batch $sequence was not imported: $imported" }
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    writerDone.countDown()
                }
            }
            val reader = thread(name = "callback-pending-fence-reader") {
                try {
                    start.await()
                    while (!stopReader.get() && writerDone.count > 0) {
                        store.pendingCallbackNormalization().forEach { observedSequences += it.eventSequence }
                        Thread.yield()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
            start.countDown()
            writer.join(15_000)
            stopReader.set(true)
            reader.join(5_000)
            check(!writer.isAlive) { "callback import race writer did not finish" }
            check(!reader.isAlive) { "callback import race reader did not finish" }
            failure.get()?.let { throw AssertionError("callback pending read/import race failed", it) }

            // A final read after the producer fence must include any row committed
            // after an earlier read captured its maximum ID.
            repeat(2) {
                store.pendingCallbackNormalization().forEach { observedSequences += it.eventSequence }
            }
            check(observedSequences.size == EVENT_COUNT.toInt()) {
                "pending read fence skipped callback events: observed ${observedSequences.size} of $EVENT_COUNT"
            }
        }
    }

    private const val MAIN_STREAM = 1
    private const val EVENT_COUNT = 128L
}
