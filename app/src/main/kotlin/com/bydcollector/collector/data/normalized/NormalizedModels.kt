package com.bydcollector.collector.data.normalized

enum class NormalizedCategory(val mqttKey: String, val staleAfterMs: Long) {
    BATTERY("battery", 30_000L),
    MOTION("motion", 5_000L),
    BODY("body", 30_000L),
    CLIMATE("climate", 30_000L),
    SAFETY("safety", 30_000L),
    DRIVER_ASSIST("driver_assist", 10_000L),
    COLLECTOR("collector", 60_000L)
}

enum class NormalizedValueType {
    NUMBER,
    BOOLEAN,
    TEXT
}

enum class NormalizedQuality {
    OK,
    STALE,
    MISSING,
    INVALID,
    UNSUPPORTED
}

data class NormalizedValue(
    val type: NormalizedValueType,
    val text: String? = null,
    val number: Double? = null,
    val bool: Boolean? = null
) {
    fun semanticKey(): String {
        return when (type) {
            NormalizedValueType.NUMBER -> number?.toString() ?: "null"
            NormalizedValueType.BOOLEAN -> bool?.toString() ?: "null"
            NormalizedValueType.TEXT -> text ?: "null"
        }
    }
}

data class NormalizedFieldDefinition(
    val fieldKey: String,
    val category: NormalizedCategory,
    val valueType: NormalizedValueType,
    val unit: String?,
    val displayName: String,
    val deviceClass: String?,
    val stateClass: String?,
    val entityPlatform: String,
    val sourceKeys: List<String>,
    val normalizerId: String,
    val mqttDefaultEnabled: Boolean = true
)

data class NormalizedObservation(
    val field: NormalizedFieldDefinition,
    val value: NormalizedValue,
    val quality: NormalizedQuality,
    val sourcePollId: Long,
    val sourceKey: String?,
    val observedAt: String
) {
    fun semanticKey(): String {
        return listOf(
            field.fieldKey,
            quality.name,
            value.type.name,
            value.semanticKey()
        ).joinToString("|")
    }
}
