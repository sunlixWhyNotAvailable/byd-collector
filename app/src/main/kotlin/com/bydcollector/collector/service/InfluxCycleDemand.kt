package com.bydcollector.collector.service

internal data class InfluxCycleRequest(
    val revision: Long,
    val generation: Long,
    val consumedDemand: Boolean
)

internal data class InfluxCycleSettlement(
    val current: Boolean,
    val followUpDemand: Boolean
)

internal enum class InfluxFollowUpAction {
    IGNORE_STALE,
    SCHEDULE_RETRY,
    REQUEST_CYCLE,
    IDLE
}

internal fun influxFollowUpAction(
    currentRequest: Boolean,
    retryDelayMs: Long?,
    followUpDemand: Boolean
): InfluxFollowUpAction = when {
    !currentRequest -> InfluxFollowUpAction.IGNORE_STALE
    retryDelayMs != null -> InfluxFollowUpAction.SCHEDULE_RETRY
    followUpDemand -> InfluxFollowUpAction.REQUEST_CYCLE
    else -> InfluxFollowUpAction.IDLE
}

internal fun isInfluxSubmissionCurrent(expectedGeneration: Long?, currentGeneration: Long): Boolean {
    return expectedGeneration == null || expectedGeneration == currentGeneration
}

/** Mutable only while the service's influxQueueLock is held. */
internal class InfluxCycleDemand {
    private var ownerRevision: Long? = null
    private var demandPending = false

    val pending: Boolean
        get() = demandPending

    fun signal() {
        demandPending = true
    }

    fun tryAcquire(revision: Long, generation: Long): InfluxCycleRequest? {
        if (ownerRevision != null) return null
        val request = InfluxCycleRequest(revision, generation, consumedDemand = demandPending)
        demandPending = false
        ownerRevision = revision
        return request
    }

    fun settle(request: InfluxCycleRequest, represented: Boolean): InfluxCycleSettlement {
        if (ownerRevision != request.revision) {
            return InfluxCycleSettlement(current = false, followUpDemand = demandPending)
        }
        ownerRevision = null
        if (!represented && request.consumedDemand) demandPending = true
        return InfluxCycleSettlement(current = true, followUpDemand = demandPending)
    }

    fun invalidate() {
        ownerRevision = null
        demandPending = false
    }
}
