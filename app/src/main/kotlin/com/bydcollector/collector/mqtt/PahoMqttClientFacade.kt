package com.bydcollector.collector.mqtt

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttSecurityException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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
    @Volatile private var client: PahoMqttClientHandle? = null
    private var clientIdentity: ClientIdentity? = null

    override val isConnected: Boolean
        get() = client?.isConnected == true

    private data class ClientIdentity(
        val serverUri: String,
        val clientId: String,
        val willTopic: String?,
        val purpose: MqttConnectionPurpose
    )

    override fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult {
        return connect(config, willMessage, MqttConnectionPurpose.RUNTIME)
    }

    @Synchronized
    override fun connect(
        config: HaMqttConfig,
        willMessage: HaMqttMessage?,
        purpose: MqttConnectionPurpose
    ): MqttActionResult {
        if (Thread.currentThread().isInterrupted) {
            return MqttActionResult.fail(
                "mqtt_cancelled",
                "MQTT worker was interrupted",
                MqttFailureKind.CANCELLED
            )
        }
        return runMqttAction {
            val will = when (purpose) {
                MqttConnectionPurpose.RUNTIME -> willMessage ?: defaultWill(config)
                MqttConnectionPurpose.TEST_NO_WILL -> willMessage
            }
            val identity = ClientIdentity(config.serverUri, config.clientId, will?.topic, purpose)
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
            MqttActionResult.ok("connected")
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
            return MqttActionResult.fail(
                "mqtt_cancelled",
                "MQTT worker was interrupted",
                MqttFailureKind.CANCELLED
            )
        }
        return runMqttAction {
            val mqttClient = client ?: return@runMqttAction MqttActionResult.fail(
                "mqtt_error",
                "MQTT client is not connected",
                MqttFailureKind.TRANSPORT
            )
            if (!mqttClient.isConnected) {
                return@runMqttAction MqttActionResult.fail(
                    "mqtt_error",
                    "MQTT client is not connected",
                    MqttFailureKind.TRANSPORT
                )
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
                "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                classifyFailure(error)
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

    private fun connectOptions(config: HaMqttConfig, will: HaMqttMessage?): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 5
            keepAliveInterval = 30
            config.username?.takeIf { it.isNotBlank() }?.let { userName = it }
            config.password?.takeIf { it.isNotBlank() }?.let { password = it.toCharArray() }
            //sets retained offline lwt for runtime connections; explicit tests use no Will
            will?.let { message ->
                setWill(message.topic, message.payload.toByteArray(Charsets.UTF_8), message.qos, message.retained)
            }
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
                "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                classifyFailure(error)
            )
        }
    }

    private fun classifyFailure(error: Throwable): MqttFailureKind {
        if (error is InterruptedException || Thread.currentThread().isInterrupted) {
            return MqttFailureKind.CANCELLED
        }
        var transportCause = false
        var current: Throwable? = error
        while (current != null) {
            if (current is MqttSecurityException || current is SSLException) {
                return MqttFailureKind.AUTHENTICATION
            }
            if (
                current is SocketTimeoutException ||
                current is InterruptedIOException ||
                current is ConnectException ||
                current is NoRouteToHostException ||
                current is UnknownHostException ||
                current is IOException
            ) {
                transportCause = true
            }
            if (current is MqttException) {
                when (current.reasonCode.toShort()) {
                    MqttException.REASON_CODE_FAILED_AUTHENTICATION,
                    MqttException.REASON_CODE_NOT_AUTHORIZED,
                    MqttException.REASON_CODE_SSL_CONFIG_ERROR -> return MqttFailureKind.AUTHENTICATION
                    MqttException.REASON_CODE_INVALID_MESSAGE -> return MqttFailureKind.DATA
                    MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION,
                    MqttException.REASON_CODE_INVALID_CLIENT_ID -> return MqttFailureKind.PROTOCOL
                    MqttException.REASON_CODE_BROKER_UNAVAILABLE,
                    MqttException.REASON_CODE_CLIENT_TIMEOUT,
                    MqttException.REASON_CODE_WRITE_TIMEOUT,
                    MqttException.REASON_CODE_SERVER_CONNECT_ERROR,
                    MqttException.REASON_CODE_CLIENT_NOT_CONNECTED,
                    MqttException.REASON_CODE_CONNECTION_LOST -> transportCause = true
                }
            }
            current = current.cause
        }
        return if (transportCause) MqttFailureKind.TRANSPORT else MqttFailureKind.OTHER
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
