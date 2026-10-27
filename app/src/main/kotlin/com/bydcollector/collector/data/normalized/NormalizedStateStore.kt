package com.bydcollector.collector.data.normalized

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

//stores latest semantic vehicle state plus change history for ha live state and influx time series
class NormalizedStateStore(
    private val helper: TelemetryDatabaseHelper,
    private val clock: Clock = SystemClockAdapter()
) {
    private data class CatalogIdentity(val normalizerId: String, val catalogVersion: String)

    private val compactV2 by lazy { helper.isCompactV2() }
    private val catalogCacheLock = Any()
    private var catalogCacheGeneration = 0L
    private var committedCatalogIdentities: Map<String, CatalogIdentity>? = null

    fun upsertCatalog(fields: List<NormalizedFieldDefinition> = NormalizedFieldCatalog.fields) {
        val cacheGeneration = synchronized(catalogCacheLock) {
            committedCatalogIdentities = null
            ++catalogCacheGeneration
        }
        val db = helper.writableDatabase
        val hadOuterTransaction = db.inTransaction()
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
        if (!hadOuterTransaction) {
            val committed = fields.associate { field ->
                field.fieldKey to CatalogIdentity(field.normalizerId, NormalizedFieldCatalog.CATALOG_VERSION)
            }
            synchronized(catalogCacheLock) {
                if (cacheGeneration == catalogCacheGeneration) {
                    committedCatalogIdentities = committed
                } else {
                    // An overlapping transaction may have committed after this one;
                    // leave the cache cold until a later uncontended catalog write.
                    committedCatalogIdentities = null
                    ++catalogCacheGeneration
                }
            }
        }
    }

    fun applyObservations(observations: List<NormalizedObservation>): NormalizedWriteSummary {
        if (observations.isEmpty()) return NormalizedWriteSummary(0, 0, 0)
        val db = helper.writableDatabase
        var result: NormalizedWriteSummary? = null
        db.beginTransaction()
        try {
            result = applyObservationsInTransaction(db, observations)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return checkNotNull(result)
    }

    internal fun applyObservationsInTransaction(
        db: SQLiteDatabase,
        observations: List<NormalizedObservation>
    ): NormalizedWriteSummary {
        check(db.inTransaction()) { "normalized callback writes require an active transaction" }
        if (observations.isEmpty()) return NormalizedWriteSummary(0, 0, 0)

        val currentByKey = loadCurrentStates(db, observations.map { it.field.fieldKey }.distinct())
        var changedCount = 0
        var historyInsertedCount = 0
        var currentInsertedCount = 0
        val changedCategories = mutableSetOf<String>()

        return db.compileStatement(CURRENT_UPDATE_SQL).use { updateCurrent ->
            db.compileStatement(CURRENT_INSERT_SQL).use { insertCurrent ->
                db.compileStatement(if (compactV2) COMPACT_HISTORY_INSERT_SQL else LEGACY_HISTORY_INSERT_SQL).use {
                    historyStatement ->
                    observations.forEach { observation ->
                        val next = observation.toStoredState()
                        val previous = currentByKey[next.fieldKey]
                        if (previous == null) currentInsertedCount += 1
                        //dedupes unchanged semantic values so history represents changes instead of every poll tick
                        val decision = NormalizedStateReducer.decide(previous, next)

                        if (decision.insertHistory) {
                            changedCount += 1
                            insertHistory(db, historyStatement, decision.current)
                            historyInsertedCount += 1
                            changedCategories += decision.current.category
                        }
                        if (previous == null) {
                            insertCurrent(insertCurrent, decision.current)
                        } else if (updateCurrent(updateCurrent, decision.current) == 0) {
                            //Retain the old update-then-insert fallback if a row vanishes during this transaction.
                            insertCurrent(insertCurrent, decision.current)
                        }
                        currentByKey[next.fieldKey] = decision.current
                    }
                    NormalizedWriteSummary(
                        observedCount = observations.size,
                        changedCount = changedCount,
                        historyInsertedCount = historyInsertedCount,
                        currentInsertedCount = currentInsertedCount,
                        changedCategories = changedCategories
                    )
                }
            }
        }
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
                quality, unit, source_poll_id, source_keys, observed_at, changed_at, quality_detail
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
        val continuity = sourceContinuityStatus()
        //marks stale fields when polling stops so ha can see old values as degraded instead of fresh
        val staleRows = currentState()
            .filter { it.quality == NormalizedQuality.OK.name }
            .filter { row ->
                val sourceKeys = row.sourceKeys.split(',').filter(String::isNotBlank)
                val sourceStates = sourceKeys.mapNotNull(continuity::get)
                if (sourceStates.any { it.lostCurrent }) return@filter true
                if (sourceStates.any { it.availableCurrent }) return@filter false
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

    private data class SourceContinuityStatus(val lostCurrent: Boolean, val availableCurrent: Boolean)

    private fun sourceContinuityStatus(): Map<String, SourceContinuityStatus> {
        val db = helper.readableDatabase
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", emptyArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        if ("business_input_source_continuity" !in tables || "normalized_source_inputs" !in tables) return emptyMap()
        val latestInputs = db.rawQuery(
            "SELECT source_key, source_identity, source_boot_id, source_elapsed_ms FROM normalized_source_inputs",
            emptyArray()
        ).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), Triple(cursor.getString(1), cursor.getString(2), cursor.getLong(3))) }
        }
        return db.rawQuery(
            "SELECT source_key, state, source_identity, source_boot_id, source_elapsed_ms, seed_identity, seed_valid " +
                "FROM business_input_source_continuity",
            emptyArray()
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val input = latestInputs[key]
                    val sameBoot = input != null && input.second == cursor.getString(3)
                    val lostCurrent = cursor.getString(1) == "lost" && sameBoot && cursor.getLong(4) >= input!!.third
                    val availableCurrent = cursor.getString(1) == "available" && cursor.getInt(6) == 1 &&
                        input?.first == cursor.getString(5)
                    put(key, SourceContinuityStatus(lostCurrent, availableCurrent))
                }
            }
        }
    }

    private fun upsertCatalogField(db: SQLiteDatabase, field: NormalizedFieldDefinition) {
        deleteIncompatibleCurrentRow(db, field)
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
            changedAt = observedAt,
            qualityDetail = reason
        )
    }

    private fun loadCurrentStates(
        db: SQLiteDatabase,
        fieldKeys: List<String>
    ): MutableMap<String, StoredNormalizedState> = buildMap {
        fieldKeys.chunked(CURRENT_STATE_SELECT_CHUNK_SIZE).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            db.rawQuery(
                "SELECT $CURRENT_STATE_COLUMNS FROM vehicle_state_current WHERE field_key IN ($placeholders)",
                chunk.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val state = cursor.toStoredNormalizedState()
                    put(state.fieldKey, state)
                }
            }
        }
    }.toMutableMap()

    private fun updateCurrent(statement: SQLiteStatement, state: StoredNormalizedState): Int {
        statement.clearBindings()
        statement.bindString(1, state.category)
        statement.bindString(2, state.valueType)
        statement.bindNullableString(3, state.valueText)
        statement.bindNullableDouble(4, state.valueNumber)
        statement.bindNullableBoolean(5, state.valueBool)
        statement.bindString(6, state.quality)
        statement.bindNullableString(7, state.qualityDetail)
        statement.bindNullableString(8, state.unit)
        statement.bindNullableLong(9, state.sourcePollId)
        statement.bindString(10, state.sourceKeys)
        statement.bindString(11, state.observedAt)
        statement.bindString(12, state.changedAt)
        statement.bindString(13, clock.nowIso())
        statement.bindString(14, state.fieldKey)
        return statement.executeUpdateDelete()
    }

    private fun insertCurrent(statement: SQLiteStatement, state: StoredNormalizedState) {
        statement.clearBindings()
        statement.bindString(1, state.fieldKey)
        statement.bindString(2, state.category)
        statement.bindString(3, state.valueType)
        statement.bindNullableString(4, state.valueText)
        statement.bindNullableDouble(5, state.valueNumber)
        statement.bindNullableBoolean(6, state.valueBool)
        statement.bindString(7, state.quality)
        statement.bindNullableString(8, state.qualityDetail)
        statement.bindNullableString(9, state.unit)
        statement.bindNullableLong(10, state.sourcePollId)
        statement.bindString(11, state.sourceKeys)
        statement.bindString(12, state.observedAt)
        statement.bindString(13, state.changedAt)
        statement.bindString(14, clock.nowIso())
        check(statement.executeInsert() != -1L) { "normalized current insert returned no row ID: ${state.fieldKey}" }
    }

    private fun insertHistory(db: SQLiteDatabase, statement: SQLiteStatement, state: StoredNormalizedState) {
        statement.clearBindings()
        if (compactV2) {
            statement.bindLong(1, state.compactHistoryFieldId(db))
            statement.bindNullableString(2, state.valueText)
            statement.bindNullableDouble(3, state.valueNumber)
            statement.bindNullableBoolean(4, state.valueBool)
            statement.bindLong(5, NormalizedQuality.valueOf(state.quality).storageCode.toLong())
            statement.bindNullableString(6, state.qualityDetail)
            statement.bindNullableLong(7, state.sourcePollId)
            statement.bindLong(8, normalizedEpochMillis(state.observedAt))
            statement.bindLong(9, normalizedEpochMillis(state.changedAt))
        } else {
            statement.bindString(1, state.fieldKey)
            statement.bindString(2, state.category)
            statement.bindString(3, state.valueType)
            statement.bindNullableString(4, state.valueText)
            statement.bindNullableDouble(5, state.valueNumber)
            statement.bindNullableBoolean(6, state.valueBool)
            statement.bindString(7, state.quality)
            statement.bindNullableString(8, state.qualityDetail)
            statement.bindNullableString(9, state.unit)
            statement.bindNullableLong(10, state.sourcePollId)
            statement.bindString(11, state.sourceKeys)
            statement.bindString(12, state.observedAt)
            statement.bindString(13, state.changedAt)
        }
        check(statement.executeInsert() != -1L) { "normalized history insert returned no row ID: ${state.fieldKey}" }
    }

    private fun SQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun SQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
        if (value == null) bindNull(index) else bindDouble(index, value)
    }

    private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun SQLiteStatement.bindNullableBoolean(index: Int, value: Boolean?) {
        when (value) {
            null -> bindNull(index)
            true -> bindLong(index, 1L)
            false -> bindLong(index, 0L)
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
        val values = if (compactV2) state.toCompactHistoryValues(db) else state.toContentValues()
        db.insertOrThrow("vehicle_state_history", null, values)
    }

    private fun StoredNormalizedState.toCompactHistoryValues(db: SQLiteDatabase): ContentValues {
        return ContentValues().apply {
            put("field_id", compactHistoryFieldId(db))
            put("value_text", valueText)
            put("value_number", valueNumber)
            when (valueBool) {
                null -> putNull("value_bool")
                true -> put("value_bool", 1)
                false -> put("value_bool", 0)
            }
            put("quality_code", NormalizedQuality.valueOf(quality).storageCode)
            put("quality_detail", qualityDetail)
            if (sourcePollId == null) putNull("source_poll_id") else put("source_poll_id", sourcePollId)
            put("observed_at_ms", normalizedEpochMillis(observedAt))
            put("changed_at_ms", normalizedEpochMillis(changedAt))
        }
    }

    private fun deleteIncompatibleCurrentRow(db: SQLiteDatabase, field: NormalizedFieldDefinition) {
        db.rawQuery(
            "SELECT value_type, unit FROM vehicle_state_current WHERE field_key = ? LIMIT 1",
            arrayOf(field.fieldKey)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val storedType = cursor.getString(0)
            val storedUnit = cursor.getStringOrNull(1)
            if (storedType != field.valueType.name || storedUnit != field.unit) {
                //drops only the incompatible live row; immutable history and catalog metadata stay intact
                db.delete("vehicle_state_current", "field_key = ?", arrayOf(field.fieldKey))
            }
        }
    }

    private fun StoredNormalizedState.compactHistoryFieldId(db: SQLiteDatabase): Long {
        val unitValue = unit.orEmpty()
        val catalogIdentity = synchronized(catalogCacheLock) {
            committedCatalogIdentities?.get(fieldKey)
        } ?: db.rawQuery(
            "SELECT normalizer_id, catalog_version FROM normalized_field_catalog WHERE field_key = ? LIMIT 1",
            arrayOf(fieldKey)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing normalized field catalog row: $fieldKey" }
            CatalogIdentity(cursor.getString(0), cursor.getString(1))
        }
        val args = arrayOf(fieldKey, category, valueType, unitValue, sourceKeys, catalogIdentity.normalizerId, catalogIdentity.catalogVersion)
        db.rawQuery(
            """
            SELECT id
            FROM normalized_history_field_catalog
            WHERE field_key = ? AND category = ? AND value_type = ? AND unit = ? AND source_keys = ?
              AND normalizer_id = ? AND catalog_version = ?
            LIMIT 1
            """.trimIndent(),
            args
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return db.insertOrThrow(
            "normalized_history_field_catalog",
            null,
            ContentValues().apply {
                put("field_key", fieldKey)
                put("category", category)
                put("value_type", valueType)
                put("unit", unitValue)
                put("source_keys", sourceKeys)
                put("normalizer_id", catalogIdentity.normalizerId)
                put("catalog_version", catalogIdentity.catalogVersion)
            }
        )
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
            put("quality_detail", qualityDetail)
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
            changedAt = getString(11),
            qualityDetail = getStringOrNull(12)
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

    private companion object {
        // Stay below SQLite's bind-variable cap while keeping the number of SELECTs bounded.
        const val CURRENT_STATE_SELECT_CHUNK_SIZE = 400
        const val CURRENT_STATE_COLUMNS =
            "field_key, category, value_type, value_text, value_number, value_bool, " +
                "quality, unit, source_poll_id, source_keys, observed_at, changed_at, quality_detail"
        const val CURRENT_UPDATE_SQL = """
            UPDATE vehicle_state_current SET category = ?, value_type = ?, value_text = ?, value_number = ?,
                value_bool = ?, quality = ?, quality_detail = ?, unit = ?, source_poll_id = ?, source_keys = ?,
                observed_at = ?, changed_at = ?, updated_at = ? WHERE field_key = ?
        """
        const val CURRENT_INSERT_SQL = """
            INSERT INTO vehicle_state_current(
                field_key, category, value_type, value_text, value_number, value_bool, quality,
                quality_detail, unit, source_poll_id, source_keys, observed_at, changed_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val LEGACY_HISTORY_INSERT_SQL = """
            INSERT INTO vehicle_state_history(
                field_key, category, value_type, value_text, value_number, value_bool, quality,
                quality_detail, unit, source_poll_id, source_keys, observed_at, changed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val COMPACT_HISTORY_INSERT_SQL = """
            INSERT INTO vehicle_state_history(
                field_id, value_text, value_number, value_bool, quality_code, quality_detail,
                source_poll_id, observed_at_ms, changed_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}

internal fun normalizedEpochMillis(value: String): Long =
    OffsetDateTime.parse(value).toInstant().toEpochMilli()

internal fun normalizedIsoTime(value: Long): String = Instant.ofEpochMilli(value).toString()

internal fun retiredNormalizedFieldKeysToDelete(activeFieldKeys: Set<String>): Set<String> {
    return setOf("hv_battery_current_a", "charging_state").filterNot { it in activeFieldKeys }.toSet()
}

data class NormalizedWriteSummary(
    val observedCount: Int,
    val changedCount: Int,
    val historyInsertedCount: Int,
    val currentInsertedCount: Int = 0,
    val changedCategories: Set<String> = emptySet()
)
