package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.normalized.StoredNormalizedState

sealed class MqttMessageBuildResult {
    data class Success(val messages: List<HaMqttMessage>) : MqttMessageBuildResult()
    data class Failure(val category: String, val message: String) : MqttMessageBuildResult()
}

class HaMqttMessageFactory(
    private val normalizedProvider: NormalizedStateProvider,
    private val configProvider: () -> HaMqttConfig,
    private val clock: Clock = SystemClockAdapter()
) {
    fun discoveryMessages(): MqttMessageBuildResult {
        val config = configProvider()
        return MqttMessageBuildResult.Success(HaDiscoveryBuilder.discoveryMessages(config))
    }

    fun fullResyncMessages(): MqttMessageBuildResult {
        val config = configProvider()
        return runCatching {
            val categories = config.enabledCategories
            val rows = HaMqttFieldFilter.publishableRows(normalizedProvider.currentState(categories), config)
            buildList {
                add(onlineStatusMessage(config, rows))
                addAll(categoryStateMessages(config, rows, categories))
            }
        }.fold(
            onSuccess = { MqttMessageBuildResult.Success(it) },
            onFailure = { stateError(it) }
        )
    }

    fun statusMessage(status: HaMqttStatus): MqttMessageBuildResult {
        return MqttMessageBuildResult.Success(listOf(statusMessage(configProvider(), status)))
    }

    fun changedCategoryMessages(categories: Set<String>): MqttMessageBuildResult {
        val config = configProvider()
        val enabledChanged = categories.intersect(config.enabledCategories)
        if (enabledChanged.isEmpty()) return MqttMessageBuildResult.Success(emptyList())

        return runCatching {
            val rows = HaMqttFieldFilter.publishableRows(normalizedProvider.currentState(enabledChanged), config)
            categoryStateMessages(config, rows, enabledChanged)
        }.fold(
            onSuccess = { MqttMessageBuildResult.Success(it) },
            onFailure = { stateError(it) }
        )
    }

    fun offlineMessage(): HaMqttMessage = offlineStatusMessage(configProvider())

    private fun categoryStateMessages(
        config: HaMqttConfig,
        rows: List<StoredNormalizedState>,
        categories: Set<String>
    ): List<HaMqttMessage> {
        val rowsByCategory = rows.groupBy { it.category }
        return categories.sorted()
            .mapNotNull { category ->
                val categoryRows = rowsByCategory[category].orEmpty()
                if (categoryRows.isEmpty()) return@mapNotNull null
                HaMqttMessage(
                    topic = "${config.normalizedTopicPrefix()}/state/$category",
                    payload = HaMqttPayloadBuilder.categoryState(category, clock.nowIso(), categoryRows),
                    retained = true,
                    qos = 1
                )
            }
    }

    private fun onlineStatusMessage(config: HaMqttConfig, rows: List<StoredNormalizedState>): HaMqttMessage {
        val categoryQuality = rows
            .groupBy { it.category }
            .mapValues { (_, categoryRows) ->
                if (categoryRows.any { it.quality != "OK" }) "degraded" else "ok"
            }
        return statusMessage(
            config,
            HaMqttStatus(
                availability = "online",
                polling = true,
                collectorStatus = "online",
                adb = "unknown",
                helper = "unknown",
                lastSuccessAt = null,
                lastError = null,
                categories = categoryQuality
            )
        )
    }

    private fun statusMessage(config: HaMqttConfig, status: HaMqttStatus): HaMqttMessage {
        return HaMqttMessage(
            topic = "${config.normalizedTopicPrefix()}/status",
            payload = HaMqttPayloadBuilder.status(status),
            retained = true,
            qos = 1
        )
    }

    private fun offlineStatusMessage(config: HaMqttConfig): HaMqttMessage {
        return HaMqttMessage(
            topic = "${config.normalizedTopicPrefix()}/status",
            payload = HaMqttPayloadBuilder.offlineStatus(),
            retained = true,
            qos = 1
        )
    }

    private fun stateError(error: Throwable): MqttMessageBuildResult.Failure {
        return MqttMessageBuildResult.Failure(
            category = "mqtt_state_error",
            message = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
        )
    }
}
