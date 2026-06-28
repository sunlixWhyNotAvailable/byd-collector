package com.bydcollector.collector.data.normalized

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

//stores latest semantic vehicle state plus change history for ha live state and influx time series
class NormalizedStateStore(
    private val helper: TelemetryDatabaseHelper,
    private val clock: Clock = SystemClockAdapter()
) {
    fun upsertCatalog(fields: List<NormalizedFieldDefinition> = NormalizedFieldCatalog.fields) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val activeFieldKeys = fields.mapTo(mutableSetOf()) { it.fieldKey }
            fields.forEach { field ->
                upsertCatalogField(db, field)
            }
            deleteRetiredCurrentRows(db, activeFieldKeys)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun applyObservations(observations: List<NormalizedObservation>): NormalizedWriteSummary {
        val db = helper.writableDatabase
        var changedCount = 0
        var historyInsertedCount = 0
        val changedCategories = mutableSetOf<String>()

        db.beginTransaction()
        try {
            observations.forEach { observation ->
                val next = observation.toStoredState()
                val previous = currentStateForField(db, next.fieldKey)
                //dedupes unchanged semantic values so history represents changes instead of every poll tick
                val decision = NormalizedStateReducer.decide(previous, next)

                if (decision.insertHistory) {
                    changedCount += 1
                    insertHistory(db, decision.current)
                    historyInsertedCount += 1
                    changedCategories += decision.current.category
                }
                upsertCurrent(db, decision.current)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return NormalizedWriteSummary(
            observedCount = observations.size,
            changedCount = changedCount,
            historyInsertedCount = historyInsertedCount,
            changedCategories = changedCategories
        )
    }

    fun currentState(categories: Set<String>? = null): List<StoredNormalizedState> {
        if (categories?.isEmpty() == true) return emptyList()

        val db = helper.readableDatabase
        val args = categories?.toList().orEmpty()
        val where = if (categories == null) {
            ""
        } else {
            "WHERE category IN (${args.joinToString(",") { "?" }})"
        }
        db.rawQuery(
            """
            SELECT field_key, category, value_type, value_text, value_number, value_bool,
                quality, unit, source_poll_id, source_keys, observed_at, changed_at
            FROM vehicle_state_current
            $where
            ORDER BY category, field_key
            """.trimIndent(),
            args.toTypedArray()
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toStoredNormalizedState())
            }
        }
    }

    fun markStale(nowIso: String = clock.nowIso()): Int {
        val now = parseIso(nowIso) ?: return 0
        //marks stale fields when polling stops so ha can see old values as degraded instead of fresh
        val staleRows = currentState()
            .filter { it.quality == NormalizedQuality.OK.name }
            .filter { row ->
                val observedAt = parseIso(row.observedAt) ?: return@filter false
                val ttl = staleAfterMs(row.category) ?: return@filter false
                ChronoUnit.MILLIS.between(observedAt, now) > ttl
            }
            .map { NormalizedStateReducer.markStale(previous = it, nowIso = nowIso) }

        if (staleRows.isEmpty()) return 0

        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            staleRows.forEach { stale ->
                insertHistory(db, stale)
                upsertCurrent(db, stale)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return staleRows.size
    }

    private fun upsertCatalogField(db: SQLiteDatabase, field: NormalizedFieldDefinition) {
        val now = clock.nowIso()
        //keeps discovery/export metadata in sqlite next to the current values that use it
        val values = ContentValues().apply {
            put("category", field.category.mqttKey)
            put("value_type", field.valueType.name)
            put("unit", field.unit)
            put("display_name", field.displayName)
            put("device_class", field.deviceClass)
            put("state_class", field.stateClass)
            put("entity_platform", field.entityPlatform)
            put("source_keys", field.sourceKeys.joinToString(","))
            put("normalizer_id", field.normalizerId)
            put("stale_after_ms", field.category.staleAfterMs)
            put("mqtt_default_enabled", if (field.mqttDefaultEnabled) 1 else 0)
            put("catalog_version", NormalizedFieldCatalog.CATALOG_VERSION)
            put("updated_at", now)
        }
        val updated = db.update(
            "normalized_field_catalog",
            values,
            "field_key = ?",
            arrayOf(field.fieldKey)
        )
        if (updated == 0) {
            values.put("field_key", field.fieldKey)
            values.put("created_at", now)
            db.insertOrThrow("normalized_field_catalog", null, values)
        }
    }

    private fun deleteRetiredCurrentRows(db: SQLiteDatabase, activeFieldKeys: Set<String>) {
        retiredNormalizedFieldKeysToDelete(activeFieldKeys).forEach { fieldKey ->
            //guards mqtt/influx current-state export from retired canonical keys after in-place updates
            db.delete("vehicle_state_current", "field_key = ?", arrayOf(fieldKey))
            //keeps catalog metadata because historical rows still reference it through sqlite foreign keys
        }
    }

    private fun NormalizedObservation.toStoredState(): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = field.fieldKey,
            category = field.category.mqttKey,
            valueType = field.valueType.name,
            valueText = value.text,
            valueNumber = value.number,
            valueBool = value.bool,
            quality = quality.name,
            unit = field.unit,
            sourcePollId = sourcePollId,
            sourceKeys = field.sourceKeys.joinToString(","),
            observedAt = observedAt,
            changedAt = observedAt
        )
    }

    private fun currentStateForField(db: SQLiteDatabase, fieldKey: String): StoredNormalizedState? {
        db.rawQuery(
            """
            SELECT field_key, category, value_type, value_text, value_number, value_bool,
                quality, unit, source_poll_id, source_keys, observed_at, changed_at
            FROM vehicle_state_current
            WHERE field_key = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(fieldKey)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toStoredNormalizedState() else null
        }
    }

    private fun upsertCurrent(db: SQLiteDatabase, state: StoredNormalizedState) {
        val values = state.toContentValues().apply {
            put("updated_at", clock.nowIso())
        }
        val updated = db.update(
            "vehicle_state_current",
            values,
            "field_key = ?",
            arrayOf(state.fieldKey)
        )
        if (updated == 0) {
            db.insertOrThrow("vehicle_state_current", null, values)
        }
    }

    private fun insertHistory(db: SQLiteDatabase, state: StoredNormalizedState) {
        db.insertOrThrow("vehicle_state_history", null, state.toContentValues())
    }

    private fun StoredNormalizedState.toContentValues(): ContentValues {
        return ContentValues().apply {
            put("field_key", fieldKey)
            put("category", category)
            put("value_type", valueType)
            put("value_text", valueText)
            put("value_number", valueNumber)
            when (valueBool) {
                null -> putNull("value_bool")
                true -> put("value_bool", 1)
                false -> put("value_bool", 0)
            }
            put("quality", quality)
            put("unit", unit)
            if (sourcePollId == null) putNull("source_poll_id") else put("source_poll_id", sourcePollId)
            put("source_keys", sourceKeys)
            put("observed_at", observedAt)
            put("changed_at", changedAt)
        }
    }

    private fun Cursor.toStoredNormalizedState(): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = getString(0),
            category = getString(1),
            valueType = getString(2),
            valueText = getStringOrNull(3),
            valueNumber = getDoubleOrNull(4),
            valueBool = getBoolOrNull(5),
            quality = getString(6),
            unit = getStringOrNull(7),
            sourcePollId = getLongOrNull(8),
            sourceKeys = getString(9),
            observedAt = getString(10),
            changedAt = getString(11)
        )
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private fun Cursor.getBoolOrNull(index: Int): Boolean? = if (isNull(index)) null else getInt(index) == 1

    private fun parseIso(value: String): OffsetDateTime? = runCatching {
        OffsetDateTime.parse(value)
    }.getOrNull()

    private fun staleAfterMs(category: String): Long? {
        return NormalizedCategory.entries
            .firstOrNull { it.mqttKey == category }
            ?.staleAfterMs
    }
}

internal fun retiredNormalizedFieldKeysToDelete(activeFieldKeys: Set<String>): Set<String> {
    return setOf("hv_battery_current_a").filterNot { it in activeFieldKeys }.toSet()
}

data class NormalizedWriteSummary(
    val observedCount: Int,
    val changedCount: Int,
    val historyInsertedCount: Int,
    val changedCategories: Set<String> = emptySet()
)
