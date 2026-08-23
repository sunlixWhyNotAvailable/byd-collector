package com.bydcollector.collector

import android.app.Application
import android.content.Context
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.maintenance.StorageFormatCutoverCoordinator
import com.bydcollector.collector.maintenance.StorageFormat
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.update.UpdateAutoCheckRuntime

//starts process-scoped app bookkeeping before either CollectorService or MainActivity is created
class BydCollectorApplication : Application() {
    private var telemetryStore: TelemetryStore? = null
    private var tripsStore: TripStore? = null
    private var cutoverCoordinator: StorageFormatCutoverCoordinator? = null
    private var debugStorageReady: Boolean? = null
    val dashboardUiStateStore by lazy { DashboardUiStateStore() }

    override fun onCreate() {
        super.onCreate()
        val settings = CollectorSettings(this)
        UpdateAutoCheckRuntime.onRuntimeStarted(settings.isUpdateAutoCheckEnabled())
    }

    override fun onTerminate() {
        tripsStore?.close()
        telemetryStore?.close()
        super.onTerminate()
    }

    @Synchronized
    fun closeTelemetryStoreForMaintenance() {
        telemetryStore?.close()
    }

    @Synchronized
    fun reopenTelemetryStoreForMaintenance(): TelemetryStore {
        return TelemetryStore(applicationContext, TelemetryDatabaseHelper(applicationContext)).also { store ->
            store.ensureCatalogImported()
            store.ensureNormalizedCatalogImported()
            telemetryStore = store
            if (StorageFormatCutoverCoordinator.detectMain(store.databaseFile()) == StorageFormat.COMPACT_V2) {
                CollectorSettings(applicationContext).clearMainStorageCutoverStatus()
            }
        }
    }

    @Synchronized
    fun ensureDebugStorageReady(): Boolean {
        return debugStorageReady ?: coordinator().ensureDebugReady().also { ready ->
            debugStorageReady = ready.takeIf { it }
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

    @Synchronized
    private fun store(): TelemetryStore {
        telemetryStore?.let { return it }
        check(coordinator().ensureMainReady()) { "Main telemetry database is not safe to open" }
        return TelemetryStore(applicationContext, TelemetryDatabaseHelper(applicationContext)).also { telemetryStore = it }
    }

    @Synchronized
    private fun trips(): TripStore {
        tripsStore?.let { return it }
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
}
