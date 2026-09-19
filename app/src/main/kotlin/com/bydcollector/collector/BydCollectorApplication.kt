package com.bydcollector.collector

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelegramLegacyMigrationDecision
import com.bydcollector.collector.data.local.TelegramLegacySnapshot
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.data.trips.TripCompression
import com.bydcollector.collector.data.trips.HistoricalEnergyBackfillRecord
import com.bydcollector.collector.data.energy.HistoricalEnergyAccumulator
import com.bydcollector.collector.data.energy.HistoricalEnergyResult
import com.bydcollector.collector.data.energy.HistoricalBackfillFinalizationGate
import java.util.concurrent.locks.ReentrantLock
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.maintenance.DatabaseMaintenanceGate
import com.bydcollector.collector.maintenance.StorageFormatCutoverCoordinator
import com.bydcollector.collector.maintenance.StorageFormat
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.telegram.TelegramDeliveryRuntime
import com.bydcollector.collector.ui.ArchiveStorageSnapshotCache
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.ui.UiSessionState
import com.bydcollector.collector.update.UpdateRuntime
import com.bydcollector.collector.update.UpdateHintOverlay
import com.bydcollector.collector.update.UpdateChecker
import com.bydcollector.collector.update.UpdateCheckResult
import com.bydcollector.collector.update.UpdateCheckSession
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.namedSingleThreadExecutor
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import com.bydcollector.collector.data.local.HistoricalMainIdentity
import com.bydcollector.collector.data.local.HistoricalPollEndpoint

//starts process-scoped app bookkeeping before either CollectorService or MainActivity is created
class BydCollectorApplication : Application() {
    private var telemetryStore: TelemetryStore? = null
    private var telegramStore: TelegramStore? = null
    private var telegramStorageError: String? = null
    private var telegramLegacyMigrationUnresolved = false
    private var tripsStore: TripStore? = null
    private var cutoverCoordinator: StorageFormatCutoverCoordinator? = null
    private var debugStorageReady: Boolean? = null
    private val databaseMaintenanceGate = DatabaseMaintenanceGate()
    // Only file barriers and archive's runtime-stop boundary share this lock, not ongoing collection.
    internal val tripsFileOperationLock = ReentrantLock(true)
    internal val operationalEventJournal by lazy { OperationalEventJournal(applicationContext) }
    val dashboardUiStateStore by lazy { DashboardUiStateStore() }
    val navigationSession by lazy { UiSessionState() }
    private val updateCheckExecutorDelegate = lazy { namedSingleThreadExecutor("byd-update-check") }
    private val historicalEnergyExecutorDelegate = lazy { namedSingleThreadExecutor("byd-historical-energy") }
    private val telegramDeliveryRuntimeDelegate = lazy { TelegramDeliveryRuntime() }
    private val historicalEnergyScheduled = AtomicBoolean(false)
    private val historicalEnergyFinished = AtomicBoolean(false)
    private val historicalEnergyFinalization = HistoricalBackfillFinalizationGate()
    private var historicalEndpointIndex: HistoricalEndpointIndex? = null
    private var historicalLastRetry: String? = null
    private var historicalLastRetryAtElapsedMs = Long.MIN_VALUE
    internal val updateChecks by lazy {
        UpdateCheckSession(updateCheckExecutorDelegate.value) {
            val startedAt = SystemClock.elapsedRealtime()
            recordUpdateEvent("check_started", "installed=${BuildConfig.VERSION_NAME}")
            UpdateChecker().check().also { result ->
                val detail = when (result) {
                    is UpdateCheckResult.Available -> "available=${result.info.version}"
                    UpdateCheckResult.UpToDate -> "up_to_date"
                    is UpdateCheckResult.Error -> "error=${result.message.replace('\n', ' ').take(256)}"
                }
                recordUpdateEvent("check_result", "$detail duration_ms=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }
    }
    internal val updateRuntime by lazy { UpdateRuntime(this) }
    val telegramDeliveryRuntime by telegramDeliveryRuntimeDelegate
    internal val updateHints by lazy { UpdateHintOverlay(this) }
    private val archiveStorageSnapshotCacheDelegate = lazy {
        ArchiveStorageSnapshotCache(
            archiveRoot = File(filesDir, "db_archive"),
            mainDatabaseFile = getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME),
            debugDatabaseFile = getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME),
            tripsDatabaseFile = getDatabasePath(TripDatabaseHelper.DATABASE_NAME)
        )
    }
    internal val archiveStorageSnapshotCache by archiveStorageSnapshotCacheDelegate

    override fun onCreate() {
        super.onCreate()
        // A Messenger bind can create only this Application. Normal runtime entry
        // points start update timing explicitly; IPC must not start checks or collection.
    }

    override fun onTerminate() {
        if (updateCheckExecutorDelegate.isInitialized()) updateCheckExecutorDelegate.value.shutdownNow()
        if (historicalEnergyExecutorDelegate.isInitialized()) historicalEnergyExecutorDelegate.value.shutdownNow()
        if (telegramDeliveryRuntimeDelegate.isInitialized()) telegramDeliveryRuntime.close()
        tripsStore?.close()
        telemetryStore?.close()
        telegramStore?.close()
        if (archiveStorageSnapshotCacheDelegate.isInitialized()) {
            archiveStorageSnapshotCache.close()
        }
        super.onTerminate()
    }

    internal fun recordUpdateEvent(message: String, detail: String? = null) {
        val timestamp = Instant.now().toString()
        val elapsedMs = SystemClock.elapsedRealtime()
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            operationalEventJournal.append(timestamp, elapsedMs, "update", message, detail)
        }
    }

