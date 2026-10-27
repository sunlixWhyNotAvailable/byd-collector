package com.bydcollector.collector.data.normalized

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NormalizedStateBatchWriteTest {
    @Test
    fun batchedApplyPreservesOrderedHistoryFreshnessAndRollbackInBothSchemas() {
        val context = RuntimeEnvironment.getApplication()
        exercise(context, "nc_${System.nanoTime()}.db", expectCompact = true)
        exercise(context, "nl_${System.nanoTime()}.db", expectCompact = false)
    }

    private fun exercise(context: Context, databaseName: String, expectCompact: Boolean) {
        if (!expectCompact) createLegacyDatabase(context, databaseName)
        val helper = TelemetryDatabaseHelper(context, databaseName)
        try {
            val db = helper.writableDatabase
            assertEquals(expectCompact, helper.isCompactV2())
            val store = NormalizedStateStore(helper)
            val soc = NormalizedFieldCatalog.soc
            val extraFields = if (expectCompact) (0 until 400).map(::batchField) else emptyList()
            val fields = listOf(soc) + extraFields
            store.upsertCatalog(fields)

            val firstBatch = listOf(
                observation(soc, 50.0, "2026-10-26T12:00:00Z"),
                observation(soc, 51.0, "2026-10-26T12:00:01Z"),
                observation(soc, 50.0, "2026-10-26T12:00:02Z")
            ) + extraFields.map { observation(it, 7.0, "2026-10-26T12:00:02Z") }
            val firstWrite = store.applyObservations(firstBatch)
            assertEquals(firstBatch.size, firstWrite.observedCount)
            assertEquals(extraFields.size + 3, firstWrite.historyInsertedCount)
            assertEquals(fields.size, firstWrite.currentInsertedCount)
            assertEquals(extraFields.size + 3, historyCount(db))
            assertEquals(listOf(50.0, 51.0, 50.0), historyNumbers(db, expectCompact, soc.fieldKey))

            val freshBatch = fields.map { field ->
                observation(field, if (field.fieldKey == soc.fieldKey) 50.0 else 7.0, "2026-10-26T12:00:03Z")
            }
            val freshWrite = store.applyObservations(freshBatch)
            assertEquals(0, freshWrite.changedCount)
            assertEquals(0, freshWrite.historyInsertedCount)
            assertEquals(0, freshWrite.currentInsertedCount)
            assertEquals(extraFields.size + 3, historyCount(db))
            val currentSoc = store.currentState().single { it.fieldKey == soc.fieldKey }
            assertEquals("2026-10-26T12:00:03Z", currentSoc.observedAt)
            assertEquals("2026-10-26T12:00:02Z", currentSoc.changedAt)
            assertEquals(fields.size, store.currentState().size)

            assertFailsWith<SQLiteConstraintException> {
                store.applyObservations(
                    listOf(observation(soc, 52.0, "2026-10-26T12:00:04Z", sourcePollId = Long.MAX_VALUE))
                )
            }
            assertEquals(currentSoc, store.currentState().single { it.fieldKey == soc.fieldKey })
            assertEquals(listOf(50.0, 51.0, 50.0), historyNumbers(db, expectCompact, soc.fieldKey))
            assertEquals(extraFields.size + 3, historyCount(db))
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createLegacyDatabase(context: Context, databaseName: String) {
        context.openOrCreateDatabase(databaseName, 0, null).use { db ->
            val schema = context.assets.open(TelemetryDatabaseHelper.LEGACY_SCHEMA_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            schema.split(';').forEach { statement ->
                val sql = statement.trim()
                if (sql.isNotEmpty()) db.execSQL(sql)
            }
            db.version = TelemetryDatabaseHelper.DATABASE_VERSION - 1
        }
    }

    private fun batchField(index: Int) = NormalizedFieldDefinition(
        fieldKey = "normalized_batch_$index",
        category = NormalizedCategory.BODY,
        valueType = NormalizedValueType.NUMBER,
        unit = "test",
        displayName = "Batch field $index",
        deviceClass = null,
        stateClass = null,
        entityPlatform = "sensor",
        sourceKeys = listOf("normalized_batch_source_$index"),
        normalizerId = "test_number",
        mqttDefaultEnabled = false
    )

    private fun observation(
        field: NormalizedFieldDefinition,
        value: Double,
        observedAt: String,
        sourcePollId: Long? = null
    ) = NormalizedObservation(
        field = field,
        value = NormalizedValue(NormalizedValueType.NUMBER, number = value),
        quality = NormalizedQuality.OK,
        sourcePollId = sourcePollId,
        sourceKey = field.sourceKeys.firstOrNull(),
        observedAt = observedAt
    )

    private fun historyCount(db: SQLiteDatabase): Int = db.rawQuery(
        "SELECT COUNT(*) FROM vehicle_state_history", emptyArray()
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun historyNumbers(db: SQLiteDatabase, compact: Boolean, fieldKey: String): List<Double> {
        val sql = if (compact) {
            "SELECT h.value_number FROM vehicle_state_history h " +
                "JOIN normalized_history_field_catalog c ON c.id = h.field_id " +
                "WHERE c.field_key = ? ORDER BY h.id"
        } else {
            "SELECT value_number FROM vehicle_state_history WHERE field_key = ? ORDER BY id"
        }
        return db.rawQuery(sql, arrayOf(fieldKey)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    assertFalse(cursor.isNull(0))
                    add(cursor.getDouble(0))
                }
            }
        }
    }
}
