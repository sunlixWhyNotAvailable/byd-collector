package com.bydcollector.collector.service

enum class VehiclePowerState {
    UNKNOWN,
    ON,
    OFF
}

data class VehiclePowerTransition(
    val previous: VehiclePowerState,
    val current: VehiclePowerState
)

/** Confirms shutdown conservatively while accepting one valid non-zero wake sample. */
class VehiclePowerBoundaryTracker(
    private val offConfirmationCount: Int = 3
) {
    private var state = VehiclePowerState.UNKNOWN
    private var consecutiveZeroes = 0

    init {
        require(offConfirmationCount > 0)
    }

    fun current(): VehiclePowerState = state

    fun observe(decodedPowerLevel: Int?): VehiclePowerTransition? {
        if (decodedPowerLevel == null || decodedPowerLevel < 0) {
            consecutiveZeroes = 0
            return null
        }
        if (decodedPowerLevel > 0) {
            consecutiveZeroes = 0
            return transitionTo(VehiclePowerState.ON)
        }
        consecutiveZeroes += 1
        return if (consecutiveZeroes >= offConfirmationCount) {
            transitionTo(VehiclePowerState.OFF)
        } else {
            null
        }
    }

    private fun transitionTo(next: VehiclePowerState): VehiclePowerTransition? {
        if (state == next) return null
        return VehiclePowerTransition(state, next).also { state = next }
    }
}
