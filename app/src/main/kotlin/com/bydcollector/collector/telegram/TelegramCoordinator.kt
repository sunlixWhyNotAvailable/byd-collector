package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.TelegramDetectedEvent
import com.bydcollector.collector.service.TelegramEventConfig
import com.bydcollector.collector.service.TelegramEventEngine
import com.bydcollector.collector.service.TelegramEventResult
import com.bydcollector.collector.service.TelegramEventState

class TelegramCoordinator(
    private val store: TelemetryStore,
    private val settings: CollectorSettings,
    private val client: TelegramHttpClient = TelegramHttpClient(),
    private val retryPolicy: TelegramRetryPolicy = TelegramRetryPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val engine = TelegramEventEngine(TelegramEventState.fromJson(store.telegramRuntimeState()))

    fun onSuccessfulPoll(observations: List<NormalizedObservation>) {
        handle(engine.onSuccessfulPoll(observations, eventConfig(), nowMs()))
    }

    fun tick(mainCollectionExpected: Boolean, lastError: String?) {
        val expired = store.pruneTelegramMessages(nowMs())
        if (expired > 0) {
            store.recordEvent(
                "telegram_outbox_pruned",
                "Telegram outbox retention removed messages",
                "expired=$expired overflow=0"
            )
        }
        handle(engine.onTick(eventConfig(), mainCollectionExpected, lastError, nowMs()))
        flushPending()
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
                    "kind=${result.kind.name.lowercase()} status=${result.httpStatus ?: "none"}"
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

    fun flushPending() {
        if (!settings.isTelegramEnabled()) return
        val token = settings.telegramBotToken()
        val chatId = settings.telegramChatId()
        if (token.isBlank() || chatId.isBlank()) return
        for (entry in store.dueTelegramMessages(nowMs())) {
            val attemptedAt = nowMs()
            when (val result = client.sendMessage(TelegramSendMessage(token, chatId, entry.payload))) {
                TelegramSendResult.Success -> {
                    store.markTelegramDelivered(entry.id)
                    store.recordEvent(
                        "telegram_message_delivered",
                        "Telegram message delivered",
                        "event=${entry.eventType}"
                    )
                }
                is TelegramSendResult.Failure -> {
                    val error = "${result.kind.name.lowercase()}:${result.httpStatus ?: "none"}"
                    if (result.kind.retryable) {
                        val delay = retryPolicy.delayForFailure(entry.attemptCount + 1, result.retryAfterSeconds)
                        store.markTelegramRetry(entry.id, error, attemptedAt, attemptedAt + delay)
                    } else {
                        store.markTelegramBlocked(entry.id, error, attemptedAt)
                    }
                    store.recordEvent(
                        "telegram_message_failed",
                        "Telegram message delivery failed",
                        "event=${entry.eventType} kind=${result.kind.name.lowercase()}"
                    )
                    break
                }
            }
        }
    }

    private fun handle(result: TelegramEventResult) {
        result.events.forEach(::enqueue)
        // Persist the advanced baseline only after every detected event is durable.
        // A process death can then cause a deduplicated replay, but cannot lose an alert.
        if (result.shouldPersist) store.saveTelegramRuntimeState(result.state.toJson(), nowMs())
    }

    private fun enqueue(event: TelegramDetectedEvent) {
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
            return
        }
        val queued = store.enqueueTelegramMessage(event.dedupeKey, event.type.key, payload, nowMs())
        if (queued.expiredCount > 0 || queued.overflowCount > 0) {
            store.recordEvent(
                "telegram_outbox_pruned",
                "Telegram outbox retention removed messages",
                "expired=${queued.expiredCount} overflow=${queued.overflowCount}"
            )
        }
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
            tripEndDelayMs = settings.telegramTripEndDelayMinutes() * 60_000L
        )
    }
}
