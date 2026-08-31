package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.local.TelegramOutboxEntry
import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.diagnostics.diagnosticSha256
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.TelegramDetectedEvent
import com.bydcollector.collector.service.TelegramEventConfig
import com.bydcollector.collector.service.TelegramEventEngine
import com.bydcollector.collector.service.TelegramEventResult
import com.bydcollector.collector.service.TelegramEventState
import com.bydcollector.collector.service.TelegramLocationSnapshot
import com.bydcollector.collector.service.TelegramPowerOffSnapshot
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture

internal data class TelegramPowerOffPreparation(
    val priorityKeys: List<String>,
    val eventDeadlineAtMs: Long?
)

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
    private val locationKindsByDedupeKey = LinkedHashMap<String, String>(16, 0.75f, true)
    private val locationKindCacheLock = Any()

    fun onSuccessfulPoll(
        observations: List<NormalizedObservation>,
        onDiagnosticLegStarted: ((String) -> Unit)? = null
    ): Long? {
        activateEnabledRuntime() ?: return null
        val startupDeadline = ensureStartupRecovery()
        val previousChargingActive = engine.state.chargingActive
        val previousTripId = engine.state.tripId
        val result = engine.onSuccessfulPoll(observations, eventConfig(), nowMs())
        val committed = handle(result)
        // Only a leg created by this poll has a proven relation to this poll's Trips session.
        // Restored active/pending legs without metadata remain explicitly unlinked.
        if (committed) {
            result.state.tripId?.takeIf {
                it != previousTripId && result.state.tripPowerSessionId == null
            }?.let { legId ->
                runCatching { onDiagnosticLegStarted?.invoke(legId) }
            }
        }
        if (result.state.chargingActive != previousChargingActive) {
            eventStore.recordEvent(
                "telegram_charging_transition",
                "Telegram charging evidence changed",
                "active=${result.state.chargingActive ?: "unknown"} source=${result.state.chargingEvidenceSource ?: "unknown"}"
            )
        }
        return nextWakeAt(result.nextWakeAtMs, nextWakeAt(startupDeadline, pendingQueueDeadline()))
    }

    /** Diagnostic writes never reset or disable the sender on failure. */
    internal fun bindTripDiagnosticParent(legId: String, powerSessionId: String) {
        runCatching {
            engine.bindTripPowerSession(legId, powerSessionId) { state ->
                telegramStore.saveTelegramRuntimeState(state.toJson(), nowMs())
            }
        }.onFailure { error ->
            runCatching {
                eventStore.recordEvent(
                    "telegram_diagnostic_correlation_error",
                    "Telegram trip diagnostic correlation could not be persisted",
                    "error=${error::class.java.simpleName}"
                )
            }
        }
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

    /** Commits the power-off obligation locally before the Trips database is closed. */
    internal fun preparePowerOffConfirmed(
        snapshot: TelegramPowerOffSnapshot = TelegramPowerOffSnapshot(),
        location: TelegramLocationSnapshot? = null
    ): TelegramPowerOffPreparation? {
        activateEnabledRuntime() ?: return null
        val recovered = recoverStartupLocally()
        val pendingSummaryKey = engine.state.pendingPowerOffLocationTripId
            ?.takeIf { !engine.state.pendingPowerOffLocationSummaryDelivered }
            ?.let { "$it:summary" }
        val pendingLocationTripId = engine.state.pendingPowerOffLocationTripId
        val result = engine.onPowerOffConfirmed(eventConfig(), snapshot, location, nowMs())
        check(handle(result)) { "Telegram power-off batch could not be rendered atomically" }
        result.locationEligibilityReason?.let { reason ->
            eventStore.recordEvent(
                "telegram_location_eligibility",
                "Telegram power-off location eligibility evaluated",
                buildTelegramDiagnosticDetail(
                    dedupeKey = pendingLocationTripId?.let { "$it:location" },
                    eventType = TelegramEventType.TRIP_SUMMARY.key,
                    extra = "trigger=power_off reason=$reason"
                )
            )
        }
        val priorityKeys = buildList {
            recovered?.events
                ?.filter { it.type == TelegramEventType.TRIP_SUMMARY && !it.locationOnly }
                ?.forEach { add(it.dedupeKey) }
            pendingSummaryKey?.let(::add)
            result.events
                .filter { it.type == TelegramEventType.TRIP_SUMMARY && !it.locationOnly }
                .forEach { add(it.dedupeKey) }
            result.events.filter { it.locationOnly }.forEach { add(it.dedupeKey) }
        }.distinct()
        return TelegramPowerOffPreparation(
            priorityKeys = priorityKeys,
            eventDeadlineAtMs = nextWakeAt(recovered?.nextWakeAtMs, result.nextWakeAtMs)
        )
    }

    /** Performs network work only after the Trips close succeeds. */
    internal fun deliverPreparedPowerOff(preparation: TelegramPowerOffPreparation): Long? {
        var deadline = preparation.eventDeadlineAtMs
        preparation.priorityKeys.forEach { key ->
            deadline = nextWakeAt(deadline, flushPending(key, force = true))
        }
        deadline = nextWakeAt(deadline, flushPending())
        return nextWakeAt(deadline, pendingQueueDeadline())
    }

    /** Compatibility hook for direct callers; service uses the ordered two-phase API. */
    fun onPowerOffConfirmed(
        snapshot: TelegramPowerOffSnapshot = TelegramPowerOffSnapshot(),
        location: TelegramLocationSnapshot? = null
    ): Long? {
        val preparation = preparePowerOffConfirmed(snapshot, location) ?: return null
        return deliverPreparedPowerOff(preparation)
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
                    buildTelegramDiagnosticDetail(
                        dedupeKey = entry.dedupeKey,
                        eventType = entry.eventType,
                        waitsForSummaryKey = entry.waitsForSummaryKey,
                        location = knownLocationKind(entry.dedupeKey)
                    )
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
                    buildTelegramDiagnosticDetail(
                        dedupeKey = entry.dedupeKey,
                        eventType = entry.eventType,
                        waitsForSummaryKey = entry.waitsForSummaryKey,
                        location = knownLocationKind(entry.dedupeKey),
                        extra = failureDetail(result)
                    )
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
        val recovered = recoverStartupLocally() ?: return null
        val recoveredKey = recovered.events
            .firstOrNull { it.type == TelegramEventType.TRIP_SUMMARY }
            ?.dedupeKey
        val pending = recoveredKey?.let(telegramStore::telegramMessageByDedupeKey)?.takeUnless { it.blocked }
            ?: telegramStore.oldestUnblockedTelegramMessage(TelegramEventType.TRIP_SUMMARY.key)
        return nextWakeAt(recovered.nextWakeAtMs, pending?.let { attempt(it, force = true) })
    }

    private fun recoverStartupLocally(): TelegramEventResult? {
        if (!startupRecoveryPending) return null
        val recovered = engine.recoverPendingTrip(eventConfig(), nowMs())
        check(handle(recovered)) { "Telegram recovery batch could not be rendered atomically" }
        startupRecoveryPending = false
        return recovered
    }

    private fun activateEnabledRuntime(): Long? {
        if (!settings.isTelegramEnabled()) return null
        return enabledRuntimeStartedAtMs ?: nowMs().also { enabledRuntimeStartedAtMs = it }
    }

    private fun nextWakeAt(eventDeadlineAtMs: Long?, queueDeadlineAtMs: Long?): Long? {
        return listOfNotNull(eventDeadlineAtMs, queueDeadlineAtMs).minOrNull()
    }

    private fun handle(result: TelegramEventResult): Boolean {
        val messages = renderTelegramBatch(result.events, ::render)
        if (messages == null) {
            engine = TelegramEventEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
            return false
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
        val eventsByDedupeKey = result.events.associateBy { it.dedupeKey }
        messages.zip(outcomes).forEach { (message, queued) ->
            if (queued.inserted) {
                val locationKind = telegramLocationKind(eventsByDedupeKey[message.dedupeKey], message.dedupeKey)
                rememberLocationKind(message.dedupeKey, locationKind)
                eventStore.recordEvent(
                    "telegram_event_queued",
                    "Telegram event queued",
                    buildTelegramDiagnosticDetail(
                        dedupeKey = message.dedupeKey,
                        eventType = message.eventType,
                        waitsForSummaryKey = message.waitsForSummaryKey,
                        location = locationKind
                    )
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
        return true
    }

    private fun buildTelegramDiagnosticDetail(
        dedupeKey: String?,
        eventType: String,
        waitsForSummaryKey: String? = null,
        location: String? = null,
        extra: String? = null
    ): String = buildString {
        append("event=").append(eventType)
        dedupeKey?.let { key ->
            append(" dedupe_ref=").append(diagnosticSha256(key))
            telegramTripId(key)?.let { append(" trip_ref=").append(diagnosticSha256(it)) }
        }
        waitsForSummaryKey?.let { append(" dependency_ref=").append(diagnosticSha256(it)) }
        location?.let { append(" location=").append(it) }
        extra?.takeIf(String::isNotBlank)?.let { append(' ').append(it) }
    }

    private fun rememberLocationKind(dedupeKey: String, location: String) {
        synchronized(locationKindCacheLock) {
            locationKindsByDedupeKey[dedupeKey] = location
            while (locationKindsByDedupeKey.size > 128) {
                locationKindsByDedupeKey.entries.iterator().apply {
                    if (hasNext()) {
                        next()
                        remove()
                    }
                }
            }
        }
    }

    private fun knownLocationKind(dedupeKey: String): String? = synchronized(locationKindCacheLock) {
        locationKindsByDedupeKey[dedupeKey]
    }

    private fun telegramTripId(dedupeKey: String): String? = when {
        dedupeKey.endsWith(":summary") -> dedupeKey.removeSuffix(":summary")
        dedupeKey.endsWith(":location") -> dedupeKey.removeSuffix(":location")
        else -> null
    }

    private fun telegramLocationKind(event: TelegramDetectedEvent?, dedupeKey: String): String = when {
        event?.locationOnly == true || dedupeKey.endsWith(":location") -> "only"
        event?.textSuffix?.isNotBlank() == true -> "attached"
        else -> "none"
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

/** Joins one poll's results without waiting or changing either owner's execution order. */
internal fun correlateTripDiagnostic(
    legId: String,
    powerSession: CompletableFuture<String?>,
    isCurrent: () -> Boolean,
    enqueue: (() -> Unit) -> Unit,
    bind: (String, String) -> Unit
) {
    if (legId.isBlank()) return
    powerSession.thenAccept { resolved ->
        val parentId = resolved?.trim()?.takeIf(String::isNotEmpty) ?: return@thenAccept
        if (!isCurrent()) return@thenAccept
        enqueue {
            if (isCurrent()) bind(legId, parentId)
        }
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
