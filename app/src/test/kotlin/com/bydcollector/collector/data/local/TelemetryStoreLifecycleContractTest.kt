package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryStoreLifecycleContractTest {
    @Test
    fun mainTelemetryStoreIsApplicationScoped() {
        val forbiddenFiles = listOf(
            "com/bydcollector/collector/MainActivity.kt",
            "com/bydcollector/collector/service/CollectorService.kt",
            "com/bydcollector/collector/system/CollectorAutoStart.kt",
            "com/bydcollector/collector/system/KeepAliveRecoveryReceiver.kt"
        )

        forbiddenFiles.forEach { path ->
            val text = sourceFile(path).readText()
            assertFalse(
                text.contains("TelemetryDatabaseHelper("),
                "$path must use BydCollectorApplication.store(context) for the main DB"
            )
        }

        val applicationText = sourceFile("com/bydcollector/collector/BydCollectorApplication.kt").readText()
        assertTrue(applicationText.contains("TelemetryDatabaseHelper(applicationContext)"))
        assertTrue(applicationText.contains("TelemetryStore(applicationContext"))

        val serviceText = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        assertTrue(serviceText.contains("debugStore.close()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
