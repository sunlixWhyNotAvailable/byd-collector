package com.bydcollector.collector.influx

data class InfluxConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val database: String,
    val username: String?,
    val password: String?,
    val measurement: String,
    val enabledCategories: Set<String>
) {
    val baseUrl: String get() = "http://${host.trim().trimEnd('/')}:$port"
    fun normalizedDatabase(): String = database.trim().ifBlank { DEFAULT_DATABASE }
    fun normalizedMeasurement(): String = measurement.trim().ifBlank { DEFAULT_MEASUREMENT }

    companion object {
        const val DEFAULT_PORT = 8086
        const val DEFAULT_DATABASE = "bydcollector"
        const val DEFAULT_MEASUREMENT = "byd_state"
    }
}
