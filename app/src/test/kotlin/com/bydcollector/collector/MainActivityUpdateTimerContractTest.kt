package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityUpdateTimerContractTest {
    @Test
    fun overlayPermissionIsNativeOptionalAndPromptedOnceUnlessUserRetries() {
        val activity = source("MainActivity.kt")
        val access = source("system/RequiredAccessChecker.kt")
        val adb = source("adb/AdbAuthorizationManager.kt")
        assertFalse(access.contains("missingOverlayGrantCommand"))
        assertFalse(adb.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(adb.contains("missingOverlayGrantCommand"))
        assertTrue(adb.contains("val repairNeeded = !permissionsGranted || !helperReady"))
        val permission = activity.substringAfter("private fun maybeRequestOverlayAccess(")
            .substringBefore("private fun maybeRunStartupLocationPermission()")
        assertTrue(permission.contains("!foreground || !mainWindowHasFocus"))
        assertTrue(permission.contains("!userInitiated && prefs.getBoolean(KEY_OVERLAY_PERMISSION_SETUP_CONSUMED, false)"))
        assertTrue(permission.indexOf("putBoolean(KEY_OVERLAY_PERMISSION_SETUP_CONSUMED, true)") <
            permission.indexOf("overlayPermissionLauncher.launch("))
        assertTrue(permission.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
        assertFalse(permission.contains("requestAccessCheck"))
        assertFalse(permission.contains("CollectorService"))
        assertTrue(activity.contains("if (enabled) maybeRequestOverlayAccess(userInitiated = true)"))
    }

    @Test
    fun cachedOpportunityIsConsumedOnDrawingNotEligibilityOrVisibility() {
        val runtime = source("update/UpdateRuntime.kt")
        val visible = runtime.substringAfter("fun onUiVisible()").substringBefore("fun onUiHidden()")
        assertFalse(visible.contains("presentation."))
        val pending = runtime.substringAfter("private fun presentPendingHint()").substringBefore("fun onAutoCheckEnabledChanged()")
        assertFalse(pending.contains("request("))
        assertFalse(pending.contains("postDelayed"))
        val overlay = source("update/UpdateHintOverlay.kt")
        val draw = overlay.substringAfter("view.doOnPreDraw {").substringBefore("// Bounded safety net")
        assertTrue(draw.contains("app.updateRuntime.onHintPresented(resultId)"))
        assertFalse(overlay.substringBefore("view.doOnPreDraw {").contains("onHintPresented("))
        val activity = source("MainActivity.kt")
        val sync = activity.substringAfter("private fun syncUpdateCheckUi()").substringBefore("private fun recordUpdateEvent")
        assertFalse(sync.contains("offer_shown"))
        assertTrue(activity.contains("renderedOfferResultId?.let(updateRuntime::onOfferPresented)"))
        val dialog = source("ui/compose/BydCollectorApp.kt").substringAfter("private fun UpdateCheckDialog(")
            .substringBefore("private fun TripsCompressionDialog(")
        assertTrue(dialog.replace("\r", "").contains("drawContent()\n                    if (state is UpdateUiState.Available) onPresented()"))
    }

    @Test
    fun processOwnsTimerAndActivityOnlyReportsVisibility() {
        val activity = source("MainActivity.kt")
        val runtime = source("update/UpdateRuntime.kt")
        val app = source("BydCollectorApplication.kt")
        val appCreate = app.substringAfter("override fun onCreate()").substringBefore("override fun onTerminate()")
        val stop = activity.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()")
        val pause = activity.substringAfter("override fun onPause()").substringBefore("override fun onStop()")

        assertTrue(runtime.contains("private val timer = Runnable"))
        assertTrue(runtime.contains("handler.removeCallbacks(timer)"))
        assertTrue(runtime.contains("handler.postDelayed(timer, action.delayMs)"))
        assertFalse(activity.contains("updateAutoCheckTimerTask"))
        assertTrue(activity.contains("updateRuntime.onUiVisible()"))
        assertTrue(stop.contains("!isChangingConfigurations"))
        assertTrue(stop.contains("updateRuntime.onUiHidden()"))
        assertFalse(pause.contains("updateRuntime.onUiHidden()"))
        assertFalse(appCreate.contains("onRuntimeStarted("))
        assertFalse(appCreate.contains(".check()"))
        assertFalse(runtime.contains("CollectorServiceController"))
        listOf("service/CollectorService.kt", "system/AutoStartRecoveryService.kt", "system/CollectorNotificationListenerService.kt").forEach {
            assertTrue(source(it).contains(".updateRuntime.start("), it)
        }
    }

    @Test
    fun updateCallbacksStayInvalidatableAndChecksAreShared() {
        val activity = source("MainActivity.kt")
        val runtime = source("update/UpdateRuntime.kt")
        val dismiss = activity.substringAfter("override fun onDismissUpdateDialog()").substringBefore("override fun onInstallUpdate()")
        val download = activity.substringAfter("private fun startUpdateDownload").substringBefore("private fun loadCredentialsAfterFirstFrame")
        assertTrue(dismiss.contains("updateRuntime.dismissOffer()"))
        assertTrue(dismiss.contains("updateUiGeneration += 1L"))
        assertTrue(runtime.contains("app.updateChecks.request(manual)"))
        assertTrue(runtime.contains("UpdateAutoCheckRuntime.onCheckStarted()"))
        assertTrue(runtime.contains("UpdateAutoCheckRuntime.onCheckCompleted("))
        assertTrue(runtime.contains("UpdateAutoCheckRuntime.onDismissed()"))
        assertTrue(runtime.contains("app.updateChecks.completionsAfter(lastProcessedCompletionToken)"))
        assertTrue(runtime.contains("completion.generation != generation"))
        assertTrue(runtime.contains("app.updateChecks.acknowledgeCompletionsThrough(lastProcessedCompletionToken)"))
        assertTrue(runtime.contains("if (staleFlightReleased)"))
        assertTrue(runtime.substringAfter("if (staleFlightReleased)").contains("UpdateAutoCheckRuntime.onTimerElapsed("))
        assertTrue(download.contains("updateChecks.clearPresentation()"))
        assertTrue(download.contains("val uiGeneration = ++updateUiGeneration"))
        assertTrue(Regex("uiGeneration == updateUiGeneration").findAll(download).count() >= 3)
        assertTrue(download.contains("updateDownloader.install(verified.info, verified.file)"))
        val destroy = activity.substringAfter("override fun onDestroy()").substringBefore("override fun onBackPressed()")
        assertTrue(destroy.contains("updateChecks.removeListener(updateCheckListener)"))
        assertFalse(destroy.contains("updateChecks.reset()"))
    }

    @Test
    fun installerHandoffKeepsTheGateUntilCollectorReturns() {
        val activity = source("MainActivity.kt")
        val runtime = source("update/UpdateRuntime.kt")
        val install = activity.substringAfter("runCatching { updateDownloader.install(")
            .substringBefore("private fun loadCredentialsAfterFirstFrame")
        val success = install.substringAfter(".onSuccess {").substringBefore(".onFailure {")
        assertTrue(success.contains("updateRuntime.onInstallerLaunched()"))
        assertFalse(success.contains("onInstallFinished()"))
        assertTrue(install.substringAfter(".onFailure {").contains("updateRuntime.onInstallFinished()"))
        assertTrue(runtime.contains("if (installing) awaitingInstallerReturn = true"))
        assertTrue(runtime.contains("if (awaitingInstallerReturn) onInstallFinished()"))
        assertTrue(activity.substringAfter("override fun onResume()").substringBefore("override fun onWindowFocusChanged")
            .contains("updateRuntime.onUiResumed()"))
        assertTrue(runtime.contains("if (!started || installing || settings.isUserShutdownRequested()"))
    }

    @Test
    fun optionalOverlaySetupFailureKeepsTheOfferAndIsLogged() {
        val show = source("update/UpdateHintOverlay.kt").substringAfter("fun show(").substringBefore("fun refresh(")
        assertTrue(show.indexOf("try {") < show.indexOf("Settings.canDrawOverlays"))
        assertTrue(show.indexOf("try {") < show.indexOf("createWindowContext"))
        assertTrue(show.contains("reason=no_main_display"))
        assertTrue(show.contains("catch (error: RuntimeException)"))
        assertTrue(show.contains("dismiss(\"window_failed\")"))
        assertFalse(show.contains("clearPresentation()"))
        assertFalse(show.contains("updateChecks.reset()"))
    }

    @Test
    fun admittedRuntimeObservesScreenAndBootWithoutStartingVehicleWork() {
        val runtime = source("update/UpdateRuntime.kt")
        val start = runtime.substringAfter("fun start(source: String)").substringBefore("fun onSystemWake(")
        assertTrue(start.indexOf("settings.isUserShutdownRequested()") < start.indexOf("observeWake()"))
        assertTrue(start.contains("wakePolicy.onEntry(SystemClock.elapsedRealtime(), isInteractive())"))
        assertTrue(start.contains("wakePolicy.sleeping ->"))
        assertTrue(runtime.contains("addAction(Intent.ACTION_SCREEN_OFF)"))
        assertTrue(runtime.contains("addAction(Intent.ACTION_SCREEN_ON)"))
        assertTrue(runtime.contains("addAction(Intent.ACTION_USER_PRESENT)"))
        val sleep = runtime.substringAfter("private fun pauseForSleep()").substringBefore("private fun restartAfterWake(")
        assertTrue(sleep.contains("handler.removeCallbacks(timer)"))
        assertTrue(sleep.contains("app.updateChecks.invalidateAutomatic()"))
        assertFalse(runtime.contains("CollectorServiceController"))
        assertFalse(runtime.contains("CollectorAutoStart"))
        val boot = source("system/BootReceiver.kt")
        assertTrue(boot.contains("action == Intent.ACTION_BOOT_COMPLETED || action == ACTION_QUICKBOOT_POWERON"))
        assertTrue(boot.indexOf("updateRuntime.onSystemWake(action)") < boot.indexOf("handoffAutoStartRecovery("))
        assertTrue(runtime.contains("app.unregisterReceiver(receiver)"))
    }

    @Test
    fun newWakeResetsCloseSuppressionButRetainsManualFlightAndOneTimer() {
        val runtime = source("update/UpdateRuntime.kt")
        val wake = runtime.substringAfter("private fun restartAfterWake(").substringBefore("fun onUiVisible()")
        assertTrue(wake.contains("UpdateAutoCheckRuntime.reset()"))
        assertTrue(wake.contains("app.updateChecks.hasCurrentManualRequest()"))
        assertTrue(wake.contains("if (manualInFlight) UpdateAutoCheckRuntime.onCheckStarted()"))
        assertTrue(wake.contains("else applyAction(action)"))
        val request = runtime.substringAfter("fun request(manual: Boolean)").substringBefore("fun dismissOffer()")
        assertTrue(request.indexOf("processCompletions(applyScheduling = false)") < request.indexOf("app.updateChecks.request(manual)"))
        assertTrue(request.contains("action != UpdateAutoCheckAction.Run"))
        assertTrue(runtime.contains("started && !wakePolicy.sleeping && settings.isUpdateAutoCheckEnabled()"))
        val disabled = runtime.substringAfter("fun onAutoCheckEnabledChanged()").substringBefore("fun request(")
        assertTrue(disabled.contains("if (!settings.isUpdateAutoCheckEnabled()) app.updateChecks.invalidateAutomatic()"))
        assertEquals(1, Regex("private val timer = Runnable").findAll(runtime).count())
    }

    private fun source(path: String): String = listOf(
        File("src/main/kotlin/com/bydcollector/collector/$path"),
        File("app/src/main/kotlin/com/bydcollector/collector/$path")
    ).first { it.isFile }.readText()
}
