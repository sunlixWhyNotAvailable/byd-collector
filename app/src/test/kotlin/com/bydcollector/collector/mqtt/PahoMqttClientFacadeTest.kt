package com.bydcollector.collector.mqtt

import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PahoMqttClientFacadeTest {
    @Test
    fun reconnectsWhenWillTopicChangesWithTopicPrefix() {
        val handles = mutableListOf<FakeHandle>()
        val facade = PahoMqttClientFacade { serverUri, clientId ->
            FakeHandle(serverUri, clientId).also { handles += it }
        }

        assertTrue(facade.connect(config("bydcollector"), willMessage = null).ok)
        assertTrue(facade.connect(config("bydcollector_next"), willMessage = null).ok)

        assertEquals(2, handles.size)
        assertEquals(1, handles.first().disconnectCount)
        assertEquals(1, handles.first().closeCount)
        assertEquals(1, handles.last().connectCount)
    }

    private class FakeHandle(
        override val serverUri: String,
        override val clientId: String
    ) : PahoMqttClientHandle {
        override var isConnected: Boolean = false
        var connectCount = 0
        var disconnectCount = 0
        var closeCount = 0

        override fun connect(options: MqttConnectOptions) {
            connectCount += 1
            isConnected = true
        }

        override fun publish(topic: String, message: MqttMessage) = Unit

        override fun disconnect() {
            disconnectCount += 1
            isConnected = false
        }

        override fun close() {
            closeCount += 1
        }
    }

    private fun config(topicPrefix: String): HaMqttConfig {
        return HaMqttConfig(
            enabled = true,
            discoveryEnabled = true,
            host = "mqtt.local",
            port = 1883,
            username = null,
            password = null,
            clientId = "bydcollector",
            topicPrefix = topicPrefix,
            discoveryPrefix = "homeassistant",
            enabledCategories = HaMqttConfig.DEFAULT_CATEGORIES
        )
    }
}
