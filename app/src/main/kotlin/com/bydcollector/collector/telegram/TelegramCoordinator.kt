package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelegramOutboxEntry
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
    private var enabledRuntimeStartedAtMs: Long? = null
    private var startupRecoveryPending = true

    fun onSuccessfulPoll(observations: List<NormalizedObservation>): Long? {
        activateEnabledRuntime() ?: return null
        val startupDeadline = ensureStartupRecovery()
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
        return nextWakeAt(result.nextWakeAtMs, nextWakeAt(startupDeadline, pendingQueueDeadline()))
    }

    fun tick(
        mainCollectionExpected: Boolean,
        lastError: String?
    ): Long? {
        val runtimeStartedAtMs = activateEnabledRuntime() ?: return null
        val startupDeadline = ensureStartupRecovery()
        val tickAtMs = nowMs()
        val expired = store.pruneTelegramMessages(tickAtMs)
        if (expired > 0) {
            store.recordEvent(
                "telegram_outbox_pruned",
                "Telegram outbox retention removed messages",
                "expired=$expired overflow=0"
            )
        }
        val result = engine.onTick(
            eventConfig(),
            mainCollectionExpected,
            lastError,
            tickAtMs,
            runtimeStartedAtMs
        )
        handle(result)
        return nextWakeAt(result.nextWakeAtMs, nextWakeAt(startupDeadline, flushPending()))
    }

    /** Root/service integration hook for confirmed vehicle power-off. */
    fun onPowerOffConfirmed(snapshot: TelegramPowerOffSnapshot = TelegramPowerOffSnapshot(), location: TelegramLocationSnapshot? = null): Long? {
        activateEnabledRuntime() ?: return null
        val startupDeadline = ensureStartupRecovery()
        engine.state.pendingPowerOffLocationTripId
            ?.takeIf { !engine.state.pendingPowerOffLocationSummaryDelivered }
            ?.let { flushPending("$it:summary", force = true) }
        val result = engine.onPowerOffConfirmed(eventConfig(), snapshot, location, nowMs())
        handle(result)
        val priorityKey = result.events.firstOrNull {
            it.type == TelegramEventType.TRIP_SUMMARY && !it.locationOnly
        }?.dedupeKey ?: result.events.firstOrNull { it.locationOnly }?.dedupeKey
        val deliveryDeadline = priorityKey?.let { flushPending(it, force = true) } ?: flushPending()
        return nextWakeAt(result.nextWakeAtMs, nextWakeAt(startupDeadline, deliveryDeadline))
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
        enabledRuntimeStartedAtMs = null
        startupRecoveryPending = true
    }

    fun flushPending(): Long? {
        val entry = store.oldestUnblockedTelegramMessage() ?: return null
        return attempt(entry, force = false)
    }

    private fun flushPending(dedupeKey: String, force: Boolean): Long? {
        val entry = store.telegramMessageByDedupeKey(dedupeKey) ?: return null
        return attempt(entry, force)
    }

    private fun attempt(entry: TelegramOutboxEntry, force: Boolean): Long? {
        if (!settings.isTelegramEnabled()) return null
        val token = settings.telegramBotToken()
        val chatId = settings.telegramChatId()
        if (token.isBlank() || chatId.isBlank()) return null
        if (entry.blocked) return null
        if (entry.dedupeKey.endsWith(":location")) {
            val summary = store.telegramMessageByDedupeKey(
                entry.dedupeKey.removeSuffix(":location") + ":summary"
            )
            if (summary != null) return summary.nextAttemptAtMs.takeIf { !summary.blocked }
        }
        val now = nowMs()
        if (!force && entry.nextAttemptAtMs > now) return entry.nextAttemptAtMs
        if (Thread.currentThread().isInterrupted) return entry.nextAttemptAtMs
        val attemptedAt = nowMs()
        return when (val result = client.sendMessage(TelegramSendMessage(token, chatId, entry.payload))) {
            TelegramSendResult.Success -> {
                val deliveredAtMs = nowMs()
                val deliveredState = engine.markTripSummaryDelivered(entry.dedupeKey, deliveredAtMs)
                try {
                    store.markTelegramDelivered(entry.id, deliveredState?.toJson(), deliveredAtMs)
                } catch (error: RuntimeException) {
                    engine = TelegramEventEngine(TelegramEventState.fromJson(store.telegramRuntimeState()))
                    throw error
                }
                store.recordEvent(
                    "telegram_message_delivered",
                    "Telegram message delivered",
                    "event=${entry.eventType}"
                )
                pendingQueueDeadline()
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
                    pendingQueueDeadline()
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
        return store.oldestUnblockedTelegramMessage()?.nextAttemptAtMs
    }

    private fun ensureStartupRecovery(): Long? {
        if (!startupRecoveryPending) return null
        val recovered = engine.recoverPendingTrip(eventConfig(), nowMs())
        handle(recovered)
        startupRecoveryPending = false
        val recoveredKey = recovered.events
            .firstOrNull { it.type == TelegramEventType.TRIP_SUMMARY }
            ?.dedupeKey
        val pending = recoveredKey?.let(store::telegramMessageByDedupeKey)?.takeUnless { it.blocked }
            ?: store.oldestUnblockedTelegramMessage(TelegramEventType.TRIP_SUMMARY.key)
        return pending?.let { attempt(it, force = true) }
    }

    private fun activateEnabledRuntime(): Long? {
        if (!settings.isTelegramEnabled()) return null
        return enabledRuntimeStartedAtMs ?: nowMs().also { enabledRuntimeStartedAtMs = it }
    }

    private fun nextWakeAt(eventDeadlineAtMs: Long?, queueDeadlineAtMs: Long?): Long? {
        return listOfNotNull(eventDeadlineAtMs, queueDeadlineAtMs).minOrNull()
    }

    private fun handle(result: TelegramEventResult) {
        val messages = renderTelegramBatch(result.events, ::render)
        if (messages == null) {
            engine = TelegramEventEngine(TelegramEventState.fromJson(store.telegramRuntimeState()))
            return
        }
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
        val language = telegramLanguage()
        val savedTemplate = settings.telegramTemplate(event.type.key)
        val rendered = renderTelegramPayload(event, savedTemplate, language)
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

    private fun telegramLanguage(): TelegramTemplateLanguage = when (settings.uiLanguageCode().trim().lowercase()) {
        "uk", "ua", "uk-ua" -> TelegramTemplateLanguage.UK
        else -> TelegramTemplateLanguage.EN
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
            sendLocation = settings.isTelegramSendLocationEnabled(),
            language = telegramLanguage(),
            navigatorMask = TelegramNavigatorMask.sanitize(settings.telegramNavigatorMask())
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

internal fun renderTelegramBatch(
    events: List<TelegramDetectedEvent>,
    render: (TelegramDetectedEvent) -> TelegramOutboxMessage?
): List<TelegramOutboxMessage>? {
    val rendered = events.map(render)
    return rendered.filterNotNull().takeIf { it.size == rendered.size }
}

internal fun renderTelegramPayload(
    event: TelegramDetectedEvent,
    savedTemplate: String?,
    language: TelegramTemplateLanguage
): TelegramTemplateRenderResult {
    if (event.locationOnly) {
        val payload = event.textSuffix.orEmpty()
        val length = payload.codePointCount(0, payload.length)
        val errors = when {
            payload.isBlank() -> listOf(TelegramTemplateError(TelegramTemplateErrorKind.EMPTY))
            length > TELEGRAM_MESSAGE_MAX_CHARS -> listOf(
                TelegramTemplateError(TelegramTemplateErrorKind.TOO_LONG, actualLength = length)
            )
            else -> emptyList()
        }
        return TelegramTemplateRenderResult(payload.takeIf { errors.isEmpty() }, errors)
    }

    fun render(template: String): TelegramTemplateRenderResult = TelegramTemplateRenderer.render(
        event.type,
        TelegramTemplateCatalog.templateForRendering(event.type, template, language, event.omitOverall),
        event.variables
    )

    val defaultTemplate = TelegramTemplateCatalog.defaultTemplate(event.type, language)
    val selected = render(savedTemplate ?: defaultTemplate)
    val base = selected.text ?: savedTemplate?.let { render(defaultTemplate).text }
        ?: return selected
    val suffix = event.textSuffix.orEmpty()
    val combined = base + suffix
    val payload = combined.takeIf {
        it.codePointCount(0, it.length) <= TELEGRAM_MESSAGE_MAX_CHARS
    } ?: base
    return TelegramTemplateRenderResult(payload, emptyList())
}
