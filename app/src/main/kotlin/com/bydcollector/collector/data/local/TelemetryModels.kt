package com.bydcollector.collector.data.local

data class CatalogSeedRow(
    val sourceId: String?,
    val key: String,
    val name: String,
    val groupName: String?,
    val includeDesc: Boolean,
    val note: String?
)

data class CatalogParameter(
    val id: Long,
    val catalogVersionId: Long,
    val sourceId: String?,
    val key: String,
    val name: String,
    val groupName: String?,
    val includeDesc: Boolean,
    val note: String?
)

data class PollReading(
    val rawKey: String,
    val rawValue: String?,
    val descValue: String? = null
)

data class PersistedPollInput(
    val timestamp: String,
    val ok: Boolean,
    val elapsedMs: Long?,
    val requestCount: Int,
    val errors: String?,
    val errorCategory: String? = null,
    val errorMessage: String? = null,
    val rawResponseBody: String?,
    val readings: List<PollReading>
)

data class CollectorEvent(
    val id: Long,
    val timestamp: String,
    val category: String,
    val message: String,
    val detail: String?
)

data class HealthSnapshot(
    val running: Boolean,
    val activeSessionId: Long?,
    val lastSuccessAt: String?,
    val lastError: String?,
    val lastErrorAt: String?,
    val lastPollStatus: String?,
    val pollCount: Long,
    val valueRowCount: Long,
    val ecRowCount: Long,
    val normalizedCurrentCount: Long,
    val normalizedHistoryCount: Long,
    val mqttLastError: String?,
    val mqttLastPublishedAt: String?,
    val mqttPendingCount: Long,
    val mqttRetryFailureCount: Int,
    val mqttNextRetryAt: String?,
    val mqttRetryLastFailureAt: String?,
    val mqttRetryLastSuccessAt: String?,
    val lastEcImport: String?,
    val lastEcImportStatus: String?,
    val elapsedMs: Long?,
    val requestCount: Int?,
    val databasePath: String,
    val databaseSizeBytes: Long,
    val latestSoc: String?,
    val latestSpeed: String?,
    val latestCharging: String?,
    val recentEvents: List<CollectorEvent>
)

interface Clock {
    fun nowIso(): String
    fun elapsedRealtimeMs(): Long
}
