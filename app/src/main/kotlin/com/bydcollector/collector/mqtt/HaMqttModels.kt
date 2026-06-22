package com.bydcollector.collector.mqtt

data class HaMqttMessage(
    val topic: String,
    val payload: String,
    val retained: Boolean,
    val qos: Int = 1
)

data class HaMqttStatus(
    val availability: String,
    val polling: Boolean,
    val collectorStatus: String,
    val adb: String,
    val helper: String,
    val lastSuccessAt: String?,
    val lastError: String?,
    val categories: Map<String, String>
)
