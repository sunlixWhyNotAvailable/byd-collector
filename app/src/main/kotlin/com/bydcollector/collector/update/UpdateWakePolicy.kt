package com.bydcollector.collector.update

/** Process-local state from the live POWER source and the main display, never a wake broadcast alone. */
class UpdateWakePolicy {
    var powerLevel: Int? = null
        private set
    var screenOn: Boolean = false
        private set
    val ready: Boolean get() = powerLevel in 1..4 && screenOn

    /** Returns whether the combined condition changed; duplicate events preserve the deadline. */
    fun onState(powerLevel: Int?, screenOn: Boolean): Boolean {
        val wasReady = ready
        this.powerLevel = powerLevel?.takeIf { it in 0..4 }
        this.screenOn = screenOn
        return ready != wasReady
    }

    fun reset() {
        powerLevel = null
        screenOn = false
    }
}
