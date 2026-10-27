package com.bydcollector.collector.data.energy

data class EnergySessionSeed(
    val powerSessionId: String,
    val startedAt: String
) {
    init {
        require(powerSessionId.isNotBlank() && powerSessionId.length <= 520)
        require(startedAt.isNotBlank() && startedAt.length <= 128)
    }
}

data class EnergyReceipt(
    val sourceIdentity: String,
    val observedAt: String,
    // The receipt clock remains the real acquisition clock, including on timer evaluations.
    val input: EnergyInput,
    val businessGeneration: String? = null,
    val businessInputId: Long? = null,
    // Timer deadlines use this clock without rewriting the retained source clock above.
    val evaluationBootId: String? = null,
    val evaluationElapsedMs: Long? = null,
    // Set only when a late input changed energy-relevant retained values.
    val lateRelevantSource: Boolean = false
) {
    init {
        require(sourceIdentity.isNotBlank()) { "Energy source identity is blank" }
        require(sourceIdentity.length <= MAX_IDENTITY_LENGTH) { "Energy source identity is too long" }
        require(observedAt.isNotBlank()) { "Energy observed time is blank" }
        require(observedAt.length <= MAX_TIME_LENGTH) { "Energy observed time is too long" }
        require((businessGeneration == null) == (businessInputId == null)) {
            "Energy business generation and input ID must be provided together"
        }
        require(businessGeneration == null || businessGeneration.isNotBlank()) {
            "Energy business generation is blank"
        }
        require(businessGeneration == null || businessGeneration.length <= MAX_GENERATION_LENGTH) {
            "Energy business generation is too long"
        }
        require(businessInputId == null || businessInputId > 0L) { "Energy business input ID is invalid" }
        if (businessGeneration != null && businessInputId != null) {
            require(sourceIdentity == businessIdentity(businessGeneration, businessInputId)) {
                "Energy business source identity mismatch"
            }
        }
        require((evaluationBootId == null) == (evaluationElapsedMs == null)) {
            "Energy evaluation boot ID and elapsed time must be provided together"
        }
        require(evaluationBootId == null || evaluationBootId.isNotBlank()) { "Energy evaluation boot ID is blank" }
        require(evaluationBootId == null || evaluationBootId.length <= MAX_BOOT_ID_LENGTH) {
            "Energy evaluation boot ID is too long"
        }
        require(evaluationElapsedMs == null || evaluationElapsedMs >= 0L) {
            "Energy evaluation elapsed time is negative"
        }
    }

    companion object {
        private const val MAX_IDENTITY_LENGTH = 512
        private const val MAX_TIME_LENGTH = 128
        private const val MAX_GENERATION_LENGTH = 480
        private const val MAX_BOOT_ID_LENGTH = 256

        fun businessIdentity(generation: String, inputId: Long): String = "business:$generation:$inputId"
    }
}

enum class EnergyPowerState {
    UNKNOWN,
    ON,
    OFF
}

enum class EnergySessionTransition {
    NONE,
    STARTED,
    STOPPED
}

enum class EnergyRecoveryState {
    NONE,
    WAITING_FOR_OFF,
    WAITING_FOR_ON
}

data class EnergySnapshot(
    val snapshotId: String,
    val sourceIdentity: String,
    val powerSessionId: String?,
    val startedAt: String?,
    val observedAt: String,
    // These legacy-named snapshot fields carry the evaluation clock; acquisition clocks stay in the input journal.
    val sourceBootId: String,
    val sourceElapsedMs: Long,
    val active: Boolean,
    val dischargedKwh: Double?,
    val regeneratedKwh: Double?,
    val netKwh: Double?,
    val energyCoveredMs: Long,
    val energyUncoveredMs: Long,
    val energyPartial: Boolean,
    val integrationQuality: EnergyIntegrationQuality,
    val reason: String
) {
    init {
        require(snapshotId.isNotBlank())
        require(snapshotId.length <= 520)
        require(sourceIdentity.isNotBlank())
        require(sourceIdentity.length <= 512)
        require(observedAt.isNotBlank())
        require(observedAt.length <= 128)
        require(sourceBootId.isNotBlank())
        require(sourceBootId.length <= 256)
        require(powerSessionId == null || (powerSessionId.isNotBlank() && powerSessionId.length <= 520))
        require(startedAt == null || (startedAt.isNotBlank() && startedAt.length <= 128))
        require((powerSessionId == null) == (startedAt == null))
        require(!active || (powerSessionId != null && startedAt != null))
        require(reason.isNotBlank() && reason.length <= 128)
        require(sourceElapsedMs >= 0L)
        require(energyCoveredMs >= 0L)
        require(energyUncoveredMs >= 0L)
        require(listOfNotNull(dischargedKwh, regeneratedKwh, netKwh).all(Double::isFinite))
        require((dischargedKwh == null) == (regeneratedKwh == null) && (dischargedKwh == null) == (netKwh == null))
        require((dischargedKwh != null) == (energyCoveredMs > 0L))
        require(energyUncoveredMs == 0L || energyPartial)
        if (dischargedKwh != null) {
            require(dischargedKwh >= 0.0 && regeneratedKwh!! >= 0.0)
            require(kotlin.math.abs((dischargedKwh - regeneratedKwh) - netKwh!!) <= NET_EPSILON)
            require(energyCoveredMs > 0L)
        }
    }

    companion object {
        private const val NET_EPSILON = 1e-9
    }
}

data class EnergyPendingProjection(val snapshot: EnergySnapshot)

