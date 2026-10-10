package com.bydcollector.collector.influx

import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog

data class InfluxConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val database: String,
    val username: String?,
    val password: String?,
    val measurement: String,
    val enabledCategories: Set<String>,
    val alternativeHost: String? = null,
    val alternativePort: Int? = null,
    val vehicleId: String = DEFAULT_VEHICLE_ID
) {
    val baseUrl: String get() = "http://${host.trim().trimEnd('/')}:$port"

    fun forProfile(profile: HaEndpointProfile): InfluxConfig {
        val endpoint = when (profile) {
            HaEndpointProfile.PRIMARY -> host to port
            HaEndpointProfile.ALTERNATIVE -> alternativeHost.orEmpty() to (alternativePort ?: 0)
        }
        require(isValidHost(endpoint.first) && endpoint.second in 1..65_535) {
            "${profile.name.lowercase()} InfluxDB endpoint is not configured"
        }
        return copy(
            host = endpoint.first.trim().trimEnd('/'),
            port = endpoint.second,
            alternativeHost = null,
            alternativePort = null
        )
    }

    fun validateEndpoints(): String? {
        validateProfile(HaEndpointProfile.PRIMARY)?.let { return it }
        val alternativePresent = !alternativeHost.isNullOrBlank() || alternativePort != null
        if (alternativePresent) return validateProfile(HaEndpointProfile.ALTERNATIVE)
        return null
    }

    fun validateProfile(profile: HaEndpointProfile): String? {
        val endpoint = when (profile) {
            HaEndpointProfile.PRIMARY -> host to port
            HaEndpointProfile.ALTERNATIVE -> alternativeHost.orEmpty() to (alternativePort ?: 0)
        }
        return when {
            !isValidHost(endpoint.first) -> "${profile.name.lowercase()} InfluxDB host is invalid"
            endpoint.second !in 1..65_535 -> "${profile.name.lowercase()} InfluxDB port is invalid"
            else -> null
        }
    }
    fun normalizedDatabase(): String = database.trim().ifBlank { DEFAULT_DATABASE }
    fun normalizedMeasurement(): String = measurement.trim().ifBlank { DEFAULT_MEASUREMENT }
    fun normalizedVehicleId(): String = vehicleId.ifBlank { DEFAULT_VEHICLE_ID }
    fun validateVehicleId(): String? = if (isValidVehicleId(vehicleId)) null else
        "InfluxDB vehicle ID cannot contain carriage returns, line feeds, or NUL characters"
    fun isCategoryEnabled(category: String): Boolean = enabledCategories.contains(category)

    companion object {
        const val DEFAULT_PORT = 8086
        const val DEFAULT_DATABASE = "bydcollector"
        const val DEFAULT_MEASUREMENT = "byd_state"
        const val DEFAULT_VEHICLE_ID = "sea_lion_07"

        fun isValidVehicleId(vehicleId: String): Boolean =
            vehicleId.none { it == '\r' || it == '\n' || it == '\u0000' }

        private fun isValidHost(host: String): Boolean {
            return host.isNotBlank() && host == host.trim() && host.none { it.isWhitespace() || it == '/' || it == '\\' }
        }
    }
}

/** The single source of truth for fields selected by both queue accounting and export. */
internal fun influxEffectiveFields(config: InfluxConfig): Set<String> =
    NormalizedFieldCatalog.fields
        .filter { field -> config.isCategoryEnabled(field.category.mqttKey) }
        .map { field -> field.fieldKey }
        .toSet()
