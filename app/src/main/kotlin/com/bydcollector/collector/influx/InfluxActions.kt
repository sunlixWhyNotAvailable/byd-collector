package com.bydcollector.collector.influx

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.service.CollectorSettings

object InfluxActions {
    fun testConnection(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: InfluxClient = HttpInfluxClient()
    ): InfluxActionResult {
        return coordinator(store, settings, client).testConnection()
    }

    fun startExport(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: InfluxClient = HttpInfluxClient()
    ): InfluxActionResult {
        return coordinator(store, settings, client).startExport()
    }

    fun stopExport(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: InfluxClient = HttpInfluxClient()
    ): InfluxActionResult {
        return coordinator(store, settings, client).stopExport()
    }

    fun reExportNewCategories(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: InfluxClient = HttpInfluxClient()
    ): InfluxActionResult {
        return coordinator(store, settings, client).reExportNewCategories()
    }

    private fun coordinator(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: InfluxClient
    ): InfluxExportCoordinator {
        return InfluxExportCoordinator(
            store = store,
            client = client,
            configProvider = { settings.influxConfig() }
        )
    }
}
