package com.bydcollector.collector

import android.content.ContentValues
import android.content.Context
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedStateStore
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Native SQLite gates for normalized catalog identity caching and empty writes. */
internal object NormalizationCatalogCacheNativeGate {
    fun cases(context: Context, prefix: String): List<Pair<String, () -> Unit>> = listOf(
        "empty_normalized_apply_does_not_import_catalog" to { emptyNormalizedApplyDoesNotImportCatalog(context, prefix) },
        "concurrent_first_catalog_import_keeps_cache_warm" to { concurrentFirstCatalogImport(context, prefix) },
        "normalized_catalog_cache_commit_rollback_reuse_and_reopen" to { catalogCacheCommitRollbackReuseAndReopen(context, prefix) }
    )

    private fun concurrentFirstCatalogImport(context: Context, prefix: String) {
        val helper = TelemetryDatabaseHelper(context, "${prefix}_normalized_concurrent.db")
        TelemetryStore(context, helper, operationalEventJournal = OperationalEventJournal(context)).use { store ->
            val db = helper.writableDatabase
            val normalized = TelemetryStore::class.java.getDeclaredField("normalizedStore")
                .apply { isAccessible = true }.get(store)
            val cacheLock = checkNotNull(NormalizedStateStore::class.java.getDeclaredField("catalogCacheLock")
                .apply { isAccessible = true }.get(normalized))
            val generation = NormalizedStateStore::class.java.getDeclaredField("catalogCacheGeneration")
                .apply { isAccessible = true }
            val cache = NormalizedStateStore::class.java.getDeclaredField("committedCatalogIdentities")
                .apply { isAccessible = true }
            fun epoch() = synchronized(cacheLock) { generation.getLong(normalized) }
            fun awaitCondition(condition: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!condition()) {
                    check(System.nanoTime() < deadline) { "catalog import interleaving timed out" }
                    Thread.sleep(5)
                }
            }
            val failures = ConcurrentLinkedQueue<Throwable>()
            val workers = List(2) { index ->
                Thread({
                    runCatching { store.ensureNormalizedCatalogImported() }
                        .exceptionOrNull()?.let(failures::add)
                }, "catalog-first-import-$index")
            }
            // Hold SQLite until both callers reach initialization, exposing overlapping upserts.
            db.beginTransaction()
            try {
                workers[0].start()
                awaitCondition { epoch() == 1L }
                workers[1].start()
                awaitCondition { workers[1].state == Thread.State.BLOCKED || epoch() > 1L }
                check(epoch() == 1L) { "concurrent ensure issued a duplicate catalog upsert" }
            } finally {
                db.endTransaction()
                workers.forEach { it.join(5_000) }
            }
            check(workers.none { it.isAlive }) { "catalog initialization deadlocked" }
            check(failures.isEmpty()) { "catalog initialization failed: $failures" }
            val identities = synchronized(cacheLock) { cache.get(normalized) as? Map<*, *> }
            check(identities?.size == NormalizedFieldCatalog.fields.size) { "committed catalog cache stayed cold" }
            store.ensureNormalizedCatalogImported()
            check(epoch() == 1L) { "subsequent ensure reimported the catalog" }
        }
    }

    private fun emptyNormalizedApplyDoesNotImportCatalog(context: Context, prefix: String) {
        val helper = TelemetryDatabaseHelper(context, "${prefix}_normalized_empty.db")
        TelemetryStore(context, helper, operationalEventJournal = OperationalEventJournal(context)).use { store ->
            val summary = store.applyNormalizedObservations(emptyList())
            check(summary.observedCount == 0 && summary.changedCount == 0 && summary.historyInsertedCount == 0)
            val catalogRows = count(helper.readableDatabase,
                "SELECT COUNT(*) FROM normalized_field_catalog")
            check(catalogRows == 0L) { "empty normalized apply imported $catalogRows catalog rows" }
        }
    }

    private fun catalogCacheCommitRollbackReuseAndReopen(context: Context, prefix: String) {
        val name = "${prefix}_normalized_catalog_cache.db"
        var helper = TelemetryDatabaseHelper(context, name)
        val database = helper.writableDatabase
        val store = NormalizedStateStore(helper)
        val fieldA = NormalizedFieldCatalog.speedKmh.copy(
            fieldKey = "normalization_cache_probe",
            sourceKeys = listOf("normalization_cache_probe_raw"),
            normalizerId = "normalization_cache_a"
        )
        val fieldB = fieldA.copy(normalizerId = "normalization_cache_b")
        val fieldC = fieldA.copy(normalizerId = "normalization_cache_c")
        var committedC = HistoryRow(-1L, "")

        fun pollId(at: String): Long {
            val sessionId = database.insertOrThrow(
                "collection_sessions", null,
                ContentValues().apply { put("session_type", "normalization_cache_gate") }
            )
            return database.insertOrThrow(
                "polls", null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("ts", at)
                    put("ok", 1)
                }
            )
        }

        fun observation(field: com.bydcollector.collector.data.normalized.NormalizedFieldDefinition,
                        value: Double, at: String, sourcePollId: Long) = NormalizedObservation(
            field = field,
            value = NormalizedValue(NormalizedValueType.NUMBER, number = value),
            quality = NormalizedQuality.OK,
            sourcePollId = sourcePollId,
            sourceKey = "poll-source-$sourcePollId",
            observedAt = at,
            reason = "same-quality-detail"
        )

        try {
            store.upsertCatalog(listOf(fieldA))
            val t1 = "2026-10-10T10:00:00Z"
            val sourceA = pollId(t1)
            check(store.applyObservations(listOf(observation(fieldA, 30.0, t1, sourceA)))
                .historyInsertedCount == 1)

            val t2 = "2026-10-10T10:00:01Z"
            val sourceB = pollId(t2)
            val unchanged = store.applyObservations(listOf(observation(fieldA, 30.0, t2, sourceB)))
            check(unchanged.historyInsertedCount == 0 && unchanged.changedCount == 0)
            val current = store.currentState().single { it.fieldKey == fieldA.fieldKey }
            check(current.observedAt == t2 && current.sourcePollId == sourceB &&
                current.quality == NormalizedQuality.OK.name && current.qualityDetail == "same-quality-detail") {
                "unchanged value failed to refresh observation source/time/quality: $current"
            }
            check(current.changedAt == t1) { "unchanged value changed its semantic-change timestamp" }
            check(count(database, "SELECT COUNT(*) FROM vehicle_state_history") == 1L)

            store.upsertCatalog(listOf(fieldB))
            val t3 = "2026-10-10T10:00:02Z"
            check(store.applyObservations(listOf(observation(fieldB, 31.0, t3, pollId(t3))))
                .historyInsertedCount == 1)
            check(historyRows(database).map { it.normalizerId } ==
                listOf("normalization_cache_a", "normalization_cache_b")) {
                "catalog update did not change compact history metadata: ${historyRows(database)}"
            }

            val t4 = "2026-10-10T10:00:03Z"
            var rolledBackFieldId = -1L
            database.beginTransaction()
            try {
                // Nested catalog updates must invalidate but must not publish before the outer commit.
                store.upsertCatalog(listOf(fieldC))
                check(store.applyObservationsInTransaction(
                    database, listOf(observation(fieldC, 32.0, t4, pollId(t4)))
                ).historyInsertedCount == 1)
                rolledBackFieldId = historyRows(database).last().fieldId
                check(historyRows(database).last().normalizerId == "normalization_cache_c")
                // Intentionally do not mark the outer transaction successful.
            } finally {
                database.endTransaction()
            }
            check(count(database, "SELECT COUNT(*) FROM normalized_history_field_catalog WHERE normalizer_id = 'normalization_cache_c'") == 0L)
            check(text(database,
                "SELECT normalizer_id FROM normalized_field_catalog WHERE field_key = ?", arrayOf(fieldA.fieldKey)
            ) == "normalization_cache_b") { "rolled-back catalog metadata escaped its transaction" }

            store.upsertCatalog(listOf(fieldC))
            val t5 = "2026-10-10T10:00:04Z"
            check(store.applyObservations(listOf(observation(fieldC, 32.0, t5, pollId(t5))))
                .historyInsertedCount == 1)
            committedC = historyRows(database).last()
            check(committedC.normalizerId == "normalization_cache_c")
            check(committedC.fieldId == rolledBackFieldId) {
                "rolled-back compact catalog id was not safely reusable: rolled back=$rolledBackFieldId committed=$committedC"
            }
            check(count(database,
                "SELECT COUNT(*) FROM normalized_history_field_catalog WHERE normalizer_id = 'normalization_cache_c'") == 1L)
        } finally {
            helper.close()
        }

        helper = TelemetryDatabaseHelper(context, name)
        try {
            val reopenedStore = NormalizedStateStore(helper)
            val t6 = "2026-10-10T10:00:05Z"
            check(reopenedStore.applyObservations(listOf(observation(fieldC, 33.0, t6, pollId(helper.writableDatabase, t6))))
                .historyInsertedCount == 1)
            val reopenedRows = historyRows(helper.readableDatabase)
            check(reopenedRows.last().normalizerId == "normalization_cache_c")
            check(reopenedRows.last().fieldId == committedC.fieldId)
            check(count(helper.readableDatabase,
                "SELECT COUNT(*) FROM normalized_history_field_catalog WHERE normalizer_id = 'normalization_cache_c'") == 1L)
        } finally {
            helper.close()
        }
    }

    private data class HistoryRow(val fieldId: Long, val normalizerId: String)

    private fun historyRows(database: android.database.sqlite.SQLiteDatabase): List<HistoryRow> =
        database.rawQuery(
            """SELECT field.id, field.normalizer_id
               FROM vehicle_state_history history
               JOIN normalized_history_field_catalog field ON field.id = history.field_id
               ORDER BY history.id""".trimIndent(), emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(HistoryRow(cursor.getLong(0), cursor.getString(1)))
            }
        }

    private fun count(database: android.database.sqlite.SQLiteDatabase, sql: String): Long =
        database.rawQuery(sql, emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun text(database: android.database.sqlite.SQLiteDatabase, sql: String, args: Array<String>): String =
        database.rawQuery(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun pollId(database: android.database.sqlite.SQLiteDatabase, at: String): Long {
        val sessionId = database.insertOrThrow(
            "collection_sessions", null,
            ContentValues().apply { put("session_type", "normalization_cache_gate") }
        )
        return database.insertOrThrow(
            "polls", null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("ts", at)
                put("ok", 1)
            }
        )
    }
}
