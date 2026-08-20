package com.bydcollector.collector.mqtt

import com.bydcollector.collector.ha.HaIntegrationCategories

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
    /** Location is opt-in independently from the existing dashboard categories. */
    val locationEnabled: Boolean = false
) {
    val serverUri: String get() = "tcp://$host:$port"
    fun isCategoryEnabled(category: String): Boolean =
        if (category == "location") locationEnabled else enabledCategories.contains(category)
    fun normalizedTopicPrefix(): String = topicPrefix.trim('/').ifBlank { DEFAULT_TOPIC_PREFIX }
    fun normalizedDiscoveryPrefix(): String = discoveryPrefix.trim('/').ifBlank { DEFAULT_DISCOVERY_PREFIX }

    companion object {
        const val DEFAULT_TOPIC_PREFIX = "bydcollector"
        const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"
        const val DEFAULT_CLIENT_ID = "bydcollector_sealion_07"
        val DEFAULT_CATEGORIES = HaIntegrationCategories.defaults
        val VISIBLE_CATEGORIES = HaIntegrationCategories.visible.toSet()
    }
}
