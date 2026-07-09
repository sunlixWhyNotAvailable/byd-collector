package com.bydcollector.collector.ha

data class TailscaleGateDecision(
    val activated: Boolean,
    val category: String,
    val message: String
)

class TailscaleActivationGate(
    private val isEnabled: () -> Boolean,
    private val lastAttemptAtMs: () -> Long,
    private val setLastAttemptAtMs: (Long) -> Unit,
    private val isReachable: (HaEndpoint) -> Boolean,
    private val activate: () -> TailscaleActivationResult,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val throttleMs: Long = DEFAULT_THROTTLE_MS
) {
    fun maybeActivate(endpoint: HaEndpoint): TailscaleGateDecision {
        if (!isEnabled()) {
            return TailscaleGateDecision(false, "tailscale_activation_disabled", "Tailscale activation disabled")
        }
        if (!endpoint.valid) {
            return TailscaleGateDecision(false, "tailscale_activation_invalid_endpoint", "HA endpoint is not configured")
        }
        if (isReachable(endpoint)) {
            return TailscaleGateDecision(false, "tailscale_activation_skipped_ha_reachable", "HA endpoint reachable")
        }

        val now = nowMs()
        val lastAttempt = lastAttemptAtMs()
        if (lastAttempt > 0L && now - lastAttempt < throttleMs) {
            return TailscaleGateDecision(false, "tailscale_activation_throttled", "Tailscale activation skipped by throttle")
        }

        setLastAttemptAtMs(now)
        val result = activate()
        return TailscaleGateDecision(
            activated = result.ok,
            category = if (result.ok) "tailscale_activation_requested" else "tailscale_activation_failed",
            message = result.message
        )
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 5 * 60 * 1_000L
    }
}
