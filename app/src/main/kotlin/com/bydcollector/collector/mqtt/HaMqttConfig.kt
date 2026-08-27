package com.bydcollector.collector.mqtt

import com.bydcollector.collector.ha.HaIntegrationCategories
import com.bydcollector.collector.ha.HaEndpointProfile

data class HaMqttConfig(
    val enabled: Boolean,
    val discoveryEnabled: Boolean,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val clientId: String,
    val topicPrefix: String,
    val discoveryPrefix: String,
    val enabledCategories: Set<String>,
    /** Optional alternative host; a non-blank host requires [alternativePort]. */
    val alternativeHost: String? = null,
    /** Optional alternative port; a value requires [alternativeHost]. */
    val alternativePort: Int? = null
) {
    val serverUri: String get() = "tcp://$host:$port"

    /**
     * Returns a single-endpoint snapshot for an attempt or one-shot test.
     * The alternative pair is intentionally cleared so a lower layer cannot
     * silently perform a second fallback attempt.
     */
    fun forProfile(profile: HaEndpointProfile): HaMqttConfig {
        val endpoint = when (profile) {
            HaEndpointProfile.PRIMARY -> host to port
            HaEndpointProfile.ALTERNATIVE -> alternativeHost.orEmpty() to (alternativePort ?: 0)
        }
        require(isValidHost(endpoint.first) && endpoint.second in 1..65_535) {
            "${profile.name.lowercase()} MQTT endpoint is not configured"
        }
        return copy(
            host = endpoint.first.trim(),
            port = endpoint.second,
            alternativeHost = null,
            alternativePort = null
        )
    }

    /** Validates the full runtime pair (primary is required; alternative is optional). */
    fun validateEndpoints(): String? {
        validateProfile(HaEndpointProfile.PRIMARY)?.let { return it }
        val alternativePresent = !alternativeHost.isNullOrBlank() || alternativePort != null
        if (alternativePresent) return validateProfile(HaEndpointProfile.ALTERNATIVE)
        return null
    }

    /** Validates only the selected profile, as required by one-shot connection tests. */
    fun validateProfile(profile: HaEndpointProfile): String? {
        val endpoint = when (profile) {
            HaEndpointProfile.PRIMARY -> host to port
            HaEndpointProfile.ALTERNATIVE -> alternativeHost.orEmpty() to (alternativePort ?: 0)
        }
        return when {
            !isValidHost(endpoint.first) -> "${profile.name.lowercase()} MQTT host is invalid"
            endpoint.second !in 1..65_535 -> "${profile.name.lowercase()} MQTT port is invalid"
            else -> null
        }
    }

    fun isCategoryEnabled(category: String): Boolean = enabledCategories.contains(category)
    fun normalizedTopicPrefix(): String = topicPrefix.trim('/').ifBlank { DEFAULT_TOPIC_PREFIX }
    fun normalizedDiscoveryPrefix(): String = discoveryPrefix.trim('/').ifBlank { DEFAULT_DISCOVERY_PREFIX }

    companion object {
        const val DEFAULT_TOPIC_PREFIX = "bydcollector"
        const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"
        const val DEFAULT_CLIENT_ID = "bydcollector_sealion_07"
        val DEFAULT_CATEGORIES = HaIntegrationCategories.defaults
        val VISIBLE_CATEGORIES = HaIntegrationCategories.visible.toSet()

        private fun isValidHost(host: String): Boolean {
            return host.isNotBlank() && host == host.trim() && host.none { it.isWhitespace() || it == '/' || it == '\\' }
        }
    }
}
