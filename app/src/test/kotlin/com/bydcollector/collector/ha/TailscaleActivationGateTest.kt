package com.bydcollector.collector.ha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TailscaleActivationGateTest {
    private val endpoint = HaEndpoint(channel = "mqtt", host = "100.64.0.10", port = 1883)

    @Test
    fun reachableHaSkipsActivation() {
        var activated = false
        var storedLastAttempt = 0L
        val gate = TailscaleActivationGate(
            isEnabled = { true },
            lastAttemptAtMs = { storedLastAttempt },
            setLastAttemptAtMs = { storedLastAttempt = it },
            isReachable = { true },
            activate = {
                activated = true
                TailscaleActivationResult(ok = true, message = "launched")
            },
            nowMs = { 10_000L }
        )

        val decision = gate.maybeActivate(endpoint)

        assertFalse(decision.activated)
        assertEquals("tailscale_activation_skipped_ha_reachable", decision.category)
        assertFalse(activated)
        assertEquals(0L, storedLastAttempt)
    }

    @Test
    fun unreachableHaRequestsActivationWhenEnabledAndNotThrottled() {
        var storedLastAttempt = 0L
        val gate = TailscaleActivationGate(
            isEnabled = { true },
            lastAttemptAtMs = { storedLastAttempt },
            setLastAttemptAtMs = { storedLastAttempt = it },
            isReachable = { false },
            activate = { TailscaleActivationResult(ok = true, message = "launched") },
            nowMs = { 10_000L }
        )

        val decision = gate.maybeActivate(endpoint)

        assertTrue(decision.activated)
        assertEquals("tailscale_activation_requested", decision.category)
        assertEquals(10_000L, storedLastAttempt)
    }

    @Test
    fun unreachableHaRespectsThrottle() {
        val gate = TailscaleActivationGate(
            isEnabled = { true },
            lastAttemptAtMs = { 9_000L },
            setLastAttemptAtMs = { error("must not update throttle while throttled") },
            isReachable = { false },
            activate = { error("must not activate while throttled") },
            nowMs = { 10_000L }
        )

        val decision = gate.maybeActivate(endpoint)

        assertFalse(decision.activated)
        assertEquals("tailscale_activation_throttled", decision.category)
    }

    @Test
    fun disabledSettingSkipsActivation() {
        val gate = TailscaleActivationGate(
            isEnabled = { false },
            lastAttemptAtMs = { 0L },
            setLastAttemptAtMs = { error("must not update throttle when disabled") },
            isReachable = { false },
            activate = { error("must not activate when disabled") },
            nowMs = { 10_000L }
        )

        val decision = gate.maybeActivate(endpoint)

        assertFalse(decision.activated)
        assertEquals("tailscale_activation_disabled", decision.category)
    }

    @Test
    fun invalidEndpointSkipsActivation() {
        val gate = TailscaleActivationGate(
            isEnabled = { true },
            lastAttemptAtMs = { 0L },
            setLastAttemptAtMs = { error("must not update throttle for invalid endpoint") },
            isReachable = { error("must not probe invalid endpoint") },
            activate = { error("must not activate for invalid endpoint") },
            nowMs = { 10_000L }
        )

        val decision = gate.maybeActivate(HaEndpoint(channel = "mqtt", host = "", port = 1883))

        assertFalse(decision.activated)
        assertEquals("tailscale_activation_invalid_endpoint", decision.category)
    }

    @Test
    fun activationFailureIsReportedAndThrottled() {
        var storedLastAttempt = 0L
        val gate = TailscaleActivationGate(
            isEnabled = { true },
            lastAttemptAtMs = { storedLastAttempt },
            setLastAttemptAtMs = { storedLastAttempt = it },
            isReachable = { false },
            activate = { TailscaleActivationResult(ok = false, message = "tailscale_not_installed") },
            nowMs = { 10_000L }
        )

        val decision = gate.maybeActivate(endpoint)

        assertFalse(decision.activated)
        assertEquals("tailscale_activation_failed", decision.category)
        assertEquals(10_000L, storedLastAttempt)
    }
}
