package com.bydcollector.collector.update

/** Pure process-local filter for noisy vehicle wake broadcasts and runtime entries. */
class UpdateWakePolicy(
    private val fallbackWakeIntervalMs: Long = 30_000L
) {
    var sleeping: Boolean = false
        private set

    private var initialized = false
    private var lastEntryOrWakeMs = 0L

    /** Records a runtime entry and admits only a real asleep-to-interactive transition. */
    fun onEntry(now: Long, interactive: Boolean): Boolean {
        if (!initialized) {
            initialized = true
            sleeping = !interactive
            lastEntryOrWakeMs = now
            return false
        }

        if (!interactive) {
            sleeping = true
            return false
        }
        if (sleeping) {
            sleeping = false
            lastEntryOrWakeMs = now
            return true
        }
        return false
    }

    fun onSleep() {
        sleeping = true
    }

    /**
     * Admits every genuine sleep-to-wake transition. While already awake,
     * ordinary duplicate broadcasts are ignored and QUICKBOOT_POWERON is a
     * bounded fallback for platforms that omit the corresponding sleep event.
     */
    fun onWake(action: String, now: Long): Boolean {
        if (!initialized) {
            initialized = true
            sleeping = false
            lastEntryOrWakeMs = now
            return true
        }
        if (sleeping) {
            sleeping = false
            lastEntryOrWakeMs = now
            return true
        }
        if (action == ACTION_QUICKBOOT_POWERON && now - lastEntryOrWakeMs >= fallbackWakeIntervalMs) {
            lastEntryOrWakeMs = now
            return true
        }
        return false
    }

    fun reset() {
        initialized = false
        sleeping = false
        lastEntryOrWakeMs = 0L
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
