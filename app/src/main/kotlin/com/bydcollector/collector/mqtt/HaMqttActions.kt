package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.service.CollectorSettings

object HaMqttActions {
    fun testConnection(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: MqttClientFacade = PahoMqttClientFacade()
    ): MqttActionResult {
        return runOneShot(store, settings, client) { testConnectionOnly() }
    }

    private fun runOneShot(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: MqttClientFacade,
        action: MqttPublishCoordinator.() -> MqttActionResult
    ): MqttActionResult {
        val result = coordinator(store, settings, client).action()
        val cleanup = client.disconnect(null)
        if (!cleanup.ok) {
            store.recordEvent("mqtt_disconnect_error", "MQTT one-shot disconnect failed", cleanup.message)
        }
        return if (result.ok && !cleanup.ok) cleanup else result
    }

    private fun coordinator(
        store: TelemetryStore,
        settings: CollectorSettings,
        client: MqttClientFacade
    ): MqttPublishCoordinator {
        return MqttPublishCoordinator(
            client = client,
            outbox = store,
            retryStateStore = store,
            messageFactory = HaMqttMessageFactory(
                normalizedProvider = store,
                configProvider = { settings.mqttConfig() }
            ),
            configProvider = { settings.mqttConfig() }
        )
    }
}
