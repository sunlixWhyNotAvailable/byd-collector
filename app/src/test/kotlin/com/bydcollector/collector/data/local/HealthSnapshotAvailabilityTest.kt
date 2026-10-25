package com.bydcollector.collector.data.local

import android.app.Application
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HealthSnapshotAvailabilityTest {
    @Test fun healthAvailabilityAndRecovery() {
        val context = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID().toString()
        val databaseName = "h_$id.db"
        val journalRoot = File(context.cacheDir, "hj_$id")
        val journal = OperationalEventJournal(journalRoot)
        var helper = TelemetryDatabaseHelper(context, databaseName)
        helper.writableDatabase
        var store = TelemetryStore(
            context,
            helper,
            operationalEventJournal = journal
        )

        try {
            val empty = store.healthSnapshot(
                running = false,
                detail = HealthSnapshotDetail.FULL,
                includeCounts = true,
                includePollHealth = false
            )
            assertEquals(0L, empty.pollCount, empty.healthReadErrors.toString())
            assertEquals(0L, empty.valueRowCount)
            assertEquals(0L, empty.ecRowCount)
            assertEquals(0L, empty.normalizedCurrentCount)
            assertEquals(0L, empty.normalizedHistoryCount)
            assertEquals(0L, empty.mqttPendingCount)
            assertEquals(0, empty.mqttRetryFailureCount)
            assertTrue(empty.recentEvents.isEmpty())
            assertEquals(HealthReadAvailability.AVAILABLE, empty.recentEventsAvailability)
            assertTrue(empty.healthReadErrors.isEmpty())

            val unloaded = store.healthSnapshot(
                running = false,
                detail = HealthSnapshotDetail.SUMMARY,
                includeCounts = false,
                includePollHealth = false
            )
            assertEquals(-1L, unloaded.pollCount)
            assertEquals(-1L, unloaded.mqttPendingCount)
            assertEquals(-1, unloaded.mqttRetryFailureCount)
            assertEquals(HealthReadAvailability.NOT_REQUESTED, unloaded.recentEventsAvailability)
            assertTrue(unloaded.healthReadErrors.isEmpty())

            val db = helper.writableDatabase
            db.execSQL("ALTER TABLE polls RENAME TO polls_health_test")
            db.execSQL("ALTER TABLE mqtt_outbox RENAME TO mqtt_outbox_health_test")
            db.execSQL("ALTER TABLE mqtt_retry_state RENAME TO mqtt_retry_state_health_test")
            db.execSQL("ALTER TABLE collector_events RENAME TO collector_events_health_test")

            val failed = store.healthSnapshot(
                running = false,
                detail = HealthSnapshotDetail.FULL,
                includeCounts = true,
                includePollHealth = false
            )
            assertEquals(-1L, failed.pollCount)
            assertEquals(-1L, failed.mqttPendingCount)
            assertEquals(-1, failed.mqttRetryFailureCount)
            assertTrue(failed.recentEvents.isEmpty())
            assertEquals(HealthReadAvailability.ERROR, failed.recentEventsAvailability)
            assertEquals(
                setOf("poll_count", "mqtt_pending_count", "mqtt_retry_state", "recent_events"),
                failed.healthReadErrors.map { it.field }.toSet()
            )
            assertTrue(failed.healthReadErrors.all { it.exceptionType.isNotBlank() && it.message.isNotBlank() })
            assertTrue(failed.healthReadErrors.single { it.field == "poll_count" }.message.contains("polls"))

            val repeatedFailure = store.healthSnapshot(
                running = false,
                detail = HealthSnapshotDetail.FULL,
                includeCounts = true,
                includePollHealth = false
            )
            assertEquals(failed.healthReadErrors, repeatedFailure.healthReadErrors)
            drainOperationalEventWrites()
            val firstJournalRows = journalRows(context, journal)
            assertEquals(4, firstJournalRows.size)
            assertTrue(firstJournalRows.all { JSONObject(it).getString("category") == "database_health_read_error" })
            assertTrue(firstJournalRows.any {
                val event = JSONObject(it)
                event.getString("detail").contains("stage=dashboard_health_read field=poll_count") &&
                    event.getString("detail").contains("SQLite")
            })

            db.execSQL("ALTER TABLE polls_health_test RENAME TO polls")
            db.execSQL("ALTER TABLE mqtt_outbox_health_test RENAME TO mqtt_outbox")
            db.execSQL("ALTER TABLE mqtt_retry_state_health_test RENAME TO mqtt_retry_state")
            db.execSQL("ALTER TABLE collector_events_health_test RENAME TO collector_events")

            val recoveredInProcess = store.healthSnapshot(
                running = false,
                detail = HealthSnapshotDetail.FULL,
                includeCounts = true,
                includePollHealth = false
            )
            assertTrue(recoveredInProcess.healthReadErrors.isEmpty())
            assertEquals(HealthReadAvailability.AVAILABLE, recoveredInProcess.recentEventsAvailability)

            db.execSQL("ALTER TABLE polls RENAME TO polls_health_test")
            val failedAfterRecovery = store.healthSnapshot(
                running = false,
                detail = HealthSnapshotDetail.FULL,
                includeCounts = true,
                includePollHealth = false
            )
            assertEquals(setOf("poll_count"), failedAfterRecovery.healthReadErrors.map { it.field }.toSet())
            assertEquals(0L, failedAfterRecovery.mqttPendingCount)
            assertEquals(0, failedAfterRecovery.mqttRetryFailureCount)
            assertEquals(HealthReadAvailability.AVAILABLE, failedAfterRecovery.recentEventsAvailability)
            drainOperationalEventWrites()
            assertEquals(5, journalRows(context, journal).size)
            db.execSQL("ALTER TABLE polls_health_test RENAME TO polls")

            store.close()

            helper = TelemetryDatabaseHelper(context, databaseName)
            store = TelemetryStore(
                context,
                helper,
                operationalEventJournal = journal
            )
            val recovered = store.healthSnapshot(
                running = false,
                detail = HealthSnapshotDetail.FULL,
                includeCounts = true,
                includePollHealth = false
            )
            assertEquals(0L, recovered.pollCount)
            assertEquals(0L, recovered.mqttPendingCount)
            assertEquals(0, recovered.mqttRetryFailureCount)
            assertTrue(recovered.recentEvents.isEmpty())
            assertEquals(HealthReadAvailability.AVAILABLE, recovered.recentEventsAvailability)
            assertTrue(recovered.healthReadErrors.isEmpty())

            assertFalse(journalRows(context, journal).isEmpty())
        } finally {
            store.close()
            helper.close()
            context.deleteDatabase(databaseName)
            journalRoot.deleteRecursively()
        }
    }

    private fun drainOperationalEventWrites() {
        val drained = CountDownLatch(1)
        sharedOperationalEventExecutor.execute { drained.countDown() }
        assertTrue(drained.await(5, TimeUnit.SECONDS), "operational event writes did not drain")
    }

    private fun journalRows(context: android.content.Context, journal: OperationalEventJournal): List<String> {
        val snapshot = File(context.cacheDir, "hjs_${UUID.randomUUID()}")
        return try {
            journal.snapshotTo(snapshot)
            snapshot.listFiles().orEmpty().flatMap { it.readLines(Charsets.UTF_8) }
        } finally {
            snapshot.deleteRecursively()
        }
    }
}
