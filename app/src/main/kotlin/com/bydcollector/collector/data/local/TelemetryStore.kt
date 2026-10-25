package com.bydcollector.collector.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import com.bydcollector.collector.data.callback.CallbackDelivery
import com.bydcollector.collector.data.callback.CallbackImportResult
import com.bydcollector.collector.data.callback.CallbackRawStore
import com.bydcollector.collector.data.callback.StoredCallbackEvent
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.energy.EnergyInput
import com.bydcollector.collector.data.energy.EnergyReceipt
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.diagnostics.DiagnosticShareSanitizer
import com.bydcollector.collector.maintenance.DeferredEnergyArchive
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.CallbackNormalizationPageResult
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceOrder
import com.bydcollector.collector.data.normalized.NormalizedSourceOrdering
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.NormalizedStateStore
import com.bydcollector.collector.data.normalized.NormalizedWriteSummary
import com.bydcollector.collector.data.normalized.PollingErrorSummaries
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import com.bydcollector.collector.data.normalized.SourceOrderedApplyResult
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.normalized.normalizedIsoTime
import com.bydcollector.collector.data.normalized.normalizedCallbackSourceStamp
import com.bydcollector.collector.data.normalized.normalizedPollSourceStamp
import com.bydcollector.collector.data.normalized.normalizedSourceInputForPollReading
import com.bydcollector.collector.data.normalized.usableNormalizedCallbackQuality
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.data.polling.PollStorage
import com.bydcollector.collector.data.polling.WorkerPollStorage
import com.bydcollector.collector.mqtt.HaMqttMessage
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
import com.bydcollector.collector.service.TelegramEventState
import com.bydcollector.collector.util.sqliteFootprintBytes
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.diagnosticDetail
import com.bydcollector.collector.diagnostics.diagnosticSha256
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class HistoricalMainIdentity(
    val canonicalPath: String,
    val schemaVersion: Long,
    val userVersion: Long,
    val lineage: String
) {
    val stableKey: String
        get() = "$canonicalPath|$schemaVersion|$userVersion|$lineage"
}

internal data class HistoricalPollEndpoint(val pollId: Long, val sessionId: Long, val timestamp: String)

data class DeferredEnergyReceipt(val id: Long, val receipt: EnergyReceipt, val retryCount: Int, val nextRetryAtMs: Long)

internal data class HistoricalEnergyPoll(
    val pollId: Long,
    val sessionId: Long,
    val timestamp: String,
    val ok: Boolean,
    val readings: List<PollReading>
)

internal data class HistoricalEnergyPollPage(
    val sourceIdentity: HistoricalMainIdentity,
    val polls: List<HistoricalEnergyPoll>
)

internal data class HistoricalPollHeaderPage(
    val sourceIdentity: HistoricalMainIdentity,
    val polls: List<HistoricalPollEndpoint>
)

data class CallbackNormalizationCommit(
    val summary: NormalizedWriteSummary?,
    val duplicate: Boolean
)

internal data class CurrentPollHealthSnapshot(
    val activeSessionId: Long?,
    val lastSuccessAt: String?,
    val lastError: String?,
    val lastErrorAt: String?,
    val lastPollStatus: String?,
    val elapsedMs: Long?,
    val requestCount: Int?,
    val historyMayBeIncomplete: Boolean,
    val available: Boolean = true
)

internal data class PollHealthRow(
    val pollId: Long,
    val timestamp: String,
    val ok: Boolean?,
    val errors: String?,
    val errorCategory: String?,
    val errorMessage: String?,
    val elapsedMs: Long?,
    val requestCount: Int?
)

internal data class PollHealthCache(
    val snapshot: CurrentPollHealthSnapshot,
    val latestPollId: Long?,
    val lastSuccessPollId: Long?,
    val lastErrorPollId: Long?,
    val lastErrorAtPollId: Long?,
    val successMayBeMissing: Boolean,
    val errorMayBeMissing: Boolean,
    val errorAtMayBeMissing: Boolean
) {
    fun merge(row: PollHealthRow): PollHealthCache {
        val previous = snapshot
        val updatesLatest = latestPollId == null || row.pollId > latestPollId
        val updatesSuccess = row.ok == true && (lastSuccessPollId == null || row.pollId > lastSuccessPollId)
        val updatesError = row.ok == false && row.errors != null &&
            (lastErrorPollId == null || row.pollId > lastErrorPollId)
        val updatesErrorAt = row.ok == false && (lastErrorAtPollId == null || row.pollId > lastErrorAtPollId)
        if (!updatesLatest && !updatesSuccess && !updatesError && !updatesErrorAt) return this

        val nextSuccessMayBeMissing = successMayBeMissing && !updatesSuccess
        val nextErrorMayBeMissing = errorMayBeMissing && !updatesError
        val nextErrorAtMayBeMissing = errorAtMayBeMissing && !updatesErrorAt
        return copy(
            snapshot = previous.copy(
                lastSuccessAt = if (updatesSuccess) row.timestamp else previous.lastSuccessAt,
                lastError = if (updatesError) row.errors else previous.lastError,
                lastErrorAt = if (updatesErrorAt) row.timestamp else previous.lastErrorAt,
                lastPollStatus = if (updatesLatest) row.toStatus() else previous.lastPollStatus,
                elapsedMs = if (updatesLatest) row.elapsedMs else previous.elapsedMs,
                requestCount = if (updatesLatest) row.requestCount else previous.requestCount,
                historyMayBeIncomplete = nextSuccessMayBeMissing || nextErrorMayBeMissing || nextErrorAtMayBeMissing
            ),
            latestPollId = if (updatesLatest) row.pollId else latestPollId,
            lastSuccessPollId = if (updatesSuccess) row.pollId else lastSuccessPollId,
            lastErrorPollId = if (updatesError) row.pollId else lastErrorPollId,
            lastErrorAtPollId = if (updatesErrorAt) row.pollId else lastErrorAtPollId,
            successMayBeMissing = nextSuccessMayBeMissing,
            errorMayBeMissing = nextErrorMayBeMissing,
            errorAtMayBeMissing = nextErrorAtMayBeMissing
        )
    }

    companion object {
        fun empty(sessionId: Long?): PollHealthCache = PollHealthCache(
            snapshot = CurrentPollHealthSnapshot(
                activeSessionId = sessionId,
                lastSuccessAt = null,
                lastError = null,
                lastErrorAt = null,
                lastPollStatus = null,
                elapsedMs = null,
                requestCount = null,
                historyMayBeIncomplete = false
            ),
            latestPollId = null,
            lastSuccessPollId = null,
            lastErrorPollId = null,
            lastErrorAtPollId = null,
            successMayBeMissing = false,
            errorMayBeMissing = false,
            errorAtMayBeMissing = false
        )

        fun fromRecentPage(
            sessionId: Long,
            newestFirstRows: List<PollHealthRow>,
            pageLimit: Int,
            globalPageFilled: Boolean = newestFirstRows.size >= pageLimit
        ): PollHealthCache {
            require(pageLimit > 0)
            val latest = newestFirstRows.firstOrNull()
            val success = newestFirstRows.firstOrNull { it.ok == true }
            val latestFailure = newestFirstRows.firstOrNull { it.ok == false }
            val error = newestFirstRows.firstOrNull { it.ok == false && it.errors != null }
            val successMayBeMissing = globalPageFilled && success == null
            val errorMayBeMissing = globalPageFilled && error == null
            val errorAtMayBeMissing = globalPageFilled && latestFailure == null
            return PollHealthCache(
                snapshot = CurrentPollHealthSnapshot(
                    activeSessionId = sessionId,
                    lastSuccessAt = success?.timestamp,
                    lastError = error?.errors,
                    lastErrorAt = latestFailure?.timestamp,
                    lastPollStatus = latest?.toStatus(),
                    elapsedMs = latest?.elapsedMs,
                    requestCount = latest?.requestCount,
                    historyMayBeIncomplete = successMayBeMissing || errorMayBeMissing || errorAtMayBeMissing
                ),
                latestPollId = latest?.pollId,
                lastSuccessPollId = success?.pollId,
                lastErrorPollId = error?.pollId,
                lastErrorAtPollId = latestFailure?.pollId,
                successMayBeMissing = successMayBeMissing,
                errorMayBeMissing = errorMayBeMissing,
                errorAtMayBeMissing = errorAtMayBeMissing
            )
        }
    }
}

private fun PollHealthRow.toStatus(): String = if (ok == true) {
    "ok at $timestamp"
} else {
    "Polling error: ${PollingErrorSummaries.summary(errorCategory, errorMessage)} at $timestamp"
}

