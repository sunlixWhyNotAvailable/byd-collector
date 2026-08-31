package com.bydcollector.collector.telegram

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramDiagnosticContractTest {
    @Test
    fun sidecarDiagnosticQueryIsBoundedAndNeverSelectsPayloadOrErrors() {
        val store = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt").readText()
        val snapshot = store.substringAfter("internal fun diagnosticSnapshot")
            .substringBefore("fun saveTelegramRuntimeState")
        assertTrue(snapshot.contains("limit in 1..64"))
        assertTrue(snapshot.contains("LIMIT ?"))
        assertTrue(snapshot.contains("pendingPowerOffLocationTripId"))
        assertTrue(snapshot.contains("pendingPowerOffLocationPowerSessionId"))
        assertTrue(snapshot.contains("rowsTruncated"))
        assertTrue(snapshot.contains("dedupe_key"))
        assertTrue(snapshot.contains("pendingKeys"))
        assertFalse(snapshot.contains("payload"))
        assertFalse(snapshot.contains("last_error"))
        assertFalse(snapshot.contains("telegramBotToken"))
        assertFalse(snapshot.contains("telegramChatId"))
    }

    @Test
    fun operationalTelegramEventsCarryOnlyHashRefsAndLocationKind() {
        val source = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        assertTrue(source.contains("buildTelegramDiagnosticDetail"))
        assertTrue(source.contains("dedupe_ref="))
        assertTrue(source.contains("trip_ref="))
        assertTrue(source.contains("dependency_ref="))
        assertFalse(source.substringAfter("private fun buildTelegramDiagnosticDetail")
            .substringBefore("private fun telegramTripId")
            .contains("if (location == null)"))
        assertTrue(source.contains("\"attached\""))
        assertTrue(source.contains("\"only\""))
        assertTrue(source.contains("\"none\""))
        val eligibility = source.substringAfter("\"telegram_location_eligibility\"")
            .substringBefore("val priorityKeys")
        assertFalse(eligibility.contains("location = \"only\""))
        assertFalse(source.substringAfter("private fun buildTelegramDiagnosticDetail")
            .substringBefore("private fun telegramTripId")
            .contains("payload"))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
}
