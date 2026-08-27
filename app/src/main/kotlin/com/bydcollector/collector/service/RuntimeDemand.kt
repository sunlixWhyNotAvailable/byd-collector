package com.bydcollector.collector.service

enum class RuntimeRecoveryAction {
    MAIN,
    DEBUG,
    MQTT,
    INFLUX,
    TELEGRAM,
    KEEP_ALIVE
}

data class RuntimeDemand(
    val main: Boolean = false,
    val debug: Boolean = false,
    val mqtt: Boolean = false,
    val influx: Boolean = false,
    val telegram: Boolean = false,
    val keepAlive: Boolean = false
) {
    val any: Boolean
        get() = main || debug || mqtt || influx || telegram || keepAlive

    val requiresPersistentOwner: Boolean
        get() = main || debug || mqtt || influx || telegram || keepAlive

    fun recoveryActions(): List<RuntimeRecoveryAction> {
        if (main) return listOf(RuntimeRecoveryAction.MAIN)
        return buildList {
            if (debug) add(RuntimeRecoveryAction.DEBUG)
            if (mqtt) add(RuntimeRecoveryAction.MQTT)
            if (influx) add(RuntimeRecoveryAction.INFLUX)
            if (telegram) add(RuntimeRecoveryAction.TELEGRAM)
            if (keepAlive) add(RuntimeRecoveryAction.KEEP_ALIVE)
        }
    }
}

data class RuntimeLiveness(
    val main: Boolean = false,
    val debug: Boolean = false,
    val keepAlive: Boolean = false,
    val mqtt: Boolean = false,
    val telegram: Boolean = false,
    val influxQueued: Boolean = false,
    val influxInFlight: Boolean = false,
    val influxRetryScheduled: Boolean = false,
    val maintenance: Boolean = false,
    val archiveStorage: Boolean = false,
    val influxOwned: Boolean = false
) {
    val active: Boolean
        get() = main || debug || keepAlive || mqtt || telegram ||
            influxQueued || influxInFlight || influxRetryScheduled || maintenance || archiveStorage || influxOwned
}