//central sqlite facade that keeps raw polls, normalized state, mqtt outbox, and influx cursors consistent
class TelemetryStore(
    private val context: Context,
    private val helper: TelemetryDatabaseHelper,
    private val clock: Clock = SystemClockAdapter(),
    private val eventRetention: Int = 200,
    private val operationalEventJournal: OperationalEventJournal
) : PollStorage,
    WorkerPollStorage,
    NormalizedStateProvider,
    MqttPublishStateRecorder,
    MqttOutboxStore,
    MqttRetryStateStore,
    InfluxExportStore,
    Closeable {
    private val directImporter = DirectCatalogImporter(helper)
    private val ecImporter = EcDatabaseImporter(context, helper, clock)
    private val normalizedStore = NormalizedStateStore(helper, clock)
    private val mainEntriesByAddress = DirectFidRegistry.entries
        .groupBy { Triple(it.dev, it.fid, it.tx) }
        .mapValues { (_, entries) -> entries.singleOrNull() }
    private val currentBootId: String? by lazy {
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
    private val callbackRawStore = CallbackRawStore(
        database = { helper.writableDatabase },
        nowMs = { java.time.OffsetDateTime.parse(clock.nowIso()).toInstant().toEpochMilli() }
    )
    private val influxCursorInitializer = InfluxCursorInitializer()
    private val influxPendingSummaryReader = BoundedInfluxPendingSummaryReader()
    private val compactV2 by lazy { helper.isCompactV2() }
    private val dashboardRowCountReader = BoundedIdRowCountReader(
        listOf(
            BoundedIdRowCountTable("polls", "polls", "id"),
            BoundedIdRowCountTable("poll_values", "poll_values", "poll_id"),
            BoundedIdRowCountTable("raw_callback_events", "raw_callback_events", "id"),
            BoundedIdRowCountTable("normalized_history", "vehicle_state_history", "id")
        )
    )
    private val decodedValueIds = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(DECODED_VALUE_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > DECODED_VALUE_CACHE_SIZE
        }
    )
    private val pollHealthLock = Any()
    private val healthReadFailureLock = Any()
    private val healthReadFailureSignatures = mutableMapOf<String, String>()
    @Volatile private var pollHealthCache: PollHealthCache? = null
    @Volatile private var pollValueColumnsEnsuredForCatalogVersionId: Long? = null
    private val normalizedCatalogImportLock = Any()
    @Volatile private var normalizedCatalogEnsured = false
    private val closed = AtomicBoolean(false)

    fun enqueueDeferredEnergy(receipt: EnergyReceipt): Boolean {
        val db = helper.writableDatabase
        val input = receipt.input
        val values = ContentValues().apply {
            put("source_identity", receipt.sourceIdentity)
            put("observed_at", receipt.observedAt)
            put("boot_id", input.bootId)
            put("elapsed_ms", input.elapsedMs)
            putNullable("voltage", input.voltage)
            putNullable("current_a", input.current)
            putNullableBoolean("power_on", input.powerOn)
            putNullableBoolean("gun_disconnected", input.gunDisconnected)
            putNullableBoolean("external_charging", input.externalCharging)
            put("created_at_ms", System.currentTimeMillis())
        }
        val inserted = db.insertWithOnConflict(
            "energy_deferred_receipts", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (!inserted) {
            val existing = deferredEnergyByIdentity(receipt.sourceIdentity)
            check(existing?.receipt == receipt) { "energy receipt identity reused with different input" }
        }
        return inserted
    }

    fun hasDeferredEnergy(): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM energy_deferred_receipts LIMIT 1", emptyArray()
    ).use { it.moveToFirst() }

    internal fun restoreDeferredEnergy(sourceFile: File, expectedCount: Long) =
        DeferredEnergyArchive.restore(sourceFile, helper.writableDatabase, expectedCount)

    fun oldestDeferredEnergy(nowMs: Long): DeferredEnergyReceipt? = helper.readableDatabase.rawQuery(
        "SELECT id, source_identity, observed_at, boot_id, elapsed_ms, voltage, current_a, " +
            "power_on, gun_disconnected, external_charging, retry_count, next_retry_at_ms, created_at_ms " +
            "FROM energy_deferred_receipts ORDER BY id LIMIT 1", emptyArray()
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        if (cursor.getLong(11) > nowMs && cursor.getLong(12) <= nowMs) return@use null
        cursor.toDeferredEnergyReceipt()
    }

    fun deferEnergyRetry(id: Long, retryCount: Int, error: Throwable, nowMs: Long) {
        val delays = longArrayOf(1_000L, 5_000L, 30_000L, 60_000L)
        val next = nowMs + delays[retryCount.coerceAtMost(delays.lastIndex)]
        check(helper.writableDatabase.update(
            "energy_deferred_receipts",
            ContentValues().apply {
                put("retry_count", retryCount + 1)
                put("next_retry_at_ms", next)
                put("last_error", "${error::class.java.simpleName}: ${error.message.orEmpty()}".take(512))
            },
            "id = ?", arrayOf(id.toString())
        ) == 1) { "deferred energy receipt disappeared" }
    }

    fun completeDeferredEnergy(id: Long) {
        check(helper.writableDatabase.delete(
            "energy_deferred_receipts", "id = ?", arrayOf(id.toString())
        ) == 1) { "deferred energy receipt disappeared" }
    }

    private fun deferredEnergyByIdentity(identity: String): DeferredEnergyReceipt? = helper.readableDatabase.rawQuery(
        "SELECT id, source_identity, observed_at, boot_id, elapsed_ms, voltage, current_a, " +
            "power_on, gun_disconnected, external_charging, retry_count, next_retry_at_ms, created_at_ms " +
            "FROM energy_deferred_receipts WHERE source_identity = ?", arrayOf(identity)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toDeferredEnergyReceipt() else null }

    private fun Cursor.toDeferredEnergyReceipt(): DeferredEnergyReceipt = DeferredEnergyReceipt(
        id = getLong(0),
        receipt = EnergyReceipt(getString(1), getString(2), EnergyInput(
            bootId = getString(3), elapsedMs = getLong(4),
            voltage = if (isNull(5)) null else getDouble(5),
            current = if (isNull(6)) null else getDouble(6),
            powerOn = if (isNull(7)) null else getInt(7) != 0,
            gunDisconnected = if (isNull(8)) null else getInt(8) != 0,
            externalCharging = if (isNull(9)) null else getInt(9) != 0
        )),
        retryCount = getInt(10), nextRetryAtMs = getLong(11)
    )

    private fun ContentValues.putNullableBoolean(key: String, value: Boolean?) {
        if (value == null) putNull(key) else put(key, if (value) 1 else 0)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(pollHealthLock) { pollHealthCache = null }
        influxCursorInitializer.clear()
        influxPendingSummaryReader.clear()
        helper.close()
    }

    fun checkpointForArchive() {
        runCatching {
            helper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
        }.onFailure { error ->
            Log.w(TAG, "database WAL checkpoint failed before archive", error)
        }
    }

    fun verifyWritableDatabase(): Boolean {
        helper.writableDatabase.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
            return cursor.moveToFirst() && cursor.getString(0) == "ok"
        }
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
        if (normalizedCatalogEnsured) return
        synchronized(normalizedCatalogImportLock) {
            if (normalizedCatalogEnsured) return
            normalizedStore.upsertCatalog()
            normalizedCatalogEnsured = true
        }
    }

    fun applyNormalizedObservations(observations: List<NormalizedObservation>): NormalizedWriteSummary {
        if (observations.isEmpty()) return NormalizedWriteSummary(0, 0, 0)
        ensureNormalizedCatalogImported()
        return normalizedStore.applyObservations(observations)
    }

    fun normalizedCurrentState(categories: Set<String>? = null): List<StoredNormalizedState> {
        ensureNormalizedCatalogImported()
        return normalizedStore.currentState(categories)
    }

    override fun currentState(categories: Set<String>?): List<StoredNormalizedState> = normalizedCurrentState(categories)

    fun dashboardRowCounts(cancellationSignal: CancellationSignal? = null): TelemetryRowCounts {
        val appendOnlyCounts = dashboardRowCountReader.refresh(helper.readableDatabase, cancellationSignal)
        val pollCount = appendOnlyCounts.getValue("polls")
        val pollValueCount = appendOnlyCounts.getValue("poll_values")
        val callbackCount = appendOnlyCounts.getValue("raw_callback_events")
        return TelemetryRowCounts(
            pollCount = pollCount,
            valueRowCount = if (pollValueCount < 0L || callbackCount < 0L) {
                BoundedIdRowCountReader.UNKNOWN_ROW_COUNT
            } else {
                pollValueCount + callbackCount
            },
            ecRowCount = scalarLong("SELECT COUNT(*) FROM ec_energy_consumption", cancellationSignal),
            normalizedCurrentCount = scalarLong("SELECT COUNT(*) FROM vehicle_state_current", cancellationSignal),
            normalizedHistoryCount = appendOnlyCounts.getValue("normalized_history")
        )
    }

    fun importCallbackBatch(
        batch: TelemetryCallbackBatch,
        digest: String,
        delivery: CallbackDelivery
    ): CallbackImportResult {
        if (batch.stream != CallbackRawStore.MAIN_STREAM) {
            return CallbackImportResult.Rejected("Main store requires Main callback stream")
        }
        return callbackRawStore.importBatch(batch, digest, delivery)
    }

    fun pendingCallbackNormalization(limit: Int = CallbackRawStore.MAX_PENDING_PAGE): List<StoredCallbackEvent> =
        callbackRawStore.pendingNormalization(limit)

    fun applyCallbackNormalization(
        eventId: Long,
        observations: List<NormalizedObservation>
    ): CallbackNormalizationCommit {
        val db = helper.writableDatabase
        var result: CallbackNormalizationCommit? = null
        db.beginTransaction()
        try {
            callbackRawStore.requireMainEventInTransaction(db, eventId)
            result = if (callbackRawStore.isNormalizedInTransaction(db, eventId)) {
                CallbackNormalizationCommit(summary = null, duplicate = true)
            } else {
                val summary = normalizedStore.applyObservationsInTransaction(db, observations)
                callbackRawStore.markNormalizedInTransaction(db, eventId)
                CallbackNormalizationCommit(summary, duplicate = false)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return checkNotNull(result)
    }

    fun applySourcePollNormalization(
        pollId: Long,
        timestamp: String,
        source: PollSampleSource,
        readings: List<PollReading>,
        normalizer: VehicleStateNormalizer
    ): SourceOrderedApplyResult {
        ensureNormalizedCatalogImported()
        val pollStamp = normalizedPollSourceStamp(source, timestamp)
        val db = helper.writableDatabase
        var result: SourceOrderedApplyResult? = null
        db.beginTransaction()
        try {
            val cached = loadNormalizedSourceInputs(db)
            val accepted = linkedSetOf<String>()
            readings.forEach { reading ->
                val input = normalizedSourceInputForPollReading(reading, pollStamp, pollId) ?: return@forEach
                if (mergeNormalizedSourceInput(db, cached, input)) accepted += reading.rawKey
            }
            val observations = normalizer.normalizeSparse(cached, accepted)
            val summary = normalizedStore.applyObservationsInTransaction(db, observations)
            result = SourceOrderedApplyResult(observations, summary, accepted,
                accepted.mapNotNull { cached[it]?.stamp }.reduceOrNull(::newerSource))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return checkNotNull(result)
    }

    fun normalizePendingCallbackPage(
        normalizer: VehicleStateNormalizer,
        limit: Int = CallbackRawStore.MAX_PENDING_PAGE
    ): CallbackNormalizationPageResult {
        require(limit in 1..CallbackRawStore.MAX_PENDING_PAGE) {
            "callback normalization page must be 1..${CallbackRawStore.MAX_PENDING_PAGE}"
        }
        ensureNormalizedCatalogImported()
        val pending = callbackRawStore.pendingNormalization(limit)
        if (pending.isEmpty()) {
            return CallbackNormalizationPageResult(0, emptyList(), emptyNormalizedSummary(), hasMore = false)
        }
        val decodedInputs = pending.map(::callbackEventInput)
        val db = helper.writableDatabase
        val applied = mutableListOf<NormalizedObservation>()
        var summary = emptyNormalizedSummary()
        var processed = 0
        var latestAppliedSource: NormalizedSourceStamp? = null
        db.beginTransaction()
        try {
            val cached = loadNormalizedSourceInputs(db)
            pending.forEachIndexed { index, event ->
                callbackRawStore.requireMainEventInTransaction(db, event.id)
                if (!callbackRawStore.isNormalizedInTransaction(db, event.id)) {
                    decodedInputs[index]?.let { input ->
                        if (mergeNormalizedSourceInput(db, cached, input)) {
                            val observations = normalizer.normalizeSparse(cached, setOf(input.reading.rawKey))
                            applied += observations
                            summary = summary + normalizedStore.applyObservationsInTransaction(db, observations)
                            if (observations.isNotEmpty()) latestAppliedSource =
                                latestAppliedSource?.let { newerSource(it, input.stamp) } ?: input.stamp
                        }
                    }
                    callbackRawStore.markNormalizedInTransaction(db, event.id)
                    processed += 1
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return CallbackNormalizationPageResult(
            processedCount = processed,
            appliedObservations = applied,
            summary = summary,
            hasMore = pending.size == limit,
            latestAppliedSource = latestAppliedSource,
            oldestPageReceivedWallMs = pending.minOf { it.receivedWallMs }
        )
    }

    private fun newerSource(a: NormalizedSourceStamp, b: NormalizedSourceStamp): NormalizedSourceStamp =
        if (NormalizedSourceOrdering.compare(b, a, currentBootId) == NormalizedSourceOrder.NEWER) b else a

    private fun loadNormalizedSourceInputs(db: SQLiteDatabase): MutableMap<String, NormalizedSourceInput> {
        return db.rawQuery(
            """
            SELECT source_key, raw_value, desc_value, source_kind, source_identity, source_boot_id,
                   source_generator_id, source_sequence, source_wall_ms, source_elapsed_ms, source_poll_id
            FROM normalized_source_inputs
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    put(
                        key,
                        NormalizedSourceInput(
                            reading = PollReading(
                                rawKey = key,
                                rawValue = cursor.getNullableString(1),
                                descValue = cursor.getNullableString(2)
                            ),
                            stamp = NormalizedSourceStamp(
                                kind = NormalizedSourceKind.entries.first {
                                    it.storageValue == cursor.getString(3)
                                },
                                identity = cursor.getString(4),
                                bootId = cursor.getString(5),
                                generatorId = cursor.getNullableString(6),
                                sequence = if (cursor.isNull(7)) null else cursor.getLong(7),
                                wallMs = cursor.getLong(8),
                                elapsedMs = cursor.getLong(9)
                            ),
                            sourcePollId = if (cursor.isNull(10)) null else cursor.getLong(10)
                        )
                    )
                }
            }.toMutableMap()
        }
    }

    private fun mergeNormalizedSourceInput(
        db: SQLiteDatabase,
        cached: MutableMap<String, NormalizedSourceInput>,
        incoming: NormalizedSourceInput
    ): Boolean {
        val key = incoming.reading.rawKey
        val previous = cached[key]
        if (previous != null) {
            when (NormalizedSourceOrdering.compare(incoming.stamp, previous.stamp, currentBootId)) {
                NormalizedSourceOrder.OLDER,
                NormalizedSourceOrder.INCOMPARABLE -> return false
                NormalizedSourceOrder.EQUAL -> {
                    require(incoming.reading.rawValue == previous.reading.rawValue &&
                        incoming.reading.descValue == previous.reading.descValue
                    ) { "source identity reused with different value: $key" }
                    return false
                }
                NormalizedSourceOrder.NEWER -> Unit
            }
        }
        db.insertWithOnConflict(
            "normalized_source_inputs",
            null,
            ContentValues().apply {
                put("source_key", key)
                putNullable("raw_value", incoming.reading.rawValue)
                putNullable("desc_value", incoming.reading.descValue)
                put("source_kind", incoming.stamp.kind.storageValue)
                put("source_identity", incoming.stamp.identity)
                put("source_boot_id", incoming.stamp.bootId)
                putNullable("source_generator_id", incoming.stamp.generatorId)
                putNullable("source_sequence", incoming.stamp.sequence)
                put("source_wall_ms", incoming.stamp.wallMs)
                put("source_elapsed_ms", incoming.stamp.elapsedMs)
                putNullable("source_poll_id", incoming.sourcePollId)
                put("updated_at_ms", epochMillis(clock.nowIso()))
            },
            SQLiteDatabase.CONFLICT_REPLACE
        ).also { require(it != -1L) { "failed to persist normalized source input: $key" } }
        cached[key] = incoming
        return true
    }

    private fun callbackEventInput(event: StoredCallbackEvent): NormalizedSourceInput? {
        if (event.stream != CallbackRawStore.MAIN_STREAM || event.rawBytes != null ||
            !usableNormalizedCallbackQuality(event.quality)
        ) {
            return null
        }
        val tx = when (event.nativeType) {
            TelemetryCallbackBatch.TYPE_INT -> DirectFidRegistry.TX_GET_INT
            TelemetryCallbackBatch.TYPE_FLOAT -> DirectFidRegistry.TX_GET_FLOAT
            else -> return null
        }
        val entry = mainEntriesByAddress[Triple(event.device, event.fid, tx)] ?: return null
        return NormalizedSourceInput(
            reading = PollReading(
                rawKey = entry.key,
                rawValue = DirectValueDecoders.rawString(event.rawBits),
                descValue = DirectValueDecoders.decode(entry, event.rawBits)
            ),
            stamp = normalizedCallbackSourceStamp(
                event.bootId, event.helperGeneration, event.stream, event.epoch,
                event.eventSequence, event.receivedWallMs, event.receivedElapsedMs
            ),
            sourcePollId = null
        )
    }

    private fun epochMillis(value: String): Long = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrElse {
        Instant.parse(value).toEpochMilli()
    }

    private fun emptyNormalizedSummary(): NormalizedWriteSummary = NormalizedWriteSummary(0, 0, 0)

    private operator fun NormalizedWriteSummary.plus(other: NormalizedWriteSummary): NormalizedWriteSummary =
        NormalizedWriteSummary(
            observedCount = observedCount + other.observedCount,
            changedCount = changedCount + other.changedCount,
            historyInsertedCount = historyInsertedCount + other.historyInsertedCount,
            currentInsertedCount = currentInsertedCount + other.currentInsertedCount,
            changedCategories = changedCategories + other.changedCategories
        )

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
        synchronized(pollHealthLock) { pollHealthCache = PollHealthCache.empty(sessionId) }
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
            synchronized(pollHealthLock) { pollHealthCache = null }
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
        val updated = helper.writableDatabase.update(
            "collection_sessions",
            ContentValues().apply {
                put("ended_at", clock.nowIso())
                put("import_quality", reason)
            },
            "id = ?",
            arrayOf(sessionId.toString())
        )
        if (updated > 0) {
            synchronized(pollHealthLock) {
                if (pollHealthCache?.snapshot?.activeSessionId == sessionId) pollHealthCache = null
            }
        }
        recordEvent("service_stop", "Collection service stopped", "session_id=$sessionId reason=$reason")
    }

    override fun insertPoll(
        sessionId: Long,
        input: PersistedPollInput,
        parameters: List<CatalogParameter>
    ): Long {
        val db = helper.writableDatabase
        ensurePollValueColumns(db, parameters)
        var inserted: PollInsertResult? = null
        //writes the poll header and wide raw values atomically so dashboard counts never see half a poll
        db.beginTransaction()
        try {
            inserted = insertPollInTransaction(db, sessionId, input, parameters)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val committed = checkNotNull(inserted)
        decodedValueIds.putAll(committed.decodedIds)
        recordCommittedPollHealth(sessionId, committed.pollId, input)
        return committed.pollId
    }

    override fun insertWorkerPoll(
        sessionId: Long,
        identity: TelemetryWorkerSampleIdentity,
        input: PersistedPollInput,
        parameters: List<CatalogParameter>
    ): WorkerPollImportResult {
        val db = helper.writableDatabase
        ensurePollValueColumns(db, parameters)
        var result: WorkerPollImportResult? = null
        var decodedIdsToCache: Map<String, Long> = emptyMap()
        db.beginTransaction()
        try {
            val existingPollId = workerPollId(db, identity)
            if (existingPollId != null) {
                result = WorkerPollImportResult(existingPollId, inserted = false)
            } else {
                val inserted = insertPollInTransaction(db, sessionId, input, parameters)
                db.insertOrThrow(
                    "telemetry_worker_imports",
                    null,
                    ContentValues().apply {
                        put("boot_id", identity.bootId)
                        put("helper_generation", identity.helperGeneration)
                        put("poll_sequence", identity.pollSequence)
                        put("poll_id", inserted.pollId)
                        put("imported_at", clock.nowIso())
                    }
                )
                decodedIdsToCache = inserted.decodedIds
                result = WorkerPollImportResult(inserted.pollId, inserted = true)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        decodedValueIds.putAll(decodedIdsToCache)
        val committed = checkNotNull(result)
        if (committed.inserted) recordCommittedPollHealth(sessionId, committed.pollId, input)
        return committed
    }

    fun recordEvent(category: String, message: String) {
        recordEvent(category, message, null)
    }

    override fun recordEvent(category: String, message: String, detail: String?) {
        if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
            com.bydcollector.collector.runtime.RuntimeEndpoint.call(context, "event", android.os.Bundle().apply {
                putString("category", category); putString("message", message); putString("detail", detail)
            })
            return
        }
        val timestamp = clock.nowIso()
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
            operationalEventJournal.append(
                timestamp = timestamp,
                elapsedMs = clock.elapsedRealtimeMs(),
                category = category,
                message = message,
                detail = detail
            )
        } catch (error: Exception) {
            Log.w(TAG, "operational journal write failed: $logLine", error)
        }
        if (OperationalEventJournal.isMaintenanceEvent(category, message)) {
            val elapsed = clock.elapsedRealtimeMs()
            dispatchOperationalEvent(sharedOperationalEventExecutor) {
                try {
                    (context.applicationContext as? com.bydcollector.collector.BydCollectorApplication)
                        ?.maintenanceEventJournal?.append(timestamp, elapsed, category, message, detail)
                } catch (error: Exception) {
                    Log.w(TAG, "maintenance journal write failed: $logLine", error)
                }
            }
        }
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            synchronized(this) {
                // Closing/archive and an already queued diagnostic must not reopen the old file.
                if (!closed.get()) recordEventInDatabase(timestamp, category, message, detail, logLine)
            }
        }
    }

    private fun recordEventInDatabase(
        timestamp: String,
        category: String,
        message: String,
        detail: String?,
        logLine: String
    ) {
        try {
            val db = helper.writableDatabase
            //stores only recent operational events so diagnostics stay useful without growing unbounded
            db.insert(
                "collector_events",
                null,
                ContentValues().apply {
                    put("ts", timestamp)
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

    override fun upsertPending(message: HaMqttMessage, targetType: String, priority: Int, destinationKey: String?) {
        val now = clock.nowIso()
        //keeps only the latest retained payload per topic because ha needs current state more than old retries
        val values = ContentValues().apply {
            put("target_type", targetType)
            put("topic", message.topic)
            put("payload", message.payload)
            put("payload_hash", sha256(message.payload))
            if (destinationKey == null) putNull("destination_key") else put("destination_key", destinationKey)
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
            SELECT target_key, target_type, topic, payload, payload_hash, retained, qos, priority, attempt_count, destination_key
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
                            attemptCount = cursor.getInt(8),
                            destinationKey = cursor.getString(9)
                        )
                    )
                }
            }
        }
    }

    override fun isCurrent(targetKey: String, payloadHash: String, destinationKey: String?): Boolean {
        val (where, args) = mqttOutboxIdentity(targetKey, payloadHash, destinationKey)
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM mqtt_outbox WHERE $where LIMIT 1",
            args
        ).use { return it.moveToFirst() }
    }

    override fun discardPending(targetKey: String, payloadHash: String, destinationKey: String?): Boolean {
        val (where, args) = mqttOutboxIdentity(targetKey, payloadHash, destinationKey)
        return helper.writableDatabase.delete("mqtt_outbox", where, args) == 1
    }

    override fun markAttempt(targetKey: String, payloadHash: String, attemptedAt: String, destinationKey: String?): Boolean {
        val (where, args) = mqttOutboxIdentity(targetKey, payloadHash, destinationKey)
        return helper.writableDatabase.update(
            "mqtt_outbox",
            ContentValues().apply {
                put("last_attempt_at", attemptedAt)
                put("updated_at", attemptedAt)
            },
            where,
            args
        ) == 1
    }

    override fun markFailed(
        targetKey: String,
        payloadHash: String,
        error: String,
        failedAt: String,
        nextAttemptAt: String?,
        destinationKey: String?
    ): Boolean {
        val (where, args) = mqttOutboxIdentity(targetKey, payloadHash, destinationKey)
        val db = helper.writableDatabase
        db.beginTransaction()
        val updateResult = try {
            val existing = db.rawQuery(
                "SELECT target_type, attempt_count FROM mqtt_outbox WHERE $where LIMIT 1",
                args
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to (cursor.getInt(1) + 1) else null
            }
            if (existing == null) null else {
                val count = db.update(
                    "mqtt_outbox",
                    ContentValues().apply {
                        put("attempt_count", existing.second)
                        put("last_error", error.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                        put("last_error_at", failedAt)
                        put("last_attempt_at", failedAt)
                        put("updated_at", failedAt)
                        if (nextAttemptAt == null) putNull("next_attempt_at") else put("next_attempt_at", nextAttemptAt)
                    },
                    where,
                    args
                )
                if (count == 1) {
                    db.setTransactionSuccessful()
                    existing.first
                } else null
            }
        } finally {
            db.endTransaction()
        }
        val targetType = updateResult ?: return false
        recordMqttPublishError(
            targetKey = targetKey,
            targetType = targetType,
            error = error,
            errorAt = failedAt
        )
        return true
    }

    override fun markPublished(targetKey: String, payloadHash: String, publishedAt: String, destinationKey: String?): Boolean {
        val (where, args) = mqttOutboxIdentity(targetKey, payloadHash, destinationKey)
        val db = helper.writableDatabase
        db.beginTransaction()
        val targetType: String?
        val deleted: Int
        try {
            targetType = db.rawQuery("SELECT target_type FROM mqtt_outbox WHERE $where LIMIT 1", args).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            deleted = if (targetType == null) 0 else db.delete("mqtt_outbox", where, args)
            if (deleted == 1) {
                recordMqttPublishSuccess(
                    targetKey = targetKey,
                    targetType = checkNotNull(targetType),
                    payloadHash = payloadHash,
                    publishedAt = publishedAt
                )
                db.setTransactionSuccessful()
            }
        } finally {
            db.endTransaction()
        }
        return deleted == 1
    }

    private fun mqttOutboxIdentity(targetKey: String, payloadHash: String, destinationKey: String?): Pair<String, Array<String>> {
        return if (destinationKey == null) {
            "target_key = ? AND payload_hash = ? AND destination_key IS NULL" to arrayOf(targetKey, payloadHash)
        } else {
            "target_key = ? AND payload_hash = ? AND destination_key = ?" to arrayOf(targetKey, payloadHash, destinationKey)
        }
    }

    override fun pendingCount(): Long = scalarLong("SELECT COUNT(*) FROM mqtt_outbox")

    /** Cancellable owner-side count that propagates SQLite failures instead of reporting false zero. */
    internal fun refreshMqttPendingCount(cancellationSignal: CancellationSignal?): Long {
        val db = helper.readableDatabase
        val cursor = if (cancellationSignal == null) {
            db.rawQuery("SELECT COUNT(*) FROM mqtt_outbox", emptyArray())
        } else {
            db.rawQuery("SELECT COUNT(*) FROM mqtt_outbox", emptyArray(), cancellationSignal)
        }
        cursor.use {
            check(it.moveToFirst()) { "MQTT outbox count returned no row" }
            return it.getLong(0)
        }
    }

    /**
     * Reads both pre-sidecar Telegram tables under one Main read transaction.
     * The extra row detects an outbox that exceeded the established 1,000-row bound
     * without allowing an unbounded migration read.
     */
    fun readLegacyTelegramSnapshot(limit: Int = TELEGRAM_MAX_PENDING.toInt()): TelegramLegacySnapshot {
        require(limit in 1..TELEGRAM_MAX_PENDING.toInt()) { "Telegram legacy snapshot limit must be 1..$TELEGRAM_MAX_PENDING" }
        val db = helper.writableDatabase
        var snapshot: TelegramLegacySnapshot? = null
        var readError: String? = null
        db.beginTransaction()
        try {
            val outbox = mutableListOf<TelegramLegacyOutboxRow>()
            var truncated = false
            if (mainTableExists(db, "telegram_outbox")) {
                db.rawQuery(
                    """
                    SELECT id, dedupe_key, event_type, payload, created_at_ms,
                           next_attempt_at_ms, attempt_count, last_attempt_at_ms,
                           last_error, blocked
                    FROM telegram_outbox
                    ORDER BY id
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf((limit + 1).toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (outbox.size == limit) {
                            truncated = true
                            continue
                        }
                        outbox += TelegramLegacyOutboxRow(
                            id = cursor.getLong(0),
                            dedupeKey = cursor.getString(1),
                            eventType = cursor.getString(2),
                            payload = cursor.getString(3),
                            createdAtMs = cursor.getLong(4),
                            nextAttemptAtMs = cursor.getLong(5),
                            attemptCount = cursor.getInt(6),
                            lastAttemptAtMs = if (cursor.isNull(7)) null else cursor.getLong(7),
                            lastError = if (cursor.isNull(8)) null else cursor.getString(8),
                            blocked = cursor.getInt(9) != 0,
                            blockedCode = cursor.getInt(9)
                        )
                    }
                }
            }

            var runtimeStateJson: String? = null
            var runtimeStateUpdatedAtMs: Long? = null
            var runtimeStateRowPresent = false
            if (mainTableExists(db, "telegram_runtime_state")) {
                db.rawQuery(
                    "SELECT state_json, updated_at_ms FROM telegram_runtime_state WHERE id = 1 LIMIT 1",
                    emptyArray()
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        runtimeStateRowPresent = true
                        runtimeStateJson = if (cursor.isNull(0)) null else cursor.getString(0)
                        runtimeStateUpdatedAtMs = if (cursor.isNull(1)) null else cursor.getLong(1)
                    }
                }
            }
            val runtimeStateValid = !runtimeStateRowPresent ||
                (runtimeStateJson?.isNotBlank() == true &&
                    runtimeStateUpdatedAtMs != null &&
                    TelegramEventState.fromJsonOrNull(runtimeStateJson) != null)
            snapshot = TelegramLegacySnapshot(
                outbox = outbox,
                runtimeStateJson = runtimeStateJson,
                runtimeStateUpdatedAtMs = runtimeStateUpdatedAtMs,
                runtimeStatePresent = runtimeStateRowPresent,
                runtimeStateValid = runtimeStateValid,
                truncated = truncated
            )
            db.setTransactionSuccessful()
        } catch (error: RuntimeException) {
            readError = error.message ?: error::class.java.simpleName
        } finally {
            db.endTransaction()
        }
        return snapshot ?: TelegramLegacySnapshot(readError = readError ?: "Legacy Telegram snapshot failed")
    }

    /**
     * Deletes only the captured legacy rows/state, and only after the caller has
     * verified the sidecar transaction.  A false verification is a no-op.
     */
    fun cleanupLegacyTelegramStorage(
        snapshot: TelegramLegacySnapshot,
        sidecarCommitVerified: Boolean
    ): Boolean {
        if (!sidecarCommitVerified || !snapshot.validForImport) return false
        val db = helper.writableDatabase
        return runCatching {
            db.beginTransaction()
            try {
                if (mainTableExists(db, "telegram_outbox")) {
                    // Refuse to remove a row that changed after the snapshot. Deleting
                    // by id alone could erase a newer event that reused that id.
                    if (snapshot.outbox.any { !legacyOutboxRowMatches(db, it) }) return@runCatching false
                    snapshot.outbox.forEach { db.delete("telegram_outbox", "id = ?", arrayOf(it.id.toString())) }
                    if (snapshot.outbox.any { legacyOutboxIdExists(db, it.id) }) return@runCatching false
                    if (legacyTableRowCount(db, "telegram_outbox") != 0L) return@runCatching false
                }
                if (snapshot.runtimeStateJson != null && mainTableExists(db, "telegram_runtime_state")) {
                    if (!legacyRuntimeStateMatches(db, snapshot)) return@runCatching false
                    db.delete(
                        "telegram_runtime_state",
                        "id = 1 AND state_json = ? AND updated_at_ms = ?",
                        arrayOf(snapshot.runtimeStateJson, (snapshot.runtimeStateUpdatedAtMs ?: Long.MIN_VALUE).toString())
                    )
                    if (legacyRuntimeStateExists(db)) return@runCatching false
                }
                if (
                    snapshot.runtimeStateJson == null &&
                    mainTableExists(db, "telegram_runtime_state") &&
                    legacyTableRowCount(db, "telegram_runtime_state") != 0L
                ) return@runCatching false
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        }.getOrDefault(false)
    }

    fun cleanupLegacyTelegramStorage(
        snapshot: TelegramLegacySnapshot,
        migration: TelegramMigrationResult
    ): Boolean = cleanupLegacyTelegramStorage(snapshot, migration.cleanupVerified)

    private fun mainTableExists(db: SQLiteDatabase, tableName: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(tableName)
    ).use { cursor -> cursor.moveToFirst() }

    private fun legacyOutboxRowMatches(db: SQLiteDatabase, row: TelegramLegacyOutboxRow): Boolean = db.rawQuery(
        """
        SELECT dedupe_key, event_type, payload, created_at_ms, next_attempt_at_ms,
               attempt_count, last_attempt_at_ms, last_error, blocked
        FROM telegram_outbox WHERE id = ?
        """.trimIndent(),
        arrayOf(row.id.toString())
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use true
        cursor.getString(0) == row.dedupeKey && cursor.getString(1) == row.eventType &&
            cursor.getString(2) == row.payload && cursor.getLong(3) == row.createdAtMs &&
            cursor.getLong(4) == row.nextAttemptAtMs && cursor.getInt(5) == row.attemptCount &&
            (if (cursor.isNull(6)) null else cursor.getLong(6)) == row.lastAttemptAtMs &&
            (if (cursor.isNull(7)) null else cursor.getString(7)) == row.lastError &&
            cursor.getInt(8) == row.blockedCode
    }

    private fun legacyOutboxIdExists(db: SQLiteDatabase, id: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM telegram_outbox WHERE id = ? LIMIT 1", arrayOf(id.toString())
    ).use { cursor -> cursor.moveToFirst() }

    private fun legacyRuntimeStateMatches(db: SQLiteDatabase, snapshot: TelegramLegacySnapshot): Boolean =
        db.rawQuery(
            "SELECT state_json, updated_at_ms FROM telegram_runtime_state WHERE id = 1",
            emptyArray()
        ).use { cursor ->
            !cursor.moveToFirst() || (
                cursor.getString(0) == snapshot.runtimeStateJson &&
                    cursor.getLong(1) == (snapshot.runtimeStateUpdatedAtMs ?: Long.MIN_VALUE)
                )
        }

    private fun legacyRuntimeStateExists(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT 1 FROM telegram_runtime_state WHERE id = 1 LIMIT 1", emptyArray()
    ).use { cursor -> cursor.moveToFirst() }

    private fun legacyTableRowCount(db: SQLiteDatabase, tableName: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $tableName", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }

    override fun ensureInfluxCursors(fieldKeys: Set<String>) {
        //creates one cursor per normalized field so enabling categories later does not reset old exports
        influxCursorInitializer.ensure(fieldKeys) { fieldKey ->
            val db = helper.writableDatabase
            val inserted = db.insertWithOnConflict(
                "influx_export_cursor",
                null,
                ContentValues().apply {
                    put("field_key", fieldKey)
                    put("last_exported_history_id", 0)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (inserted == -1L) {
                // IGNORE can mean an existing row; never cache an unconfirmed failed insert.
                val exists = db.rawQuery(
                    "SELECT 1 FROM influx_export_cursor WHERE field_key = ? LIMIT 1",
                    arrayOf(fieldKey)
                ).use { it.moveToFirst() }
                check(exists) { "Cannot initialize Influx cursor for $fieldKey" }
            }
        }
    }

    override fun pendingInfluxSummary(fieldKeys: Set<String>): InfluxPendingSummary {
        if (fieldKeys.isEmpty()) return InfluxPendingSummary(rows = 0, oldestObservedAt = null, complete = true)
        ensureInfluxCursors(fieldKeys)
        return influxPendingSummaryReader.summary(helper.readableDatabase, compactV2, fieldKeys)
    }

    internal fun refreshInfluxPendingSummary(
        fieldKeys: Set<String>,
        cancellationSignal: CancellationSignal? = null
    ): InfluxPendingSummary {
        if (fieldKeys.isEmpty()) return InfluxPendingSummary(rows = 0, oldestObservedAt = null, complete = true)
        ensureInfluxCursors(fieldKeys)
        return influxPendingSummaryReader.refresh(
            helper.readableDatabase,
            compactV2,
            fieldKeys,
            cancellationSignal
        )
    }

    override fun pendingInfluxRows(
        fieldKeys: Set<String>,
        limit: Int
    ): List<InfluxPendingHistoryRow> {
        if (fieldKeys.isEmpty() || limit <= 0) return emptyList()
        ensureInfluxCursors(fieldKeys)
        val placeholders = fieldKeys.joinToString(",") { "?" }
        val args = fieldKeys.toList() + limit.toString()
        val sql = if (compactV2) {
            """
            SELECT history.id, field.field_key, field.category, field.value_type,
                   history.value_text, history.value_number, history.value_bool,
                   history.quality_code, NULLIF(field.unit, ''), history.source_poll_id,
                   field.source_keys, history.observed_at_ms, history.changed_at_ms, history.quality_detail
            FROM vehicle_state_history AS history
            INNER JOIN normalized_history_field_catalog AS field ON field.id = history.field_id
            INNER JOIN influx_export_cursor AS export_cursor
                    ON export_cursor.field_key = field.field_key
            WHERE field.field_key IN ($placeholders)
              AND history.id > export_cursor.last_exported_history_id
            ORDER BY history.id
            LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT history.id, history.field_key, history.category, history.value_type,
                   history.value_text, history.value_number, history.value_bool,
                   history.quality, history.unit, history.source_poll_id,
                   history.source_keys, history.observed_at, history.changed_at, history.quality_detail
            FROM vehicle_state_history AS history
            INNER JOIN influx_export_cursor AS export_cursor
                    ON export_cursor.field_key = history.field_key
            WHERE history.field_key IN ($placeholders)
              AND history.id > export_cursor.last_exported_history_id
            ORDER BY history.id
            LIMIT ?
            """.trimIndent()
        }
        helper.readableDatabase.rawQuery(
            sql,
            args.toTypedArray()
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
                            quality = if (compactV2) {
                                NormalizedQuality.fromStorageCode(cursor.getInt(7)).name
                            } else {
                                cursor.getString(7)
                            },
                            unit = cursor.getNullableString(8),
                            sourcePollId = if (cursor.isNull(9)) null else cursor.getLong(9),
                            sourceKeys = cursor.getString(10),
                            observedAt = if (compactV2) normalizedIsoTime(cursor.getLong(11)) else cursor.getString(11),
                            changedAt = if (compactV2) normalizedIsoTime(cursor.getLong(12)) else cursor.getString(12),
                            qualityDetail = cursor.getNullableString(13)
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

    override fun skipInfluxRow(fieldKey: String, historyId: Long, reason: String, skippedAt: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("influx_export_events", null, ContentValues().apply {
                put("ts", skippedAt)
                put("event_type", "influx_export_rejected_row")
                put("message", "field=$fieldKey reason=$reason")
                put("batch_count", 1)
                put("from_history_id", historyId)
                put("to_history_id", historyId)
            })
            updateInfluxCursorSuccess(fieldKey, historyId, skippedAt)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    private data class RecentEventsHealthRead(
        val events: List<CollectorEvent>,
        val availability: HealthReadAvailability
    )

    fun healthSnapshot(
        running: Boolean,
        detail: HealthSnapshotDetail = HealthSnapshotDetail.FULL,
        includeCounts: Boolean = detail == HealthSnapshotDetail.FULL,
        includePollHealth: Boolean = true
    ): HealthSnapshot {
        val includeIntegrations = detail != HealthSnapshotDetail.SUMMARY
        val includeFullDetails = detail == HealthSnapshotDetail.FULL
        val healthReadErrors = mutableListOf<HealthReadError>()
        val mqttRetryState = if (includeIntegrations) {
            safeMqttRetryState(healthReadErrors)
        } else {
            MqttRetryState(
                failureCount = UNKNOWN_COUNT_INT,
                nextAttemptAt = null,
                lastFailureAt = null,
                lastSuccessAt = null,
                lastError = null
            )
        }
        val recentEventsRead = if (includeFullDetails) {
            safeRecentEvents(healthReadErrors)
        } else {
            RecentEventsHealthRead(emptyList(), HealthReadAvailability.NOT_REQUESTED)
        }
        val pollHealth = if (includePollHealth) {
            currentPollHealth(running)
        } else {
            PollHealthCache.empty(null).snapshot
        }
        //Failed health reads are explicit unknowns; full diagnostic details go to the independent journal.
        return HealthSnapshot(
            running = running,
            activeSessionId = pollHealth.activeSessionId,
            lastSuccessAt = pollHealth.lastSuccessAt,
            lastError = pollHealth.lastError,
            lastErrorAt = pollHealth.lastErrorAt,
            lastPollStatus = pollHealth.lastPollStatus,
            pollCount = if (includeCounts) safeHealthCount("poll_count", "SELECT COUNT(*) FROM polls", healthReadErrors) else UNKNOWN_COUNT,
            valueRowCount = if (includeCounts) {
                safeHealthCount(
                    "value_row_count",
                    "SELECT (SELECT COUNT(*) FROM poll_values) + (SELECT COUNT(*) FROM raw_callback_events)",
                    healthReadErrors
                )
            } else {
                UNKNOWN_COUNT
            },
            ecRowCount = if (includeCounts) safeHealthCount("ec_row_count", "SELECT COUNT(*) FROM ec_energy_consumption", healthReadErrors) else UNKNOWN_COUNT,
            normalizedCurrentCount = if (includeCounts) safeHealthCount("normalized_current_count", "SELECT COUNT(*) FROM vehicle_state_current", healthReadErrors) else UNKNOWN_COUNT,
            normalizedHistoryCount = if (includeCounts) safeHealthCount("normalized_history_count", "SELECT COUNT(*) FROM vehicle_state_history", healthReadErrors) else UNKNOWN_COUNT,
            mqttLastError = if (includeIntegrations) safeScalarString("SELECT last_error FROM mqtt_publish_state WHERE last_error IS NOT NULL ORDER BY last_error_at DESC, updated_at DESC, id DESC LIMIT 1") else null,
            mqttLastPublishedAt = if (includeIntegrations) safeScalarString("SELECT last_published_at FROM mqtt_publish_state WHERE last_published_at IS NOT NULL ORDER BY last_published_at DESC, id DESC LIMIT 1") else null,
            mqttPendingCount = if (includeIntegrations) safeMqttPendingCount(healthReadErrors) else UNKNOWN_COUNT,
            mqttRetryFailureCount = mqttRetryState.failureCount,
            mqttNextRetryAt = mqttRetryState.nextAttemptAt,
            mqttRetryLastFailureAt = mqttRetryState.lastFailureAt,
            mqttRetryLastSuccessAt = mqttRetryState.lastSuccessAt,
            lastEcImport = if (includeFullDetails) safeScalarString("SELECT ts FROM ec_import_runs ORDER BY id DESC LIMIT 1") else null,
            lastEcImportStatus = if (includeFullDetails) safeLastEcImportStatus() else null,
            elapsedMs = if (includeFullDetails) pollHealth.elapsedMs else null,
            requestCount = if (includeFullDetails) pollHealth.requestCount else null,
            databasePath = databaseFile().absolutePath,
            databaseSizeBytes = sqliteFootprintBytes(databaseFile()),
            latestSoc = if (includeFullDetails) safeLatestReading(listOf("statistic_1014_1145045040_5", "statistic_1014_1134559272_5", "SOC", "soc")) else null,
            latestSpeed = if (includeFullDetails) safeLatestReading(listOf("speed_1013_-1807745016_7", "Speed", "speed")) else null,
            latestCharging = if (includeFullDetails) safeLatestReading(listOf("charging_charge_current", "ChargingStatus", "chargeGunState")) else null,
            recentEvents = recentEventsRead.events,
            recentEventsAvailability = recentEventsRead.availability,
            healthReadErrors = healthReadErrors.toList()
        )
    }

    internal fun currentPollHealth(running: Boolean): CurrentPollHealthSnapshot {
        if (!running) return PollHealthCache.empty(null).snapshot

        synchronized(pollHealthLock) {
            val sessionId = try {
                activeSessionId()
            } catch (error: Throwable) {
                Log.w(TAG, "active poll session query failed", error)
                return unavailablePollHealth(null)
            } ?: return PollHealthCache.empty(null).snapshot

            pollHealthCache?.takeIf { it.snapshot.activeSessionId == sessionId }?.let { return it.snapshot }

            val restored = try {
                restorePollHealth(sessionId)
            } catch (error: Throwable) {
                Log.w(TAG, "active poll health restore failed", error)
                null
            } ?: return unavailablePollHealth(sessionId)

            val stillActiveSessionId = try {
                activeSessionId()
            } catch (error: Throwable) {
                Log.w(TAG, "active poll session recheck failed", error)
                return unavailablePollHealth(sessionId)
            }
            if (stillActiveSessionId != sessionId) {
                return if (stillActiveSessionId == null) {
                    PollHealthCache.empty(null).snapshot
                } else {
                    unavailablePollHealth(stillActiveSessionId)
                }
            }

            pollHealthCache = restored
            return restored.snapshot
        }
    }

    private fun restorePollHealth(sessionId: Long): PollHealthCache {
        val rows = helper.readableDatabase.rawQuery(
            """
            SELECT id, session_id, ts, ok, errors, error_category, error_message, elapsed_ms, request_count
            FROM polls
            ORDER BY id DESC
            LIMIT $POLL_HEALTH_RECENT_PAGE_LIMIT
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            var globalRowsScanned = 0
            val filteredRows = buildList {
                while (cursor.moveToNext()) {
                    globalRowsScanned += 1
                    if (cursor.getLong(1) == sessionId) {
                        add(
                            PollHealthRow(
                                pollId = cursor.getLong(0),
                                timestamp = cursor.getString(2),
                                ok = if (cursor.isNull(3)) null else cursor.getInt(3) == 1,
                                errors = cursor.getNullableString(4),
                                errorCategory = cursor.getNullableString(5),
                                errorMessage = cursor.getNullableString(6),
                                elapsedMs = if (cursor.isNull(7)) null else cursor.getLong(7),
                                requestCount = if (cursor.isNull(8)) null else cursor.getInt(8)
                            )
                        )
                    }
                }
            }
            filteredRows to (globalRowsScanned >= POLL_HEALTH_RECENT_PAGE_LIMIT)
        }
        return PollHealthCache.fromRecentPage(
            sessionId,
            rows.first,
            POLL_HEALTH_RECENT_PAGE_LIMIT,
            globalPageFilled = rows.second
        )
    }

    private fun recordCommittedPollHealth(sessionId: Long, pollId: Long, input: PersistedPollInput) {
        val row = PollHealthRow(
            pollId = pollId,
            timestamp = input.timestamp,
            ok = input.ok,
            errors = input.errors.truncateForStorage(MAX_ERROR_TEXT_LENGTH),
            errorCategory = input.errorCategory,
            errorMessage = input.errorMessage.truncateForStorage(MAX_ERROR_TEXT_LENGTH),
            elapsedMs = input.elapsedMs,
            requestCount = input.requestCount
        )
        synchronized(pollHealthLock) {
            val current = pollHealthCache?.takeIf { it.snapshot.activeSessionId == sessionId }
                ?: runCatching { restorePollHealth(sessionId) }
                    .onFailure { error -> Log.w(TAG, "post-commit poll health restore failed", error) }
                    .getOrNull()
                ?: return
            pollHealthCache = current.merge(row)
        }
    }

    private fun unavailablePollHealth(sessionId: Long?): CurrentPollHealthSnapshot =
        PollHealthCache.empty(sessionId).snapshot.copy(
            historyMayBeIncomplete = true,
            available = false
        )

    private fun safeHealthCount(
        field: String,
        sql: String,
        errors: MutableList<HealthReadError>
    ): Long = readHealthValue(field, UNKNOWN_COUNT, errors) { scalarLong(sql) }

    private fun safeMqttPendingCount(errors: MutableList<HealthReadError>): Long =
        readHealthValue("mqtt_pending_count", UNKNOWN_COUNT, errors) { pendingCount() }

    private fun safeMqttRetryState(errors: MutableList<HealthReadError>): MqttRetryState = readHealthValue(
        field = "mqtt_retry_state",
        fallback = MqttRetryState(
            failureCount = UNKNOWN_COUNT_INT,
            nextAttemptAt = null,
            lastFailureAt = null,
            lastSuccessAt = null,
            lastError = null
        ),
        errors = errors
    ) { retryState() }

    private inline fun <T> readHealthValue(
        field: String,
        fallback: T,
        errors: MutableList<HealthReadError>,
        read: () -> T
    ): T {
        return try {
            read().also { clearHealthReadFailure(field) }
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Exception) {
            errors += healthReadError(field, error)
            fallback
        }
    }

    private fun safeRecentEvents(errors: MutableList<HealthReadError>): RecentEventsHealthRead {
        val readErrorsBefore = errors.size
        val events = readHealthValue("recent_events", emptyList(), errors) { recentEvents() }
        val readFailed = errors.size != readErrorsBefore
        return RecentEventsHealthRead(
            events = events,
            availability = if (readFailed) HealthReadAvailability.ERROR else HealthReadAvailability.AVAILABLE
        )
    }

    private fun healthReadError(field: String, error: Exception): HealthReadError {
        val detail = error.diagnosticDetail("stage=dashboard_health_read field=$field")
        val fingerprint = healthErrorFingerprint(error)
        val shouldJournal = synchronized(healthReadFailureLock) {
            if (healthReadFailureSignatures[field] == fingerprint) {
                false
            } else {
                healthReadFailureSignatures[field] = fingerprint
                true
            }
        }
        if (shouldJournal) enqueueHealthReadDiagnostic(field, error, detail)

        val message = runCatching {
            DiagnosticShareSanitizer().sanitizeText(error.message.orEmpty())
        }.getOrElse { "[message withheld; sanitization failed]" }
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .let(::truncateHealthErrorMessage)
        return HealthReadError(
            field = field,
            exceptionType = error.javaClass.name,
            message = message.ifBlank { "[no message]" }
        )
    }

    private fun healthErrorFingerprint(error: Throwable): String {
        val sanitizer = DiagnosticShareSanitizer()
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var visitedCount = 0
        val signature = buildString {
            fun appendError(label: String, current: Throwable, depth: Int) {
                if (depth > MAX_HEALTH_ERROR_CHAIN_DEPTH || !visited.add(current)) return
                visitedCount += 1
                if (visitedCount > MAX_HEALTH_ERROR_CHAIN_NODES) {
                    append("chain_truncated")
                    return
                }
                val safeMessage = runCatching {
                    sanitizer.sanitizeText(current.message.orEmpty())
                }.getOrElse { "[message withheld]" }
                val boundedMessage = if (safeMessage.length > MAX_HEALTH_FINGERPRINT_MESSAGE_CHARS) {
                    safeMessage.take(MAX_HEALTH_FINGERPRINT_MESSAGE_CHARS) + "[TRUNCATED]"
                } else {
                    safeMessage
                }
                append(label).append('|')
                    .append(current.javaClass.name).append('|')
                    .append(boundedMessage.length).append(':').append(boundedMessage).append('\n')
                current.suppressed.forEachIndexed { index, suppressed ->
                    appendError("suppressed[$index]", suppressed, depth + 1)
                }
                current.cause?.let { appendError("cause", it, depth + 1) }
            }
            appendError("root", error, 0)
        }
        return diagnosticSha256(signature)
    }

    private fun enqueueHealthReadDiagnostic(field: String, originalError: Exception, detail: String) {
        val message = "Dashboard health read failed: $field"
        try {
            sharedOperationalEventExecutor.execute {
                val appendResult = runCatching {
                    operationalEventJournal.tryAppend(
                        Instant.now().toString(),
                        SystemClock.elapsedRealtime(),
                        "database_health_read_error",
                        message,
                        detail
                    )
                }
                if (appendResult.getOrDefault(false)) return@execute
                val journalError = appendResult.exceptionOrNull()
                if (journalError != null && journalError !== originalError) originalError.addSuppressed(journalError)
                val writeDetail = listOfNotNull(
                    detail,
                    journalError?.diagnosticDetail("stage=dashboard_health_journal_write field=$field")
                        ?: "[journal append unavailable]"
                ).joinToString("\n")
                runCatching { Log.e(TAG, "Could not persist dashboard health read error; original_detail=$writeDetail") }
            }
        } catch (dispatchError: RuntimeException) {
            if (dispatchError !== originalError) originalError.addSuppressed(dispatchError)
            val dispatchDetail = listOf(
                detail,
                dispatchError.diagnosticDetail("stage=dashboard_health_journal_dispatch field=$field")
            ).joinToString("\n")
            runCatching { Log.e(TAG, "Could not queue dashboard health read error; original_detail=$dispatchDetail") }
        }
    }

    private fun clearHealthReadFailure(field: String) {
        synchronized(healthReadFailureLock) { healthReadFailureSignatures.remove(field) }
    }

    private fun truncateHealthErrorMessage(message: String): String {
        if (message.length <= MAX_HEALTH_ERROR_MESSAGE_CHARS) return message
        val marker = " [TRUNCATED; limit=$MAX_HEALTH_ERROR_MESSAGE_CHARS chars]"
        return message.take(MAX_HEALTH_ERROR_MESSAGE_CHARS - marker.length) + marker
    }

    fun databaseFile(): File = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)

    /** Read-only active-Main access for bounded historical reconstruction; never uses polls.elapsed_ms as time. */
    internal fun historicalPollHeaderPage(afterPollId: Long, limit: Int): HistoricalPollHeaderPage {
        require(afterPollId >= 0L && limit in 1..1024)
        val before = historicalMainIdentity()
        val polls = helper.readableDatabase.rawQuery(
            "SELECT id, session_id, ts FROM polls WHERE id > ? ORDER BY id LIMIT $limit",
            arrayOf(afterPollId.toString())
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HistoricalPollEndpoint(cursor.getLong(0), cursor.getLong(1), cursor.getString(2))) }
        }
        check(before == historicalMainIdentity()) { "active_main_replaced" }
        return HistoricalPollHeaderPage(before, polls)
    }

    internal fun historicalEnergyPollPage(
        firstPollId: Long,
        lastPollId: Long,
        afterPollId: Long,
        limit: Int
    ): HistoricalEnergyPollPage {
        require(firstPollId > 0L && lastPollId >= firstPollId && afterPollId < lastPollId && limit in 1..256)
        val db = helper.readableDatabase
        val before = historicalMainIdentity()
        val columns = tableColumns(db, "poll_values")
        val keys = HISTORICAL_ENERGY_KEYS.filter { PollValueColumns.raw(it) in columns }
        require(HISTORICAL_REQUIRED_ENERGY_KEYS.all { PollValueColumns.raw(it) in columns }) {
            "historical_energy_schema_missing"
        }
        val compact = keys.any { PollValueColumns.descId(it) in columns }
        if (compact) require(tableColumns(db, "decoded_value_dictionary").containsAll(setOf("id", "value"))) {
            "historical_energy_dictionary_missing"
        }
        val valuesSql = keys.joinToString(separator = "") { key ->
            val raw = quoteIdentifier(PollValueColumns.raw(key))
            val decoded = when {
                PollValueColumns.descId(key) in columns ->
                    "(SELECT d.value FROM decoded_value_dictionary d WHERE d.id = v.${quoteIdentifier(PollValueColumns.descId(key))})"
                PollValueColumns.desc(key) in columns -> "v.${quoteIdentifier(PollValueColumns.desc(key))}"
                else -> "NULL"
            }
            ", v.$raw, $decoded"
        }
        val sql = "SELECT p.id, p.session_id, p.ts, p.ok$valuesSql FROM polls p " +
            "LEFT JOIN poll_values v ON v.poll_id = p.id WHERE p.id > ? AND p.id >= ? AND p.id <= ? ORDER BY p.id LIMIT $limit"
        val polls = db.rawQuery(sql, arrayOf(afterPollId.toString(), firstPollId.toString(), lastPollId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    var index = 4
                    val readings = keys.map { key ->
                        PollReading(
                            rawKey = key,
                            rawValue = if (cursor.isNull(index)) null else cursor.getString(index),
                            descValue = if (cursor.isNull(index + 1)) null else cursor.getString(index + 1)
                        ).also { index += 2 }
                    }
                    add(HistoricalEnergyPoll(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getInt(3) == 1, readings))
                }
            }
        }
        check(before == historicalMainIdentity()) { "active_main_replaced" }
        return HistoricalEnergyPollPage(before, polls)
    }

    private fun historicalMainIdentity(): HistoricalMainIdentity {
        val file = databaseFile().canonicalFile
        val db = helper.readableDatabase
        fun pragma(name: String): Long = db.rawQuery("PRAGMA $name", null).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        val lineage = if (tableColumns(db, "storage_meta").containsAll(setOf("schema_family", "created_at_ms"))) {
            db.rawQuery("SELECT schema_family || ':' || created_at_ms FROM storage_meta ORDER BY schema_family LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else "storage-meta-empty" }
        } else {
            db.rawQuery("SELECT id || ':' || session_id || ':' || ts FROM polls ORDER BY id LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else "legacy-empty" }
        }
        return HistoricalMainIdentity(file.path, pragma("schema_version"), pragma("user_version"), lineage)
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun activeSessionId(): Long? = scalarLongOrNull(
        "SELECT id FROM collection_sessions WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1"
    )

    private fun safeLatestReading(keys: List<String>): String? = runCatching {
        latestReading(keys)
    }.onFailure { error ->
        Log.w(TAG, "latest reading query failed for $keys", error)
    }.getOrNull()

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

        //keeps raw and decoded ids queryable by stable columns instead of repeating text every poll
        val existingColumns = pollValueColumnNames(db)
            .map { it.lowercase(Locale.US) }
            .toMutableSet()
        parameters.forEach { parameter ->
            val columns = if (compactV2) {
                buildList {
                    add(PollValueColumns.raw(parameter.key) to "INTEGER")
                    if (parameter.includeDesc) add(PollValueColumns.descId(parameter.key) to "INTEGER")
                }
            } else {
                PollValueColumns.forParameter(parameter).map { it to "TEXT" }
            }
            columns.forEach { (columnName, storageType) ->
                val normalizedColumnName = columnName.lowercase(Locale.US)
                if (!existingColumns.contains(normalizedColumnName)) {
                    db.execSQL("ALTER TABLE poll_values ADD COLUMN ${quoteIdentifier(columnName)} $storageType")
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

    private fun insertPollInTransaction(
        db: SQLiteDatabase,
        sessionId: Long,
        input: PersistedPollInput,
        parameters: List<CatalogParameter>
    ): PollInsertResult {
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
        val decodedIds = if (input.ok) {
            insertPollValues(db, pollId, input.readings, parameters)
        } else {
            emptyMap()
        }
        return PollInsertResult(pollId, decodedIds)
    }

    private fun workerPollId(db: SQLiteDatabase, identity: TelemetryWorkerSampleIdentity): Long? {
        db.rawQuery(
            """
            SELECT poll_id
            FROM telemetry_worker_imports
            WHERE boot_id = ? AND helper_generation = ? AND poll_sequence = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(identity.bootId, identity.helperGeneration, identity.pollSequence.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun insertPollValues(
        db: SQLiteDatabase,
        pollId: Long,
        readings: List<PollReading>,
        parameters: List<CatalogParameter>
    ): Map<String, Long> {
        val parametersByKey = parameters.associateBy { it.key }
        val resolvedDecodedIds = mutableMapOf<String, Long>()
        db.insertOrThrow(
            "poll_values",
            null,
            ContentValues().apply {
                put("poll_id", pollId)
                readings.forEach { reading ->
                    val parameter = parametersByKey[reading.rawKey] ?: return@forEach
                    if (compactV2) {
                        reading.rawInt?.let { put(PollValueColumns.raw(parameter.key), it) }
                            ?: putNull(PollValueColumns.raw(parameter.key))
                    } else {
                        put(PollValueColumns.raw(parameter.key), reading.rawValue)
                    }
                    if (parameter.includeDesc && compactV2) {
                        reading.descValue?.let { decoded ->
                            put(
                                PollValueColumns.descId(parameter.key),
                                decodedValueId(db, decoded, resolvedDecodedIds)
                            )
                        }
                    } else if (parameter.includeDesc) {
                        put(PollValueColumns.desc(parameter.key), reading.descValue)
                    }
                }
            }
        )
        readings.forEach { reading ->
            if (parametersByKey[reading.rawKey] == null) return@forEach
            val source = reading.callbackSource ?: return@forEach
            db.insertOrThrow(
                "poll_callback_sources",
                null,
                ContentValues().apply {
                    put("poll_id", pollId)
                    put("source_key", reading.rawKey)
                    put("boot_id", source.bootId)
                    put("helper_generation", source.helperGeneration)
                    put("stream", source.stream)
                    put("epoch", source.epoch)
                    put("event_sequence", source.eventSequence)
                    put("device", source.device)
                    put("fid", source.fid)
                    put("native_type", source.nativeType)
                    put("raw_bits", source.rawBits)
                    put("received_wall_ms", source.receivedWallMs)
                    put("received_elapsed_ms", source.receivedElapsedMs)
                    source.sourceWallMs?.let { put("source_wall_ms", it) } ?: putNull("source_wall_ms")
                    put("quality", source.quality)
                }
            )
        }
        return resolvedDecodedIds
    }

    private data class PollInsertResult(
        val pollId: Long,
        val decodedIds: Map<String, Long>
    )

    private fun decodedValueId(
        db: SQLiteDatabase,
        value: String,
        resolvedInTransaction: MutableMap<String, Long>
    ): Long {
        decodedValueIds[value]?.let { return it }
        resolvedInTransaction[value]?.let { return it }
        val insertedId = db.insertWithOnConflict(
            "decoded_value_dictionary",
            null,
            ContentValues().apply { put("value", value) },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        val id = if (insertedId != -1L) {
            insertedId
        } else {
            db.rawQuery(
                "SELECT id FROM decoded_value_dictionary WHERE value = ? LIMIT 1",
                arrayOf(value)
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Decoded value dictionary lookup failed" }
                cursor.getLong(0)
            }
        }
        resolvedInTransaction[value] = id
        return id
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

    private fun scalarLong(sql: String, cancellationSignal: CancellationSignal? = null): Long {
        val query = if (cancellationSignal == null) {
            helper.readableDatabase.rawQuery(sql, emptyArray())
        } else {
            helper.readableDatabase.rawQuery(sql, emptyArray(), cancellationSignal)
        }
        query.use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

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

    private fun ContentValues.putNullable(key: String, value: Any?) {
        when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            else -> error("Unsupported nullable ContentValues type for $key: ${value::class.java.name}")
        }
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
        internal val HISTORICAL_REQUIRED_ENERGY_KEYS = listOf(
            "charging_charge_battery_volt",
            "charging_charge_current",
            "bodywork_power_level",
            "charging_1009_876609586_5"
        )
        internal val HISTORICAL_OPTIONAL_VETO_KEYS = listOf(
            "charging_1009_89128973_5",
            "charging_charging_vtov_discharge_status",
            "charging_1009_876609560_5"
        )
        internal val HISTORICAL_ENERGY_KEYS = HISTORICAL_REQUIRED_ENERGY_KEYS + HISTORICAL_OPTIONAL_VETO_KEYS
        private const val TAG = "BYDCollectorEvent"
        private const val MAX_ERROR_TEXT_LENGTH = 2_048
        private const val MAX_HEALTH_ERROR_MESSAGE_CHARS = 160
        private const val MAX_HEALTH_FINGERPRINT_MESSAGE_CHARS = 1_024
        private const val MAX_HEALTH_ERROR_CHAIN_DEPTH = 16
        private const val MAX_HEALTH_ERROR_CHAIN_NODES = 64
        private const val MAX_RAW_RESPONSE_BODY_LENGTH = 4_096
        private const val POLL_HEALTH_RECENT_PAGE_LIMIT = 128
        private const val UNKNOWN_COUNT = -1L
        private const val UNKNOWN_COUNT_INT = -1
        private const val DECODED_VALUE_CACHE_SIZE = 2_048
        private const val TELEGRAM_MAX_PENDING = 1_000L
    }
}
