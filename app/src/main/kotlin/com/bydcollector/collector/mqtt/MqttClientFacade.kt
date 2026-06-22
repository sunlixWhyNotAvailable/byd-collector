package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.normalized.StoredNormalizedState

data class MqttActionResult(
    val ok: Boolean,
    val category: String,
    val message: String
) {
    companion object {
        fun ok(message: String = "ok") = MqttActionResult(true, "ok", message)
        fun fail(category: String, message: String) = MqttActionResult(false, category, message)
    }
}

interface MqttClientFacade {
    fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult
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
