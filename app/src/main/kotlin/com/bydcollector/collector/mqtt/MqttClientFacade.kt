package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.normalized.StoredNormalizedState

enum class MqttConnectionPurpose {
    RUNTIME,
    TEST_NO_WILL
}

enum class MqttFailureKind {
    TRANSPORT,
    AUTHENTICATION,
    DATA,
    PROTOCOL,
    CANCELLED,
    OTHER
}

data class MqttActionResult(
    val ok: Boolean,
    val category: String,
    val message: String,
    val failureKind: MqttFailureKind? = null
) {
    companion object {
        fun ok(message: String = "ok") = MqttActionResult(true, "ok", message)
        fun fail(
            category: String,
            message: String,
            failureKind: MqttFailureKind? = null
        ) = MqttActionResult(false, category, message, failureKind)
    }
}

interface MqttClientFacade {
    fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult
    /**
     * Runtime callers retain the legacy two-argument surface while one-shot
     * tests can explicitly request no availability Will. A nullable Will alone
     * is not sufficient because the Paho adapter supplies its runtime default.
     */
    fun connect(
        config: HaMqttConfig,
        willMessage: HaMqttMessage?,
        purpose: MqttConnectionPurpose
    ): MqttActionResult = connect(config, willMessage)

    /** Concrete facades report transport state so coordinators can keep a sticky route. */
    val isConnected: Boolean

    fun publish(message: HaMqttMessage): MqttActionResult
    fun disconnect(gracefulMessage: HaMqttMessage?): MqttActionResult
}

interface NormalizedStateProvider {
    fun currentState(categories: Set<String>? = null): List<StoredNormalizedState>
}

interface MqttPublishStateRecorder {
    fun recordMqttPublishSuccess(
        targetKey: String,
        targetType: String,
        payloadHash: String,
        publishedAt: String
    )

    fun recordMqttPublishError(
        targetKey: String,
        targetType: String,
        error: String,
        errorAt: String
    )
}

object NoopMqttPublishStateRecorder : MqttPublishStateRecorder {
    override fun recordMqttPublishSuccess(
        targetKey: String,
        targetType: String,
        payloadHash: String,
        publishedAt: String
    ) = Unit

    override fun recordMqttPublishError(
        targetKey: String,
        targetType: String,
        error: String,
        errorAt: String
    ) = Unit
}
