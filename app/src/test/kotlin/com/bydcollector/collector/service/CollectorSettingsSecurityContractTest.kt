package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorSettingsSecurityContractTest {
    @Test
    fun legacyTripDelayMigratesToBoundedSeconds() {
        assertEquals(120, CollectorSettings.legacyTripDelaySeconds(2))
        assertEquals(300, CollectorSettings.legacyTripDelaySeconds(60))
        assertEquals(5, CollectorSettings.legacyTripDelaySeconds(0))
        assertEquals(10, CollectorSettings.DEFAULT_TELEGRAM_TRIP_END_DELAY_SECONDS)
    }

    @Test
    fun keystoreReadsNeverFallBackToLegacyPlaintext() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val secretValue = source.substringAfter("private fun secretValue")
            .substringBefore("private fun writeSecret")
        val migration = source.substringAfter("private fun migrateLegacySecret")
            .substringBefore("private fun migrateTripEndDelayToSeconds")

        assertFalse(secretValue.contains("prefs."))
        assertTrue(migration.contains("!existing.isNullOrBlank() -> true"))
        assertTrue(migration.contains("remove(preferenceKey)"))
        assertTrue(migration.contains("putBoolean(integrationEnabledKey, false)"))
        assertTrue(source.contains("legacyPreferenceKey?.let(editor::remove)"))
        assertTrue(source.contains("integrationEnabledKey?.let { editor.putBoolean(it, false) }"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
