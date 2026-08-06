package com.bydcollector.collector.data.normalized

enum class NormalizedCategory(val mqttKey: String, val staleAfterMs: Long) {
    BATTERY("battery", 30_000L),
    MOTION("motion", 5_000L),
    BODY("body", 30_000L),
    CLIMATE("climate", 30_000L),
    SAFETY("safety", 30_000L),
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
    UNSUPPORTED;

    val storageCode: Int
        get() = when (this) {
            OK -> 0
            STALE -> 1
            MISSING -> 2
            INVALID -> 3
            UNSUPPORTED -> 4
        }

    companion object {
        fun fromStorageCode(code: Int): NormalizedQuality = when (code) {
            0 -> OK
            1 -> STALE
            2 -> MISSING
            3 -> INVALID
            4 -> UNSUPPORTED
            else -> error("Unknown normalized quality code: $code")
        }
    }
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
    val observedAt: String,
    val reason: String? = null
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
