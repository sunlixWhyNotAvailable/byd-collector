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
        assertFalse(store.contains("fun commitTelegramEvents"))
        assertTrue(sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt").readText().contains("fun commitTelegramEvents"))
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
        val store = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt").readText()
        val commit = store.substringAfter("fun commitTelegramEvents")
            .substringBefore("private fun enqueueTelegramMessage")

        assertTrue(handle.contains("commitTelegramEvents("))
        assertTrue(handle.contains("TelegramEventState.fromJson"))
        assertTrue(handle.contains("renderTelegramBatch(result.events, ::render)"))
        assertTrue(handle.contains("if (messages == null)"))
        assertFalse(handle.contains("mapNotNull"))
        assertFalse(handle.contains("saveTelegramRuntimeState"))
        assertInOrder(commit, "db.beginTransaction()", "enqueueTelegramMessage(db, it, nowMs)")
        assertInOrder(commit, "enqueueTelegramMessage(db, it, nowMs)", "saveTelegramRuntimeState(db, it, nowMs)")
        assertInOrder(commit, "saveTelegramRuntimeState(db, it, nowMs)", "db.setTransactionSuccessful()")
        assertInOrder(commit, "db.setTransactionSuccessful()", "db.endTransaction()")
    }

    @Test
    fun outboxFlushSkipsBlockedRowsAndAttemptsOnlyOncePerTick() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val store = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt").readText()
        val helper = sourceFile("com/bydcollector/collector/data/local/TelegramDatabaseHelper.kt").readText()
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
        assertTrue(flush.contains("oldestUnblockedTelegramMessage()"))
        assertInOrder(attempt, "if (entry.blocked) return null", "entry.nextAttemptAtMs > now")
        assertInOrder(attempt, "entry.nextAttemptAtMs > now", "Thread.currentThread().isInterrupted")
        assertInOrder(attempt, "Thread.currentThread().isInterrupted", "client.sendMessage")
        assertFalse(flush.contains("for ("))
        assertTrue(attempt.contains("entry.attemptCount + 1, result.retryAfterSeconds"))
        assertTrue(attempt.contains("markTelegramBlocked"))
        assertTrue(attempt.contains("entry.waitsForSummaryKey"))
        assertTrue(attempt.contains("telegramMessageByDedupeKey(dependencyKey)"))
        assertInOrder(attempt, "entry.waitsForSummaryKey", "client.sendMessage")
        assertFalse(coordinator.contains("BACKLOG_SUCCESS_DELAY_MS"))
        assertFalse(store.contains("delayOldestTelegramMessageUntil"))
        assertTrue(helper.contains("MAX_PENDING = 1_000L"))
        assertTrue(helper.contains("RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L"))
        assertInOrder(connectionTest, "client.sendMessage", "unblockTelegramMessages(nowMs())")
        assertTrue(credentialsChanged.contains("unblockTelegramMessages(nowMs())"))
    }

    @Test
    fun ageRetentionNeverDeletesNeverAttemptedOutboxRows() {
        val store = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt").readText()
        val enqueue = store.substringAfter("private fun enqueueTelegramMessage")
            .substringBefore("fun oldestUnblockedTelegramMessage")
        val prune = store.substringAfter("fun pruneTelegramMessages")
            .substringBefore("fun markTelegramDelivered")

        assertTrue(enqueue.contains("created_at_ms < ? AND attempt_count > 0"))
        assertTrue(prune.contains("created_at_ms < ? AND attempt_count > 0"))
        assertTrue(enqueue.contains("id IN (SELECT id FROM telegram_outbox ORDER BY id LIMIT ?)"))
    }

    @Test
    fun successfulAndBlockedAttemptsScheduleTheNextUnblockedMessageImmediately() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val store = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt").readText()
        val attempt = coordinator.substringAfter("private fun attempt").substringBefore("private fun pendingQueueDeadline")
        val success = attempt.substringAfter("TelegramSendResult.Success").substringBefore("is TelegramSendResult.Failure")
        val permanentFailure = attempt.substringAfter("} else {").substringBefore("recordEvent(")
        val unblock = store.substringAfter("fun unblockTelegramMessages")
            .substringBefore("fun telegramRuntimeState")

        assertInOrder(
            success,
            "engine.markTripSummaryDelivered(entry.dedupeKey, deliveredAtMs)",
            "markTelegramDelivered(entry.id, deliveredState?.toJson(), deliveredAtMs)",
            "pendingQueueDeadline()"
        )
        val deliveryCommit = store.substringAfter("fun markTelegramDelivered")
            .substringBefore("fun markTelegramRetry")
        assertInOrder(
            deliveryCommit,
            "db.beginTransactionNonExclusive()",
            "SELECT dedupe_key FROM telegram_outbox",
            "db.delete(\"telegram_outbox\"",
            "waits_for_summary_key = ?",
            "saveTelegramRuntimeState(db",
            "db.setTransactionSuccessful()",
            "db.endTransaction()"
        )
        assertInOrder(permanentFailure, "markTelegramBlocked", "pendingQueueDeadline()")
        val blockedCommit = store.substringAfter("fun markTelegramBlocked").substringBefore("fun unblockTelegramMessages")
        assertTrue(blockedCommit.contains("put(\"last_attempt_at_ms\", attemptedAtMs)"))
        assertTrue(unblock.contains("next_attempt_at_ms = MAX(next_attempt_at_ms, ?)"))
        assertFalse(unblock.contains("put(\"next_attempt_at_ms\", nowMs)"))
    }

    @Test
    fun startupAndPowerOffPrioritizeTripSummariesAfterDurableCommit() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val tripRuntime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()
        val tick = coordinator.substringAfter("fun tick(").substringBefore("fun onPowerOffConfirmed")
        val engineTick = tick.substringAfter("val result = engine.onTick")
        val startup = coordinator.substringAfter("private fun ensureStartupRecovery")
            .substringBefore("private fun recoverStartupLocally")
        val recovery = coordinator.substringAfter("private fun recoverStartupLocally")
            .substringBefore("private fun activateEnabledRuntime")
        val prepare = coordinator.substringAfter("internal fun preparePowerOffConfirmed")
            .substringBefore("/** Performs network work")
        val delivery = coordinator.substringAfter("internal fun deliverPreparedPowerOff")
            .substringBefore("/** Compatibility hook")
        val servicePowerOff = service.substringAfter("private fun prepareConfirmedPowerOff")
            .substringBefore("private fun telegramLocationSnapshot")
        val tripPowerOff = tripRuntime.substringAfter("private fun handlePowerOff")
            .substringBefore("private fun ensureGpsRunning")

        assertInOrder(recovery, "engine.recoverPendingTrip", "handle(recovered)")
        assertFalse(recovery.contains("attempt("))
        assertInOrder(startup, "recoverStartupLocally()", "attempt(it, force = true)")
        assertInOrder(
            prepare,
            "recoverStartupLocally()",
            "engine.onPowerOffConfirmed",
            "handle(result)"
        )
        assertFalse(prepare.contains("flushPending("))
        assertFalse(prepare.contains("client.sendMessage"))
        assertInOrder(delivery, "preparation.priorityKeys.forEach", "flushPending(key, force = true)")
        assertInOrder(delivery, "flushPending(key, force = true)", "flushPending()")
        assertFalse(prepare.contains("oldestUnblockedTelegramMessage()"))
        assertTrue(prepare.contains("check(handle(result))"))
        assertTrue(recovery.contains("check(handle(recovered))"))
        assertTrue(prepare.contains("!engine.state.pendingPowerOffLocationSummaryDelivered"))
        assertTrue(coordinator.contains("val runtimeStartedAtMs = activateEnabledRuntime() ?: return null"))
        assertInOrder(engineTick, "tickAtMs,", "runtimeStartedAtMs")
        assertTrue(coordinator.contains("private var enabledRuntimeStartedAtMs: Long? = null"))
        assertTrue(coordinator.contains("enabledRuntimeStartedAtMs = null"))
        assertTrue(coordinator.contains("if (!settings.isTelegramEnabled()) return null"))
        assertTrue(prepare.contains("activateEnabledRuntime() ?: return null"))
        assertInOrder(
            servicePowerOff,
            "runOnTelegramExecutorBlocking",
            "coordinator.preparePowerOffConfirmed(",
            "coordinator.deliverPreparedPowerOff(preparation)"
        )
        assertInOrder(
            tripPowerOff,
            "prepareConfirmedPowerOff(",
            "state = TripSession.STATE_CLOSED",
            "deliverAfterClose()"
        )
        assertFalse(servicePowerOff.contains("coordinator.flushPending()"))
        assertTrue(coordinator.contains("telegram_location_eligibility"))
        assertTrue(coordinator.contains("trigger=power_off reason="))
    }

    @Test
    fun runtimeSidecarSqliteFailureClosesStorageAndTurnsTelegramRed() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val application = sourceFile("com/bydcollector/collector/BydCollectorApplication.kt").readText()
        val execute = service.substringAfter("private fun <T> executeTelegram")
            .substringBefore("private fun <T> executeChannel")
        val failure = service.substringAfter("private fun handleTelegramExecutionFailure")
            .substringBefore("private fun shutdownInfluxExecutor")

        assertTrue(execute.contains("onException = ::handleTelegramExecutionFailure"))
        assertTrue(failure.contains("error !is SQLiteException"))
        assertTrue(failure.contains("markTelegramStorageUnavailable(store, error)"))
        assertTrue(failure.contains("telegramCoordinator = null"))
        assertTrue(failure.contains("telegramWorkGeneration.incrementAndGet()"))
        assertTrue(application.contains("markTelegramStorageFailure(mainStore, error)"))
        assertTrue(application.contains("setTelegramConnectionStatus(\"storage_error\", TELEGRAM_STORAGE_ERROR)"))
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

    @Test
    fun delayedLocationOnlyFollowUpDoesNotRewriteTemplateLimitOrHideFallbackDiagnostics() {
        val coordinator = sourceFile("com/bydcollector/collector/telegram/TelegramCoordinator.kt").readText()
        val render = coordinator.substringAfter("private fun render(event: TelegramDetectedEvent)")
            .substringBefore("private fun telegramLanguage")

        assertTrue(render.contains("event.type == TelegramEventType.TRIP_SUMMARY && !event.locationOnly"))
        assertTrue(render.contains("if (rendered.usedFallback)"))
        assertTrue(render.contains("\"telegram_template_fallback\""))
        assertTrue(render.contains("reason=invalid_custom_template"))
    }

    @Test
    fun productionUsesCompleteDedicatedLocationLimitWarning() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertFalse(app.contains("\"${'$'}{strings.telegram.templateLimitWarning} ${'$'}{strings.telegram.templateLimitWithLocation}\""))
        assertTrue(app.contains("TelegramPayloadLimitState.WITH_LOCATION -> strings.telegram.templateLimitWithLocation"))
        assertTrue(strings.contains("templateLimitWithLocation = \"Перевищено обмеження шаблону 4 096 символів із локацією\""))
        assertTrue(strings.contains("templateLimitWithLocation = \"Template exceeds the 4,096-character limit with location\""))
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