data class EnergyProcessResult(
    val snapshot: EnergySnapshot?,
    val pendingProjection: EnergyPendingProjection?,
    val duplicate: Boolean = false,
    val stale: Boolean = false,
    val transition: EnergySessionTransition = EnergySessionTransition.NONE
)

data class EnergyRuntimeState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val powerState: EnergyPowerState = EnergyPowerState.UNKNOWN,
    val powerSessionId: String? = null,
    val startedAt: String? = null,
    val active: Boolean = false,
    val lastSourceIdentity: String? = null,
    val lastSourceBootId: String? = null,
    val lastSourceElapsedMs: Long? = null,
    val anchor: EnergyAnchor? = null,
    val totals: EnergyTotals = EnergyTotals(),
    val integrationQuality: EnergyIntegrationQuality = EnergyIntegrationQuality.EXCLUDED,
    val reason: String = REASON_UNAVAILABLE,
    val currentSnapshot: EnergySnapshot? = null,
    val recoveryState: EnergyRecoveryState = EnergyRecoveryState.NONE,
    val lastBusinessGeneration: String? = null,
    val lastBusinessInputId: Long? = null,
    val lastEvaluationBootId: String? = null,
    val lastEvaluationElapsedMs: Long? = null
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION)
        require(!active || (powerSessionId != null && startedAt != null))
        require(powerSessionId == null || (powerSessionId.isNotBlank() && powerSessionId.length <= 520))
        require(startedAt == null || (startedAt.isNotBlank() && startedAt.length <= 128))
        require((powerSessionId == null) == (startedAt == null))
        require((lastSourceIdentity == null) == (lastSourceBootId == null))
        require((lastSourceIdentity == null) == (lastSourceElapsedMs == null))
        require(lastSourceIdentity == null || (lastSourceIdentity.isNotBlank() && lastSourceIdentity.length <= 512))
        require(lastSourceBootId == null || (lastSourceBootId.isNotBlank() && lastSourceBootId.length <= 256))
        require(lastSourceElapsedMs == null || lastSourceElapsedMs >= 0L)
        require((lastBusinessGeneration == null) == (lastBusinessInputId == null))
        require(lastBusinessGeneration == null || (lastBusinessGeneration.isNotBlank() && lastBusinessGeneration.length <= 480))
        require(lastBusinessInputId == null || lastBusinessInputId > 0L)
        require((lastEvaluationBootId == null) == (lastEvaluationElapsedMs == null))
        require(lastEvaluationBootId == null || (lastEvaluationBootId.isNotBlank() && lastEvaluationBootId.length <= 256))
        require(lastEvaluationElapsedMs == null || lastEvaluationElapsedMs >= 0L)
        require(reason.isNotBlank() && reason.length <= 128)
        anchor?.let {
            require(active)
            require(it.bootId.isNotBlank() && it.bootId.length <= 256)
            require(it.elapsedMs >= 0L && it.powerKw.isFinite())
            require(it.bootId == lastEvaluationBootId)
            require(it.elapsedMs == lastEvaluationElapsedMs)
        }
        currentSnapshot?.let {
            require(it.powerSessionId == powerSessionId)
            require(it.startedAt == startedAt)
            require(it.active == active)
            require(it.energyCoveredMs == totals.coveredMs)
            require(it.energyUncoveredMs == totals.uncoveredMs)
            require(it.energyPartial == totals.partial)
            require(it.dischargedKwh == totals.dischargedKwh.takeIf { totals.coveredMs > 0L })
            require(it.regeneratedKwh == totals.regeneratedKwh.takeIf { totals.coveredMs > 0L })
            require(it.netKwh == totals.netKwh.takeIf { totals.coveredMs > 0L })
            require(it.integrationQuality == integrationQuality)
            require(it.reason == reason)
            if (active) {
                require(it.sourceIdentity == lastSourceIdentity)
                require(it.sourceBootId == lastEvaluationBootId)
                require(it.sourceElapsedMs == lastEvaluationElapsedMs)
            }
        }
        require(recoveryState == EnergyRecoveryState.NONE || (!active && anchor == null && currentSnapshot == null))
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val REASON_UNAVAILABLE = "unavailable"
        const val REASON_STARTED_MID_POWER_SESSION = "started_mid_power_session"
        const val REASON_RECOVERED_PENDING = "recovered_pending_without_anchor"
        const val REASON_CORRUPT_STATE = "corrupt_state_waiting_for_power_cycle"
    }
}

data class EnergyRuntimeStatus(
    val active: Boolean,
    val lastEvaluationBootId: String?,
    val lastEvaluationElapsedMs: Long?,
    val lastBusinessGeneration: String?,
    val lastBusinessInputId: Long?
)

data class EnergyRuntimeRow(
    val stateJson: String,
    val pendingProjectionJson: String?,
    val updatedAt: String
)

interface EnergyRuntimeStorage {
    fun readEnergyRuntimeRow(): EnergyRuntimeRow?
    fun commitEnergyRuntimeRow(stateJson: String, pendingProjectionJson: String?, updatedAt: String)
    fun clearEnergyPending(expectedPendingJson: String, updatedAt: String): Boolean

    fun quarantineEnergyRuntimeRow(row: EnergyRuntimeRow, reason: String, updatedAt: String) {
        throw UnsupportedOperationException("Energy runtime quarantine storage is not configured")
    }
}

class EnergyProjectionPendingException(val projection: EnergyPendingProjection) :
    IllegalStateException("Energy projection ${projection.snapshot.snapshotId} must be applied first")
