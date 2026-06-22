package com.bydcollector.collector.data.normalized

data class StoredNormalizedState(
    val fieldKey: String,
    val category: String,
    val valueType: String,
    val valueText: String?,
    val valueNumber: Double?,
    val valueBool: Boolean?,
    val quality: String,
    val unit: String?,
    val sourcePollId: Long?,
    val sourceKeys: String,
    val observedAt: String,
    val changedAt: String
) {
    fun semanticSignature(): String {
        return listOf(
            valueType,
            valueText ?: "null",
            valueNumber?.toString() ?: "null",
            valueBool?.toString() ?: "null",
            quality
        ).joinToString("|")
    }
}

data class NormalizedStateDecision(
    val current: StoredNormalizedState,
    val insertHistory: Boolean
)

object NormalizedStateReducer {
    fun decide(
        previous: StoredNormalizedState?,
        next: StoredNormalizedState
    ): NormalizedStateDecision {
        if (previous == null) {
            return NormalizedStateDecision(
                current = next.copy(changedAt = next.observedAt),
                insertHistory = true
            )
        }

        if (previous.semanticSignature() != next.semanticSignature()) {
            return NormalizedStateDecision(
                current = next.copy(changedAt = next.observedAt),
                insertHistory = true
            )
        }

        return NormalizedStateDecision(
            current = next.copy(changedAt = previous.changedAt),
            insertHistory = false
        )
    }

    fun markStale(previous: StoredNormalizedState, nowIso: String): StoredNormalizedState {
        return previous.copy(
            quality = NormalizedQuality.STALE.name,
            changedAt = nowIso
        )
    }
}
