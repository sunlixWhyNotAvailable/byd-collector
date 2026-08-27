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

    @Test
    fun explicitTestConnectionOmitsRuntimeDefaultWill() {
        val handles = mutableListOf<FakeHandle>()
        val facade = PahoMqttClientFacade { serverUri, clientId ->
            FakeHandle(serverUri, clientId).also { handles += it }
        }

        assertTrue(
            facade.connect(
                config("bydcollector"),
                willMessage = null,
                purpose = MqttConnectionPurpose.TEST_NO_WILL
            ).ok
        )

        assertEquals(null, handles.single().lastOptions?.willDestination)
    }

    @Test
    fun offlineDisconnectReusesAndClosesTheOriginalHandleExactlyOnce() {
        val handles = mutableListOf<FakeHandle>()
        val facade = PahoMqttClientFacade { serverUri, clientId ->
            FakeHandle(serverUri, clientId).also { handles += it }
        }

        assertTrue(facade.connect(config("bydcollector"), willMessage = null).ok)
        assertTrue(facade.disconnect(offlineMessage()).ok)
        assertTrue(facade.disconnect(offlineMessage()).ok)

        assertEquals(1, handles.size)
        assertEquals(5_000L, handles.single().timeToWaitMs)
        assertEquals(1, handles.single().publishCount)
        assertEquals(1, handles.single().disconnectCount)
        assertEquals(1, handles.single().closeCount)
    }

    @Test
    fun gracefulPublishFailureStillDisconnectsAndClosesOnce() {
        val handle = FakeHandle("tcp://mqtt.local:1883", "bydcollector", failPublish = true)
        val facade = PahoMqttClientFacade { _, _ -> handle }

        assertTrue(facade.connect(config("bydcollector"), willMessage = null).ok)
        assertTrue(!facade.disconnect(offlineMessage()).ok)

        assertEquals(1, handle.publishCount)
        assertEquals(1, handle.disconnectCount)
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun failedConnectClosesTheNewHandleWithoutRetainingIt() {
        val handles = mutableListOf<FakeHandle>()
        val facade = PahoMqttClientFacade { serverUri, clientId ->
            FakeHandle(serverUri, clientId, failConnect = true).also { handles += it }
        }

        assertTrue(!facade.connect(config("bydcollector"), willMessage = null).ok)
        assertTrue(facade.disconnect(null).ok)

        assertEquals(1, handles.size)
        assertEquals(0, handles.single().disconnectCount)
        assertEquals(1, handles.single().closeCount)
    }

    @Test
    fun disconnectFailureStillClosesTheHandleExactlyOnce() {
        val handle = FakeHandle("tcp://mqtt.local:1883", "bydcollector", failDisconnect = true)
        val facade = PahoMqttClientFacade { _, _ -> handle }

        assertTrue(facade.connect(config("bydcollector"), willMessage = null).ok)
        assertTrue(!facade.disconnect(null).ok)

        assertEquals(1, handle.disconnectCount)
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun interruptedRetiredWorkerCannotCreateANewHandle() {
        val handles = mutableListOf<FakeHandle>()
        val facade = PahoMqttClientFacade { serverUri, clientId ->
            FakeHandle(serverUri, clientId).also { handles += it }
        }

        Thread.currentThread().interrupt()
        try {
            assertTrue(!facade.connect(config("bydcollector"), willMessage = null).ok)
        } finally {
            Thread.interrupted()
        }

        assertTrue(handles.isEmpty())
    }

    private class FakeHandle(
        override val serverUri: String,
        override val clientId: String,
        private val failConnect: Boolean = false,
        private val failPublish: Boolean = false,
        private val failDisconnect: Boolean = false
    ) : PahoMqttClientHandle {
        override var isConnected: Boolean = false
        var connectCount = 0
        var publishCount = 0
        var disconnectCount = 0
        var closeCount = 0
        var timeToWaitMs: Long? = null
        var lastOptions: MqttConnectOptions? = null

        override fun setTimeToWait(timeoutMs: Long) {
            timeToWaitMs = timeoutMs
        }

        override fun connect(options: MqttConnectOptions) {
            connectCount += 1
            lastOptions = options
            if (failConnect) error("connect failed")
            isConnected = true
        }

        override fun publish(topic: String, message: MqttMessage) {
            publishCount += 1
            if (failPublish) error("publish failed")
        }

        override fun disconnect() {
            disconnectCount += 1
            if (failDisconnect) error("disconnect failed")
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

    private fun offlineMessage(): HaMqttMessage = HaMqttMessage(
        topic = "bydcollector/status",
        payload = "offline",
        retained = true,
        qos = 1
    )
}
