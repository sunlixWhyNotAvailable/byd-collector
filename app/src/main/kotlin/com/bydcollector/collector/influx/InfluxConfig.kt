package com.bydcollector.collector.influx

data class InfluxConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val database: String,
    val username: String?,
    val password: String?,
    val measurement: String,
    val enabledCategories: Set<String>,
    /** Location history is opt-in independently from the existing categories. */
    val locationEnabled: Boolean = false
) {
    val baseUrl: String get() = "http://${host.trim().trimEnd('/')}:$port"
    fun normalizedDatabase(): String = database.trim().ifBlank { DEFAULT_DATABASE }
    fun normalizedMeasurement(): String = measurement.trim().ifBlank { DEFAULT_MEASUREMENT }
    fun isCategoryEnabled(category: String): Boolean =
        if (category == "location") locationEnabled else enabledCategories.contains(category)

    companion object {
        const val DEFAULT_PORT = 8086
        const val DEFAULT_DATABASE = "bydcollector"
        const val DEFAULT_MEASUREMENT = "byd_state"
    }
}
