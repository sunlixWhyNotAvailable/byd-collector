package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(applicationText.contains("TelemetryStore("))
        assertTrue(applicationText.contains("internal val operationalEventJournal by lazy"))
        assertEquals(
            2,
            applicationText.split("operationalEventJournal = operationalEventJournal").size - 1
        )
        val onCreate = applicationText.substringAfter("override fun onCreate()").substringBefore("override fun onTerminate()")
        assertFalse(onCreate.contains("TelemetryDatabaseHelper("))
        assertFalse(onCreate.contains("TelemetryStore("))
        assertTrue(applicationText.contains("private fun store(): TelemetryStore"))
        assertTrue(applicationText.contains("private val databaseMaintenanceGate = DatabaseMaintenanceGate()"))
        assertTrue(applicationText.contains("fun <T> withDatabaseRead(action: () -> T)"))
        assertTrue(applicationText.contains("fun <T> withTelemetryStoreRead(action: (TelemetryStore) -> T)"))
        assertTrue(applicationText.contains("fun <T> withExclusiveDatabaseMaintenance(action: () -> T)"))
        assertInOrder(applicationText, "val current = telemetryStore", "telemetryStore = null", "current?.close()")
        val store = applicationText.substringAfter("private fun store(): TelemetryStore")
            .substringBefore("private data class StoreReadResult")
        assertInOrder(store, "withDatabaseRead", "return withExclusiveDatabaseMaintenance", "coordinator().ensureMainReady()")
        val debugReady = applicationText.substringAfter("fun ensureDebugStorageReady(): Boolean")
            .substringBefore("fun isDebugStorageReady")
        assertTrue(debugReady.contains("withExclusiveDatabaseMaintenance"))
        assertFalse(debugReady.contains("withDatabaseRead"))

        val serviceText = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        assertTrue(serviceText.contains("debugStore.close()"))
    }

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previousIndex = -1
        tokens.forEach { token ->
            val index = source.indexOf(token, previousIndex + 1)
            assertTrue(index > previousIndex, "Missing or out-of-order token: $token")
            previousIndex = index
        }
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
