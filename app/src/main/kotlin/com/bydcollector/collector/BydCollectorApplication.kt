package com.bydcollector.collector

import android.app.Application
import android.content.Context
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.data.trips.TripCompression
import java.util.concurrent.locks.ReentrantLock
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.maintenance.DatabaseMaintenanceGate
import com.bydcollector.collector.maintenance.StorageFormatCutoverCoordinator
import com.bydcollector.collector.maintenance.StorageFormat
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.update.UpdateAutoCheckRuntime

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

    override fun onCreate() {
        super.onCreate()
        val settings = CollectorSettings(this)
        UpdateAutoCheckRuntime.onRuntimeStarted(settings.isUpdateAutoCheckEnabled())
    }

    override fun onTerminate() {
        tripsStore?.close()
        telemetryStore?.close()
        telegramStore?.close()
        super.onTerminate()
    }

    @Synchronized
    fun closeTelemetryStoreForMaintenance() {
        val current = telemetryStore
        telemetryStore = null
        current?.close()
    }

    fun <T> withDatabaseRead(action: () -> T): T = databaseMaintenanceGate.withRead(action)

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

    @Synchronized
    fun markTelegramStorageUnavailable(mainStore: TelemetryStore, error: Throwable) {
        markTelegramStorageFailure(mainStore, error)
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

        fun store(context: Context): TelemetryStore {
            return (context.applicationContext as BydCollectorApplication).store()
        }

        fun dashboardUiStateStore(context: Context): DashboardUiStateStore {
            return (context.applicationContext as BydCollectorApplication).dashboardUiStateStore
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
        val snapshot = mainStore.readLegacyTelegramSnapshot()
        if (snapshot.requiresPreservation) {
            telegramLegacyMigrationUnresolved = true
            if (!settings.setTelegramLegacyMigrationRequired(true)) {
                markTelegramStorageFailure(mainStore, IllegalStateException("Cannot persist Telegram migration requirement"))
                return
            }
        }
        val sidecar = telegramStore ?: runCatching { TelegramStore(applicationContext) }
            .getOrElse { error ->
                markTelegramStorageFailure(mainStore, error)
                return
            }
            .also { telegramStore = it }
        if (
            (settings.telegramLegacyMigrationRequired() || telegramLegacyMigrationUnresolved) &&
            !snapshot.requiresPreservation
        ) {
            markTelegramStorageFailure(
                mainStore,
                IllegalStateException("Legacy Telegram data remains in an archived Main database")
            )
            return
        }
        val migration = runCatching { sidecar.importLegacySnapshot(snapshot) }
            .getOrElse { error ->
                markTelegramStorageFailure(mainStore, error)
                return
            }
        if (!migration.verified) {
            markTelegramStorageFailure(
                mainStore,
                IllegalStateException(migration.errorMessage ?: "Telegram sidecar migration was not verified")
            )
            return
        }
        val sidecarStateValid = runCatching { sidecar.verifyRuntimeState() }
            .getOrElse { error ->
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
        val cleaned = runCatching { mainStore.cleanupLegacyTelegramStorage(snapshot, migration) }
            .getOrDefault(false)
        if (!cleaned) {
            telegramLegacyMigrationUnresolved = true
            settings.setTelegramLegacyMigrationRequired(true)
            markTelegramStorageFailure(mainStore, IllegalStateException("Legacy Telegram cleanup was not verified"))
            return
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
        telegramStore?.let { runCatching { it.close() } }
        telegramStore = null
        telegramStorageError = detail
        CollectorSettings(applicationContext, mainStore)
            .setTelegramConnectionStatus("storage_error", TELEGRAM_STORAGE_ERROR)
        runCatching {
            mainStore.recordEvent(
                TELEGRAM_STORAGE_ERROR,
                "Telegram sidecar migration failed",
                detail
            )
        }
    }

}
