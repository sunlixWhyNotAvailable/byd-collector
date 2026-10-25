package com.bydcollector.collector.mqtt

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteTableLockedException
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.util.diagnosticDetail
import java.util.concurrent.CancellationException
import java.time.OffsetDateTime
import java.util.Locale

//coordinates retained ha mqtt publishing with an sqlite outbox so short broker outages do not lose current state
class MqttPublishCoordinator(
    private val client: MqttClientFacade,
    private val outbox: MqttOutboxStore,
    private val retryStateStore: MqttRetryStateStore,
    private val messageFactory: HaMqttMessageFactory,
    private val configProvider: () -> HaMqttConfig,
    private val retryPolicy: MqttRetryPolicy = MqttRetryPolicy(),
    private val clock: Clock = SystemClockAdapter(),
    private val publicationJournal: MqttPublicationJournalStore = NoopMqttPublicationJournalStore,
    private val storageDiagnostic: (String, String) -> Unit = { _, _ -> }
) {
    private var sessionConnection: HaMqttConfig? = null
    private var sessionToken: String? = null
    @Volatile
    private var currentRoute: HaEndpointProfile? = null
    private val storageRetryLock = Any()
    private var storageGeneration = 0L
    private var storageRetryAt: Long? = null
    private var storageLockRetries = 0
    private var storageRecoveryRequired = false
    private var storageTerminalFailure: String? = null
    private var latestStatus: HaMqttStatus? = null

    /** A new run or explicit stop invalidates old in-flight storage failures. */
    fun resetStorageRecovery() = synchronized(storageRetryLock) {
        storageGeneration++
        storageRetryAt = null
        storageLockRetries = 0
        storageRecoveryRequired = false
        storageTerminalFailure = null
        latestStatus = null
    }

    fun runWithStorageRecovery(
        stage: String,
        isCurrent: () -> Boolean = { true },
        action: () -> MqttActionResult
    ): MqttActionResult {
        val pass = synchronized(storageRetryLock) { storageGeneration }
        if (!isCurrent()) return MqttActionResult.ok("MQTT work superseded")
        synchronized(storageRetryLock) { storageTerminalFailure }?.let {
            return MqttActionResult.fail("mqtt_storage_error", it)
        }
        storageRetryDelay()?.takeIf { it > 0L }?.let {
            return MqttActionResult.fail("mqtt_sqlite_busy", "SQLite retry pending")
        }
        return try {
            // Rebuild latest retained state as inputs may change while storage is blocked.
            // This also completes discovery/full-state enqueue interrupted during Start.
            val recovering = synchronized(storageRetryLock) { storageRecoveryRequired }
            val result = if (recovering) startLiveExport() else action()
            synchronized(storageRetryLock) {
                if (pass == storageGeneration && isCurrent()) {
                    storageRetryAt = null
                    storageLockRetries = 0
                    storageRecoveryRequired = false
                }
            }
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            if (!isCurrent() || synchronized(storageRetryLock) { pass != storageGeneration }) {
                return MqttActionResult.ok("MQTT work superseded")
            }
            val retry = recordStorageFailure(error, stage, pass, isCurrent)
            MqttActionResult.fail(
                if (retry) "mqtt_sqlite_busy" else "mqtt_storage_error",
                error.diagnosticDetail("stage=$stage")
            )
        }
    }

    private fun storageRetryDelay(): Long? = synchronized(storageRetryLock) {
        storageRetryAt?.let { (it - clock.elapsedRealtimeMs()).coerceAtLeast(0L) }
    }

    private fun recordStorageFailure(error: RuntimeException, stage: String, pass: Long, isCurrent: () -> Boolean): Boolean {
        val details = synchronized(storageRetryLock) {
            if (pass != storageGeneration || !isCurrent()) return false
            val retry = (error is SQLiteDatabaseLockedException || error is SQLiteTableLockedException) && storageLockRetries < 3
            if (retry) storageLockRetries++
            storageRetryAt = if (retry) clock.elapsedRealtimeMs() + 30_000L else null
            storageRecoveryRequired = retry
            if (!retry) storageTerminalFailure = error.diagnosticDetail("stage=$stage")
            retry to "stage=$stage retry=$retry attempt=$storageLockRetries/3 retry_at_elapsed_ms=$storageRetryAt"
        }
        try {
            storageDiagnostic(if (details.first) "mqtt_storage_retry_pending" else "mqtt_storage_terminal_failure",
                error.diagnosticDetail(details.second))
        } catch (loggingError: Throwable) {
            if (loggingError !== error) error.addSuppressed(loggingError)
            android.util.Log.e("MqttStorage", error.diagnosticDetail(details.second))
        }
        return details.first
    }

    val activeRoute: HaEndpointProfile?
        get() = if (client.isConnected) currentRoute else null

    private fun beginSession(config: HaMqttConfig) {
        sessionConnection = config.copy(enabledCategories = config.enabledCategories.toSet())
        sessionToken = MqttPublicationGate.registerSession(
            config.normalizedDeviceId(),
            MqttDestinationIdentity.configuredScopes(config).map { it.key }.toSet()
        )
        currentRoute = null
    }

    private fun endSession() {
        MqttPublicationGate.withLock {
            MqttPublicationGate.unregisterSession(sessionToken)
            sessionToken = null
            sessionConnection = null
            currentRoute = null
        }
    }

    fun testConnectionOnly(configOverride: HaMqttConfig? = null): MqttActionResult {
        val config = configOverride ?: configProvider()

        val connect = client.connect(
            config,
            willMessage = null,
            purpose = MqttConnectionPurpose.TEST_NO_WILL
        )
        //A manual test reports only its own result. Runtime retry/error state belongs to real export
        //attempts and must not briefly overwrite the HA status pill.
        return if (connect.ok) MqttActionResult.ok("connected") else connect
    }

    fun startLiveExport(): MqttActionResult {
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }

        //queues discovery before state so a failed initial connection retains complete restart work
        enqueueBuildResult(messageFactory.discoveryMessages(config), DISCOVERY_PRIORITY, config)?.let { return it }
        enqueueBuildResult(messageFactory.fullResyncMessages(config, synchronized(storageRetryLock) { latestStatus }), STATE_PRIORITY, config)?.let { return it }
        return flushPendingInternal(force = true, allowFullResyncAfterSuccess = false)
    }

    fun retryDelayMs(isCurrent: () -> Boolean = { true }): Long? {
        val config = runtimeConfig()
        if (!config.enabled || validateEnabled(config) != null || !isCurrent()) return null
        storageRetryDelay()?.let { return it }
        val pass = synchronized(storageRetryLock) { storageGeneration }
        val nextAttemptAt = try {
            if (outbox.pendingCount() == 0L) return null
            retryStateStore.retryState().nextAttemptAt ?: return null
        } catch (error: RuntimeException) {
            if (!isCurrent() || synchronized(storageRetryLock) { pass != storageGeneration }) return null
            if (recordStorageFailure(error, "mqtt_retry_preparation", pass, isCurrent)) return storageRetryDelay()
            throw error
        }
        return runCatching {
            val now = OffsetDateTime.parse(clock.nowIso()).toInstant().toEpochMilli()
            (OffsetDateTime.parse(nextAttemptAt).toInstant().toEpochMilli() - now).coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    fun queueDiscoveryAndFlush(force: Boolean = true): MqttActionResult {
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }
        if (!config.discoveryEnabled) {
            return MqttActionResult.fail("ha_discovery_disabled", "Home Assistant MQTT discovery is disabled")
        }
        return enqueueBuildResult(messageFactory.discoveryMessages(config), DISCOVERY_PRIORITY, config)
            ?: flushPending(force)
    }

    fun queueFullResyncAndFlush(force: Boolean = true): MqttActionResult {
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.fullResyncMessages(config, synchronized(storageRetryLock) { latestStatus }), STATE_PRIORITY, config)
            ?: flushPending(force)
    }

    fun queueStatusAndFlush(status: HaMqttStatus, force: Boolean = false): MqttActionResult {
        // A failed enqueue must not turn the next retained-state resync into generic "online".
        synchronized(storageRetryLock) { latestStatus = status }
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.statusMessage(config, status), STATUS_PRIORITY, config)
            ?: flushPending(force)
    }

    fun queueChangedCategoriesAndFlush(categories: Set<String>, force: Boolean = false): MqttActionResult {
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.changedCategoryMessages(config, categories), STATE_PRIORITY, config)
            ?: flushPending(force)
    }

    fun flushPending(force: Boolean = false): MqttActionResult {
        return flushPendingInternal(force = force, allowFullResyncAfterSuccess = true)
    }

    fun disconnectOffline(): MqttActionResult {
        val config = runtimeConfig()
        val result = client.disconnect(currentRoute?.let { messageFactory.offlineMessage(config) })
        endSession()
        if (!result.ok) recordRetryFailure(result.message, retryStateStore.retryState())
        return result
    }

    fun disconnectForMaintenance(): MqttActionResult {
        val result = client.disconnect(null)
        endSession()
        return result
    }

    private fun flushPendingInternal(
        force: Boolean,
        allowFullResyncAfterSuccess: Boolean
    ): MqttActionResult {
        val config = runtimeConfig(capture = true)
        if (!config.enabled) return MqttActionResult.ok("mqtt disabled")
        validateEnabled(config)?.let { return it }

        val startingRetryState = retryStateStore.retryState()
        //honors persisted backoff unless the user explicitly tests/starts the channel
        if (!retryPolicy.canAttempt(startingRetryState, clock.nowIso(), force)) {
            return MqttActionResult.ok("mqtt backoff active")
        }

        val snapshot = if (force) {
            outbox.pendingMessages(PUBLISH_LIMIT)
        } else {
            outbox.dueMessages(clock.nowIso(), PUBLISH_LIMIT)
        }
        val destinationKey = MqttDestinationIdentity.destinationKey(config, publicationJournal)
        val pending = snapshot.filter { message ->
            val current = MqttPublicationGate.withLock {
                outbox.isCurrent(message.targetKey, message.payloadHash, message.destinationKey) &&
                    message.destinationKey == destinationKey
            }
            if (!current) {
                MqttPublicationGate.withLock {
                    outbox.discardPending(message.targetKey, message.payloadHash, message.destinationKey)
                }
            }
            current
        }
        if (pending.isEmpty()) return MqttActionResult.ok("nothing pending")

        val attemptedRoutes = mutableSetOf<HaEndpointProfile>()
        val connect = connectRuntime(config, attemptedRoutes = attemptedRoutes)
        if (!connect.ok) {
            val nextAttemptAt = recordRetryFailure(connect.message, startingRetryState)
            pending.forEach {
                MqttPublicationGate.withLock {
                    outbox.markFailed(it.targetKey, it.payloadHash, connect.message, clock.nowIso(), nextAttemptAt, it.destinationKey)
                }
            }
            return connect
        }

        for (pendingMessage in pending) {
            var attempt = publishIfCurrent(pendingMessage, config)
            if (attempt.discarded) continue
            var publish = attempt.result ?: MqttActionResult.fail("mqtt_error", "MQTT publish did not run", MqttFailureKind.OTHER)
            if (!publish.ok && publish.failureKind == MqttFailureKind.TRANSPORT) {
                val failedRoute = currentRoute ?: HaEndpointProfile.PRIMARY
                client.disconnect(null)
                currentRoute = null
                val reconnect = otherProfile(config, failedRoute)?.takeUnless { it in attemptedRoutes }?.let {
                    connectRuntime(
                        config,
                        preferred = it,
                        allowOther = false,
                        attemptedRoutes = attemptedRoutes
                    )
                }
                publish = if (reconnect?.ok == true) {
                    attempt = publishIfCurrent(pendingMessage, config)
                    if (attempt.discarded) continue
                    attempt.result ?: publish
                } else reconnect ?: publish
            }
            if (!publish.ok) {
                if (publish.failureKind == MqttFailureKind.TRANSPORT) {
                    client.disconnect(null)
                    currentRoute = null
                }
                val nextAttemptAt = recordRetryFailure(publish.message, retryStateStore.retryState())
                MqttPublicationGate.withLock {
                    if (outbox.isCurrent(pendingMessage.targetKey, pendingMessage.payloadHash, pendingMessage.destinationKey)) {
                        outbox.markFailed(
                            pendingMessage.targetKey,
                            pendingMessage.payloadHash,
                            publish.message,
                            clock.nowIso(),
                            nextAttemptAt,
                            pendingMessage.destinationKey
                        )
                    }
                }
                return publish
            }
        }

        val hadPreviousFailure = startingRetryState.failureCount > 0 || startingRetryState.lastError != null
        retryStateStore.recordRetrySuccess(clock.nowIso())
        if (hadPreviousFailure && allowFullResyncAfterSuccess) {
            //after reconnect, resync everything because retained topics may have gone stale during outage
            return enqueueBuildResult(messageFactory.fullResyncMessages(runtimeConfig(), synchronized(storageRetryLock) { latestStatus }), STATE_PRIORITY, config)
                ?: flushPendingInternal(force = true, allowFullResyncAfterSuccess = false)
        }
        return MqttActionResult.ok()
    }

    private data class PublishAttempt(val result: MqttActionResult?, val discarded: Boolean)

    private fun publishIfCurrent(pending: PendingMqttMessage, config: HaMqttConfig): PublishAttempt {
        return MqttPublicationGate.withLock {
            val currentDestinationKey = MqttDestinationIdentity.destinationKey(config, publicationJournal)
            if (pending.destinationKey != currentDestinationKey ||
                !outbox.isCurrent(pending.targetKey, pending.payloadHash, pending.destinationKey)) {
                outbox.discardPending(pending.targetKey, pending.payloadHash, pending.destinationKey)
                return@withLock PublishAttempt(null, discarded = true)
            }
            if (!outbox.markAttempt(pending.targetKey, pending.payloadHash, clock.nowIso(), pending.destinationKey)) {
                return@withLock PublishAttempt(null, discarded = true)
            }

            val route = currentRoute ?: HaEndpointProfile.PRIMARY
            val scope = MqttDestinationIdentity.scopeForProfile(config, route)
            val isDiscovery = pending.targetType == "discovery" && pending.message.retained
            val isTombstone = isDiscovery && pending.message.payload.isEmpty()
            // A queued payload can predate a name/model edit without changing its destination.
            val publishedDevice = if (isDiscovery && !isTombstone) runCatching {
                org.json.JSONObject(pending.message.payload).getJSONObject("device")
            }.getOrNull() else null
            val publishedName = publishedDevice?.optString("name").orEmpty()
            val publishedIdentifier = publishedDevice?.optJSONArray("identifiers")?.optString(0)?.takeIf { it.isNotBlank() }
            val publishedModel = publishedDevice?.optString("model")?.takeIf { it.isNotBlank() }
            if (isDiscovery && !isTombstone && scope != null) {
                publicationJournal.recordDiscoveryAttempt(
                    scopeKey = scope.key,
                    deviceId = config.normalizedDeviceId(),
                    topic = pending.message.topic,
                    payloadHash = pending.payloadHash,
                    deviceName = publishedName,
                    at = clock.nowIso(),
                    deviceIdentifier = publishedIdentifier,
                    deviceModel = publishedModel
                )
            }

            val publish = client.publish(pending.message)
            val now = clock.nowIso()
            if (publish.ok) {
                if (isDiscovery && scope != null) {
                    publicationJournal.recordDiscoveryAcknowledged(
                        scopeKey = scope.key,
                        deviceId = config.normalizedDeviceId(),
                        topic = pending.message.topic,
                        payloadHash = pending.payloadHash,
                        deviceName = publishedName,
                        at = now,
                        remove = isTombstone,
                        deviceIdentifier = publishedIdentifier,
                        deviceModel = publishedModel
                    )
                }
                //Conditional deletion prevents a stale due-message snapshot from deleting newer work.
                outbox.markPublished(pending.targetKey, pending.payloadHash, now, pending.destinationKey)
            } else if (isDiscovery && !isTombstone && scope != null) {
                publicationJournal.recordDiscoveryFailure(
                    scopeKey = scope.key,
                    deviceId = config.normalizedDeviceId(),
                    topic = pending.message.topic,
                    error = publish.message,
                    at = now
                )
            }
            PublishAttempt(publish, discarded = false)
        }
    }

    private fun enqueueBuildResult(
        buildResult: MqttMessageBuildResult,
        fallbackPriority: Int,
        config: HaMqttConfig
    ): MqttActionResult? {
        return when (buildResult) {
            is MqttMessageBuildResult.Failure -> MqttActionResult.fail(buildResult.category, buildResult.message)
            is MqttMessageBuildResult.Success -> {
                val pendingResult = MqttPublicationGate.withLock {
                    val scopeKeys = MqttDestinationIdentity.configuredScopes(config).map { it.key }.toSet()
                    if (publicationJournal.isCleanupPending(config.normalizedDeviceId(), scopeKeys)) {
                        MqttActionResult.fail(
                            "mqtt_cleanup_pending",
                            "MQTT publication is paused while retained discovery cleanup is pending",
                            MqttFailureKind.PROTOCOL
                        )
                    } else {
                        val destinationKey = MqttDestinationIdentity.destinationKey(config, publicationJournal)
                        //Upsert each topic atomically with the exact destination generation.
                        buildResult.messages.forEach { message ->
                            outbox.upsertPending(
                                message = message,
                                targetType = targetType(message),
                                priority = priorityFor(message, fallbackPriority, config),
                                destinationKey = destinationKey
                            )
                        }
                        null
                    }
                }
                pendingResult
            }
        }
    }

    private fun validateEnabled(config: HaMqttConfig): MqttActionResult? {
        if (!config.enabled) return MqttActionResult.fail("mqtt_disabled", "MQTT publishing is disabled")
        config.validateEndpoints()?.let {
            return MqttActionResult.fail("mqtt_endpoint_invalid", it, MqttFailureKind.PROTOCOL)
        }
        config.validateIdentity()?.let {
            return MqttActionResult.fail("mqtt_identity_invalid", it, MqttFailureKind.PROTOCOL)
        }
        return null
    }

    private fun runtimeConfig(capture: Boolean = false): HaMqttConfig {
        return MqttPublicationGate.withLock {
            val live = configProvider()
            //Rejected drafts and Device IDs fenced by cleanup must not become the next frozen session.
            if (capture && sessionConnection == null && validateEnabled(live) == null) {
                val scopes = MqttDestinationIdentity.configuredScopes(live).map { it.key }.toSet()
                if (!publicationJournal.isCleanupPending(live.normalizedDeviceId(), scopes)) beginSession(live)
            }
            val frozen = sessionConnection ?: live
            //Connection and destination fields remain frozen for the owned session;
            //enablement/category switches remain live for the existing worker.
            frozen.copy(
                enabled = live.enabled,
                discoveryEnabled = frozen.discoveryEnabled,
                enabledCategories = live.enabledCategories.toSet()
            )
        }
    }

    private fun connectRuntime(
        config: HaMqttConfig,
        preferred: HaEndpointProfile? = null,
        allowOther: Boolean = true,
        attemptedRoutes: MutableSet<HaEndpointProfile> = mutableSetOf()
    ): MqttActionResult {
        val heldRoute = currentRoute
        if (heldRoute != null && client.isConnected) {
            attemptedRoutes.add(heldRoute)
            return MqttActionResult.ok("connected")
        }
        if (!client.isConnected) {
            currentRoute = null
        }
        config.validateEndpoints()?.let {
            currentRoute = null
            return MqttActionResult.fail("mqtt_endpoint_invalid", it, MqttFailureKind.PROTOCOL)
        }
        val profiles = buildList {
            val first = preferred ?: HaEndpointProfile.PRIMARY
            add(first)
            if (allowOther) otherProfile(config, first)?.let(::add)
        }
        var lastFailure: MqttActionResult? = null
        for (profile in profiles) {
            if (!attemptedRoutes.add(profile)) continue
            val endpoint = try {
                config.forProfile(profile)
            } catch (error: IllegalArgumentException) {
                lastFailure = MqttActionResult.fail(
                    "mqtt_endpoint_invalid",
                    error.message ?: "MQTT endpoint is not configured",
                    MqttFailureKind.PROTOCOL
                )
                continue
            }
            val connect = client.connect(endpoint, messageFactory.offlineMessage(config), MqttConnectionPurpose.RUNTIME)
            if (connect.ok) {
                currentRoute = profile
                return connect
            }
            lastFailure = connect
            currentRoute = null
            client.disconnect(null)
            if (!allowOther || connect.failureKind != MqttFailureKind.TRANSPORT) return connect
        }
        return lastFailure ?: MqttActionResult.fail("mqtt_error", "MQTT connection failed", MqttFailureKind.TRANSPORT)
    }

    private fun otherProfile(config: HaMqttConfig, profile: HaEndpointProfile): HaEndpointProfile? {
        return when (profile) {
            HaEndpointProfile.PRIMARY -> {
                if (config.alternativeHost.isNullOrBlank() && config.alternativePort == null) null
                else HaEndpointProfile.ALTERNATIVE
            }
            HaEndpointProfile.ALTERNATIVE -> HaEndpointProfile.PRIMARY
        }
    }

    private fun recordRetryFailure(error: String, stateBeforeFailure: MqttRetryState): String {
        val nextFailureCount = stateBeforeFailure.failureCount + 1
        val delayMs = retryPolicy.delayForFailure(nextFailureCount)
        val failedAt = clock.nowIso()
        val nextAttemptAt = plusMillis(failedAt, delayMs)
        retryStateStore.recordRetryFailure(error, failedAt, nextAttemptAt)
        return nextAttemptAt
    }

    private fun plusMillis(iso: String, millis: Long): String {
        return runCatching {
            OffsetDateTime.parse(iso).plusNanos(millis * 1_000_000L).toString()
        }.getOrElse {
            iso
        }
    }

    private fun priorityFor(message: HaMqttMessage, fallbackPriority: Int, config: HaMqttConfig): Int {
        if (isLegacySunroofTombstone(message, config)) return LEGACY_SUNROOF_TOMBSTONE_PRIORITY
        return when (targetType(message)) {
            "discovery" -> DISCOVERY_PRIORITY
            "status" -> STATUS_PRIORITY
            "state" -> STATE_PRIORITY
            else -> fallbackPriority
        }
    }

    private fun isLegacySunroofTombstone(message: HaMqttMessage, config: HaMqttConfig): Boolean {
        return message.retained && message.payload.isEmpty() && message.topic.endsWith(
            "/binary_sensor/${config.normalizedDeviceId()}/bodywork_sunroof_windoblind_position/config"
        )
    }

    private fun targetType(message: HaMqttMessage): String {
        val topic = message.topic.lowercase(Locale.US)
        return when {
            topic.endsWith("/status") -> "status"
            "/state/" in topic -> "state"
            topic.endsWith("/config") -> "discovery"
            else -> "mqtt"
        }
    }

    private companion object {
        //ensures the retained legacy entity is cleared before the replacement sensor config is delivered
        const val LEGACY_SUNROOF_TOMBSTONE_PRIORITY = 9
        const val DISCOVERY_PRIORITY = 10
        const val STATUS_PRIORITY = 20
        const val STATE_PRIORITY = 50
        const val PUBLISH_LIMIT = 100
    }
}
