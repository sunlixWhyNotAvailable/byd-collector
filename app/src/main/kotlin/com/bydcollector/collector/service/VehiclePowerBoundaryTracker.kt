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

/** Tracks the first valid vehicle power transition without adding polling delay. */
class VehiclePowerBoundaryTracker {
    private var state = VehiclePowerState.UNKNOWN

    fun current(): VehiclePowerState = state

    /** Restores the only durable power evidence before the first sample is observed. */
    internal fun restoreBaseline(hasOpenSession: Boolean) {
        check(state == VehiclePowerState.UNKNOWN) { "Vehicle power baseline is already established" }
        state = if (hasOpenSession) VehiclePowerState.ON else VehiclePowerState.OFF
    }

    fun observe(decodedPowerLevel: Int?): VehiclePowerTransition? {
        if (decodedPowerLevel == null || decodedPowerLevel < 0) {
            return null
        }
        if (decodedPowerLevel > 0) {
            return transitionTo(VehiclePowerState.ON)
        }
        return transitionTo(VehiclePowerState.OFF)
    }

    fun rollback(transition: VehiclePowerTransition) {
        if (state == transition.current) state = transition.previous
    }

    private fun transitionTo(next: VehiclePowerState): VehiclePowerTransition? {
        if (state == next) return null
        return VehiclePowerTransition(state, next).also { state = next }
    }
}
