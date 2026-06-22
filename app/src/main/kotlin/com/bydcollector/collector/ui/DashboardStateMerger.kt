package com.bydcollector.collector.ui

object DashboardStateMerger {
    fun merge(
        previous: DashboardState?,
        next: DashboardState,
        preserveDebugStatus: Boolean,
        preserveVehicleKpis: Boolean
    ): DashboardState {
        if (previous == null) return next

        var merged = next
        if (preserveDebugStatus) {
            merged = merged.copy(
                debugParameterCount = previous.debugParameterCount,
                debugReadingCount = previous.debugReadingCount,
                debugLastReadingAt = previous.debugLastReadingAt,
                debugLastErrorAt = previous.debugLastErrorAt,
                debugLastError = previous.debugLastError,
                debugErrorCount = previous.debugErrorCount,
                debugLastSessionId = previous.debugLastSessionId
            )
        }
        if (preserveVehicleKpis) {
            merged = merged.copy(vehicleKpis = previous.vehicleKpis)
        }
        return merged
    }
}
