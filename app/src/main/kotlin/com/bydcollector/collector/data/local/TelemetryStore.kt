package com.bydcollector.collector.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedStateStore
import com.bydcollector.collector.data.normalized.NormalizedWriteSummary
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import com.bydcollector.collector.data.polling.PollStorage
import com.bydcollector.collector.mqtt.HaMqttMessage
import com.bydcollector.collector.influx.InfluxCursor
import com.bydcollector.collector.influx.InfluxExportStateSnapshot
import com.bydcollector.collector.influx.InfluxExportStore
import com.bydcollector.collector.influx.InfluxPendingHistoryRow
import com.bydcollector.collector.influx.InfluxPendingSummary
import com.bydcollector.collector.mqtt.MqttOutboxStore
import com.bydcollector.collector.mqtt.MqttRetryState
import com.bydcollector.collector.mqtt.MqttRetryStateStore
import com.bydcollector.collector.mqtt.PendingMqttMessage
import com.bydcollector.collector.mqtt.MqttPublishStateRecorder
import com.bydcollector.collector.mqtt.NormalizedStateProvider
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Locale

//central sqlite facade that keeps raw polls, normalized state, mqtt outbox, and influx cursors consistent
class TelemetryStore(
    private val context: Context,
    private val helper: TelemetryDatabaseHelper,
    private val clock: Clock = SystemClockAdapter(),
    private val eventRetention: Int = 200
) : PollStorage,
    NormalizedStateProvider,
    MqttPublishStateRecorder,
    MqttOutboxStore,
    MqttRetryStateStore,
    InfluxExportStore,
    Closeable {
    private val directImporter = DirectCatalogImporter(helper)
    private val ecImporter = EcDatabaseImporter(context, helper, clock)
    private val normalizedStore = NormalizedStateStore(helper, clock)
    @Volatile private var pollValueColumnsEnsuredForCatalogVersionId: Long? = null

    override fun close() {
        helper.close()
    }

    fun ensureCatalogImported(): Long {
        val catalogVersionId = directImporter.ensureImported()
        //adds wide poll columns for the active catalog before any poll attempts to write values
        ensurePollValueColumns(helper.writableDatabase, catalogParameters(catalogVersionId))
        return catalogVersionId
    }

    override fun getActiveCatalogParameters(): List<CatalogParameter> {
        val catalogVersionId = ensureCatalogImported()
        return catalogParameters(catalogVersionId)
    }

    fun ensureNormalizedCatalogImported() {
        normalizedStore.upsertCatalog()
    }

    fun applyNormalizedObservations(observations: List<NormalizedObservation>): NormalizedWriteSummary {
        ensureNormalizedCatalogImported()
        return normalizedStore.applyObservations(observations)
    }

    fun normalizedCurrentState(categories: Set<String>? = null): List<StoredNormalizedState> {
        ensureNormalizedCatalogImported()
        return normalizedStore.currentState(categories)
    }

    override fun currentState(categories: Set<String>?): List<StoredNormalizedState> = normalizedCurrentState(categories)

    private fun catalogParameters(catalogVersionId: Long): List<CatalogParameter> {
        helper.readableDatabase.rawQuery(
            """
            SELECT id, catalog_version_id, source_id, key, name, group_name, include_desc, note
            FROM parameter_catalog
            WHERE catalog_version_id = ?
            ORDER BY id
            """.trimIndent(),
            arrayOf(catalogVersionId.toString())
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toCatalogParameter())
            }
        }
    }

    fun openSession(
        sessionType: String = "service_run",
        vehicleModel: String = "BYD Sea Lion 07 EV"
    ): Long {
        val catalogVersionId = ensureCatalogImported()
        //closes stale sessions because android can kill the process before onDestroy/endSession runs
        closeOpenSessions("implicit_end")
        val sessionId = helper.writableDatabase.insertOrThrow(
            "collection_sessions",
            null,
            ContentValues().apply {
                put("catalog_version_id", catalogVersionId)
                put("session_type", sessionType)
                put("started_at", clock.nowIso())
                put("vehicle_model", vehicleModel)
                put("import_quality", "production")
            }
        )
        recordEvent("service_start", "Collection service started", "session_id=$sessionId")
        return sessionId
    }

    fun closeOpenSessions(reason: String = "implicit_end"): Int {
        val now = clock.nowIso()
        val updated = helper.writableDatabase.update(
            "collection_sessions",
            ContentValues().apply {
                put("ended_at", now)
                put("import_quality", reason)
            },
            "ended_at IS NULL",
            emptyArray()
        )
        if (updated > 0) {
            recordEvent(
                "session_recovered",
                "Closed stale open collection sessions",
                "count=$updated reason=$reason"
            )
        }
        return updated
    }

    fun importEcDatabaseAtSessionStart(sessionId: Long): EcImportResult {
        val result = ecImporter.importAtSessionStart(sessionId)
        if (result.ok) {
            recordEvent(
                "ec_import",
                "EC_database.db import completed",
                "session_id=$sessionId rows=${result.sourceRowCount} inserted=${result.insertedCount} updated=${result.updatedCount}"
            )
        } else {
            recordEvent(
                "ec_import_error",
                "EC_database.db import skipped",
                "session_id=$sessionId category=${result.errorCategory} message=${result.errorMessage}"
            )
        }
        return result
    }

    fun endSession(sessionId: Long, reason: String = "stopped") {
        helper.writableDatabase.update(
            "collection_sessions",
            ContentValues().apply {
                put("ended_at", clock.nowIso())
                put("import_quality", reason)
            },
            "id = ?",
            arrayOf(sessionId.toString())
        )
        recordEvent("service_stop", "Collection service stopped", "session_id=$sessionId reason=$reason")
    }

    override fun insertPoll(
        sessionId: Long,
        input: PersistedPollInput,
        parameters: List<CatalogParameter>
    ): Long {
        val db = helper.writableDatabase
        ensurePollValueColumns(db, parameters)
        //writes the poll header and wide raw values atomically so dashboard counts never see half a poll
        db.beginTransaction()
        try {
            val requestedParameterCount = parameters.size
            val receivedParameterCount = if (input.ok) {
                input.readings.map { it.rawKey }.distinct().size
            } else {
                0
            }
            val pollId = db.insertOrThrow(
                "polls",
                null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("ts", input.timestamp)
                    put("ok", if (input.ok) 1 else 0)
                    input.elapsedMs?.let { put("elapsed_ms", it) }
                    put("request_count", input.requestCount)
                    put("errors", input.errors.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                    put("error_category", input.errorCategory)
                    put("error_message", input.errorMessage.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                    put("requested_parameter_count", requestedParameterCount)
                    put("received_parameter_count", receivedParameterCount)
                    put("missing_parameter_count", (requestedParameterCount - receivedParameterCount).coerceAtLeast(0))
                    if (input.ok) {
                        putNull("raw_response_body")
                    } else {
                        put("raw_response_body", input.rawResponseBody.truncateForStorage(MAX_RAW_RESPONSE_BODY_LENGTH))
                    }
                    put(
                        "import_quality",
                        when {
                            input.ok && input.errorCategory != null -> "partial_success"
                            input.ok -> "full_success"
                            else -> "failure_recorded"
                        }
                    )
                }
            )

            if (input.ok) {
                insertPollValues(db, pollId, input.readings, parameters)
            }

            db.setTransactionSuccessful()
            return pollId
        } finally {
            db.endTransaction()
        }
    }

    fun recordEvent(category: String, message: String) {
        recordEvent(category, message, null)
    }

    override fun recordEvent(category: String, message: String, detail: String?) {
        val logLine = buildString {
            append(category).append(": ").append(message)
            detail?.takeIf { it.isNotBlank() }?.let { append(" detail=").append(it) }
        }
        if (category.contains("error", ignoreCase = true)
            || category.contains("failed", ignoreCase = true)
            || category.contains("timeout", ignoreCase = true)
            || category.contains("unavailable", ignoreCase = true)
        ) {
            Log.w("BYDCollectorEvent", logLine)
        } else {
            Log.i("BYDCollectorEvent", logLine)
        }
        try {
            val db = helper.writableDatabase
            //stores only recent operational events so diagnostics stay useful without growing unbounded
            db.insert(
                "collector_events",
                null,
                ContentValues().apply {
                    put("ts", clock.nowIso())
                    put("category", category)
                    put("message", message)
                    put("detail", detail)
                }
            )
            db.execSQL(
                "DELETE FROM collector_events WHERE id NOT IN (SELECT id FROM collector_events ORDER BY id DESC LIMIT $eventRetention)"
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "collector event write failed: $logLine", error)
        }
    }

    override fun recordMqttPublishSuccess(
        targetKey: String,
        targetType: String,
        payloadHash: String,
        publishedAt: String
    ) {
        upsertMqttPublishState(
            targetKey = targetKey,
            targetType = targetType,
            values = ContentValues().apply {
                put("payload_hash", payloadHash)
                put("last_published_at", publishedAt)
                putNull("last_error_at")
                putNull("last_error")
            }
        )
    }

    override fun recordMqttPublishError(
        targetKey: String,
        targetType: String,
        error: String,
        errorAt: String
    ) {
        upsertMqttPublishState(
            targetKey = targetKey,
            targetType = targetType,
            values = ContentValues().apply {
                put("last_error_at", errorAt)
                put("last_error", error.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
            }
        )
    }

    override fun upsertPending(message: HaMqttMessage, targetType: String, priority: Int) {
        val now = clock.nowIso()
        //keeps only the latest retained payload per topic because ha needs current state more than old retries
        val values = ContentValues().apply {
            put("target_type", targetType)
            put("topic", message.topic)
            put("payload", message.payload)
            put("payload_hash", sha256(message.payload))
            put("retained", if (message.retained) 1 else 0)
            put("qos", message.qos)
            put("priority", priority)
            putNull("next_attempt_at")
            put("attempt_count", 0)
            putNull("last_error")
            putNull("last_error_at")
            putNull("last_attempt_at")
            put("updated_at", now)
        }
        val db = helper.writableDatabase
        val updated = db.update(
            "mqtt_outbox",
            values,
            "target_key = ?",
            arrayOf(message.topic)
        )
        if (updated > 0) return

        values.put("target_key", message.topic)
        values.put("created_at", now)
        values.put("attempt_count", 0)
        val inserted = db.insertWithOnConflict(
            "mqtt_outbox",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted != -1L) return

        values.remove("created_at")
        db.update(
            "mqtt_outbox",
            values,
            "target_key = ?",
            arrayOf(message.topic)
        )
    }

    override fun dueMessages(nowIso: String, limit: Int): List<PendingMqttMessage> {
        if (limit <= 0) return emptyList()
        helper.readableDatabase.rawQuery(
            """
            SELECT target_key, target_type, topic, payload, payload_hash, retained, qos, priority, attempt_count
            FROM mqtt_outbox
            WHERE next_attempt_at IS NULL OR next_attempt_at <= ?
            ORDER BY priority, updated_at
            LIMIT ?
            """.trimIndent(),
            arrayOf(nowIso, limit.toString())
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingMqttMessage(
                            targetKey = cursor.getString(0),
                            targetType = cursor.getString(1),
                            payloadHash = cursor.getString(4),
                            message = HaMqttMessage(
                                topic = cursor.getString(2),
                                payload = cursor.getString(3),
                                retained = cursor.getInt(5) == 1,
                                qos = cursor.getInt(6)
                            ),
                            priority = cursor.getInt(7),
                            attemptCount = cursor.getInt(8)
                        )
                    )
                }
            }
        }
    }

    override fun markAttempt(targetKey: String, payloadHash: String, attemptedAt: String) {
        helper.writableDatabase.update(
            "mqtt_outbox",
            ContentValues().apply {
                put("last_attempt_at", attemptedAt)
                put("updated_at", attemptedAt)
            },
            "target_key = ? AND payload_hash = ?",
            arrayOf(targetKey, payloadHash)
        )
    }

    override fun markFailed(
        targetKey: String,
        payloadHash: String,
        error: String,
        failedAt: String,
        nextAttemptAt: String?
    ) {
        val targetType = mqttOutboxTargetType(targetKey, payloadHash) ?: targetTypeForTopic(targetKey)
        val attemptCount = (mqttOutboxAttemptCount(targetKey, payloadHash) ?: 0) + 1
        helper.writableDatabase.update(
            "mqtt_outbox",
            ContentValues().apply {
                put("attempt_count", attemptCount)
                put("last_error", error.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                put("last_error_at", failedAt)
                put("last_attempt_at", failedAt)
                put("updated_at", failedAt)
                if (nextAttemptAt == null) putNull("next_attempt_at") else put("next_attempt_at", nextAttemptAt)
            },
            "target_key = ? AND payload_hash = ?",
            arrayOf(targetKey, payloadHash)
        )
        recordMqttPublishError(
            targetKey = targetKey,
            targetType = targetType,
            error = error,
            errorAt = failedAt
        )
    }

    override fun markPublished(targetKey: String, payloadHash: String, publishedAt: String) {
        val targetType = mqttOutboxTargetType(targetKey, payloadHash) ?: targetTypeForTopic(targetKey)
        helper.writableDatabase.delete(
            "mqtt_outbox",
            "target_key = ? AND payload_hash = ?",
            arrayOf(targetKey, payloadHash)
        )
        recordMqttPublishSuccess(
            targetKey = targetKey,
            targetType = targetType,
            payloadHash = payloadHash,
            publishedAt = publishedAt
        )
    }

    override fun pendingCount(): Long = safeScalarLong("SELECT COUNT(*) FROM mqtt_outbox")

    override fun ensureInfluxCursors(fieldKeys: Set<String>) {
        if (fieldKeys.isEmpty()) return
        val db = helper.writableDatabase
        //creates one cursor per normalized field so enabling categories later does not reset old exports
        fieldKeys.forEach { fieldKey ->
            db.insertWithOnConflict(
                "influx_export_cursor",
                null,
                ContentValues().apply {
                    put("field_key", fieldKey)
                    put("last_exported_history_id", 0)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    override fun pendingInfluxSummary(fieldKeys: Set<String>): InfluxPendingSummary {
        if (fieldKeys.isEmpty()) return InfluxPendingSummary(rows = 0, oldestObservedAt = null)
        ensureInfluxCursors(fieldKeys)
        val placeholders = fieldKeys.joinToString(",") { "?" }
        helper.readableDatabase.rawQuery(
            """
            SELECT COUNT(*), MIN(history.observed_at)
            FROM vehicle_state_history history
            JOIN influx_export_cursor cursor ON cursor.field_key = history.field_key
            WHERE history.field_key IN ($placeholders)
              AND history.id > cursor.last_exported_history_id
            """.trimIndent(),
            fieldKeys.toTypedArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) return InfluxPendingSummary(rows = 0, oldestObservedAt = null)
            return InfluxPendingSummary(
                rows = cursor.getLong(0),
                oldestObservedAt = cursor.getNullableString(1)
            )
        }
    }

    override fun influxCursors(fieldKeys: Set<String>): List<InfluxCursor> {
        if (fieldKeys.isEmpty()) return emptyList()
        ensureInfluxCursors(fieldKeys)
        val placeholders = fieldKeys.joinToString(",") { "?" }
        helper.readableDatabase.rawQuery(
            """
            SELECT field_key, last_exported_history_id
            FROM influx_export_cursor
            WHERE field_key IN ($placeholders)
            ORDER BY field_key
            """.trimIndent(),
            fieldKeys.toTypedArray()
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        InfluxCursor(
                            fieldKey = cursor.getString(0),
                            lastExportedHistoryId = cursor.getLong(1)
                        )
                    )
                }
            }
        }
    }

    override fun pendingInfluxRows(
        fieldKey: String,
        afterHistoryId: Long,
        limit: Int
    ): List<InfluxPendingHistoryRow> {
        if (limit <= 0) return emptyList()
        helper.readableDatabase.rawQuery(
            """
            SELECT id, field_key, category, value_type, value_text, value_number, value_bool,
                   quality, unit, source_poll_id, source_keys, observed_at, changed_at
            FROM vehicle_state_history
            WHERE field_key = ? AND id > ?
            ORDER BY id
            LIMIT ?
            """.trimIndent(),
            arrayOf(fieldKey, afterHistoryId.toString(), limit.toString())
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        InfluxPendingHistoryRow(
                            id = cursor.getLong(0),
                            fieldKey = cursor.getString(1),
                            category = cursor.getString(2),
                            valueType = cursor.getString(3),
                            valueText = cursor.getNullableString(4),
                            valueNumber = if (cursor.isNull(5)) null else cursor.getDouble(5),
                            valueBool = if (cursor.isNull(6)) null else cursor.getInt(6) == 1,
                            quality = cursor.getString(7),
                            unit = cursor.getNullableString(8),
                            sourcePollId = if (cursor.isNull(9)) null else cursor.getLong(9),
                            sourceKeys = cursor.getString(10),
                            observedAt = cursor.getString(11),
                            changedAt = cursor.getString(12)
                        )
                    )
                }
            }
        }
    }

    override fun updateInfluxCursorSuccess(fieldKey: String, historyId: Long, exportedAt: String) {
        ensureInfluxCursors(setOf(fieldKey))
        helper.writableDatabase.update(
            "influx_export_cursor",
            ContentValues().apply {
                put("last_exported_history_id", historyId)
                put("last_success_at", exportedAt)
                putNull("last_error_at")
                putNull("last_error")
            },
            "field_key = ?",
            arrayOf(fieldKey)
        )
    }

    override fun updateInfluxCursorError(fieldKey: String, error: String, errorAt: String) {
        ensureInfluxCursors(setOf(fieldKey))
        helper.writableDatabase.update(
            "influx_export_cursor",
            ContentValues().apply {
                put("last_error_at", errorAt)
                put("last_error", error.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
            },
            "field_key = ?",
            arrayOf(fieldKey)
        )
    }

    override fun influxExportState(): InfluxExportStateSnapshot {
        helper.readableDatabase.rawQuery(
            """
            SELECT status, mode, pending_rows, oldest_pending_at, next_retry_at,
                   last_success_at, last_error_at, last_error, exported_rows_total
            FROM influx_export_state
            WHERE id = 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return InfluxExportStateSnapshot(
                    status = "stopped",
                    mode = null,
                    pendingRows = 0,
                    oldestPendingAt = null,
                    nextRetryAt = null,
                    lastSuccessAt = null,
                    lastErrorAt = null,
                    lastError = null,
                    exportedRowsTotal = 0
                )
            }
            return InfluxExportStateSnapshot(
                status = cursor.getString(0),
                mode = cursor.getNullableString(1),
                pendingRows = cursor.getLong(2),
                oldestPendingAt = cursor.getNullableString(3),
                nextRetryAt = cursor.getNullableString(4),
                lastSuccessAt = cursor.getNullableString(5),
                lastErrorAt = cursor.getNullableString(6),
                lastError = cursor.getNullableString(7),
                exportedRowsTotal = cursor.getLong(8)
            )
        }
    }

    override fun updateInfluxExportState(
        status: String,
        mode: String?,
        pendingRows: Long,
        oldestPendingAt: String?,
        nextRetryAt: String?,
        lastSuccessAt: String?,
        lastErrorAt: String?,
        lastError: String?,
        exportedRowsDelta: Long
    ) {
        val currentTotal = influxExportState().exportedRowsTotal
        val now = clock.nowIso()
        val values = ContentValues().apply {
            put("status", status)
            if (mode == null) putNull("mode") else put("mode", mode)
            put("pending_rows", pendingRows)
            if (oldestPendingAt == null) putNull("oldest_pending_at") else put("oldest_pending_at", oldestPendingAt)
            if (nextRetryAt == null) putNull("next_retry_at") else put("next_retry_at", nextRetryAt)
            if (lastSuccessAt == null) putNull("last_success_at") else put("last_success_at", lastSuccessAt)
            if (lastErrorAt == null) putNull("last_error_at") else put("last_error_at", lastErrorAt)
            if (lastError == null) putNull("last_error") else put("last_error", lastError.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
            put("exported_rows_total", currentTotal + exportedRowsDelta)
            put("updated_at", now)
        }
        val updated = helper.writableDatabase.update("influx_export_state", values, "id = 1", emptyArray())
        if (updated > 0) return
        values.put("id", 1)
        helper.writableDatabase.insertWithOnConflict(
            "influx_export_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun recordInfluxEvent(
        eventType: String,
        message: String?,
        batchCount: Int?,
        fromHistoryId: Long?,
        toHistoryId: Long?
    ) {
        helper.writableDatabase.insert(
            "influx_export_events",
            null,
            ContentValues().apply {
                put("ts", clock.nowIso())
                put("event_type", eventType)
                put("message", message?.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                batchCount?.let { put("batch_count", it) }
                fromHistoryId?.let { put("from_history_id", it) }
                toHistoryId?.let { put("to_history_id", it) }
            }
        )
    }

    override fun retryState(): MqttRetryState {
        helper.readableDatabase.rawQuery(
            """
            SELECT failure_count, next_attempt_at, last_failure_at, last_success_at, last_error
            FROM mqtt_retry_state
            WHERE id = 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return MqttRetryState(
                    failureCount = 0,
                    nextAttemptAt = null,
                    lastFailureAt = null,
                    lastSuccessAt = null,
                    lastError = null
                )
            }
            return MqttRetryState(
                failureCount = cursor.getInt(0),
                nextAttemptAt = cursor.getNullableString(1),
                lastFailureAt = cursor.getNullableString(2),
                lastSuccessAt = cursor.getNullableString(3),
                lastError = cursor.getNullableString(4)
            )
        }
    }

    override fun recordRetryFailure(error: String, failedAt: String, nextAttemptAt: String) {
        val failureCount = retryState().failureCount + 1
        upsertRetryState(
            ContentValues().apply {
                put("failure_count", failureCount)
                put("next_attempt_at", nextAttemptAt)
                put("last_failure_at", failedAt)
                put("last_error", error.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                put("updated_at", failedAt)
            }
        )
    }

    override fun recordRetrySuccess(successAt: String) {
        upsertRetryState(
            ContentValues().apply {
                put("failure_count", 0)
                putNull("next_attempt_at")
                put("last_success_at", successAt)
                putNull("last_error")
                put("updated_at", successAt)
            }
        )
    }

    fun recentEvents(limit: Int = eventRetention): List<CollectorEvent> {
        helper.readableDatabase.rawQuery(
            """
            SELECT id, ts, category, message, detail
            FROM collector_events
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        CollectorEvent(
                            id = cursor.getLong(0),
                            timestamp = cursor.getString(1),
                            category = cursor.getString(2),
                            message = cursor.getString(3),
                            detail = cursor.getString(4)
                        )
                    )
                }
            }
        }
    }

    fun healthSnapshot(running: Boolean): HealthSnapshot {
        val mqttRetryState = safeMqttRetryState()
        val activeSessionId = if (running) safeActiveSessionId() else null
        val activePollScope = ActivePollSessionScope.from(running = running, activeSessionId = activeSessionId)
        //uses safe queries because the dashboard must keep rendering through migrations and partial failures
        return HealthSnapshot(
            running = running,
            activeSessionId = activeSessionId,
            lastSuccessAt = activePollScope.lastSuccessAtSql()?.let { safeScalarString(it) },
            lastError = activePollScope.lastErrorSql()?.let { safeScalarString(it) },
            lastErrorAt = activePollScope.lastErrorAtSql()?.let { safeScalarString(it) },
            lastPollStatus = activePollScope.lastPollStatusSql()?.let { safeLastPollStatus(it) },
            pollCount = safeScalarLong("SELECT COUNT(*) FROM polls"),
            valueRowCount = safeScalarLong("SELECT COUNT(*) FROM poll_values"),
            ecRowCount = safeScalarLong("SELECT COUNT(*) FROM ec_energy_consumption"),
            normalizedCurrentCount = safeScalarLong("SELECT COUNT(*) FROM vehicle_state_current"),
            normalizedHistoryCount = safeScalarLong("SELECT COUNT(*) FROM vehicle_state_history"),
            mqttLastError = safeScalarString("SELECT last_error FROM mqtt_publish_state WHERE last_error IS NOT NULL ORDER BY last_error_at DESC, updated_at DESC, id DESC LIMIT 1"),
            mqttLastPublishedAt = safeScalarString("SELECT last_published_at FROM mqtt_publish_state WHERE last_published_at IS NOT NULL ORDER BY last_published_at DESC, id DESC LIMIT 1"),
            mqttPendingCount = safeMqttPendingCount(),
            mqttRetryFailureCount = mqttRetryState.failureCount,
            mqttNextRetryAt = mqttRetryState.nextAttemptAt,
            mqttRetryLastFailureAt = mqttRetryState.lastFailureAt,
            mqttRetryLastSuccessAt = mqttRetryState.lastSuccessAt,
            lastEcImport = safeScalarString("SELECT ts FROM ec_import_runs ORDER BY id DESC LIMIT 1"),
            lastEcImportStatus = safeLastEcImportStatus(),
            elapsedMs = activePollScope.elapsedMsSql()?.let { safeScalarLongOrNull(it) },
            requestCount = activePollScope.requestCountSql()?.let { safeScalarLongOrNull(it) }?.toInt(),
            databasePath = databaseFile().absolutePath,
            databaseSizeBytes = databaseFile().length(),
            latestSoc = safeLatestReading(listOf("statistic_1014_1145045040_5", "statistic_1014_1134559272_5", "SOC", "soc")),
            latestSpeed = safeLatestReading(listOf("speed_1013_-1807745016_7", "Speed", "speed")),
            latestCharging = safeLatestReading(listOf("charging_charge_current", "ChargingStatus", "chargeGunState")),
            recentEvents = safeRecentEvents()
        )
    }

    private fun safeMqttPendingCount(): Long = runCatching {
        pendingCount()
    }.onFailure { error ->
        Log.w(TAG, "mqtt pending count query failed", error)
    }.getOrDefault(0L)

    private fun safeMqttRetryState(): MqttRetryState = runCatching {
        retryState()
    }.onFailure { error ->
        Log.w(TAG, "mqtt retry state query failed", error)
    }.getOrDefault(
        MqttRetryState(
            failureCount = 0,
            nextAttemptAt = null,
            lastFailureAt = null,
            lastSuccessAt = null,
            lastError = null
        )
    )

    fun databaseFile(): File = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)

    private fun activeSessionId(): Long? = scalarLongOrNull(
        "SELECT id FROM collection_sessions WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1"
    )

    private fun safeActiveSessionId(): Long? = runCatching {
        activeSessionId()
    }.onFailure { error ->
        Log.w(TAG, "active session query failed", error)
    }.getOrNull()

    private fun safeLatestReading(keys: List<String>): String? = runCatching {
        latestReading(keys)
    }.onFailure { error ->
        Log.w(TAG, "latest reading query failed for $keys", error)
    }.getOrNull()

    private fun safeRecentEvents(): List<CollectorEvent> = runCatching {
        recentEvents()
    }.onFailure { error ->
        Log.w(TAG, "recent events query failed", error)
    }.getOrDefault(emptyList())

    private fun safeLastPollStatus(sql: String): String? = runCatching {
        lastPollStatus(sql)
    }.onFailure { error ->
        Log.w(TAG, "last poll status query failed", error)
    }.getOrNull()

    private fun lastPollStatus(sql: String): String? {
        helper.readableDatabase.rawQuery(
            sql,
            emptyArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val ts = cursor.getString(0)
            return if (cursor.getInt(1) == 1) {
                "ok at $ts"
            } else {
                val category = cursor.getString(2)
                val message = cursor.getString(3)
                "Polling error: ${pollingErrorSummary(category, message)} at $ts"
            }
        }
    }

    private fun safeLastEcImportStatus(): String? = runCatching {
        lastEcImportStatus()
    }.onFailure { error ->
        Log.w(TAG, "last EC import status query failed", error)
    }.getOrNull()

    private fun lastEcImportStatus(): String? {
        helper.readableDatabase.rawQuery(
            """
            SELECT ts, ok, source_row_count, inserted_count, updated_count, error_category, error_message FROM ec_import_runs
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val timestamp = cursor.getString(0)
            val ok = cursor.getInt(1) == 1
            val sourceRows = cursor.getLong(2)
            val inserted = cursor.getLong(3)
            val updated = cursor.getLong(4)
            val errorCategory = cursor.getString(5)
            val errorMessage = cursor.getString(6)
            val status = if (ok) "ok" else "error=${errorCategory ?: "unknown"}"
            val message = errorMessage?.takeIf { it.isNotBlank() }?.let { " message=$it" }.orEmpty()
            return "$timestamp $status rows=$sourceRows inserted=$inserted updated=$updated$message"
        }
    }

    private fun latestReading(keys: List<String>): String? {
        val columns = pollValueColumnNames(helper.readableDatabase)
        keys.forEach { key ->
            val rawColumn = PollValueColumns.raw(key)
            if (!columns.contains(rawColumn)) return@forEach
            val columnName = quoteIdentifier(rawColumn)
            helper.readableDatabase.rawQuery(
                """
                SELECT $columnName
                FROM poll_values
                JOIN polls ON polls.id = poll_values.poll_id
                WHERE $columnName IS NOT NULL
                ORDER BY poll_values.poll_id DESC
                LIMIT 1
                """.trimIndent(),
                emptyArray()
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        }
        return null
    }

    private fun ensurePollValueColumns(db: SQLiteDatabase, parameters: List<CatalogParameter>) {
        val catalogVersionId = parameters.firstOrNull()?.catalogVersionId ?: return
        if (pollValueColumnsEnsuredForCatalogVersionId == catalogVersionId) return

        //keeps raw and desc values queryable by stable column names instead of burying them in json blobs
        val existingColumns = pollValueColumnNames(db)
            .map { it.lowercase(Locale.US) }
            .toMutableSet()
        parameters.forEach { parameter ->
            PollValueColumns.forParameter(parameter).forEach { columnName ->
                val normalizedColumnName = columnName.lowercase(Locale.US)
                if (!existingColumns.contains(normalizedColumnName)) {
                    db.execSQL("ALTER TABLE poll_values ADD COLUMN ${quoteIdentifier(columnName)} TEXT")
                    existingColumns.add(normalizedColumnName)
                }
            }
        }
        pollValueColumnsEnsuredForCatalogVersionId = catalogVersionId
    }

    private fun pollValueColumnNames(db: SQLiteDatabase): Set<String> {
        db.rawQuery("PRAGMA table_info(poll_values)", emptyArray()).use { cursor ->
            return buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
    }

    private fun insertPollValues(
        db: SQLiteDatabase,
        pollId: Long,
        readings: List<PollReading>,
        parameters: List<CatalogParameter>
    ) {
        val parametersByKey = parameters.associateBy { it.key }
        db.insertOrThrow(
            "poll_values",
            null,
            ContentValues().apply {
                put("poll_id", pollId)
                readings.forEach { reading ->
                    val parameter = parametersByKey[reading.rawKey] ?: return@forEach
                    put(PollValueColumns.raw(parameter.key), reading.rawValue)
                    if (parameter.includeDesc) {
                        put(PollValueColumns.desc(parameter.key), reading.descValue)
                    }
                }
            }
        )
    }

    private fun upsertMqttPublishState(targetKey: String, targetType: String, values: ContentValues) {
        val db = helper.writableDatabase
        val now = clock.nowIso()
        values.put("target_type", targetType)
        values.put("updated_at", now)
        val updated = db.update(
            "mqtt_publish_state",
            values,
            "target_key = ?",
            arrayOf(targetKey)
        )
        if (updated > 0) return

        values.put("target_key", targetKey)
        values.put("created_at", now)
        val inserted = db.insertWithOnConflict(
            "mqtt_publish_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted != -1L) return

        db.update(
            "mqtt_publish_state",
            values,
            "target_key = ?",
            arrayOf(targetKey)
        )
    }

    private fun upsertRetryState(values: ContentValues) {
        val db = helper.writableDatabase
        val updated = db.update("mqtt_retry_state", values, "id = 1", emptyArray())
        if (updated > 0) return

        values.put("id", 1)
        val inserted = db.insertWithOnConflict(
            "mqtt_retry_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted != -1L) return

        values.remove("id")
        db.update("mqtt_retry_state", values, "id = 1", emptyArray())
    }

    private fun mqttOutboxTargetType(targetKey: String, payloadHash: String): String? {
        helper.readableDatabase.rawQuery(
            "SELECT target_type FROM mqtt_outbox WHERE target_key = ? AND payload_hash = ? LIMIT 1",
            arrayOf(targetKey, payloadHash)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun mqttOutboxAttemptCount(targetKey: String, payloadHash: String): Int? {
        helper.readableDatabase.rawQuery(
            "SELECT attempt_count FROM mqtt_outbox WHERE target_key = ? AND payload_hash = ? LIMIT 1",
            arrayOf(targetKey, payloadHash)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else null
        }
    }

    private fun scalarString(sql: String): String? {
        helper.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun safeScalarString(sql: String): String? = runCatching {
        scalarString(sql)
    }.onFailure { error ->
        Log.w(TAG, "scalar string query failed: $sql", error)
    }.getOrNull()

    private fun scalarLong(sql: String): Long {
        helper.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun safeScalarLong(sql: String): Long = runCatching {
        scalarLong(sql)
    }.onFailure { error ->
        Log.w(TAG, "scalar long query failed: $sql", error)
    }.getOrDefault(0L)

    private fun scalarLongOrNull(sql: String): Long? {
        helper.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    private fun safeScalarLongOrNull(sql: String): Long? = runCatching {
        scalarLongOrNull(sql)
    }.onFailure { error ->
        Log.w(TAG, "scalar nullable long query failed: $sql", error)
    }.getOrNull()

    private fun Cursor.toCatalogParameter(): CatalogParameter {
        return CatalogParameter(
            id = getLong(0),
            catalogVersionId = getLong(1),
            sourceId = getString(2),
            key = getString(3),
            name = getString(4),
            groupName = getString(5),
            includeDesc = getInt(6) == 1,
            note = getString(7)
        )
    }

    private fun Cursor.getNullableString(index: Int): String? {
        return if (isNull(index)) null else getString(index)
    }

    private fun quoteIdentifier(value: String): String {
        require(PollValueColumns.isSafeIdentifier(value)) { "Unsafe SQLite identifier: $value" }
        return "\"$value\""
    }

    private fun String?.truncateForStorage(maxLength: Int): String? {
        val value = this ?: return null
        if (value.length <= maxLength) return value
        return value.take(maxLength) + "...[truncated ${value.length - maxLength} chars]"
    }

    private fun pollingErrorSummary(category: String?, message: String?): String {
        return when (category) {
            "network_error", "timeout" -> "Direct telemetry unavailable"
            "http_error", "di_success_false", "parse_error" -> "Direct telemetry error"
            "adb_authorization_required" -> "ADB not authorized"
            "adb_authorization_unavailable" -> "ADB unavailable"
            "adb_authorization_timeout" -> "ADB authorization timeout"
            "bridge_launch_failed", "bridge_unavailable",
            "helper_launch_failed", "helper_unavailable", "helper_launch_backoff",
            "autoservice_snapshot_empty" -> "Direct vehicle helper unavailable"
            else -> category ?: "unknown"
        } + message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    }

    private fun targetTypeForTopic(topic: String): String {
        val normalized = topic.lowercase(Locale.US)
        return when {
            normalized.endsWith("/status") -> "status"
            "/state/" in normalized -> "state"
            normalized.endsWith("/config") -> "discovery"
            else -> "mqtt"
        }
    }

    private fun sha256(payload: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "BYDCollectorEvent"
        private const val MAX_ERROR_TEXT_LENGTH = 2_048
        private const val MAX_RAW_RESPONSE_BODY_LENGTH = 4_096
    }
}
