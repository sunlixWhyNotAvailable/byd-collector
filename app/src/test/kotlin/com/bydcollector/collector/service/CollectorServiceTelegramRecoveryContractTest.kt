package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceTelegramRecoveryContractTest {
    @Test
    fun serviceUsesOneGuardedRecoveryPathForLifecycleHints() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(service.contains("onConfirmedPowerOn = { requestTelegramRecovery(RECOVERY_VEHICLE_ON) }"))
        assertTrue(service.contains("requestTelegramRecovery(\n            if (unblockBlocked) RECOVERY_STARTUP_CREDENTIALS else RECOVERY_STARTUP\n        )"))
        assertTrue(service.contains("requestTelegramRecovery(RECOVERY_MANUAL_TEST_SUCCESS)"))
        assertTrue(service.contains("coordinator.testConnection { result ->"))
        assertTrue(service.contains("coordinator.recoverPending(request.trigger)"))
        assertTrue(service.contains("telegramRecoveryCoalescer.request(trigger, key, replacePendingKey)"))
        assertTrue(service.contains("onSettled = { completeTelegramRecovery(request.token, generation) }"))
        assertTrue(service.contains("!telegramRecoveryCoalescer.isCurrent(request.token)"))
    }

    @Test
    fun networkCallbackIsActiveDefaultOnlyAndNotValidatedGated() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(service.contains("registerDefaultNetworkCallback(callback)"))
        assertTrue(service.contains("unregisterNetworkCallback(callback)"))
        assertTrue(service.contains("override fun onCapabilitiesChanged"))
        assertTrue(service.contains("override fun onLinkPropertiesChanged"))
        assertTrue(service.contains("telegramNetworkRecoveryRevision.incrementAndGet()"))
        assertTrue(service.contains("validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)"))
        assertTrue(service.contains("mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) linkProperties.mtu else 0"))
        assertFalse(service.contains("if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))"))
        assertFalse(service.contains("getNetworkCapabilities(network)"))
        assertFalse(service.contains("TelegramReachabilityProbe"))
    }

    @Test
    fun networkCallbackAndRecoveryAreInvalidatedBeforeTeardown() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val destroy = service.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onTaskRemoved")

        assertTrue(destroy.contains("telegramWorkGeneration.incrementAndGet()"))
        assertTrue(destroy.contains("telegramRecoveryCoalescer.invalidate()"))
        assertTrue(destroy.contains("unregisterTelegramNetworkCallback()"))
        assertTrue(destroy.indexOf("unregisterTelegramNetworkCallback()") < destroy.indexOf("shutdownTelegramExecutor()"))
    }

    @Test
    fun confirmedPowerOnUsesTransitionBoundaryAndIsHistoryIndependent() {
        val runtime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()
        val transition = runtime.substringAfter("val transition = powerTracker.observe(decodedPower)")
            .substringBefore("if (powerTracker.current()")

        assertTrue(transition.contains("VehiclePowerState.ON ->"))
        assertTrue(transition.contains("handlePowerOn(timestamp, snapshot)"))
        assertTrue(transition.contains("runCatching { onConfirmedPowerOn() }"))
        assertFalse(transition.contains("powerTracker.current() == VehiclePowerState.ON"))
        assertTrue(runtime.contains("private val onConfirmedPowerOn: () -> Unit = {}"))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull(File::isFile) ?: error("Missing source file: $path")
}
