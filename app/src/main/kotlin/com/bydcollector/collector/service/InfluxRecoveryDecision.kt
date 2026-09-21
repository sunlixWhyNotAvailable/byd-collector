package com.bydcollector.collector.service

internal enum class InfluxRecoveryAction {
    PRESERVE,
    REQUEST_CYCLE,
    INITIALIZE
}

internal fun influxRecoveryAction(
    sessionInitialized: Boolean,
    workActive: Boolean,
    retryScheduled: Boolean
): InfluxRecoveryAction = when {
    workActive || retryScheduled -> InfluxRecoveryAction.PRESERVE
    sessionInitialized -> InfluxRecoveryAction.REQUEST_CYCLE
    else -> InfluxRecoveryAction.INITIALIZE
}
