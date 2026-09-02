package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityUpdateTimerContractTest {
    @Test
    fun updateAutoCheckUsesStableRunnableForPostAndRemove() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val onCreate = source.substringAfter("override fun onCreate").substringBefore("override fun onResume")
        val updateCheck = source.substringAfter("private fun runUpdateCheck(force: Boolean)")
            .substringBefore("private fun startUpdateDownload")

        assertTrue(source.contains("private val updateAutoCheckTimerTask = Runnable { onUpdateAutoCheckTimerElapsed() }"))
        assertTrue(source.contains("handler.removeCallbacks(updateAutoCheckTimerTask)"))
        assertTrue(source.contains("handler.postDelayed(updateAutoCheckTimerTask, action.delayMs)"))
        assertTrue(onCreate.contains("startRuntimeUpdateAutoCheck()"))
        assertTrue(onCreate.indexOf("startRuntimeUpdateAutoCheck()") < onCreate.indexOf("dashboardExecutor.execute"))
        assertFalse(source.contains("startupUpdateAutoCheckStarted"))
        assertFalse(updateCheck.contains("startupAccessFlowCompleted"))
        assertFalse(source.contains("handler.removeCallbacks(::onUpdateAutoCheckTimerElapsed)"))
        assertFalse(source.contains("handler.postDelayed(::onUpdateAutoCheckTimerElapsed"))
    }

    @Test
    fun dismissInvalidatesAsyncUpdateUiCallbacks() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val dismiss = source.substringAfter("override fun onDismissUpdateDialog()")
            .substringBefore("override fun onInstallUpdate()")
        val check = source.substringAfter("private fun runUpdateCheck(force: Boolean)")
            .substringBefore("private fun startUpdateDownload")
        val download = source.substringAfter("private fun startUpdateDownload")
            .substringBefore("private fun saveMqttDraft")

        assertTrue(source.contains("private var updateUiGeneration = 0L"))
        assertTrue(dismiss.contains("updateUiGeneration += 1L"))
        assertTrue(dismiss.contains("updateChecks.dismiss()"))
        assertTrue(dismiss.contains("UpdateAutoCheckRuntime.onDismissed()"))
        assertTrue(check.contains("updateChecks.request(manual = force)"))
        assertTrue(check.contains("if (accepted)"))
        assertTrue(check.contains("UpdateAutoCheckRuntime.onCheckStarted()"))
        assertTrue(download.contains("updateChecks.clearPresentation()"))
        assertTrue(download.contains("val uiGeneration = ++updateUiGeneration"))
        assertTrue(Regex("uiGeneration == updateUiGeneration").findAll(download).count() >= 3)
        assertTrue(download.contains("updateDownloader.install(verified.info, verified.file)"))
    }

    @Test
    fun runtimeOwnsCheckAndUiOnlyObservesItAcrossRecreation() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val app = sourceFile("com/bydcollector/collector/BydCollectorApplication.kt").readText()
        val appCreate = app.substringAfter("override fun onCreate()").substringBefore("override fun onTerminate()")
        val onStop = activity.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()")
        val onDestroy = activity.substringAfter("override fun onDestroy()").substringBefore("override fun onBackPressed()")

        assertTrue(app.contains("UpdateCheckSession(updateCheckExecutorDelegate.value)"))
        assertTrue(appCreate.contains("UpdateAutoCheckRuntime.onRuntimeStarted"))
        assertFalse(appCreate.contains(".check()"))
        assertTrue(activity.contains("updateChecks.addListener(updateCheckListener)"))
        assertTrue(onDestroy.contains("updateChecks.removeListener(updateCheckListener)"))
        assertFalse(onDestroy.contains("updateChecks.reset()"))
        assertFalse(activity.contains("updateChecker.check"))
        assertTrue(onStop.contains("!isChangingConfigurations"))
        assertTrue(onStop.contains("UpdateAutoCheckRuntime.onBackground"))
        assertTrue(onStop.contains("handler.removeCallbacks(updateAutoCheckTimerTask)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
