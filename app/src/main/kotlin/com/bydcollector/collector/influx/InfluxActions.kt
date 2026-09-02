package com.bydcollector.collector.influx

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.service.CollectorSettings

object InfluxActions {
    fun testConnection(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: InfluxClient? = null,
        profile: HaEndpointProfile = HaEndpointProfile.PRIMARY,
        diagnostics: InfluxDiagnosticSink = InfluxRuntimeDiagnosticsProcess.instance::record
    ): InfluxActionResult {
        val base = settings.influxConfig()
        base.validateProfile(profile)?.let {
            return InfluxActionResult.fail("influx_endpoint_invalid", it, failureKind = InfluxFailureKind.PROTOCOL)
        }
        val selected = runCatching { base.forProfile(profile) }.getOrElse {
            return InfluxActionResult.fail(
                "influx_endpoint_invalid",
                it.message ?: "InfluxDB endpoint is not configured",
                failureKind = InfluxFailureKind.PROTOCOL
            )
        }
        return coordinator(store, settings, client ?: HttpInfluxClient(diagnostics), diagnostics)
            .testConnection(selected, profile)
    }

    private fun coordinator(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: InfluxClient,
        diagnostics: InfluxDiagnosticSink = {}
    ): InfluxExportCoordinator {
        return InfluxExportCoordinator(
            store = store,
            client = client,
            configProvider = { settings.influxConfig() },
            diagnostics = diagnostics
        )
    }
}
