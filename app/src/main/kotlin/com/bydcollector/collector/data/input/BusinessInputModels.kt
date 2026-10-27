package com.bydcollector.collector.data.input

import com.bydcollector.collector.data.callback.StoredCallbackEvent
import com.bydcollector.collector.data.callback.StoredSourceBoundary
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp

enum class BusinessInputKind(val storageValue: String) {
    CALLBACK("callback"),
    GET("get"),
    CONTINUITY("continuity"),
    TIMER("timer")
}

enum class BusinessInputOrigin(val storageValue: String) {
    LIVE("live"),
    REPLAY("replay"),
    TIMER("timer")
}

data class BusinessInputRef(
    val id: Long,
    val kind: BusinessInputKind,
    val rawRefId: Long?,
    val readingKeys: List<String>,
    val sourceIdentity: String,
    val bootId: String?,
    val generation: String?,
    val sequence: Long?,
    val epoch: Long?,
    val acquiredWallMs: Long?,
    val acquiredElapsedMs: Long?,
    val sessionId: Long?,
    val pollId: Long?,
    val origin: BusinessInputOrigin,
    val ok: Boolean,
    val quality: String,
    val errorCategory: String?,
    val errorMessage: String?
)

data class BusinessInputDelta(
    val ref: BusinessInputRef,
    val callbackEvent: StoredCallbackEvent? = null,
    val continuityBoundary: StoredSourceBoundary? = null,
    val readings: List<PollReading> = emptyList(),
    val rawResponseBody: String? = null,
    val sourceInputs: Map<String, NormalizedSourceInput> = emptyMap(),
    val continuityUpdates: Map<String, BusinessInputContinuity> = emptyMap()
)

enum class BusinessContinuityState(val storageValue: String) {
    AVAILABLE("available"),
    LOST("lost")
}

data class BusinessInputContinuity(
    val state: BusinessContinuityState,
    val stamp: NormalizedSourceStamp,
    val seedKind: String?,
    val seedIdentity: String?,
    val seedRecordSequence: Long?,
    val seedValid: Boolean,
    val reason: String,
    val diagnostic: String? = null,
    val rawBoundaryId: Long? = null,
    val helperGeneration: String? = null,
    val stream: Int? = null,
    val sourceEpoch: Long? = null
)

data class BusinessInputTimerSource(
    val identity: String,
    val bootId: String,
    val generation: String,
    val sequence: Long,
    val wallMs: Long,
    val elapsedMs: Long
)

object BusinessInputCheckpointKeys {
    val ALL = setOf(
        "charging_charge_battery_volt",
        "charging_charge_current",
        "bodywork_power_level",
        "charging_1009_876609586_5",
        "charging_1009_89128973_5",
        "charging_charging_vtov_discharge_status",
        "charging_1009_876609560_5",
        "statistic_1014_1145045040_5",
        "statistic_1014_1246765072_5",
        "statistic_statistic_this_trip_total_elec_consumption",
        "speed_1013_-1807745016_7"
    )
}
