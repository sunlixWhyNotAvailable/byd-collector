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

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
