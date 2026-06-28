package com.bydcollector.collector.keepalive

//remembers steady-state reconcile results so the watchdog does not open local adb every minute
class KeepAliveReconcileState(
    private val aliveTtlMs: Long
) {
    private var lastAppliedConfig: KeepAliveConfig? = null
    private var lastAliveAtMs: Long = 0L

    fun currentConfigEnabled(): Boolean = lastAppliedConfig?.anyEnabled == true

    fun configChanged(config: KeepAliveConfig): Boolean = config != lastAppliedConfig

    fun shouldStopDaemonForDisabledConfig(configChanged: Boolean): Boolean {
        return configChanged || currentConfigEnabled()
    }

    fun markConfigApplied(config: KeepAliveConfig) {
        lastAppliedConfig = config
    }

    fun markAlive(nowMs: Long) {
        lastAliveAtMs = nowMs
    }

    fun clearAlive() {
        lastAliveAtMs = 0L
    }

    fun aliveFresh(nowMs: Long): Boolean {
        return lastAliveAtMs > 0L && nowMs - lastAliveAtMs < aliveTtlMs
    }
}
