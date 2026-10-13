package com.bydcollector.collector.mqtt

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.UUID

data class MqttCleanupSelection(
    val deviceId: String,
    val fingerprint: String
) : java.io.Serializable

data class MqttCleanupGroup(
    val deviceId: String,
    val deviceName: String,
    val topicCount: Int,
    val uncertainCount: Int,
    val error: String?,
    val current: Boolean,
    val state: String,
    val selection: MqttCleanupSelection,
    val deviceIdentifier: String? = null,
    val deviceModel: String? = null
) : java.io.Serializable {
    fun identityLine(): String = listOf(deviceId, deviceIdentifier, deviceName, deviceModel)
        .joinToString(" | ") { it?.takeIf(String::isNotBlank) ?: "—" }

    companion object {
        fun current(config: HaMqttConfig, state: String = "loading") = MqttCleanupGroup(
            deviceId = config.normalizedDeviceId(), deviceName = config.deviceName, topicCount = 0,
            uncertainCount = 0, error = null, current = true, state = state,
            selection = MqttCleanupSelection(config.normalizedDeviceId(), ""),
            deviceIdentifier = config.deviceIdentifier, deviceModel = config.deviceModel
        )
    }
}

data class MqttCleanupTargetRef(
    val scopeKey: String,
    val deviceId: String,
    val topic: String
) : java.io.Serializable

data class MqttCleanupOperationTarget(
    val target: MqttCleanupTargetRef,
    val acknowledged: Boolean
)

interface MqttPublicationJournalStore {
    fun destinationEpoch(scopeKey: String, deviceId: String): Long
    fun isCleanupPending(deviceId: String, configuredScopeKeys: Set<String>): Boolean
    fun recordDiscoveryAttempt(scopeKey: String, deviceId: String, topic: String, payloadHash: String, deviceName: String, at: String, deviceIdentifier: String? = null, deviceModel: String? = null)
    fun recordDiscoveryAcknowledged(scopeKey: String, deviceId: String, topic: String, payloadHash: String, deviceName: String, at: String, remove: Boolean, deviceIdentifier: String? = null, deviceModel: String? = null)
    fun recordDiscoveryFailure(scopeKey: String, deviceId: String, topic: String, error: String, at: String)
    fun cleanupGroups(configuredScopeKeys: Set<String>, currentDeviceId: String): List<MqttCleanupGroup>
    fun cleanupSelectionTargets(selection: MqttCleanupSelection, configuredScopeKeys: Set<String>): List<MqttCleanupTargetRef>
    fun completedCleanupOperation(selection: MqttCleanupSelection): String?
    fun beginCleanup(selection: MqttCleanupSelection, configuredScopeKeys: Set<String>, startedAt: String): String
    fun cleanupTargets(operationId: String): List<MqttCleanupOperationTarget>
    fun cleanupOperationCompleted(operationId: String): Boolean
    fun acknowledgeCleanupTarget(operationId: String, target: MqttCleanupTargetRef, acknowledgedAt: String)
    fun failCleanup(operationId: String, target: MqttCleanupTargetRef?, error: String, failedAt: String)
    fun completeCleanup(operationId: String, completedAt: String)
}

object NoopMqttPublicationJournalStore : MqttPublicationJournalStore {
    override fun destinationEpoch(scopeKey: String, deviceId: String): Long = 0L
    override fun isCleanupPending(deviceId: String, configuredScopeKeys: Set<String>): Boolean = false
    override fun recordDiscoveryAttempt(scopeKey: String, deviceId: String, topic: String, payloadHash: String, deviceName: String, at: String, deviceIdentifier: String?, deviceModel: String?) = Unit
    override fun recordDiscoveryAcknowledged(scopeKey: String, deviceId: String, topic: String, payloadHash: String, deviceName: String, at: String, remove: Boolean, deviceIdentifier: String?, deviceModel: String?) = Unit
    override fun recordDiscoveryFailure(scopeKey: String, deviceId: String, topic: String, error: String, at: String) = Unit
    override fun cleanupGroups(configuredScopeKeys: Set<String>, currentDeviceId: String): List<MqttCleanupGroup> = emptyList()
    override fun cleanupSelectionTargets(selection: MqttCleanupSelection, configuredScopeKeys: Set<String>): List<MqttCleanupTargetRef> = emptyList()
    override fun completedCleanupOperation(selection: MqttCleanupSelection): String? = null
    override fun beginCleanup(selection: MqttCleanupSelection, configuredScopeKeys: Set<String>, startedAt: String): String =
        throw UnsupportedOperationException("MQTT cleanup journal is unavailable")
    override fun cleanupTargets(operationId: String): List<MqttCleanupOperationTarget> = emptyList()
    override fun cleanupOperationCompleted(operationId: String): Boolean = false
    override fun acknowledgeCleanupTarget(operationId: String, target: MqttCleanupTargetRef, acknowledgedAt: String) = Unit
    override fun failCleanup(operationId: String, target: MqttCleanupTargetRef?, error: String, failedAt: String) = Unit
    override fun completeCleanup(operationId: String, completedAt: String) = Unit
}

