package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityUpdateTimerContractTest {
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
        assertTrue(runtime.contains("UpdateAutoCheckRuntime.onDismissed()"))
        assertTrue(runtime.contains("!app.updateChecks.snapshot().inFlight"))
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

    private fun source(path: String): String = listOf(
        File("src/main/kotlin/com/bydcollector/collector/$path"),
        File("app/src/main/kotlin/com/bydcollector/collector/$path")
    ).first { it.isFile }.readText()
}
