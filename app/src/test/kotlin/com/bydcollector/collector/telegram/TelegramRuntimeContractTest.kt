package com.bydcollector.collector.telegram

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TelegramRuntimeContractTest {
    @Test
    fun newTripDiscardsOnlyUndeliveredTripSummariesBeforeStateHandling() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val poll = coordinator.substringAfter("fun onSuccessfulPoll")
            .substringBefore("fun tick")
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()

        assertInOrder(poll, "val previousTripId", "deleteUndeliveredTelegramMessages")
        assertInOrder(poll, "deleteUndeliveredTelegramMessages", "handle(result)")
        assertTrue(poll.contains("TelegramEventType.TRIP_SUMMARY.key"))
        assertTrue(store.contains("\"event_type = ?\""))
        assertTrue(coordinator.contains("telegramTripEndDelaySeconds() * 1_000L"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing token: " + first)
        assertTrue(secondIndex > firstIndex, "Expected " + first + " before " + second)
    }
}
