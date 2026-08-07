package com.bydcollector.collector.telegram

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
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
        assertTrue(coordinator.contains("reachabilityMainPollState: () -> Pair<Boolean, Long?>"))
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

    @Test
    fun outboxAndRuntimeStateUseOneSqliteTransaction() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val handle = coordinator.substringAfter("private fun handle")
            .substringBefore("private fun render")
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val commit = store.substringAfter("fun commitTelegramEvents")
            .substringBefore("private fun enqueueTelegramMessage")

        assertTrue(handle.contains("store.commitTelegramEvents("))
        assertTrue(handle.contains("engine = TelegramEventEngine(TelegramEventState.fromJson(store.telegramRuntimeState()))"))
        assertFalse(handle.contains("saveTelegramRuntimeState"))
        assertInOrder(commit, "db.beginTransaction()", "enqueueTelegramMessage(db, message, nowMs)")
        assertInOrder(commit, "enqueueTelegramMessage(db, message, nowMs)", "saveTelegramRuntimeState(db, it, nowMs)")
        assertInOrder(commit, "saveTelegramRuntimeState(db, it, nowMs)", "db.setTransactionSuccessful()")
        assertInOrder(commit, "db.setTransactionSuccessful()", "db.endTransaction()")
    }

    @Test
    fun flushStopsBetweenRequestsWhenMaintenanceInterruptsTheWorker() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val probe = sourceFile("com/bydcollector/collector/telegram/TelegramReachabilityProbe.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val flush = coordinator.substringAfter("fun flushPending").substringBefore("private fun handle")
        val mainPollState = service.substringAfter("private fun telegramReachabilityMainPollState")
            .substringBefore("private fun exportInfluxAfterNormalizedWrite")

        assertInOrder(flush, "for (entry in store.dueTelegramMessages(nowMs()))", "Thread.currentThread().isInterrupted")
        assertInOrder(flush, "Thread.currentThread().isInterrupted", "client.sendMessage")
        assertInOrder(coordinator, "flushPending()", "if (Thread.currentThread().isInterrupted) return result.nextWakeAtMs")
        assertInOrder(coordinator, "if (Thread.currentThread().isInterrupted) return result.nextWakeAtMs", "reachabilityProbe?.maybeProbe(")
        assertInOrder(probe, "val reservation = synchronized(this)", "val (mainCollectionExpected, mainPollStaleMs) = mainPollState()")
        assertInOrder(probe, "val (mainCollectionExpected, mainPollStaleMs) = mainPollState()", "lastProbeAtMs = nowElapsedMs")
        assertTrue(coordinator.contains("mainPollState = reachabilityMainPollState"))
        assertTrue(service.contains("reachabilityMainPollState = ::telegramReachabilityMainPollState"))
        assertTrue(mainPollState.contains("synchronized(mainObserverLock)"))
        assertTrue(mainPollState.contains("activeMainSessionId != null"))
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
