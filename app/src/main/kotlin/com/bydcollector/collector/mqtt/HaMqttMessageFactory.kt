package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.normalized.StoredNormalizedState

sealed class MqttMessageBuildResult {
    data class Success(val messages: List<HaMqttMessage>) : MqttMessageBuildResult()
    data class Failure(val category: String, val message: String) : MqttMessageBuildResult()
}

//builds ha mqtt messages from normalized state rather than raw BYD keys
class HaMqttMessageFactory(
    private val normalizedProvider: NormalizedStateProvider,
    private val configProvider: () -> HaMqttConfig,
    private val clock: Clock = SystemClockAdapter()
) {
    fun discoveryMessages(): MqttMessageBuildResult {
        return discoveryMessages(configProvider())
    }

    fun discoveryMessages(configOverride: HaMqttConfig): MqttMessageBuildResult {
        val config = configOverride
        return MqttMessageBuildResult.Success(HaDiscoveryBuilder.discoveryMessages(config))
    }

    fun fullResyncMessages(): MqttMessageBuildResult {
        return fullResyncMessages(configProvider())
    }

    fun fullResyncMessages(configOverride: HaMqttConfig): MqttMessageBuildResult {
        val config = configOverride
        return runCatching {
            //filters at publish time so changing categories does not require rewriting normalized storage
            val rows = HaMqttFieldFilter.publishableRows(normalizedProvider.currentState(config.enabledCategories), config)
            buildList {
                add(onlineStatusMessage(config, rows))
                addAll(categoryStateMessages(config, rows, config.enabledCategories))
            }
        }.fold(
            onSuccess = { MqttMessageBuildResult.Success(it) },
            onFailure = { stateError(it) }
        )
    }

    fun statusMessage(status: HaMqttStatus): MqttMessageBuildResult {
        return statusMessage(configProvider(), status)
    }

    fun statusMessage(configOverride: HaMqttConfig, status: HaMqttStatus): MqttMessageBuildResult {
        return MqttMessageBuildResult.Success(listOf(buildStatusMessage(configOverride, status)))
    }

    fun changedCategoryMessages(categories: Set<String>): MqttMessageBuildResult {
        return changedCategoryMessages(configProvider(), categories)
    }

    fun changedCategoryMessages(configOverride: HaMqttConfig, categories: Set<String>): MqttMessageBuildResult {
        val config = configOverride
        val enabledChanged = categories.intersect(config.enabledCategories)
        //skips disabled categories even if their normalized values changed in sqlite
        if (enabledChanged.isEmpty()) return MqttMessageBuildResult.Success(emptyList())

        return runCatching {
            val rows = HaMqttFieldFilter.publishableRows(normalizedProvider.currentState(enabledChanged), config)
            categoryStateMessages(config, rows, enabledChanged)
        }.fold(
            onSuccess = { MqttMessageBuildResult.Success(it) },
            onFailure = { stateError(it) }
        )
    }

    fun offlineMessage(): HaMqttMessage = offlineMessage(configProvider())

    fun offlineMessage(configOverride: HaMqttConfig): HaMqttMessage = offlineStatusMessage(configOverride)

    private fun categoryStateMessages(
        config: HaMqttConfig,
        rows: List<StoredNormalizedState>,
        categories: Set<String>
    ): List<HaMqttMessage> {
        val rowsByCategory = rows.groupBy { it.category }
        //keeps one retained state topic per category so ha receives compact grouped updates
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
        return buildStatusMessage(
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

    private fun buildStatusMessage(config: HaMqttConfig, status: HaMqttStatus): HaMqttMessage {
        return HaMqttMessage(
            topic = "${config.normalizedTopicPrefix()}/status",
            payload = HaMqttPayloadBuilder.status(status),
            retained = true,
            qos = 1
        )
    }

    private fun offlineStatusMessage(config: HaMqttConfig): HaMqttMessage {
        //retained offline status lets ha mark all entities unavailable after collector shutdown
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
