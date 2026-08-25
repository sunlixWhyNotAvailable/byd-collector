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
        assertTrue(coordinator.contains("pendingQueueDeadline()"))
        assertTrue(coordinator.contains("flushPending()"))
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
        assertTrue(handle.contains("renderTelegramBatch(result.events, ::render)"))
        assertTrue(handle.contains("if (messages == null)"))
        assertFalse(handle.contains("mapNotNull"))
        assertFalse(handle.contains("saveTelegramRuntimeState"))
        assertInOrder(commit, "db.beginTransaction()", "enqueueTelegramMessage(db, message, nowMs)")
        assertInOrder(commit, "enqueueTelegramMessage(db, message, nowMs)", "saveTelegramRuntimeState(db, it, nowMs)")
        assertInOrder(commit, "saveTelegramRuntimeState(db, it, nowMs)", "db.setTransactionSuccessful()")
        assertInOrder(commit, "db.setTransactionSuccessful()", "db.endTransaction()")
    }

    @Test
    fun outboxFlushSkipsBlockedRowsAndAttemptsOnlyOncePerTick() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val flush = coordinator.substringAfter("fun flushPending").substringBefore("private fun pendingQueueDeadline")
        val attempt = coordinator.substringAfter("private fun attempt").substringBefore("private fun pendingQueueDeadline")
        val oldest = store.substringAfter("fun oldestUnblockedTelegramMessage")
            .substringBefore("fun telegramMessageByDedupeKey")
        val connectionTest = coordinator.substringAfter("fun testConnection")
            .substringBefore("fun credentialsChanged")
        val credentialsChanged = coordinator.substringAfter("fun credentialsChanged")
            .substringBefore("fun integrationDisabled")

        assertTrue(oldest.contains("ORDER BY id"))
        assertTrue(oldest.contains("LIMIT 1"))
        assertTrue(oldest.contains("WHERE blocked = 0"))
        assertTrue(flush.contains("store.oldestUnblockedTelegramMessage()"))
        assertInOrder(attempt, "if (entry.blocked) return null", "entry.nextAttemptAtMs > now")
        assertInOrder(attempt, "entry.nextAttemptAtMs > now", "Thread.currentThread().isInterrupted")
        assertInOrder(attempt, "Thread.currentThread().isInterrupted", "client.sendMessage")
        assertFalse(flush.contains("for ("))
        assertTrue(attempt.contains("entry.attemptCount + 1, result.retryAfterSeconds"))
        assertTrue(attempt.contains("store.markTelegramBlocked"))
        assertFalse(coordinator.contains("BACKLOG_SUCCESS_DELAY_MS"))
        assertFalse(store.contains("delayOldestTelegramMessageUntil"))
        assertTrue(store.contains("TELEGRAM_MAX_PENDING = 1_000L"))
        assertTrue(store.contains("TELEGRAM_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L"))
        assertInOrder(connectionTest, "client.sendMessage", "store.unblockTelegramMessages(nowMs())")
        assertTrue(credentialsChanged.contains("store.unblockTelegramMessages(nowMs())"))
    }

    @Test
    fun successfulAndBlockedAttemptsScheduleTheNextUnblockedMessageImmediately() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val attempt = coordinator.substringAfter("private fun attempt").substringBefore("private fun pendingQueueDeadline")
        val success = attempt.substringAfter("TelegramSendResult.Success").substringBefore("is TelegramSendResult.Failure")
        val permanentFailure = attempt.substringAfter("} else {").substringBefore("store.recordEvent(")
        val unblock = store.substringAfter("fun unblockTelegramMessages")
            .substringBefore("fun telegramRuntimeState")

        assertInOrder(
            success,
            "engine.markTripSummaryDelivered(entry.dedupeKey, deliveredAtMs)",
            "store.markTelegramDelivered(entry.id, deliveredState?.toJson(), deliveredAtMs)",
            "pendingQueueDeadline()"
        )
        val deliveryCommit = store.substringAfter("fun markTelegramDelivered")
            .substringBefore("fun markTelegramRetry")
        assertInOrder(
            deliveryCommit,
            "db.beginTransactionNonExclusive()",
            "db.delete(\"telegram_outbox\"",
            "saveTelegramRuntimeState(db",
            "db.setTransactionSuccessful()",
            "db.endTransaction()"
        )
        assertInOrder(permanentFailure, "store.markTelegramBlocked", "pendingQueueDeadline()")
        assertTrue(unblock.contains("next_attempt_at_ms = MAX(next_attempt_at_ms, ?)"))
        assertFalse(unblock.contains("put(\"next_attempt_at_ms\", nowMs)"))
    }

    @Test
    fun startupAndPowerOffPrioritizeTripSummariesAfterDurableCommit() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val tick = coordinator.substringAfter("fun tick(").substringBefore("fun onPowerOffConfirmed")
        val engineTick = tick.substringAfter("val result = engine.onTick")
        val startup = coordinator.substringAfter("private fun ensureStartupRecovery")
            .substringBefore("private fun nextWakeAt")
        val powerOff = coordinator.substringAfter("fun onPowerOffConfirmed")
            .substringBefore("fun testConnection")
        val servicePowerOff = service.substringAfter("private fun handleConfirmedPowerOff")
            .substringBefore("private fun telegramLocationSnapshot")

        assertInOrder(startup, "engine.recoverPendingTrip", "handle(recovered)")
        assertInOrder(startup, "handle(recovered)", "attempt(it, force = true)")
        assertInOrder(
            powerOff,
            "engine.state.pendingPowerOffLocationTripId",
            "flushPending(\"${'$'}it:summary\", force = true)",
            "engine.onPowerOffConfirmed",
            "handle(result)",
            "flushPending(it, force = true)"
        )
        assertTrue(powerOff.contains("!engine.state.pendingPowerOffLocationSummaryDelivered"))
        assertTrue(coordinator.contains("val runtimeStartedAtMs = activateEnabledRuntime() ?: return null"))
        assertInOrder(engineTick, "tickAtMs,", "runtimeStartedAtMs")
        assertTrue(coordinator.contains("private var enabledRuntimeStartedAtMs: Long? = null"))
        assertTrue(coordinator.contains("enabledRuntimeStartedAtMs = null"))
        assertTrue(coordinator.contains("if (!settings.isTelegramEnabled()) return null"))
        assertTrue(powerOff.contains("activateEnabledRuntime() ?: return null"))
        assertTrue(servicePowerOff.contains("telegramCoordinator.onPowerOffConfirmed("))
        assertFalse(servicePowerOff.contains("telegramCoordinator.flushPending()"))
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
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertTrue(coordinator.contains("settings.uiLanguageCode()"))
        assertTrue(coordinator.contains("settings.telegramNavigatorMask()"))
        assertTrue(coordinator.contains("val defaultTemplate = TelegramTemplateCatalog.defaultTemplate"))
        assertTrue(coordinator.contains("savedTemplate ?: defaultTemplate"))
        assertFalse(coordinator.contains("TelegramBuiltInTemplates.isKnownBuiltIn"))
        assertTrue(engine.contains("charge_step_duration"))
        assertTrue(engine.contains("config.navigatorMask"))
        assertInOrder(engine, "TelegramNavigatorMask.GOOGLE", "TelegramNavigatorMask.WAZE")
        assertInOrder(engine, "TelegramNavigatorMask.WAZE", "TelegramNavigatorMask.APPLE")
        assertInOrder(engine, "TelegramNavigatorMask.APPLE", "TelegramNavigatorMask.OSM")
        assertTrue(mask.contains("const val NONE = 0"))
        assertTrue(mask.contains("const val ALL = 15"))
        assertTrue(engine.contains("val navigatorMask: Int = TelegramNavigatorMask.NONE"))
        assertTrue(actions.contains("val navigatorMask: Int = TelegramNavigatorMask.NONE"))
        assertTrue(coordinator.contains("settings.telegramTripTemplateLimitRevision()"))
        assertTrue(coordinator.contains("settings.setTelegramTripTemplateLimitState(rendered.limitState, tripTemplateLimitRevision)"))
        assertTrue(settings.contains("KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_STATE"))
        assertTrue(settings.contains("expectedRevision: Long"))
        assertTrue(activity.contains("key == CollectorSettings.KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_STATE"))
        assertTrue(activity.contains("tripLimitInputsChanged"))
        assertTrue(activity.contains("settings.resetTelegramTripTemplateLimitState()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previousIndex = -1
        tokens.forEach { token ->
            val index = source.indexOf(token, previousIndex + 1)
            assertTrue(index >= 0, "Missing token: $token")
            assertTrue(index > previousIndex, "Expected tokens in order")
            previousIndex = index
        }
    }
}
