package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityUpdateTimerContractTest {
    @Test
    fun updateAutoCheckUsesStableRunnableForPostAndRemove() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertTrue(source.contains("private val updateAutoCheckTimerTask = Runnable { onUpdateAutoCheckTimerElapsed() }"))
        assertTrue(source.contains("handler.removeCallbacks(updateAutoCheckTimerTask)"))
        assertTrue(source.contains("handler.postDelayed(updateAutoCheckTimerTask, action.delayMs)"))
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
        assertTrue(check.contains("val uiGeneration = ++updateUiGeneration"))
        assertTrue(check.contains("uiGeneration != updateUiGeneration"))
        assertTrue(download.contains("val uiGeneration = ++updateUiGeneration"))
        assertTrue(Regex("uiGeneration == updateUiGeneration").findAll(download).count() >= 3)
        assertTrue(download.contains("updateDownloader.install(verified.info, verified.file)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
