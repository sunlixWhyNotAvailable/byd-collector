package com.bydcollector.collector.mqtt

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

interface PahoMqttClientHandle {
    val serverUri: String
    val clientId: String
    val isConnected: Boolean
    fun connect(options: MqttConnectOptions)
    fun publish(topic: String, message: MqttMessage)
    fun disconnect()
    fun close()
}

class PahoMqttClientFacade(
    internal val clientFactory: (serverUri: String, clientId: String) -> PahoMqttClientHandle = { serverUri, clientId ->
        PahoMqttClientHandleAdapter(MqttClient(serverUri, clientId, MemoryPersistence()))
    }
) : MqttClientFacade {
    private var client: PahoMqttClientHandle? = null

    override fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult {
        return runMqttAction {
            val mqttClient = client?.takeIf { it.serverUri == config.serverUri && it.clientId == config.clientId }
                ?: replaceClient(config)
            if (!mqttClient.isConnected) {
                mqttClient.connect(connectOptions(config, willMessage))
            }
            MqttActionResult.ok()
        }
    }

    private fun replaceClient(config: HaMqttConfig): PahoMqttClientHandle {
        val oldClient = client
        client = null
        oldClient?.let { closeBestEffort(it) }
        return clientFactory(config.serverUri, config.clientId).also { client = it }
    }

    private fun closeBestEffort(oldClient: PahoMqttClientHandle) {
        if (oldClient.isConnected) {
            runCatching { oldClient.disconnect() }
        }
        runCatching { oldClient.close() }
    }

    override fun publish(message: HaMqttMessage): MqttActionResult {
        return runMqttAction {
            val mqttClient = client ?: return@runMqttAction MqttActionResult.fail("mqtt_error", "MQTT client is not connected")
            if (!mqttClient.isConnected) {
                return@runMqttAction MqttActionResult.fail("mqtt_error", "MQTT client is not connected")
            }
            mqttClient.publish(message.topic, mqttMessage(message))
            MqttActionResult.ok()
        }
    }

    override fun disconnect(gracefulMessage: HaMqttMessage?): MqttActionResult {
        return runMqttAction {
            val mqttClient = client ?: return@runMqttAction MqttActionResult.ok()
            client = null
            try {
                if (mqttClient.isConnected) {
                    gracefulMessage?.let { mqttClient.publish(it.topic, mqttMessage(it)) }
                    mqttClient.disconnect()
                }
                MqttActionResult.ok()
            } finally {
                mqttClient.close()
            }
        }
    }

    private fun connectOptions(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 5
            keepAliveInterval = 30
            config.username?.takeIf { it.isNotBlank() }?.let { userName = it }
            config.password?.takeIf { it.isNotBlank() }?.let { password = it.toCharArray() }
            val will = willMessage ?: HaMqttMessage(
                topic = "${config.normalizedTopicPrefix()}/status",
                payload = HaMqttPayloadBuilder.offlineStatus(),
                retained = true,
                qos = 1
            )
            setWill(will.topic, will.payload.toByteArray(Charsets.UTF_8), will.qos, will.retained)
        }
    }

    private fun mqttMessage(message: HaMqttMessage): MqttMessage {
        return MqttMessage(message.payload.toByteArray(Charsets.UTF_8)).apply {
            qos = message.qos
            isRetained = message.retained
        }
    }

    private fun runMqttAction(action: () -> MqttActionResult): MqttActionResult {
        return try {
            action()
        } catch (error: Exception) {
            MqttActionResult.fail(
                "mqtt_error",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }
}

private class PahoMqttClientHandleAdapter(
    private val delegate: MqttClient
) : PahoMqttClientHandle {
    override val serverUri: String get() = delegate.serverURI
    override val clientId: String get() = delegate.clientId
    override val isConnected: Boolean get() = delegate.isConnected
    override fun connect(options: MqttConnectOptions) = delegate.connect(options)
    override fun publish(topic: String, message: MqttMessage) = delegate.publish(topic, message)
    override fun disconnect() = delegate.disconnect()
    override fun close() = delegate.close()
}
