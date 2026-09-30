package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelegramDeliveryReceipt
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.local.TelegramOutboxEntry
import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.diagnostics.diagnosticSha256
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.trips.TripCompletionIntent
import com.bydcollector.collector.data.trips.TripTime
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.TelegramDetectedEvent
import com.bydcollector.collector.service.TelegramEventConfig
import com.bydcollector.collector.service.TelegramEventEngine
import com.bydcollector.collector.service.TelegramEventResult
import com.bydcollector.collector.service.TelegramEventState
import com.bydcollector.collector.service.TelegramLocationSnapshot
import com.bydcollector.collector.service.TelegramPowerOffSnapshot
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class TelegramPowerOffPreparation(
    val priorityKeys: List<String>,
    val eventDeadlineAtMs: Long?
)

class TelegramCoordinator internal constructor(
    private val eventStore: TelemetryStore,
    private val telegramStore: TelegramStore,
    private val settings: CollectorSettings,
    private val normalizer: VehicleStateNormalizer,
    private val dispatchSend: (TelegramDeliveryAttempt) -> Unit,
    private val retryPolicy: TelegramRetryPolicy = TelegramRetryPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val currentEnergySnapshot: () -> EnergySnapshot? = { null },
    private val protectedTelegramIds: () -> Set<Long> = { emptySet() },
    private val deliveryLaneAvailable: () -> Boolean = { true },
    private val onConnectionTestSettled: (TelegramSendResult) -> Unit = {},
    private val ecMeanKwhPer100Km: () -> Double? = { null }
) {
    private var engine = createEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
    private var enabledRuntimeStartedAtMs: Long? = null
    private var startupRecoveryPending = true
    private val locationKindsByDedupeKey = LinkedHashMap<String, String>(16, 0.75f, true)
    private val locationKindCacheLock = Any()
    private val sourceCounts = linkedMapOf<String, Long>()
    private val rejectedSourceRefs = linkedSetOf<String>()
    private val rejectedKeyRefs = linkedSetOf<String>()
    private val sourceOrigins = linkedSetOf<String>()
    private val delivery = TelegramDeliveryQueue(
        store = telegramStore,
        credentials = { TelegramDeliveryCredentials(settings.isTelegramEnabled(), settings.telegramBotToken(), settings.telegramChatId()) },
        commitDelivery = ::commitDelivery,
        record = ::recordDelivery,
        nowMs = nowMs,
        retryPolicy = retryPolicy,
        deliveryLaneAvailable = deliveryLaneAvailable
    )

    fun onSourcePoll(
        pollId: Long,
        timestamp: String,
        source: PollSampleSource,
        readings: List<PollReading>,
        origin: PollOrigin,
        currentBootId: String,
        energySnapshot: EnergySnapshot? = null,
        onDiagnosticLegStarted: ((String) -> Unit)? = null
    ): Long? {
        activateEnabledRuntime() ?: return null
        val startupDeadline = ensureStartupRecovery(energySnapshot)
        val previousChargingActive = engine.state.chargingActive
        val previousBmsConflict = engine.state.bmsFinishHighPowerConflictActive
        val previousTripId = engine.state.tripId
        val result = engine.onSourcePoll(
            pollId, timestamp, source, readings, origin, currentBootId,
            eventConfig(), nowMs(), normalizer, energySnapshot
        )
        val committed = handle(result)
        if (committed) recordSourceDecisions(result, origin)
        // Only a leg created by this poll has a proven relation to this poll's Trips session.
        // Restored active/pending legs without metadata remain explicitly unlinked.
        if (committed) {
            result.state.tripId?.takeIf {
                it != previousTripId && result.state.tripPowerSessionId == null
            }?.let { legId ->
                runCatching { onDiagnosticLegStarted?.invoke(legId) }
            }
        }
        if (committed && result.state.chargingActive != previousChargingActive) {
            eventStore.recordEvent(
                "telegram_charging_transition",
                "Telegram charging evidence changed",
                "active=${result.state.chargingActive ?: "unknown"} source=${result.state.chargingEvidenceSource ?: "unknown"}"
            )
        }
        if (committed && !previousBmsConflict && result.state.bmsFinishHighPowerConflictActive) {
            eventStore.recordEvent(
                "telegram_bms_finish_power_conflict",
                "Telegram BMS finish conflicts with charging power",
                "bms_state=finished power_relation=gte_0_5_kw action=power_fallback"
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
        flushSourceDiagnostics(tickAtMs)
        val expired = telegramStore.pruneTelegramMessages(tickAtMs, protectedTelegramIds())
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

    /** Accepts a durable Trips completion. The receipt and batch commit together. */
    internal fun acceptTripCompletion(
        intent: TripCompletionIntent,
        location: TelegramLocationSnapshot?
    ): TelegramPowerOffPreparation? {
        if (telegramStore.hasTripCompletionReceipt(intent.sequence, intent.identity)) return null
        check(settings.isTelegramEnabled()) { "Telegram completion is waiting for the enabled runtime" }
        return preparePowerOffConfirmed(
            snapshot = TelegramPowerOffSnapshot(intent.odometerKm, intent.soc, intent.tripEnergyKwh, intent.energySnapshot),
            location = location,
            occurredAtMs = checkNotNull(TripTime.instant(intent.observedAt)).toEpochMilli(),
            completion = intent
        )
    }

    /** Local processing only; no network dependency in the Trips commit path. */
    internal fun preparePowerOffConfirmed(
        snapshot: TelegramPowerOffSnapshot = TelegramPowerOffSnapshot(),
        location: TelegramLocationSnapshot? = null,
        occurredAtMs: Long = nowMs(),
        completion: TripCompletionIntent? = null
    ): TelegramPowerOffPreparation? {
        activateEnabledRuntime() ?: return null
        val recovered = recoverStartupLocally(snapshot.energySnapshot, occurredAtMs)
        // No Telegram samples may have been processed while its SQLite was unavailable.
        // Preserve the known complete power session instead of inventing moving-leg splits.
        if (completion != null) engine = createEngine(recoverCompletionState(engine.state, completion))
        val pendingSummaryKey = engine.state.pendingPowerOffLocationTripId
            ?.takeIf { !engine.state.pendingPowerOffLocationSummaryDelivered }
            ?.let { "$it:summary" }
        val pendingLocationTripId = engine.state.pendingPowerOffLocationTripId
        val result = engine.onPowerOffConfirmed(eventConfig(), snapshot, location, occurredAtMs)
        check(handle(result, completion)) { "Telegram power-off batch could not be rendered atomically" }
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

    fun testConnection() {
        settings.setTelegramConnectionStatus("testing", null)
        val request = TelegramSendMessage(
            botToken = settings.telegramBotToken(),
            chatId = settings.telegramChatId(),
            text = "BYD Collector: Telegram connection test"
        )
        val selection = delivery.beginConnectionTest(
            request = request,
            requestKey = "manual:${UUID.randomUUID()}"
        )
        when (selection) {
            is TelegramConnectionSelection.Immediate -> finishConnectionTest(selection.result, request, null)
            is TelegramConnectionSelection.Ready -> dispatchSend(selection.attempt)
            TelegramConnectionSelection.Busy -> {
                settings.setTelegramConnectionStatus("failed", "sender_busy")
                runCatching { onConnectionTestSettled(TelegramSendResult.Failure(TelegramSendFailureKind.CONFIGURATION)) }
            }
        }
    }

    /** Current coordinator/store settles a process-owned HTTP result; no old store closure is retained. */
    internal fun settlePendingResult(
        outcome: TelegramPendingResult,
        onDurablySettled: () -> Unit
    ): Long? {
        val attempt = outcome.attempt
        if (attempt.entry != null) {
            return delivery.settleReceivedAttempt(
                attempt = attempt,
                result = outcome.result,
                confirmedAtMs = outcome.confirmedAtMs,
                onDurablySettled = onDurablySettled
            )
        }
        val result = delivery.settleReceivedConnectionTest(
            attempt = attempt,
            result = outcome.result,
            confirmedAtMs = outcome.confirmedAtMs,
            persistSuccess = { success ->
                val receipt = TelegramDeliveryReceipt(
                    dedupeKey = checkNotNull(attempt.dedupeKey),
                    eventType = attempt.eventType,
                    confirmedAtMs = outcome.confirmedAtMs,
                    telegramMessageId = success.messageId
                )
                try {
                    telegramStore.recordTelegramDelivery(receipt)
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                    recordLocalReceiptFailure(
                        receipt, error, "requested_at_ms=${attempt.startedAtMs}"
                    )
                    throw error
                }
            },
            onDurablySettled = onDurablySettled
        )
        finishConnectionTest(result, attempt.request, attempt, outcome.confirmedAtMs)
        return runCatching { delivery.pendingDeadline() }.getOrNull()
    }

    private fun finishConnectionTest(
        result: TelegramSendResult,
        request: TelegramSendMessage,
        attempt: TelegramDeliveryAttempt?,
        confirmedAtMs: Long? = null
    ) {
        val credentialsStillMatch = settings.telegramBotToken() == request.botToken &&
            settings.telegramChatId() == request.chatId
        when (result) {
            is TelegramSendResult.Success -> {
                if (credentialsStillMatch) {
                    runCatching { settings.setTelegramConnectionStatus("success", null) }
                    runCatching { telegramStore.unblockTelegramMessages(nowMs()) }
                }
                runCatching {
                    eventStore.recordEvent(
                        "telegram_connection_test_success",
                        "Telegram connection test succeeded",
                        "request_ref=${attempt?.dedupeKey?.let(::diagnosticSha256) ?: "none"} " +
                            "requested_at_ms=${attempt?.startedAtMs ?: "none"} " +
                            "http_confirmed_at_ms=${confirmedAtMs ?: "none"} " +
                            "message_id=${result.messageId ?: "none"}"
                    )
                }
            }
            is TelegramSendResult.Failure -> {
                if (credentialsStillMatch) {
                    runCatching { settings.setTelegramConnectionStatus("failed", result.kind.name.lowercase()) }
                }
                runCatching {
                    eventStore.recordEvent(
                        "telegram_connection_test_failed",
                        "Telegram connection test failed",
                        "request_ref=${attempt?.dedupeKey?.let(::diagnosticSha256) ?: "none"} " +
                            "requested_at_ms=${attempt?.startedAtMs ?: "none"} " +
                            "http_result_at_ms=${confirmedAtMs ?: "none"} ${failureDetail(result)}"
                    )
                }
            }
        }
        runCatching { onConnectionTestSettled(result) }
    }

    fun credentialsChanged() {
        delivery.resetSession()
        telegramStore.unblockTelegramMessages(nowMs())
    }

    fun integrationDisabled() {
        flushSourceDiagnostics(nowMs())
        delivery.resetSession()
        telegramStore.saveTelegramRuntimeState(engine.reset().toJson(), nowMs())
        enabledRuntimeStartedAtMs = null
        startupRecoveryPending = true
    }

    fun flushPending(): Long? {
        return dispatchAttempt(delivery.beginAttempt("tick"))
    }

    private fun flushPending(dedupeKey: String, force: Boolean): Long? {
        return dispatchAttempt(delivery.beginAttempt("power_off", dedupeKey, expediteLocal = force))
    }

    /** Called only on the existing serialized Telegram worker after lifecycle guards. */
    fun recoverPending(trigger: String): Long? {
        activateEnabledRuntime() ?: return null
        val recovered = recoverStartupLocally()
        return nextWakeAt(recovered?.nextWakeAtMs, dispatchAttempt(delivery.beginRecovery(trigger)))
    }

    private fun dispatchAttempt(attempt: TelegramDeliveryAttempt?): Long? {
        if (attempt != null) dispatchSend(attempt)
        return delivery.pendingDeadline()
    }

    private fun pendingQueueDeadline(): Long? {
        return delivery.pendingDeadline()
    }

    private fun commitDelivery(entry: TelegramOutboxEntry, messageId: Long?, deliveredAtMs: Long) {
        val deliveredState = stateAfterTripSummaryDelivery(engine.state, entry.dedupeKey, deliveredAtMs)
        try {
            val inserted = telegramStore.markTelegramDelivered(
                entry = entry,
                stateJson = deliveredState?.toJson(),
                telegramMessageId = messageId,
                deliveredAtMs = deliveredAtMs
            )
            if (inserted && deliveredState != null) {
                engine.markTripSummaryDelivered(entry.dedupeKey, deliveredAtMs)
            }
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            recordLocalReceiptFailure(
                TelegramDeliveryReceipt(entry.dedupeKey, entry.eventType, deliveredAtMs, messageId),
                error, "created_at_ms=${entry.createdAtMs} occurred_at_ms=${entry.occurredAtMs ?: "unknown"}"
            )
            runCatching { telegramStore.telegramRuntimeState() }
                .getOrNull()
                ?.let { engine = createEngine(TelegramEventState.fromJson(it)) }
            throw error
        }
    }

    private fun recordLocalReceiptFailure(receipt: TelegramDeliveryReceipt, error: Exception, context: String) {
        runCatching {
            eventStore.recordEvent(
                "telegram_message_local_commit_pending",
                "Telegram HTTP success is waiting for its local receipt",
                buildTelegramDiagnosticDetail(
                    dedupeKey = receipt.dedupeKey,
                    eventType = receipt.eventType,
                    location = knownLocationKind(receipt.dedupeKey),
                    extra = "delivery_state=known_success_local_commit_pending $context " +
                        "http_confirmed_at_ms=${receipt.confirmedAtMs} " +
                        "message_id=${receipt.telegramMessageId ?: "none"} storage_error=${error::class.java.simpleName}"
                )
            )
        }
    }

    private fun stateAfterTripSummaryDelivery(
        state: TelegramEventState,
        dedupeKey: String,
        deliveredAtMs: Long
    ): TelegramEventState? {
        val tripId = state.pendingPowerOffLocationTripId ?: return null
        if (dedupeKey != "$tripId:summary" || state.pendingPowerOffLocationSummaryDelivered) return null
        return state.copy(
            pendingPowerOffLocationSummaryDelivered = true,
            lastPersistedAtMs = deliveredAtMs
        )
    }

    private fun recordDelivery(kind: String, entry: TelegramOutboxEntry?, detail: String) {
        eventStore.recordEvent(
            "telegram_message_$kind",
            "Telegram delivery $kind",
            buildTelegramDiagnosticDetail(
                dedupeKey = entry?.dedupeKey,
                eventType = entry?.eventType ?: "queue",
                waitsForSummaryKey = entry?.waitsForSummaryKey,
                location = entry?.let { knownLocationKind(it.dedupeKey) },
                extra = detail
            )
        )
    }

    private fun ensureStartupRecovery(energySnapshot: EnergySnapshot? = null): Long? {
        val recovered = recoverStartupLocally(energySnapshot) ?: return null
        return nextWakeAt(recovered.nextWakeAtMs, dispatchAttempt(delivery.beginRecovery("startup")))
    }

    private fun recoverStartupLocally(energySnapshot: EnergySnapshot? = null, occurredAtMs: Long = nowMs()): TelegramEventResult? {
        if (!startupRecoveryPending) return null
        val durableEnergySnapshot = energySnapshot ?: runCatching(currentEnergySnapshot).getOrNull()
        val recovered = engine.recoverPendingTrip(eventConfig(), occurredAtMs, durableEnergySnapshot)
        check(handle(recovered)) { "Telegram recovery batch could not be rendered atomically" }
        startupRecoveryPending = false
        return recovered
    }

    private fun activateEnabledRuntime(): Long? {
        if (!settings.isTelegramEnabled()) return null
        return enabledRuntimeStartedAtMs ?: nowMs().also { enabledRuntimeStartedAtMs = it }
    }

    private fun createEngine(state: TelegramEventState) = TelegramEventEngine(state, ecMeanKwhPer100Km)

    private fun nextWakeAt(eventDeadlineAtMs: Long?, queueDeadlineAtMs: Long?): Long? {
        return listOfNotNull(eventDeadlineAtMs, queueDeadlineAtMs).minOrNull()
    }

    private fun handle(result: TelegramEventResult, completion: TripCompletionIntent? = null): Boolean {
        val messages = renderTelegramBatch(result.events, ::render)
        if (messages == null) {
            engine = createEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
            return false
        }
        val committedAt = nowMs()
        val outcomes = try {
            telegramStore.commitTelegramEvents(
                messages = messages,
                stateJson = result.state.toJson().takeIf { result.shouldPersist },
                nowMs = committedAt,
                completionSequence = completion?.sequence,
                completionIdentity = completion?.identity,
                protectedTelegramIds = protectedTelegramIds()
            )
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            engine = createEngine(TelegramEventState.fromJson(telegramStore.telegramRuntimeState()))
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
                        location = locationKind,
                        extra = "occurred_at_ms=${message.occurredAtMs ?: "unknown"} " +
                            "created_at_ms=$committedAt " +
                            "source_ref=${eventsByDedupeKey[message.dedupeKey]?.sourceIdentityHash ?: "none"}"
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

    private fun recordSourceDecisions(result: TelegramEventResult, origin: PollOrigin) {
        result.sourceOrderStats?.let { stats ->
            sourceOrigins += origin.name.lowercase()
            for ((reason, count) in listOf(
                "accepted" to stats.accepted, "seeded" to stats.seeded,
                "repeated" to stats.repeated, "older" to stats.older,
                "incomparable" to stats.incomparable, "conflicting_equal" to stats.conflictingEqual,
                "invalid" to stats.invalid
            )) sourceCounts[reason] = (sourceCounts[reason] ?: 0L) + count
            // Bounded examples only; counts retain all rejects until the existing tick flushes them.
            stats.sourceRefs.take((8 - rejectedSourceRefs.size).coerceAtLeast(0)).forEach(rejectedSourceRefs::add)
            stats.keyRefs.take((16 - rejectedKeyRefs.size).coerceAtLeast(0)).forEach(rejectedKeyRefs::add)
        }
        result.detectorDecisions.forEach { decision ->
            runCatching {
                eventStore.recordEvent(
                    "telegram_detector_decision", "Telegram fresh-source decision",
                    "detector=${decision.detector} source_ref=${decision.sourceIdentityHash} " +
                        "source_at_ms=${decision.sourceAtMs} decided_at_ms=${decision.decisionAtMs} " +
                        "origin=${origin.name.lowercase()} candidate=${decision.candidate} " +
                        "confirmed=${decision.confirmed} reason=${decision.reason}"
                )
            }
        }
    }

    /** Uses the existing owner tick, never another polling loop or per-cache-sample log. */
    private fun flushSourceDiagnostics(atMs: Long) {
        if (sourceCounts.values.none { it > 0L }) return
        runCatching {
            eventStore.recordEvent(
                "telegram_source_order", "Telegram input ordering summary",
                sourceCounts.entries.joinToString(" ") { "${it.key}=${it.value}" } +
                    " processed_at_ms=$atMs origins=${sourceOrigins.joinToString(",")} " +
                    "source_refs=${rejectedSourceRefs.joinToString(",")} key_refs=${rejectedKeyRefs.joinToString(",")}")
        }.onSuccess {
            sourceCounts.clear()
            rejectedSourceRefs.clear()
            rejectedKeyRefs.clear()
            sourceOrigins.clear()
        }
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
            waitsForSummaryKey = event.waitsForSummaryKey,
            occurredAtMs = event.occurredAtMs
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

    private fun failureDetail(result: TelegramSendResult.Failure): String {
        return telegramFailureDetail(result)
    }
}

/** A fallback only when the sender has no surviving leg/parked obligation. */
internal fun recoverCompletionState(state: TelegramEventState, intent: TripCompletionIntent): TelegramEventState {
    if (state.tripId != null || state.pendingPowerOffLocationTripId != null) return state
    val trip = intent.session?.takeIf { it.movementObserved } ?: return state
    val tripStartedAtMs = TripTime.instant(trip.startedAt)?.toEpochMilli()
    val tripEndedAtMs = trip.endedAt?.let { TripTime.instant(it)?.toEpochMilli() }
    return state.copy(
        tripId = "${intent.identity}:recovered",
        tripPowerSessionId = trip.tripId,
        tripStartedAtMs = tripStartedAtMs,
        tripStartOdometerKm = trip.startOdometerKm,
        tripStartSoc = trip.startSoc,
        tripStartEnergyKwh = trip.startTripEnergyKwh,
        tripParkedSinceMs = null,
        tripEndedAtMs = tripEndedAtMs,
        tripEndOdometerKm = trip.lastOdometerKm,
        tripEndSoc = trip.endSoc,
        tripEndEnergyKwh = trip.lastTripEnergyKwh,
        tripAccumulatedEnergyKwh = trip.energyKwh,
        lastTripEnergyCounterKwh = trip.lastTripEnergyKwh,
        bootStartSoc = trip.startSoc,
        bootEndSoc = trip.endSoc,
        bootTotalDistanceKm = 0.0,
        bootTotalEnergyKwh = 0.0,
        bootTotalDurationMs = 0L,
        bootTotalStartedAtMs = tripStartedAtMs,
        bootTotalEndedAtMs = tripEndedAtMs
    )
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
