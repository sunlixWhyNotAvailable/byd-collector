package com.bydcollector.collector

import android.app.Application
import android.content.Context
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.update.UpdateAutoCheckRuntime

//starts process-scoped app bookkeeping before either CollectorService or MainActivity is created
class BydCollectorApplication : Application() {
    lateinit var telemetryStore: TelemetryStore
        private set

    override fun onCreate() {
        super.onCreate()
        telemetryStore = TelemetryStore(applicationContext, TelemetryDatabaseHelper(applicationContext))
        val settings = CollectorSettings(this, telemetryStore)
        UpdateAutoCheckRuntime.onRuntimeStarted(settings.isUpdateAutoCheckEnabled())
    }

    override fun onTerminate() {
        if (::telemetryStore.isInitialized) telemetryStore.close()
        super.onTerminate()
    }

    @Synchronized
    fun closeTelemetryStoreForMaintenance() {
        if (::telemetryStore.isInitialized) telemetryStore.close()
    }

    @Synchronized
    fun reopenTelemetryStoreForMaintenance(): TelemetryStore {
        return TelemetryStore(applicationContext, TelemetryDatabaseHelper(applicationContext)).also { store ->
            store.ensureCatalogImported()
            store.ensureNormalizedCatalogImported()
            telemetryStore = store
        }
    }

    companion object {
        fun store(context: Context): TelemetryStore {
            return (context.applicationContext as BydCollectorApplication).telemetryStore
        }
    }
}
