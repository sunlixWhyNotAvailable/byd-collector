package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessWorkflowContractTest {
    @Test
    fun uiUsesRuntimeSnapshotWithoutWaitingOrTtlCaching() {
        val manager = sourceFile("com/bydcollector/collector/adb/AdbAuthorizationManager.kt").readText()
        val provider = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertTrue(manager.contains("private var runtimeSnapshot = AccessRuntimeSnapshot()"))
        assertFalse(manager.contains("KEY_LAST_AUTH_CATEGORY"))
        assertFalse(manager.contains("KEY_LAST_AUTH_CHECK_AT_MS"))
        assertTrue(provider.contains("val accessSnapshot = AdbAuthorizationManager.currentSnapshot()"))
        assertTrue(provider.contains("permissionsGranted = accessSnapshot.permissionsGranted"))
        assertTrue(provider.contains("adbAuthorized = accessSnapshot.adbAuthorized"))
        assertFalse(provider.contains("requiredAccessCache"))
        assertTrue(app.contains("state?.adbAuthorized == true"))
        assertTrue(app.contains("state?.permissionsGranted == true"))
        assertFalse(app.contains("hasAllAccess"))
    }

    @Test
    fun coldAndManualUiFlowsUseTheRequiredModesAndOrdering() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val manager = sourceFile("com/bydcollector/collector/adb/AdbAuthorizationManager.kt").readText()

        assertTrue(source.contains("private const val STARTUP_ADB_SELF_CHECK_DELAY_MS = 600L"))
        assertFalse(source.contains("DELAYED_ADB_AUTH_AFTER_BACKGROUND_MS"))
        assertTrue(source.contains("requestAccessCheck(source, AccessCheckMode.FORCE"))
        assertTrue(source.contains("mode = AccessCheckMode.COLD_START"))
        assertTrue(source.contains("override fun onWindowFocusChanged(hasFocus: Boolean)"))
        assertTrue(source.contains("private fun startupUiBlocked(): Boolean"))
        assertTrue(source.contains("settings.dbMaintenanceStatus()"))
        assertTrue(source.contains("settings.archiveStorageJobStatus().running"))
        assertTrue(source.contains("onArchiveDeletePromptVisibilityChanged"))
        assertTrue(source.contains("handler.removeCallbacks(startupAdbSelfCheckTask)"))
        assertTrue(source.contains("val submitted = requestAccessCheck("))
        assertTrue(source.contains("if (!submitted) startupAdbSelfCheckPosted = false"))
        assertTrue(manager.contains("private val automaticPromptAttemptedInProcess = AtomicBoolean(false)"))
        assertTrue(manager.contains("return submitted"))
        assertTrue(manager.contains("automaticPromptAttemptedInProcess.compareAndSet(false, true)"))
        assertFalse(manager.contains("ADB_PREFS"))
        assertFalse(manager.contains("KEY_AUTO_PROMPTED_KEY_FINGERPRINT"))
        assertFalse(manager.contains("KEY_AUTO_PROMPTED_APP_VERSION"))
        assertInOrder(method(source, "override fun onStartMain", "override fun onStopMain"), "requestAccessCheck", "CollectorServiceController.start")
        assertInOrder(method(source, "override fun onStartDebug", "override fun onStopDebug"), "requestAccessCheck", "CollectorServiceController.startDebug")
        assertInOrder(method(source, "override fun onStartMqtt", "override fun onStopMqtt"), "requestAccessCheck", "CollectorServiceController.startMqttExport")
        assertInOrder(method(source, "override fun onStartInflux", "override fun onStopInflux"), "requestAccessCheck", "CollectorServiceController.startInfluxExport")
        assertInOrder(method(source, "private fun startDiagnostics", "private fun stopDiagnostics"), "requestAccessCheck", "DiagnosticLogRecorder.start")
    }

    @Test
    fun serviceUsesExactActiveWorkAndIndependentFiveMinuteCadence() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()

        assertTrue(service.contains("private const val ACCESS_SELF_CHECK_INTERVAL_MS = 5 * 60_000L"))
        assertTrue(service.contains("if (accessSelfCheckScheduled) return"))
        assertTrue(service.contains("mainHandler.removeCallbacks(accessSelfCheckTask)"))
        assertTrue(settings.contains("isPollingEnabled() && !isMainManuallyStopped()"))
        assertTrue(settings.contains("isDebugPollingEnabled() && !isDebugManuallyStopped()"))
        assertTrue(settings.contains("isMqttEnabled() && !isMqttManuallyStopped()"))
        assertTrue(settings.contains("isInfluxEnabled() && !isInfluxManuallyStopped()"))
        assertTrue(autoStart.contains("clearsManualStops(action) && settings.isAutoStartEnabled() && settings.hasActiveAccessWork()"))
        assertTrue(autoStart.contains("const val WATCHDOG_DELAY_MS = 60_000L"))
    }

    private fun sourceFile(path: String): File {
        return listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun method(source: String, start: String, end: String): String {
        return source.substringAfter(start).substringBefore(end)
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }
}
