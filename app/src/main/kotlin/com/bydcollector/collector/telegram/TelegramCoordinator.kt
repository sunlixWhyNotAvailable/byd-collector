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

class TelegramCoordinator(
    private val store: TelemetryStore,
    private val settings: CollectorSettings,
    private val client: TelegramHttpClient = TelegramHttpClient(),
    private val reachabilityProbe: TelegramReachabilityProbe? = null,
    private val retryPolicy: TelegramRetryPolicy = TelegramRetryPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var engine = TelegramEventEngine(TelegramEventState.fromJson(store.telegramRuntimeState()))

    fun onSuccessfulPoll(observations: List<NormalizedObservation>): Long? {
        val previousTripId = engine.state.tripId
        val previousChargingActive = engine.state.chargingActive
        val result = engine.onSuccessfulPoll(observations, eventConfig(), nowMs())
        if (result.state.tripId != null && result.state.tripId != previousTripId) {
            val deleted = store.deleteUndeliveredTelegramMessages(TelegramEventType.TRIP_SUMMARY.key)
            if (deleted > 0) {
                store.recordEvent(
                    "telegram_trip_summaries_discarded",
                    "Undelivered trip summaries discarded when a new trip started",
                    "count=$deleted"
                )
            }
        }
        handle(result)
        if (result.state.chargingActive != previousChargingActive) {
            store.recordEvent(
                "telegram_charging_transition",
                "Telegram charging evidence changed",
                "active=${result.state.chargingActive ?: "unknown"} source=${result.state.chargingEvidenceSource ?: "unknown"}"
            )
        }
        return result.nextWakeAtMs
    }

    fun tick(
        mainCollectionExpected: Boolean,
        lastError: String?,
        mainPollStaleMs: Long? = null,
        reachabilityMainPollState: () -> Pair<Boolean, Long?> = { mainCollectionExpected to mainPollStaleMs }
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
        flushPending()
        if (Thread.currentThread().isInterrupted) return result.nextWakeAtMs
        reachabilityProbe?.maybeProbe(
            telegramEnabled = settings::isTelegramEnabled,
            mainPollState = reachabilityMainPollState
        ) {
            client.getMe(settings.telegramBotToken())
        }
        return result.nextWakeAtMs
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
        reachabilityProbe?.reset()
        store.saveTelegramRuntimeState(engine.reset().toJson(), nowMs())
    }

    fun flushPending() {
        if (!settings.isTelegramEnabled()) return
        val token = settings.telegramBotToken()
        val chatId = settings.telegramChatId()
        if (token.isBlank() || chatId.isBlank()) return
        for (entry in store.dueTelegramMessages(nowMs())) {
            if (Thread.currentThread().isInterrupted) return
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
                    val error = failureCode(result)
                    if (result.kind.retryable) {
                        val delay = retryPolicy.delayForFailure(entry.attemptCount + 1, result.retryAfterSeconds)
                        store.markTelegramRetry(entry.id, error, attemptedAt, attemptedAt + delay)
                    } else {
                        store.markTelegramBlocked(entry.id, error, attemptedAt)
                    }
                    store.recordEvent(
                        "telegram_message_failed",
                        "Telegram message delivery failed",
                        "event=${entry.eventType} ${failureDetail(result)}"
                    )
                    break
                }
            }
        }
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
        return TelegramOutboxMessage(event.dedupeKey, event.type.key, payload)
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
            tripEndDelayMs = settings.telegramTripEndDelaySeconds() * 1_000L
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
}