    internal fun beginHistoricalEnergyBackfillOwner(): Long = historicalEnergyFinalization.enable()

    /** Called only after a LIVE cycle proves helper replay has drained. */
    internal fun scheduleHistoricalEnergyBackfill(generation: Long) {
        if (!historicalEnergyCanContinue(generation)) return
        if (historicalEnergyFinished.get()) return
        if (!historicalEnergyScheduled.compareAndSet(false, true)) return
        historicalEnergyExecutorDelegate.value.execute {
            val progressed = runCatching { runHistoricalEnergyBackfillTrip(generation) }
                .onFailure { recordHistoricalEnergyEvent("retry", it.message ?: it::class.java.simpleName) }
                .getOrDefault(false)
            historicalEnergyScheduled.set(false)
            if (progressed && historicalEnergyCanContinue(generation)) scheduleHistoricalEnergyBackfill(generation)
        }
    }

    internal fun cancelHistoricalEnergyBackfill() {
        historicalEnergyFinalization.cancel()
    }

    private fun runHistoricalEnergyBackfillTrip(generation: Long): Boolean {
        if (!historicalEnergyCanContinue(generation)) return false
        val trips = tripsStoreOrNull() ?: return false
        if (!tripsFileOperationLock.tryLock()) return historicalEnergyRetry("trips_maintenance_busy")
        val candidate = try {
            trips.nextHistoricalEnergyCandidate()
        } finally {
            tripsFileOperationLock.unlock()
        }
        if (candidate == null) {
            historicalEnergyFinished.set(true)
            return false
        }
        val endpointIndex = historicalEndpointIndex?.takeIf { candidate.tripId in it.candidateIds }
            ?: buildHistoricalEndpointIndex(trips, generation)?.also { historicalEndpointIndex = it }
            ?: return if (historicalEnergyCanContinue(generation)) historicalEnergyRetry("endpoint_index_unavailable") else false
        val sourceKey = endpointIndex.sourceIdentity.stableKey
        val reject: (String) -> Boolean = { reason ->
            val record = HistoricalEnergyBackfillRecord(
                candidate.tripId, candidate.startedAt, candidate.endedAt!!, sourceKey,
                "rejected", reason.take(128), Instant.now().toString()
            )
            val committed = finalizeHistoricalEnergy(generation, trips, record, null)
            if (committed) recordHistoricalEnergyEvent("rejected", "trip=${candidate.tripId.take(80)} reason=$reason")
            committed
        }
        val startInstant = runCatching { Instant.parse(candidate.startedAt) }.getOrNull()
            ?: return reject("malformed_trip_bounds")
        val endInstant = runCatching { Instant.parse(candidate.endedAt!!) }.getOrNull()
            ?: return reject("malformed_trip_bounds")
        val starts = endpointIndex.endpoints[startInstant].orEmpty()
        val ends = endpointIndex.endpoints[endInstant].orEmpty()
        if (starts.size != 1) return reject("missing_or_ambiguous_start_bookend")
        if (ends.size != 1) return reject("missing_or_ambiguous_end_bookend")
        val first = starts.single()
        val last = ends.single()
        if (first.pollId >= last.pollId || first.sessionId != last.sessionId) return reject("invalid_bookend_session_or_order")

        var afterTripId: String? = null
        while (true) {
            if (!historicalEnergyCanContinue(generation)) return false
            val page = tryTripsRead(trips) { closedTripBoundsPage(afterTripId, HISTORICAL_TRIP_PAGE_SIZE) }
                ?: return historicalEnergyRetry("trips_maintenance_busy")
            if (page.any { HistoricalEnergyAccumulator.overlaps(candidate, it) }) return reject("ambiguous_overlapping_trip")
            if (page.size < HISTORICAL_TRIP_PAGE_SIZE) break
            afterTripId = page.last().tripId
        }

        val accumulator = HistoricalEnergyAccumulator(candidate, first.pollId, last.pollId, first.sessionId)
        var afterPollId = first.pollId - 1L
        while (afterPollId < last.pollId) {
            if (!historicalEnergyCanContinue(generation)) return false
            val page = try {
                tryDatabaseRead {
                    StoreReadResult(
                        synchronized(this) { telemetryStore }
                            ?.historicalEnergyPollPage(first.pollId, last.pollId, afterPollId, HISTORICAL_POLL_PAGE_SIZE)
                    )
                }?.value ?: return historicalEnergyRetry("main_maintenance_busy")
            } catch (error: IllegalArgumentException) {
                if (error.message?.startsWith("historical_energy_") == true) return reject(error.message!!)
                throw error
            }
            if (page.sourceIdentity.stableKey != sourceKey) {
                historicalEndpointIndex = null
                return historicalEnergyRetry("active_main_replaced")
            }
            if (page.polls.isEmpty()) break
            page.polls.forEach(accumulator::accept)
            val next = page.polls.last().pollId
            if (next <= afterPollId) return false
            afterPollId = next
        }
        val result = accumulator.finish()
        val record = HistoricalEnergyBackfillRecord(
            candidate.tripId,
            candidate.startedAt,
            candidate.endedAt!!,
            sourceKey,
            if (result is HistoricalEnergyResult.Complete) "complete" else "rejected",
            when (result) {
                is HistoricalEnergyResult.Complete -> "complete"
                is HistoricalEnergyResult.Rejected -> result.reason
            },
            Instant.now().toString()
        )
        val snapshot = (result as? HistoricalEnergyResult.Complete)?.snapshot
        val committed = finalizeHistoricalEnergy(generation, trips, record, snapshot)
        if (committed) recordHistoricalEnergyEvent(record.outcome, "trip=${candidate.tripId.take(80)} reason=${record.reason}")
        return committed
    }

