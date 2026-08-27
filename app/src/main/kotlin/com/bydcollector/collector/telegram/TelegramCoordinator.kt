package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelegramStore
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
    private val eventStore: TelemetryStore,
    private val telegramStore: TelegramStore,
    private val settings: CollectorSettings,
    private val client: TelegramHttpClient = TelegramHttpClient(),
    private val retryPolicy: TelegramRetryPolicy = TelegramRetryPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var engine = TelegramEventEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
    private var enabledRuntimeStartedAtMs: Long? = null
    private var startupRecoveryPending = true

    fun onSuccessfulPoll(observations: List<NormalizedObservation>): Long? {
        activateEnabledRuntime() ?: return null
        val startupDeadline = ensureStartupRecovery()
        val previousChargingActive = engine.state.chargingActive
        val result = engine.onSuccessfulPoll(observations, eventConfig(), nowMs())
        handle(result)
        if (result.state.chargingActive != previousChargingActive) {
            eventStore.recordEvent(
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
        val expired = telegramStore.pruneTelegramMessages(tickAtMs)
        if (expired > 0) {
            eventStore.recordEvent(
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
        result.locationEligibilityReason?.let { reason ->
            eventStore.recordEvent(
                "telegram_location_eligibility",
                "Telegram power-off location eligibility evaluated",
                "trigger=power_off reason=$reason"
            )
        }
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
                telegramStore.unblockTelegramMessages(nowMs())
                eventStore.recordEvent("telegram_connection_test_success", "Telegram connection test succeeded")
            }
            is TelegramSendResult.Failure -> {
                settings.setTelegramConnectionStatus("failed", result.kind.name.lowercase())
                eventStore.recordEvent(
                    "telegram_connection_test_failed",
                    "Telegram connection test failed",
                    failureDetail(result)
                )
            }
        }
        return result
    }

    fun credentialsChanged() {
        telegramStore.unblockTelegramMessages(nowMs())
    }

    fun integrationDisabled() {
        telegramStore.saveTelegramRuntimeState(engine.reset().toJson(), nowMs())
        enabledRuntimeStartedAtMs = null
        startupRecoveryPending = true
    }

    fun flushPending(): Long? {
        val entry = telegramStore.oldestUnblockedTelegramMessage() ?: return null
        return attempt(entry, force = false)
    }

    private fun flushPending(dedupeKey: String, force: Boolean): Long? {
        val entry = telegramStore.telegramMessageByDedupeKey(dedupeKey) ?: return null
        return attempt(entry, force)
    }

    private fun attempt(entry: TelegramOutboxEntry, force: Boolean): Long? {
        if (!settings.isTelegramEnabled()) return null
        val token = settings.telegramBotToken()
        val chatId = settings.telegramChatId()
        if (token.isBlank() || chatId.isBlank()) return null
        if (entry.blocked) return null
        entry.waitsForSummaryKey?.let { dependencyKey ->
            val summary = telegramStore.telegramMessageByDedupeKey(dependencyKey)
            return summary?.takeIf { !it.blocked }?.nextAttemptAtMs
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
                    telegramStore.markTelegramDelivered(entry.id, deliveredState?.toJson(), deliveredAtMs)
                } catch (error: RuntimeException) {
                    engine = TelegramEventEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
                    throw error
                }
                eventStore.recordEvent(
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
                        telegramStore.markTelegramRetry(entry.id, error, attemptedAt, it)
                    }
                } else {
                    telegramStore.markTelegramBlocked(entry.id, error, attemptedAt)
                    pendingQueueDeadline()
                }
                eventStore.recordEvent(
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
        return telegramStore.oldestUnblockedTelegramMessage()?.nextAttemptAtMs
    }

    private fun ensureStartupRecovery(): Long? {
        if (!startupRecoveryPending) return null
        val recovered = engine.recoverPendingTrip(eventConfig(), nowMs())
        handle(recovered)
        startupRecoveryPending = false
        val recoveredKey = recovered.events
            .firstOrNull { it.type == TelegramEventType.TRIP_SUMMARY }
            ?.dedupeKey
        val pending = recoveredKey?.let(telegramStore::telegramMessageByDedupeKey)?.takeUnless { it.blocked }
            ?: telegramStore.oldestUnblockedTelegramMessage(TelegramEventType.TRIP_SUMMARY.key)
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
            engine = TelegramEventEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
            return
        }
        val committedAt = nowMs()
        val outcomes = try {
            telegramStore.commitTelegramEvents(
                messages = messages,
                stateJson = result.state.toJson().takeIf { result.shouldPersist },
                nowMs = committedAt
            )
        } catch (error: RuntimeException) {
            engine = TelegramEventEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
            throw error
        }
        messages.zip(outcomes).forEach { (message, queued) ->
            if (queued.inserted) {
                eventStore.recordEvent(
                    "telegram_event_queued",
                    "Telegram event queued",
                    "event=${message.eventType}"
                )
            }
            if (queued.expiredCount > 0 || queued.overflowCount > 0) {
                eventStore.recordEvent(
                    "telegram_outbox_pruned",
                    "Telegram outbox retention removed messages",
                    "expired=${queued.expiredCount} overflow=${queued.overflowCount}"
                )
            }
        }
    }

    private fun render(event: TelegramDetectedEvent): TelegramOutboxMessage? {
        val tripTemplateLimitRevision = if (event.type == TelegramEventType.TRIP_SUMMARY && !event.locationOnly) {
            settings.telegramTripTemplateLimitRevision()
        } else {
            null
        }
        val language = telegramLanguage()
        val savedTemplate = settings.telegramTemplate(event.type.key)
        val rendered = renderTelegramPayload(event, savedTemplate, language)
        if (tripTemplateLimitRevision != null) {
            settings.setTelegramTripTemplateLimitState(rendered.limitState, tripTemplateLimitRevision)
        }
        if (rendered.usedFallback) {
            eventStore.recordEvent(
                "telegram_template_fallback",
                "Telegram custom template was invalid; built-in fallback rendered",
                "event=${event.type.key} reason=invalid_custom_template"
            )
        }
        val payload = rendered.text
        if (payload == null) {
            eventStore.recordEvent(
                "telegram_template_invalid",
                "Telegram event skipped because its template is invalid",
                "event=${event.type.key} errors=${rendered.errors.joinToString(",") { it.kind.name.lowercase() }}"
            )
            return null
        }
        return TelegramOutboxMessage(
            dedupeKey = event.dedupeKey,
            eventType = event.type.key,
            payload = payload,
            waitsForSummaryKey = event.waitsForSummaryKey
        )
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
        return TelegramTemplateRenderResult(
            payload.takeIf { errors.isEmpty() },
            errors,
            if (errors.any { it.kind == TelegramTemplateErrorKind.TOO_LONG }) {
                TelegramPayloadLimitState.WITH_LOCATION
            } else {
                TelegramPayloadLimitState.NONE
            }
        )
    }

    fun render(template: String): TelegramTemplateRenderResult = TelegramTemplateRenderer.render(
        event.type,
        TelegramTemplateCatalog.templateForRendering(event.type, template, language, event.omitOverall),
        event.variables
    )

    val defaultTemplate = TelegramTemplateCatalog.defaultTemplate(event.type, language)
    val selected = render(savedTemplate ?: defaultTemplate)
    val templateTooLong = selected.errors.any { it.kind == TelegramTemplateErrorKind.TOO_LONG }
    val fallback = savedTemplate?.takeIf { selected.text == null }?.let { render(defaultTemplate) }
    val base = selected.text ?: fallback?.text ?: return selected
    val suffix = event.textSuffix.orEmpty()
    val combined = base + suffix
    val locationTooLong = combined.codePointCount(0, combined.length) > TELEGRAM_MESSAGE_MAX_CHARS
    val payload = if (locationTooLong) base else combined
    val limitState = when {
        templateTooLong -> TelegramPayloadLimitState.TEMPLATE
        locationTooLong && suffix.isNotEmpty() -> TelegramPayloadLimitState.WITH_LOCATION
        else -> TelegramPayloadLimitState.NONE
    }
    return TelegramTemplateRenderResult(
        payload,
        emptyList(),
        limitState,
        usedFallback = fallback?.text != null && selected.errors.any { it.kind != TelegramTemplateErrorKind.TOO_LONG }
    )
}
