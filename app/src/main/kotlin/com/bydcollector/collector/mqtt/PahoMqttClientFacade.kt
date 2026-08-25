package com.bydcollector.collector.mqtt

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

interface PahoMqttClientHandle {
    val serverUri: String
    val clientId: String
    val isConnected: Boolean
    fun setTimeToWait(timeoutMs: Long)
    fun connect(options: MqttConnectOptions)
    fun publish(topic: String, message: MqttMessage)
    fun disconnect()
    fun close()
}

//thin paho wrapper that keeps mqtt connection lifecycle replaceable in unit tests
class PahoMqttClientFacade(
    internal val clientFactory: (serverUri: String, clientId: String) -> PahoMqttClientHandle = { serverUri, clientId ->
        PahoMqttClientHandleAdapter(MqttClient(serverUri, clientId, MemoryPersistence()))
    }
) : MqttClientFacade {
    private var client: PahoMqttClientHandle? = null
    private var clientIdentity: ClientIdentity? = null

    private data class ClientIdentity(
        val serverUri: String,
        val clientId: String,
        val willTopic: String
    )

    @Synchronized
    override fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult {
        if (Thread.currentThread().isInterrupted) {
            return MqttActionResult.fail("mqtt_cancelled", "MQTT worker was interrupted")
        }
        return runMqttAction {
            val will = willMessage ?: defaultWill(config)
            val identity = ClientIdentity(config.serverUri, config.clientId, will.topic)
            val mqttClient = client?.takeIf { clientIdentity == identity }
                ?: replaceClient(config, identity)
            if (!mqttClient.isConnected) {
                try {
                    mqttClient.connect(connectOptions(config, will))
                } catch (error: Exception) {
                    if (client === mqttClient) {
                        client = null
                        clientIdentity = null
                    }
                    closeBestEffort(mqttClient)
                    throw error
                }
            }
            MqttActionResult.ok()
        }
    }

    private fun replaceClient(config: HaMqttConfig, identity: ClientIdentity): PahoMqttClientHandle {
        //closes stale clients when broker/client-id settings change to avoid publishing to an old target
        val oldClient = client
        client = null
        clientIdentity = null
        oldClient?.let { closeBestEffort(it) }
        return clientFactory(config.serverUri, config.clientId).also {
            it.setTimeToWait(MQTT_ACTION_TIMEOUT_MS)
            client = it
            clientIdentity = identity
        }
    }

    private fun closeBestEffort(oldClient: PahoMqttClientHandle) {
        if (oldClient.isConnected) {
            runCatching { oldClient.disconnect() }
        }
        runCatching { oldClient.close() }
    }

    @Synchronized
    override fun publish(message: HaMqttMessage): MqttActionResult {
        if (Thread.currentThread().isInterrupted) {
            return MqttActionResult.fail("mqtt_cancelled", "MQTT worker was interrupted")
        }
        return runMqttAction {
            val mqttClient = client ?: return@runMqttAction MqttActionResult.fail("mqtt_error", "MQTT client is not connected")
            if (!mqttClient.isConnected) {
                return@runMqttAction MqttActionResult.fail("mqtt_error", "MQTT client is not connected")
            }
            mqttClient.publish(message.topic, mqttMessage(message))
            MqttActionResult.ok()
        }
    }

    @Synchronized
    override fun disconnect(gracefulMessage: HaMqttMessage?): MqttActionResult {
        val mqttClient = client ?: return MqttActionResult.ok()
        client = null
        clientIdentity = null
        val publishError = if (mqttClient.isConnected && gracefulMessage != null) {
            runCatching { mqttClient.publish(gracefulMessage.topic, mqttMessage(gracefulMessage)) }.exceptionOrNull()
        } else {
            null
        }
        val disconnectError = if (mqttClient.isConnected) {
            runCatching { mqttClient.disconnect() }.exceptionOrNull()
        } else {
            null
        }
        val closeError = runCatching { mqttClient.close() }.exceptionOrNull()
        val error = publishError ?: disconnectError ?: closeError
        return if (error == null) {
            MqttActionResult.ok()
        } else {
            MqttActionResult.fail(
                "mqtt_error",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun defaultWill(config: HaMqttConfig): HaMqttMessage {
        return HaMqttMessage(
            topic = "${config.normalizedTopicPrefix()}/status",
            payload = HaMqttPayloadBuilder.offlineStatus(),
            retained = true,
            qos = 1
        )
    }

    private fun connectOptions(config: HaMqttConfig, will: HaMqttMessage): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 5
            keepAliveInterval = 30
            config.username?.takeIf { it.isNotBlank() }?.let { userName = it }
            config.password?.takeIf { it.isNotBlank() }?.let { password = it.toCharArray() }
            //sets retained offline lwt so ha marks entities unavailable if the collector process dies
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

    private companion object {
        const val MQTT_ACTION_TIMEOUT_MS = 5_000L
    }
}

private class PahoMqttClientHandleAdapter(
    private val delegate: MqttClient
) : PahoMqttClientHandle {
    override val serverUri: String get() = delegate.serverURI
    override val clientId: String get() = delegate.clientId
    override val isConnected: Boolean get() = delegate.isConnected
    override fun setTimeToWait(timeoutMs: Long) = delegate.setTimeToWait(timeoutMs)
    override fun connect(options: MqttConnectOptions) = delegate.connect(options)
    override fun publish(topic: String, message: MqttMessage) = delegate.publish(topic, message)
    override fun disconnect() = delegate.disconnect()
    override fun close() = delegate.close()
}