    private fun buildHistoricalEndpointIndex(trips: TripStore, generation: Long): HistoricalEndpointIndex? {
        val candidates = mutableListOf<com.bydcollector.collector.data.trips.TripSession>()
        var afterTripId: String? = null
        while (true) {
            if (!historicalEnergyCanContinue(generation)) return null
            val page = tryTripsRead(trips) { historicalEnergyCandidatePage(afterTripId, HISTORICAL_TRIP_PAGE_SIZE) } ?: return null
            candidates += page
            if (page.size < HISTORICAL_TRIP_PAGE_SIZE) break
            afterTripId = page.last().tripId
        }
        if (candidates.isEmpty()) return null
        val targets = candidates.flatMap { listOfNotNull(runCatching { Instant.parse(it.startedAt) }.getOrNull(), it.endedAt?.let { end -> runCatching { Instant.parse(end) }.getOrNull() }) }.toSet()
        val endpoints = targets.associateWith { mutableListOf<HistoricalPollEndpoint>() }.toMutableMap()
        var sourceIdentity: HistoricalMainIdentity? = null
        var afterPollId = 0L
        while (true) {
            if (!historicalEnergyCanContinue(generation)) return null
            val page = tryDatabaseRead {
                StoreReadResult(
                    synchronized(this) { telemetryStore }?.historicalPollHeaderPage(afterPollId, HISTORICAL_HEADER_PAGE_SIZE)
                )
            }?.value ?: return null
            val expected = sourceIdentity
            if (expected != null && expected.stableKey != page.sourceIdentity.stableKey) return null
            sourceIdentity = page.sourceIdentity
            page.polls.forEach { endpoint ->
                val instant = runCatching { Instant.parse(endpoint.timestamp) }.getOrNull()
                endpoints[instant]?.let { matches -> if (matches.size < 2) matches += endpoint }
            }
            if (page.polls.size < HISTORICAL_HEADER_PAGE_SIZE) break
            val next = page.polls.last().pollId
            if (next <= afterPollId) return null
            afterPollId = next
        }
        return HistoricalEndpointIndex(
            sourceIdentity = sourceIdentity ?: return null,
            candidateIds = candidates.mapTo(mutableSetOf()) { it.tripId },
            endpoints = endpoints
        )
    }

