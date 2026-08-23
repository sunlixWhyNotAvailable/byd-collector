package com.bydcollector.collector.ui

import com.bydcollector.collector.data.local.HealthSnapshotDetail

enum class DashboardLoadProfile(
    val healthDetail: HealthSnapshotDetail?,
    val readsTelemetryStore: Boolean,
    val readsDebugStatus: Boolean,
    val readsVehicleKpis: Boolean,
    val readsArchiveDetails: Boolean,
    val readsIntegrationSettings: Boolean,
    val readsRuntimeSettings: Boolean
) {
    INITIAL(
        healthDetail = null,
        readsTelemetryStore = false,
        readsDebugStatus = false,
        readsVehicleKpis = false,
        readsArchiveDetails = false,
        readsIntegrationSettings = true,
        readsRuntimeSettings = true
    ),
    CHROME(
        healthDetail = HealthSnapshotDetail.SUMMARY,
        readsTelemetryStore = true,
        readsDebugStatus = false,
        readsVehicleKpis = false,
        readsArchiveDetails = false,
        readsIntegrationSettings = false,
        readsRuntimeSettings = false
    ),
    MAIN(
        healthDetail = HealthSnapshotDetail.INTEGRATIONS,
        readsTelemetryStore = true,
        readsDebugStatus = false,
        readsVehicleKpis = false,
        readsArchiveDetails = false,
        readsIntegrationSettings = true,
        readsRuntimeSettings = true
    ),
    ALL_PARAMETERS(
        healthDetail = HealthSnapshotDetail.SUMMARY,
        readsTelemetryStore = true,
        readsDebugStatus = true,
        readsVehicleKpis = true,
        readsArchiveDetails = false,
        readsIntegrationSettings = false,
        readsRuntimeSettings = true
    ),
    HA(
        healthDetail = HealthSnapshotDetail.INTEGRATIONS,
        readsTelemetryStore = true,
        readsDebugStatus = false,
        readsVehicleKpis = false,
        readsArchiveDetails = false,
        readsIntegrationSettings = true,
        readsRuntimeSettings = false
    ),
    STORAGE(
        healthDetail = null,
        readsTelemetryStore = false,
        readsDebugStatus = false,
        readsVehicleKpis = false,
        readsArchiveDetails = true,
        readsIntegrationSettings = false,
        readsRuntimeSettings = false
    ),
    EXTRA(
        healthDetail = null,
        readsTelemetryStore = false,
        readsDebugStatus = false,
        readsVehicleKpis = false,
        readsArchiveDetails = false,
        readsIntegrationSettings = false,
        readsRuntimeSettings = true
    )
}
