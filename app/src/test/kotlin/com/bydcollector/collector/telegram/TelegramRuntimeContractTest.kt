package com.bydcollector.collector.telegram

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramRuntimeContractTest {
    @Test
    fun newTripsPreserveEveryQueuedTripSummary() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val poll = coordinator.substringAfter("fun onSuccessfulPoll")
            .substringBefore("fun tick")
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()

        assertTrue(poll.contains("handle(result)"))
        assertFalse(coordinator.contains("deleteUndeliveredTelegramMessages"))
        assertFalse(store.contains("fun deleteUndeliveredTelegramMessages"))
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
        assertTrue(coordinator.contains("nextWakeAt(result.nextWakeAtMs, pendingQueueDeadline())"))
        assertTrue(coordinator.contains("nextWakeAt(result.nextWakeAtMs, flushPending())"))
        assertTrue(coordinator.contains("listOfNotNull(eventDeadlineAtMs, queueDeadlineAtMs).minOrNull()"))
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
    fun outboxFlushIsStrictHeadOfLineAndAttemptsOnlyOncePerTick() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val flush = coordinator.substringAfter("fun flushPending").substringBefore("private fun pendingQueueDeadline")
        val oldest = store.substringAfter("fun oldestTelegramMessage")
            .substringBefore("fun delayOldestTelegramMessageUntil")
        val connectionTest = coordinator.substringAfter("fun testConnection")
            .substringBefore("fun credentialsChanged")
        val credentialsChanged = coordinator.substringAfter("fun credentialsChanged")
            .substringBefore("fun integrationDisabled")

        assertTrue(oldest.contains("ORDER BY id"))
        assertTrue(oldest.contains("LIMIT 1"))
        assertFalse(oldest.contains("WHERE blocked = 0"))
        assertInOrder(flush, "store.oldestTelegramMessage()", "if (entry.blocked) return null")
        assertInOrder(flush, "entry.nextAttemptAtMs > now", "Thread.currentThread().isInterrupted")
        assertInOrder(flush, "Thread.currentThread().isInterrupted", "client.sendMessage")
        assertFalse(flush.contains("for ("))
        assertTrue(flush.contains("entry.attemptCount + 1, result.retryAfterSeconds"))
        assertTrue(flush.contains("store.markTelegramBlocked"))
        assertTrue(flush.contains("store.delayOldestTelegramMessageUntil(nowMs() + BACKLOG_SUCCESS_DELAY_MS)"))
        assertTrue(coordinator.contains("BACKLOG_SUCCESS_DELAY_MS = 5_000L"))
        assertTrue(store.contains("TELEGRAM_MAX_PENDING = 1_000L"))
        assertTrue(store.contains("TELEGRAM_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L"))
        assertInOrder(connectionTest, "client.sendMessage", "store.unblockTelegramMessages(nowMs())")
        assertTrue(credentialsChanged.contains("store.unblockTelegramMessages(nowMs())"))
    }

    @Test
    fun successPacingSurvivesABlockedHeadBeingUnblocked() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val flush = coordinator.substringAfter("fun flushPending").substringBefore("private fun pendingQueueDeadline")
        val pace = store.substringAfter("fun delayOldestTelegramMessageUntil")
            .substringBefore("fun pruneTelegramMessages")
        val unblock = store.substringAfter("fun unblockTelegramMessages")
            .substringBefore("fun telegramRuntimeState")

        assertInOrder(flush, "store.markTelegramDelivered(entry.id)", "nowMs() + BACKLOG_SUCCESS_DELAY_MS")
        assertTrue(pace.contains("maxOf(entry.nextAttemptAtMs, minimumAttemptAtMs)"))
        assertFalse(pace.contains("if (entry.blocked) return null"))
        assertTrue(pace.contains("return nextAttemptAtMs.takeUnless { entry.blocked }"))
        assertTrue(unblock.contains("next_attempt_at_ms = MAX(next_attempt_at_ms, ?)"))
        assertFalse(unblock.contains("put(\"next_attempt_at_ms\", nowMs)"))
    }

    @Test
    fun serviceKeepsTheIdleTickAndRemovesReachabilityAndOffcarWiring() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val client = sourceFile("com/bydcollector/collector/telegram/TelegramHttpClient.kt").readText()

        assertTrue(service.contains("TELEGRAM_TICK_INTERVAL_MS = 15_000L"))
        assertTrue(service.contains("DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT"))
        assertFalse(service.contains("TelegramReachabilityProbe"))
        assertFalse(service.contains("telegram_reachability"))
        assertFalse(service.contains("mainHeartbeat"))
        assertFalse(service.contains("offcarDisarm"))
        assertFalse(coordinator.contains("getMe"))
        assertFalse(client.contains("getMe"))
        assertFalse(client.contains("TelegramRequestEvidence"))
    }

    @Test
    fun runtimeUsesAppLanguageAndSanitizedNavigatorSelection() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val engine = sourceFile("com/bydcollector/collector/service/TelegramEventEngine.kt").readText()
        val mask = sourceFile("com/bydcollector/collector/telegram/TelegramNavigatorMask.kt").readText()

        assertTrue(coordinator.contains("settings.uiLanguageCode()"))
        assertTrue(coordinator.contains("settings.telegramNavigatorMask()"))
        assertTrue(coordinator.contains("savedTemplate ?: TelegramTemplateCatalog.defaultTemplate"))
        assertFalse(coordinator.contains("TelegramBuiltInTemplates.isKnownBuiltIn"))
        assertTrue(engine.contains("charge_step_duration"))
        assertTrue(engine.contains("config.navigatorMask"))
        assertInOrder(engine, "TelegramNavigatorMask.GOOGLE", "TelegramNavigatorMask.WAZE")
        assertInOrder(engine, "TelegramNavigatorMask.WAZE", "TelegramNavigatorMask.APPLE")
        assertInOrder(engine, "TelegramNavigatorMask.APPLE", "TelegramNavigatorMask.OSM")
        assertTrue(mask.contains("const val ALL = 15"))
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
