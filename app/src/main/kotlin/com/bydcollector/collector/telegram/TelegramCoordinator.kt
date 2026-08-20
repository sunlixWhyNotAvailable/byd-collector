package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.TelegramDetectedEvent
import com.bydcollector.collector.service.TelegramEventConfig
import com.bydcollector.collector.service.TelegramEventEngine
import com.bydcollector.collector.service.TelegramEventResult
import com.bydcollector.collector.service.TelegramEventState
import com.bydcollector.collector.service.TelegramLocationSnapshot
import com.bydcollector.collector.service.TelegramPowerOffSnapshot

class TelegramCoordinator(
    private val store: TelemetryStore,
    private val settings: CollectorSettings,
    private val client: TelegramHttpClient = TelegramHttpClient(),
    private val retryPolicy: TelegramRetryPolicy = TelegramRetryPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var engine = TelegramEventEngine(TelegramEventState.fromJson(store.telegramRuntimeState()))

    fun onSuccessfulPoll(observations: List<NormalizedObservation>): Long? {
        val previousChargingActive = engine.state.chargingActive
        val result = engine.onSuccessfulPoll(observations, eventConfig(), nowMs())
        handle(result)
        if (result.state.chargingActive != previousChargingActive) {
            store.recordEvent(
                "telegram_charging_transition",
                "Telegram charging evidence changed",
                "active=${result.state.chargingActive ?: "unknown"} source=${result.state.chargingEvidenceSource ?: "unknown"}"
            )
        }
        return nextWakeAt(result.nextWakeAtMs, pendingQueueDeadline())
    }

    fun tick(
        mainCollectionExpected: Boolean,
        lastError: String?
    ): Long? {
        val expired = store.pruneTelegramMessages(nowMs())
        if (expired > 0) {
            store.recordEvent(
                "telegram_outbox_pruned",
                "Telegram outbox retention removed messages",
                "expired=$expired overflow=0"
            )
        }
        val result = engine.onTick(eventConfig(), mainCollectionExpected, lastError, nowMs())
        handle(result)
        return nextWakeAt(result.nextWakeAtMs, flushPending())
    }

    /** Root/service integration hook for confirmed vehicle power-off. */
    fun onPowerOffConfirmed(snapshot: TelegramPowerOffSnapshot = TelegramPowerOffSnapshot(), location: TelegramLocationSnapshot? = null): Long? {
        val result = engine.onPowerOffConfirmed(eventConfig(), snapshot, location, nowMs())
        handle(result)
        return nextWakeAt(result.nextWakeAtMs, pendingQueueDeadline())
    }

    fun testConnection(): TelegramSendResult {
        settings.setTelegramConnectionStatus("testing", null)
        val result = client.sendMessage(
            TelegramSendMessage(
                botToken = settings.telegramBotToken(),
                chatId = settings.telegramChatId(),
                text = "BYD Collector: Telegram connection test"
            )
        )
        when (result) {
            TelegramSendResult.Success -> {
                settings.setTelegramConnectionStatus("success", null)
                store.unblockTelegramMessages(nowMs())
                store.recordEvent("telegram_connection_test_success", "Telegram connection test succeeded")
            }
            is TelegramSendResult.Failure -> {
                settings.setTelegramConnectionStatus("failed", result.kind.name.lowercase())
                store.recordEvent(
                    "telegram_connection_test_failed",
                    "Telegram connection test failed",
                    failureDetail(result)
                )
            }
        }
        return result
    }

    fun credentialsChanged() {
        store.unblockTelegramMessages(nowMs())
    }

    fun integrationDisabled() {
        store.saveTelegramRuntimeState(engine.reset().toJson(), nowMs())
    }

    fun flushPending(): Long? {
        if (!settings.isTelegramEnabled()) return null
        val token = settings.telegramBotToken()
        val chatId = settings.telegramChatId()
        if (token.isBlank() || chatId.isBlank()) return null
        val entry = store.oldestTelegramMessage() ?: return null
        if (entry.blocked) return null
        val now = nowMs()
        if (entry.nextAttemptAtMs > now) return entry.nextAttemptAtMs
        if (Thread.currentThread().isInterrupted) return entry.nextAttemptAtMs
        val attemptedAt = nowMs()
        return when (val result = client.sendMessage(TelegramSendMessage(token, chatId, entry.payload))) {
            TelegramSendResult.Success -> {
                store.markTelegramDelivered(entry.id)
                store.recordEvent(
                    "telegram_message_delivered",
                    "Telegram message delivered",
                    "event=${entry.eventType}"
                )
                store.delayOldestTelegramMessageUntil(nowMs() + BACKLOG_SUCCESS_DELAY_MS)
            }
            is TelegramSendResult.Failure -> {
                val error = failureCode(result)
                val nextAttemptAtMs = if (result.kind.retryable) {
                    val delay = retryPolicy.delayForFailure(entry.attemptCount + 1, result.retryAfterSeconds)
                    (attemptedAt + delay).also {
                        store.markTelegramRetry(entry.id, error, attemptedAt, it)
                    }
                } else {
                    store.markTelegramBlocked(entry.id, error, attemptedAt)
                    null
                }
                store.recordEvent(
                    "telegram_message_failed",
                    "Telegram message delivery failed",
                    "event=${entry.eventType} ${failureDetail(result)}"
                )
                nextAttemptAtMs
            }
        }
    }

    private fun pendingQueueDeadline(): Long? {
        if (!settings.isTelegramEnabled()) return null
        if (settings.telegramBotToken().isBlank() || settings.telegramChatId().isBlank()) return null
        return store.oldestTelegramMessage()?.takeUnless { it.blocked }?.nextAttemptAtMs
    }

    private fun nextWakeAt(eventDeadlineAtMs: Long?, queueDeadlineAtMs: Long?): Long? {
        return listOfNotNull(eventDeadlineAtMs, queueDeadlineAtMs).minOrNull()
    }

    private fun handle(result: TelegramEventResult) {
        val messages = result.events.mapNotNull(::render)
        val committedAt = nowMs()
        val outcomes = try {
            store.commitTelegramEvents(
                messages = messages,
                stateJson = result.state.toJson().takeIf { result.shouldPersist },
                nowMs = committedAt
            )
        } catch (error: RuntimeException) {
            engine = TelegramEventEngine(TelegramEventState.fromJson(store.telegramRuntimeState()))
            throw error
        }
        messages.zip(outcomes).forEach { (message, queued) ->
            if (queued.inserted) {
                store.recordEvent(
                    "telegram_event_queued",
                    "Telegram event queued",
                    "event=${message.eventType}"
                )
            }
            if (queued.expiredCount > 0 || queued.overflowCount > 0) {
                store.recordEvent(
                    "telegram_outbox_pruned",
                    "Telegram outbox retention removed messages",
                    "expired=${queued.expiredCount} overflow=${queued.overflowCount}"
                )
            }
        }
    }

    private fun render(event: TelegramDetectedEvent): TelegramOutboxMessage? {
        val template = settings.telegramTemplate(event.type.key)
            ?: TelegramTemplateCatalog.spec(event.type).defaultTemplate
        val rendered = TelegramTemplateRenderer.render(event.type, template, event.variables)
        val payload = rendered.text
        if (payload == null) {
            store.recordEvent(
                "telegram_template_invalid",
                "Telegram event skipped because its template is invalid",
                "event=${event.type.key} errors=${rendered.errors.joinToString(",") { it.kind.name.lowercase() }}"
            )
            return null
        }
        return TelegramOutboxMessage(event.dedupeKey, event.type.key, payload + (event.textSuffix ?: ""))
    }

    private fun eventConfig(): TelegramEventConfig {
        val enabled = if (settings.isTelegramEnabled()) {
            TelegramEventType.entries.filterTo(mutableSetOf()) { settings.isTelegramEventEnabled(it.key) }
        } else {
            emptySet()
        }
        return TelegramEventConfig(
            enabledEvents = enabled,
            chargeStepPercent = settings.telegramChargeStepPercent(),
            lowVoltageThreshold = settings.telegramLowVoltageThreshold().toDouble(),
            unavailableDelayMs = settings.telegramUnavailableDelayMinutes() * 60_000L,
            tripEndDelayMs = settings.telegramTripEndDelaySeconds() * 1_000L,
            sendLocation = settings.isTelegramSendLocationEnabled()
        )
    }

    private fun failureCode(result: TelegramSendResult.Failure): String {
        return listOfNotNull(
            result.kind.name.lowercase(),
            result.httpStatus?.toString(),
            result.exceptionClass
        ).joinToString(":")
    }

    private fun failureDetail(result: TelegramSendResult.Failure): String {
        return "kind=${result.kind.name.lowercase()} " +
            "status=${result.httpStatus ?: "none"} " +
            "exception=${result.exceptionClass ?: "none"}"
    }

    private companion object {
        const val BACKLOG_SUCCESS_DELAY_MS = 5_000L
    }
}