/** Durable MQTT discovery history; deliberately separate from the archivable telemetry database. */
class MqttPublicationJournal(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), MqttPublicationJournalStore {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE mqtt_discovery_history (
                scope_key TEXT NOT NULL,
                device_id TEXT NOT NULL,
                topic TEXT NOT NULL,
                acknowledged_payload_hash TEXT,
                acknowledged_device_name TEXT,
                acknowledged_device_identifier TEXT,
                acknowledged_device_model TEXT,
                uncertain_payload_hash TEXT,
                uncertain_device_name TEXT,
                uncertain_device_identifier TEXT,
                uncertain_device_model TEXT,
                generation INTEGER NOT NULL DEFAULT 0,
                first_seen_at TEXT NOT NULL,
                last_attempt_at TEXT,
                last_published_at TEXT,
                last_error TEXT,
                PRIMARY KEY(scope_key, device_id, topic)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_mqtt_discovery_history_id ON mqtt_discovery_history(device_id, scope_key)")
        db.execSQL(
            """
            CREATE TABLE mqtt_destination_epoch (
                scope_key TEXT NOT NULL,
                device_id TEXT NOT NULL,
                epoch INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(scope_key, device_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE mqtt_cleanup_operation (
                id TEXT PRIMARY KEY NOT NULL,
                device_id TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                state TEXT NOT NULL,
                last_error TEXT,
                started_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_mqtt_cleanup_device ON mqtt_cleanup_operation(device_id, state)")
        db.execSQL(
            """
            CREATE TABLE mqtt_cleanup_target (
                operation_id TEXT NOT NULL,
                scope_key TEXT NOT NULL,
                device_id TEXT NOT NULL,
                topic TEXT NOT NULL,
                acknowledged INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                acknowledged_at TEXT,
                PRIMARY KEY(operation_id, scope_key, device_id, topic),
                FOREIGN KEY(operation_id) REFERENCES mqtt_cleanup_operation(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            for (prefix in listOf("acknowledged", "uncertain")) {
                db.execSQL("ALTER TABLE mqtt_discovery_history ADD COLUMN ${prefix}_device_identifier TEXT")
                db.execSQL("ALTER TABLE mqtt_discovery_history ADD COLUMN ${prefix}_device_model TEXT")
            }
        }
    }

    override fun destinationEpoch(scopeKey: String, deviceId: String): Long {
        readableDatabase.rawQuery(
            "SELECT epoch FROM mqtt_destination_epoch WHERE scope_key = ? AND device_id = ?",
            arrayOf(scopeKey, deviceId)
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    override fun isCleanupPending(deviceId: String, configuredScopeKeys: Set<String>): Boolean {
        if (configuredScopeKeys.isEmpty()) return false
        val placeholders = configuredScopeKeys.joinToString(",") { "?" }
        val args = arrayOf(deviceId, "running", "error", *configuredScopeKeys.toTypedArray())
        readableDatabase.rawQuery(
            """
            SELECT 1 FROM mqtt_cleanup_operation o
            JOIN mqtt_cleanup_target t ON t.operation_id = o.id
            WHERE o.device_id = ? AND o.state IN (?, ?) AND t.scope_key IN ($placeholders)
            LIMIT 1
            """.trimIndent(), args
        ).use { return it.moveToFirst() }
    }

    override fun recordDiscoveryAttempt(
        scopeKey: String,
        deviceId: String,
        topic: String,
        payloadHash: String,
        deviceName: String,
        at: String,
        deviceIdentifier: String?,
        deviceModel: String?
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("uncertain_payload_hash", payloadHash)
            put("uncertain_device_name", deviceName)
            put("uncertain_device_identifier", deviceIdentifier)
            put("uncertain_device_model", deviceModel)
            put("last_attempt_at", at)
            put("generation", destinationEpoch(scopeKey, deviceId))
            putNull("last_error")
        }
        if (db.update("mqtt_discovery_history", values, "scope_key = ? AND device_id = ? AND topic = ?", arrayOf(scopeKey, deviceId, topic)) > 0) return
        values.put("scope_key", scopeKey)
        values.put("device_id", deviceId)
        values.put("topic", topic)
        values.put("first_seen_at", at)
        db.insertOrThrow("mqtt_discovery_history", null, values)
    }

    override fun recordDiscoveryAcknowledged(
        scopeKey: String,
        deviceId: String,
        topic: String,
        payloadHash: String,
        deviceName: String,
        at: String,
        remove: Boolean,
        deviceIdentifier: String?,
        deviceModel: String?
    ) {
        val db = writableDatabase
        if (remove) {
            db.delete("mqtt_discovery_history", "scope_key = ? AND device_id = ? AND topic = ?", arrayOf(scopeKey, deviceId, topic))
            return
        }
        val values = ContentValues().apply {
            put("acknowledged_payload_hash", payloadHash)
            put("acknowledged_device_name", deviceName)
            put("acknowledged_device_identifier", deviceIdentifier)
            put("acknowledged_device_model", deviceModel)
            putNull("uncertain_payload_hash")
            putNull("uncertain_device_name")
            putNull("uncertain_device_identifier")
            putNull("uncertain_device_model")
            put("last_published_at", at)
            put("generation", destinationEpoch(scopeKey, deviceId))
            putNull("last_error")
        }
        val where = "scope_key = ? AND device_id = ? AND topic = ?"
        val args = arrayOf(scopeKey, deviceId, topic)
        if (db.update("mqtt_discovery_history", values, where, args) > 0) return
        values.put("scope_key", scopeKey)
        values.put("device_id", deviceId)
        values.put("topic", topic)
        values.put("first_seen_at", at)
        db.insertOrThrow("mqtt_discovery_history", null, values)
    }

    override fun recordDiscoveryFailure(scopeKey: String, deviceId: String, topic: String, error: String, at: String) {
        writableDatabase.update(
            "mqtt_discovery_history",
            ContentValues().apply {
                put("last_error", error.take(MAX_ERROR_LENGTH))
                put("last_attempt_at", at)
            },
            "scope_key = ? AND device_id = ? AND topic = ?",
            arrayOf(scopeKey, deviceId, topic)
        )
    }

    override fun cleanupGroups(configuredScopeKeys: Set<String>, currentDeviceId: String): List<MqttCleanupGroup> {
        if (configuredScopeKeys.isEmpty()) return emptyList()
        val entries = readHistory(configuredScopeKeys)
        val cleanupState = readCleanupState(configuredScopeKeys)
        return entries.groupBy { it.target.deviceId }.map { (deviceId, group) ->
            val targetRefs = group.map { it.target }.distinctBy { it.scopeKey to it.topic }
            val fingerprint = historyFingerprint(group)
            val cleanup = cleanupState[deviceId]
            val currentCleanupPending = deviceId == currentDeviceId &&
                (cleanup?.first == "running" || cleanup?.first == "error")
            val reportedError = if (currentCleanupPending) {
                "mqtt_cleanup_pending_identity" + cleanup?.second?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
            } else cleanup?.second
            val identity = group.maxByOrNull { it.identityAt }
            MqttCleanupGroup(
                deviceId = deviceId,
                deviceName = identity?.deviceName.orEmpty(),
                deviceIdentifier = identity?.deviceIdentifier,
                deviceModel = identity?.deviceModel,
                topicCount = targetRefs.map { it.topic }.distinct().size,
                uncertainCount = group.filter { it.uncertain }.map { it.target.topic }.distinct().size,
                error = reportedError,
                current = deviceId == currentDeviceId,
                state = when {
                    deviceId == currentDeviceId -> "current"
                    cleanup?.first == "error" -> "error"
                    cleanup?.first == "running" -> "cleaning"
                    group.any { it.uncertain } -> "uncertain"
                    else -> "published"
                },
                selection = MqttCleanupSelection(deviceId, fingerprint)
            )
        }.sortedBy { it.deviceId }
    }

    override fun cleanupSelectionTargets(selection: MqttCleanupSelection, configuredScopeKeys: Set<String>): List<MqttCleanupTargetRef> {
        val selected = readHistory(readableDatabase, configuredScopeKeys, selection.deviceId)
        if (selected.isEmpty() || historyFingerprint(selected) != selection.fingerprint) return emptyList()
        return selected.map { it.target }.distinctBy { Triple(it.scopeKey, it.deviceId, it.topic) }
    }

    override fun completedCleanupOperation(selection: MqttCleanupSelection): String? = readableDatabase.rawQuery(
        "SELECT id FROM mqtt_cleanup_operation WHERE device_id = ? AND fingerprint = ? AND state = 'complete' ORDER BY updated_at DESC LIMIT 1",
        arrayOf(selection.deviceId, selection.fingerprint)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun beginCleanup(selection: MqttCleanupSelection, configuredScopeKeys: Set<String>, startedAt: String): String {
        val db = writableDatabase
        return db.transaction {
            val completedReceipt = db.rawQuery(
                "SELECT id FROM mqtt_cleanup_operation WHERE device_id = ? AND fingerprint = ? AND state = 'complete' ORDER BY updated_at DESC LIMIT 1",
                arrayOf(selection.deviceId, selection.fingerprint)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (completedReceipt != null) return@transaction completedReceipt

            val selected = readHistory(db, configuredScopeKeys, selection.deviceId)
            check(selected.isNotEmpty() && historyFingerprint(selected) == selection.fingerprint) {
                "MQTT cleanup selection is stale; refresh the published-ID list"
            }
            val scopes = selected.map { it.target.scopeKey }.toSet()
            check(scopes.all { it in configuredScopeKeys }) { "MQTT cleanup broker is no longer configured" }

            val existing = findOverlappingOperation(db, selection.deviceId, scopes)
            if (existing != null) {
                check(existing.second == selection.fingerprint) { "Another cleanup for this ID is already pending" }
                db.update(
                    "mqtt_cleanup_operation",
                    ContentValues().apply { put("state", "running"); putNull("last_error"); put("updated_at", startedAt) },
                    "id = ?",
                    arrayOf(existing.first)
                )
                return@transaction existing.first
            }

            val operationId = UUID.randomUUID().toString()
            db.insertOrThrow(
                "mqtt_cleanup_operation",
                null,
                ContentValues().apply {
                    put("id", operationId)
                    put("device_id", selection.deviceId)
                    put("fingerprint", selection.fingerprint)
                    put("state", "running")
                    put("started_at", startedAt)
                    put("updated_at", startedAt)
                }
            )
            selected.map { it.target }.distinctBy { Triple(it.scopeKey, it.deviceId, it.topic) }.forEach { target ->
                db.insertOrThrow(
                    "mqtt_cleanup_target",
                    null,
                    ContentValues().apply {
                        put("operation_id", operationId)
                        put("scope_key", target.scopeKey)
                        put("device_id", target.deviceId)
                        put("topic", target.topic)
                    }
                )
            }
            scopes.forEach { scopeKey ->
                val previousEpoch = db.rawQuery(
                    "SELECT epoch FROM mqtt_destination_epoch WHERE scope_key = ? AND device_id = ? LIMIT 1",
                    arrayOf(scopeKey, selection.deviceId)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                if (previousEpoch == null) {
                    db.insertOrThrow(
                        "mqtt_destination_epoch",
                        null,
                        ContentValues().apply {
                            put("scope_key", scopeKey)
                            put("device_id", selection.deviceId)
                            put("epoch", 1L)
                        }
                    )
                } else {
                    db.update(
                        "mqtt_destination_epoch",
                        ContentValues().apply { put("epoch", previousEpoch + 1L) },
                        "scope_key = ? AND device_id = ?",
                        arrayOf(scopeKey, selection.deviceId)
                    )
                }
            }
            operationId
        }
    }

    override fun cleanupTargets(operationId: String): List<MqttCleanupOperationTarget> {
        readableDatabase.rawQuery(
            "SELECT scope_key, device_id, topic, acknowledged FROM mqtt_cleanup_target WHERE operation_id = ? ORDER BY scope_key, topic",
            arrayOf(operationId)
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(
                    MqttCleanupOperationTarget(
                        target = MqttCleanupTargetRef(cursor.getString(0), cursor.getString(1), cursor.getString(2)),
                        acknowledged = cursor.getInt(3) == 1
                    )
                )
            }
        }
    }

    override fun cleanupOperationCompleted(operationId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM mqtt_cleanup_operation WHERE id = ? AND state = 'complete' LIMIT 1",
            arrayOf(operationId)
        ).use { return it.moveToFirst() }
    }

    override fun acknowledgeCleanupTarget(operationId: String, target: MqttCleanupTargetRef, acknowledgedAt: String) {
        val db = writableDatabase
        val updated = db.update(
            "mqtt_cleanup_target",
            ContentValues().apply {
                put("acknowledged", 1)
                putNull("last_error")
                put("acknowledged_at", acknowledgedAt)
            },
            "operation_id = ? AND scope_key = ? AND device_id = ? AND topic = ?",
            arrayOf(operationId, target.scopeKey, target.deviceId, target.topic)
        )
        check(updated == 1) { "MQTT cleanup target is no longer pending" }
        db.update(
            "mqtt_cleanup_operation",
            ContentValues().apply { put("state", "running"); putNull("last_error"); put("updated_at", acknowledgedAt) },
            "id = ?",
            arrayOf(operationId)
        )
    }

    override fun failCleanup(operationId: String, target: MqttCleanupTargetRef?, error: String, failedAt: String) {
        val db = writableDatabase
        if (target != null) {
            db.update(
                "mqtt_cleanup_target",
                ContentValues().apply { put("last_error", error.take(MAX_ERROR_LENGTH)) },
                "operation_id = ? AND scope_key = ? AND device_id = ? AND topic = ? AND acknowledged = 0",
                arrayOf(operationId, target.scopeKey, target.deviceId, target.topic)
            )
        }
        db.update(
            "mqtt_cleanup_operation",
            ContentValues().apply { put("state", "error"); put("last_error", error.take(MAX_ERROR_LENGTH)); put("updated_at", failedAt) },
            "id = ?",
            arrayOf(operationId)
        )
    }

    override fun completeCleanup(operationId: String, completedAt: String) {
        val db = writableDatabase
        db.transaction {
            val targets = db.rawQuery(
                "SELECT scope_key, device_id, topic, acknowledged FROM mqtt_cleanup_target WHERE operation_id = ? ORDER BY scope_key, topic",
                arrayOf(operationId)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        MqttCleanupOperationTarget(
                            MqttCleanupTargetRef(cursor.getString(0), cursor.getString(1), cursor.getString(2)),
                            cursor.getInt(3) == 1
                        )
                    )
                }
            }
            check(targets.isNotEmpty() && targets.all { it.acknowledged }) { "MQTT cleanup still has unacknowledged topics" }
            targets.forEach { item ->
                db.delete(
                    "mqtt_discovery_history",
                    "scope_key = ? AND device_id = ? AND topic = ?",
                    arrayOf(item.target.scopeKey, item.target.deviceId, item.target.topic)
                )
            }
            db.delete("mqtt_cleanup_target", "operation_id = ?", arrayOf(operationId))
            db.update(
                "mqtt_cleanup_operation",
                ContentValues().apply { put("state", "complete"); putNull("last_error"); put("updated_at", completedAt) },
                "id = ?",
                arrayOf(operationId)
            )
        }
    }

    private fun findOverlappingOperation(db: SQLiteDatabase, deviceId: String, scopeKeys: Set<String>): Pair<String, String>? {
        if (scopeKeys.isEmpty()) return null
        val placeholders = scopeKeys.joinToString(",") { "?" }
        val args = arrayOf(deviceId, "running", "error", *scopeKeys.toTypedArray())
        db.rawQuery(
            """
            SELECT o.id, o.fingerprint FROM mqtt_cleanup_operation o
            JOIN mqtt_cleanup_target t ON t.operation_id = o.id
            WHERE o.device_id = ? AND o.state IN (?, ?) AND t.scope_key IN ($placeholders)
            LIMIT 1
            """.trimIndent(), args
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null }
    }

    private fun readCleanupState(scopeKeys: Set<String>): Map<String, Pair<String, String?>> {
        if (scopeKeys.isEmpty()) return emptyMap()
        val placeholders = scopeKeys.joinToString(",") { "?" }
        val args = arrayOf("running", "error", *scopeKeys.toTypedArray())
        readableDatabase.rawQuery(
            """
            SELECT o.device_id, o.state, o.last_error FROM mqtt_cleanup_operation o
            JOIN mqtt_cleanup_target t ON t.operation_id = o.id
            WHERE o.state IN (?, ?) AND t.scope_key IN ($placeholders)
            GROUP BY o.id ORDER BY o.updated_at DESC
            """.trimIndent(), args
        ).use { cursor ->
            return buildMap { while (cursor.moveToNext()) putIfAbsent(cursor.getString(0), cursor.getString(1) to cursor.getString(2)) }
        }
    }

    private fun readHistory(scopeKeys: Set<String>, deviceId: String? = null): List<HistoryEntry> =
        readHistory(readableDatabase, scopeKeys, deviceId)

    private fun readHistory(db: SQLiteDatabase, scopeKeys: Set<String>, deviceId: String? = null): List<HistoryEntry> {
        if (scopeKeys.isEmpty()) return emptyList()
        val placeholders = scopeKeys.joinToString(",") { "?" }
        val deviceClause = if (deviceId == null) "" else " AND device_id = ?"
        val args = if (deviceId == null) scopeKeys.toTypedArray() else arrayOf(*scopeKeys.toTypedArray(), deviceId)
        db.rawQuery(
            """
            SELECT scope_key, device_id, topic, acknowledged_payload_hash, acknowledged_device_name,
                   uncertain_payload_hash, uncertain_device_name, generation,
                   acknowledged_device_identifier, acknowledged_device_model,
                   uncertain_device_identifier, uncertain_device_model, last_attempt_at, last_published_at
            FROM mqtt_discovery_history WHERE scope_key IN ($placeholders)$deviceClause
            ORDER BY scope_key, device_id, topic
            """.trimIndent(), args
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val ackHash = cursor.getString(3)
                    val uncertainHash = cursor.getString(5)
                    add(
                        HistoryEntry(
                            target = MqttCleanupTargetRef(cursor.getString(0), cursor.getString(1), cursor.getString(2)),
                            deviceName = if (uncertainHash != null) cursor.getString(6).orEmpty() else cursor.getString(4).orEmpty(),
                            deviceIdentifier = cursor.getString(if (uncertainHash != null) 10 else 8),
                            deviceModel = cursor.getString(if (uncertainHash != null) 11 else 9),
                            identityAt = cursor.getString(if (uncertainHash != null) 12 else 13).orEmpty(),
                            acknowledgedHash = ackHash,
                            uncertainHash = uncertainHash,
                            generation = cursor.getLong(7)
                        )
                    )
                }
            }
        }
    }

    private fun historyFingerprint(entries: List<HistoryEntry>): String {
        val fields = entries.sortedWith(compareBy({ it.target.scopeKey }, { it.target.deviceId }, { it.target.topic }))
            .flatMap { listOf(it.target.scopeKey, it.target.deviceId, it.target.topic, it.generation.toString()) }
        val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private data class HistoryEntry(
        val target: MqttCleanupTargetRef,
        val deviceName: String,
        val deviceIdentifier: String?,
        val deviceModel: String?,
        val identityAt: String,
        val acknowledgedHash: String?,
        val uncertainHash: String?,
        val generation: Long
    ) {
        val uncertain: Boolean get() = uncertainHash != null
    }

    private inline fun <T> SQLiteDatabase.transaction(action: () -> T): T {
        beginTransaction()
        try {
            return action().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    companion object {
        const val DATABASE_NAME = "mqtt_publication_journal.db"
        const val DATABASE_VERSION = 2
        private const val MAX_ERROR_LENGTH = 512
    }
}
