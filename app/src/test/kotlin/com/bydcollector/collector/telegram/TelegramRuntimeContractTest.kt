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

    @Test
    fun pendingTripDeadlineReschedulesTheExistingTelegramTick() {
        val engine = sourceFile("com/bydcollector/collector/service/TelegramEventEngine.kt").readText()
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val schedule = service.substringAfter("private fun scheduleTelegramTick")
            .substringBefore("private fun cancelTelegramTick")
        val tickTask = service.substringAfter("private val telegramTickTask")
            .substringBefore("private val accessSelfCheckTask")
        val postSchedule = service.substringAfter("private fun postTelegramTickSchedule")
            .substringBefore("private fun cancelTelegramTick")
        val executeTelegram = service.substringAfter("private fun <T> executeTelegram")
            .substringBefore("private fun <T> executeChannel")
        val executeChannel = service.substringAfter("private fun <T> executeChannel")
            .substringBefore("private data class ChannelActionStatus")

        assertInOrder(engine, "finalizePendingTrip(config, nowMs, events)", "if (!mainCollectionExpected)")
        assertTrue(engine.contains("nextWakeAtMs: Long?"))
        assertTrue(engine.contains("pendingTripDeadline(config)"))
        assertTrue(coordinator.contains("fun onSuccessfulPoll(observations: List<NormalizedObservation>): Long?"))
        assertTrue(coordinator.contains("fun tick(mainCollectionExpected: Boolean, lastError: String?): Long?"))
        assertTrue(coordinator.contains("return result.nextWakeAtMs"))
        assertTrue(service.contains("private fun scheduleTelegramTick(deadlineAtMs: Long? = null)"))
        assertTrue(schedule.contains("maintenanceBlocksRuntimeStart()"))
        assertTrue(tickTask.contains("maintenanceBlocksRuntimeStart()"))
        assertTrue(schedule.contains("minOf(it, regularAtMs)"))
        assertTrue(service.contains("scheduleTelegramTick(deadlineAtMs)"))
        assertTrue(schedule.contains("telegramTickAtMs?.let { it <= targetAtMs }"))
        assertTrue(executeTelegram.contains("canExecute = { !maintenanceBlocksRuntimeStart() }"))
        assertInOrder(executeChannel, "!canExecute()", "action()")
        assertInOrder(postSchedule, "mainHandler.post", "submittedGeneration != telegramWorkGeneration.get()")
        assertInOrder(postSchedule, "submittedGeneration != telegramWorkGeneration.get()", "scheduleTelegramTick(deadlineAtMs)")
        assertInOrder(executeChannel, "submittedGeneration != generation.get()", "submittedGeneration == generation.get()")
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