    private fun historicalEnergyCanContinue(generation: Long): Boolean =
        historicalEnergyFinalization.isCurrent(generation)

    private fun finalizeHistoricalEnergy(
        generation: Long,
        store: TripStore,
        record: HistoricalEnergyBackfillRecord,
        snapshot: com.bydcollector.collector.data.energy.EnergySnapshot?
    ): Boolean {
        if (!tripsFileOperationLock.tryLock()) return false
        return try {
            historicalEnergyFinalization.finalizeIfCurrent(generation) {
                store.commitHistoricalEnergyBackfill(record, snapshot)
            } == true
        } finally {
            tripsFileOperationLock.unlock()
        }
    }

    private fun historicalEnergyRetry(reason: String): Boolean {
        recordHistoricalEnergyEvent("retry", reason)
        return false
    }

    private fun <T : Any> tryTripsRead(store: TripStore, action: TripStore.() -> T): T? {
        if (!tripsFileOperationLock.tryLock()) return null
        return try { store.action() } finally { tripsFileOperationLock.unlock() }
    }

    private fun recordHistoricalEnergyEvent(message: String, detail: String) {
        if (message == "retry") {
            val now = SystemClock.elapsedRealtime()
            if (detail == historicalLastRetry && now - historicalLastRetryAtElapsedMs < HISTORICAL_RETRY_LOG_INTERVAL_MS) return
            historicalLastRetry = detail
            historicalLastRetryAtElapsedMs = now
        }
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            operationalEventJournal.append(
                Instant.now().toString(), SystemClock.elapsedRealtime(), "historical_energy", message, detail.take(320)
            )
        }
    }

    @Synchronized
    fun closeTelemetryStoreForMaintenance() {
        val current = telemetryStore
        telemetryStore = null
        current?.close()
    }

    fun <T> withDatabaseRead(action: () -> T): T = databaseMaintenanceGate.withRead(action)

    internal fun <T : Any> tryDatabaseRead(action: () -> T): T? = databaseMaintenanceGate.tryRead(action)

    fun <T> withTelemetryStoreRead(action: (TelemetryStore) -> T): T {
        while (true) {
            val result = withDatabaseRead {
                synchronized(this) { telemetryStore }?.let { StoreReadResult(action(it)) }
            }
            if (result != null) return result.value
            store()
        }
    }

    fun <T> withExclusiveDatabaseMaintenance(action: () -> T): T =
        databaseMaintenanceGate.withExclusive(action)

    @Synchronized
    fun telegramStoreOrNull(): TelegramStore? = telegramStore.takeIf { telegramStorageError == null }

    @Synchronized
    fun telegramStorageError(): String? = telegramStorageError

    /** Returns the already-open Trips store without opening or recovering its files. */
    @Synchronized
    internal fun tripsStoreOrNull(): TripStore? = tripsStore

    @Synchronized
    fun markTelegramStorageUnavailable(mainStore: TelemetryStore, error: Throwable) {
        markTelegramStorageFailure(mainStore, error)
    }

    @Synchronized
    internal fun reconcileTelegramStorage(mainStore: TelemetryStore): TelegramStore? {
        telegramStoreOrNull()?.let { return it }
        initializeTelegramStorage(mainStore)
        return telegramStoreOrNull()
    }

    @Synchronized
    fun reopenTelemetryStoreForMaintenance(): TelemetryStore {
        return TelemetryStore(
            applicationContext,
            TelemetryDatabaseHelper(applicationContext),
            operationalEventJournal = operationalEventJournal
        ).also { store ->
            store.ensureCatalogImported()
            store.ensureNormalizedCatalogImported()
            telemetryStore = store
            initializeTelegramStorage(store)
            if (StorageFormatCutoverCoordinator.detectMain(store.databaseFile()) == StorageFormat.COMPACT_V2) {
                CollectorSettings(applicationContext).clearMainStorageCutoverStatus()
            }
        }
    }

    fun ensureDebugStorageReady(): Boolean {
        return withExclusiveDatabaseMaintenance {
            synchronized(this) {
                debugStorageReady ?: coordinator().ensureDebugReady().also { ready ->
                    debugStorageReady = ready.takeIf { it }
                }
            }
        }
    }

    @Synchronized
    fun isDebugStorageReady(): Boolean = debugStorageReady == true

    @Synchronized
    fun setDebugStorageReadyAfterMaintenance(ready: Boolean) {
        debugStorageReady = ready.takeIf { it }
        CollectorSettings(applicationContext).setDebugStorageCutoverError(if (ready) null else "Debug database verification failed")
    }

    companion object {
        const val TELEGRAM_STORAGE_ERROR = "telegram_storage_migration_failed"
        private const val HISTORICAL_TRIP_PAGE_SIZE = 64
        private const val HISTORICAL_POLL_PAGE_SIZE = 128
        private const val HISTORICAL_HEADER_PAGE_SIZE = 512
        private const val HISTORICAL_RETRY_LOG_INTERVAL_MS = 30_000L

        fun store(context: Context): TelemetryStore {
            return (context.applicationContext as BydCollectorApplication).store()
        }

        fun dashboardUiStateStore(context: Context): DashboardUiStateStore {
            return (context.applicationContext as BydCollectorApplication).dashboardUiStateStore
        }

        fun navigationSession(context: Context): UiSessionState {
            return (context.applicationContext as BydCollectorApplication).navigationSession
        }

        fun trips(context: Context): TripStore {
            return (context.applicationContext as BydCollectorApplication).trips()
        }

        fun ensureDebugStorageReady(context: Context): Boolean {
            return (context.applicationContext as BydCollectorApplication).ensureDebugStorageReady()
        }

        fun isDebugStorageReady(context: Context): Boolean {
            return (context.applicationContext as BydCollectorApplication).isDebugStorageReady()
        }
    }

    private fun store(): TelemetryStore {
        withDatabaseRead { synchronized(this) { telemetryStore } }?.let { return it }
        return withExclusiveDatabaseMaintenance {
            synchronized(this) {
                telemetryStore?.let { return@synchronized it }
                check(coordinator().ensureMainReady()) { "Main telemetry database is not safe to open" }
                TelemetryStore(
                    applicationContext,
                    TelemetryDatabaseHelper(applicationContext),
                    operationalEventJournal = operationalEventJournal
                ).also {
                    telemetryStore = it
                    initializeTelegramStorage(it)
                }
            }
        }
    }

    private data class StoreReadResult<T>(val value: T)

    private data class HistoricalEndpointIndex(
        val sourceIdentity: HistoricalMainIdentity,
        val candidateIds: Set<String>,
        val endpoints: Map<Instant, List<HistoricalPollEndpoint>>
    )

    @Synchronized
    private fun trips(): TripStore {
        tripsStore?.let { return it }
        TripCompression.recoverBeforeOpen(applicationContext)
        return TripStore(TripDatabaseHelper(applicationContext)).also { store ->
            check(store.verify()) { "Trips database is not safe to open" }
            tripsStore = store
        }
    }


    private fun coordinator(): StorageFormatCutoverCoordinator {
        return cutoverCoordinator ?: StorageFormatCutoverCoordinator(
            applicationContext,
            CollectorSettings(applicationContext)
        ).also { cutoverCoordinator = it }
    }

    @Synchronized
    private fun initializeTelegramStorage(mainStore: TelemetryStore) {
        val settings = CollectorSettings(applicationContext, mainStore)
        val sidecar = telegramStore ?: try {
            TelegramStore(applicationContext)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            markTelegramStorageFailure(mainStore, error)
            return
        }.also { telegramStore = it }
        val importAlreadyComplete = try {
            sidecar.isMainImportComplete()
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            markTelegramStorageFailure(mainStore, error)
            return
        }
        // Read Main only after the durable sidecar marker. A transient unknown
        // read cannot erase a completed import or manufacture a new obligation.
        val snapshot = try {
            mainStore.readLegacyTelegramSnapshot()
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            TelegramLegacySnapshot(
                readError = "${error::class.java.simpleName}: ${error.message ?: "Legacy Telegram snapshot read failed"}"
            )
        }
        when (
            snapshot.migrationDecision(
                importAlreadyComplete = importAlreadyComplete,
                migrationPreviouslyRequired = settings.telegramLegacyMigrationRequired() ||
                    telegramLegacyMigrationUnresolved
            )
        ) {
            TelegramLegacyMigrationDecision.VERIFY_COMPLETED_IMPORT,
            TelegramLegacyMigrationDecision.IMPORT_EMPTY -> Unit
            TelegramLegacyMigrationDecision.RETRY_UNKNOWN_READ -> {
                markTelegramStorageFailure(
                    mainStore,
                    IllegalStateException(snapshot.readError ?: "Legacy Telegram snapshot read is unknown")
                )
                return
            }
            TelegramLegacyMigrationDecision.IMPORT_PROVEN_DATA -> {
                telegramLegacyMigrationUnresolved = true
                if (!settings.setTelegramLegacyMigrationRequired(true)) {
                    markTelegramStorageFailure(mainStore, IllegalStateException("Cannot persist Telegram migration requirement"))
                    return
                }
            }
            TelegramLegacyMigrationDecision.FAIL_PROVEN_MISSING -> {
                markTelegramStorageFailure(
                    mainStore,
                    IllegalStateException("Legacy Telegram data remains in an archived Main database")
                )
                return
            }
        }
        val migration = sidecar.importLegacySnapshot(snapshot)
        if (!migration.verified) {
            markTelegramStorageFailure(
                mainStore,
                IllegalStateException(migration.errorMessage ?: "Telegram sidecar migration was not verified")
            )
            return
        }
        val sidecarStateValid = try {
            sidecar.verifyRuntimeState()
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            markTelegramStorageFailure(mainStore, error)
            return
        }
        if (!sidecarStateValid) {
            markTelegramStorageFailure(mainStore, IllegalStateException("Telegram sidecar runtime state is malformed"))
            return
        }
        if (!settings.setTelegramLegacyMigrationRequired(false)) {
            markTelegramStorageFailure(mainStore, IllegalStateException("Cannot clear Telegram migration requirement"))
            return
        }
        if (migration.cleanupVerified) {
            val cleaned = try {
                mainStore.cleanupLegacyTelegramStorage(snapshot, migration)
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                false
            }
            if (!cleaned && snapshot.requiresPreservation) {
                try {
                    mainStore.recordEvent(
                        "telegram_legacy_cleanup_deferred",
                        "Legacy Telegram cleanup was deferred after verified sidecar import",
                        null
                    )
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                }
            }
        } else if (snapshot.requiresPreservation) {
            try {
                mainStore.recordEvent(
                    "telegram_legacy_cleanup_deferred",
                    "Legacy Telegram cleanup was deferred because the Main snapshot was not verified",
                    migration.errorMessage
                )
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
            }
        }
        telegramLegacyMigrationUnresolved = false
        telegramStorageError = null
        if (
            settings.telegramConnectionStatus() == "storage_error" &&
            settings.telegramConnectionMessage() == TELEGRAM_STORAGE_ERROR
        ) {
            settings.setTelegramConnectionStatus("not_tested", null)
        }
        if (settings.mainStorageCutoverDeferredReason() != null) {
            runCatching { StorageFormatCutoverCoordinator.readMainPreflight(mainStore.databaseFile()) }
                .getOrNull()
                ?.takeUnless { it.blocksAutomaticCutover }
                ?.let { settings.clearMainStorageCutoverStatus() }
        }
    }

    private fun markTelegramStorageFailure(mainStore: TelemetryStore, error: Throwable) {
        val detail = (error.message ?: error::class.java.simpleName).take(300)
        telegramStore?.let {
            try {
                it.close()
            } catch (closeError: Exception) {
                if (closeError is InterruptedException) throw closeError
            }
        }
        telegramStore = null
        telegramStorageError = detail
        CollectorSettings(applicationContext, mainStore)
            .setTelegramConnectionStatus("storage_error", TELEGRAM_STORAGE_ERROR)
        try {
            mainStore.recordEvent(
                TELEGRAM_STORAGE_ERROR,
                "Telegram sidecar migration failed",
                detail
            )
        } catch (eventError: Exception) {
            if (eventError is InterruptedException) throw eventError
        }
    }

}
