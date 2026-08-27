package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.ha.HaEndpointProfile
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
    private val clock: Clock = SystemClockAdapter()
) {
    private var sessionConnection: HaMqttConfig? = null
    @Volatile
    private var currentRoute: HaEndpointProfile? = null

    val activeRoute: HaEndpointProfile?
        get() = if (client.isConnected) currentRoute else null

    private fun beginSession(config: HaMqttConfig) {
        sessionConnection = config.copy(enabledCategories = config.enabledCategories.toSet())
        currentRoute = null
    }

    private fun endSession() {
        sessionConnection = null
        currentRoute = null
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
        enqueueBuildResult(messageFactory.discoveryMessages(config), DISCOVERY_PRIORITY)?.let { return it }
        enqueueBuildResult(messageFactory.fullResyncMessages(config), STATE_PRIORITY)?.let { return it }
        return flushPendingInternal(force = true, allowFullResyncAfterSuccess = false)
    }

    fun retryDelayMs(): Long? {
        val config = runtimeConfig()
        if (!config.enabled || outbox.pendingCount() == 0L) return null
        val nextAttemptAt = retryStateStore.retryState().nextAttemptAt ?: return null
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
        return enqueueBuildResult(messageFactory.discoveryMessages(config), DISCOVERY_PRIORITY)
            ?: flushPending(force)
    }

    fun queueFullResyncAndFlush(force: Boolean = true): MqttActionResult {
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.fullResyncMessages(config), STATE_PRIORITY)
            ?: flushPending(force)
    }

    fun queueStatusAndFlush(status: HaMqttStatus, force: Boolean = false): MqttActionResult {
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.statusMessage(config, status), STATUS_PRIORITY)
            ?: flushPending(force)
    }

    fun queueChangedCategoriesAndFlush(categories: Set<String>, force: Boolean = false): MqttActionResult {
        val config = runtimeConfig(capture = true)
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.changedCategoryMessages(config, categories), STATE_PRIORITY)
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

        val startingRetryState = retryStateStore.retryState()
        //honors persisted backoff unless the user explicitly tests/starts the channel
        if (!retryPolicy.canAttempt(startingRetryState, clock.nowIso(), force)) {
            return MqttActionResult.ok("mqtt backoff active")
        }

        val pending = if (force) {
            outbox.pendingMessages(PUBLISH_LIMIT)
        } else {
            outbox.dueMessages(clock.nowIso(), PUBLISH_LIMIT)
        }
        if (pending.isEmpty()) return MqttActionResult.ok("nothing pending")

        val attemptedRoutes = mutableSetOf<HaEndpointProfile>()
        val connect = connectRuntime(config, attemptedRoutes = attemptedRoutes)
        if (!connect.ok) {
            val nextAttemptAt = recordRetryFailure(connect.message, startingRetryState)
            pending.forEach {
                outbox.markFailed(it.targetKey, it.payloadHash, connect.message, clock.nowIso(), nextAttemptAt)
            }
            return connect
        }

        pending.forEach { pendingMessage ->
            outbox.markAttempt(pendingMessage.targetKey, pendingMessage.payloadHash, clock.nowIso())
            var publish = client.publish(pendingMessage.message)
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
                publish = if (reconnect?.ok == true) client.publish(pendingMessage.message) else reconnect ?: publish
            }
            if (!publish.ok) {
                if (publish.failureKind == MqttFailureKind.TRANSPORT) {
                    client.disconnect(null)
                    currentRoute = null
                }
                val nextAttemptAt = recordRetryFailure(publish.message, retryStateStore.retryState())
                outbox.markFailed(
                    pendingMessage.targetKey,
                    pendingMessage.payloadHash,
                    publish.message,
                    clock.nowIso(),
                    nextAttemptAt
                )
                return publish
            }
            outbox.markPublished(
                targetKey = pendingMessage.targetKey,
                payloadHash = pendingMessage.payloadHash,
                publishedAt = clock.nowIso()
            )
        }

        val hadPreviousFailure = startingRetryState.failureCount > 0 || startingRetryState.lastError != null
        retryStateStore.recordRetrySuccess(clock.nowIso())
        if (hadPreviousFailure && allowFullResyncAfterSuccess) {
            //after reconnect, resync everything because retained topics may have gone stale during outage
            return enqueueBuildResult(messageFactory.fullResyncMessages(runtimeConfig()), STATE_PRIORITY)
                ?: flushPendingInternal(force = true, allowFullResyncAfterSuccess = false)
        }
        return MqttActionResult.ok()
    }

    private fun enqueueBuildResult(
        buildResult: MqttMessageBuildResult,
        fallbackPriority: Int
    ): MqttActionResult? {
        return when (buildResult) {
            is MqttMessageBuildResult.Failure -> MqttActionResult.fail(buildResult.category, buildResult.message)
            is MqttMessageBuildResult.Success -> {
                //upserts by topic so the outbox holds the newest payload, not a backlog of obsolete states
                buildResult.messages.forEach { message ->
                    outbox.upsertPending(
                        message = message,
                        targetType = targetType(message),
                        priority = priorityFor(message, fallbackPriority)
                    )
                }
                null
            }
        }
    }

    private fun validateEnabled(config: HaMqttConfig): MqttActionResult? {
        if (!config.enabled) return MqttActionResult.fail("mqtt_disabled", "MQTT publishing is disabled")
        config.validateEndpoints()?.let {
            return MqttActionResult.fail("mqtt_endpoint_invalid", it, MqttFailureKind.PROTOCOL)
        }
        return null
    }

    private fun runtimeConfig(capture: Boolean = false): HaMqttConfig {
        val live = configProvider()
        if (capture && live.enabled && sessionConnection == null) beginSession(live)
        val frozen = sessionConnection ?: live
        //Connection and destination fields remain frozen for the owned session;
        //enablement/category switches remain live for the existing worker.
        return frozen.copy(
            enabled = live.enabled,
            discoveryEnabled = frozen.discoveryEnabled,
            enabledCategories = live.enabledCategories.toSet()
        )
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

    private fun priorityFor(message: HaMqttMessage, fallbackPriority: Int): Int {
        return when (targetType(message)) {
            "discovery" -> DISCOVERY_PRIORITY
            "status" -> STATUS_PRIORITY
            "state" -> STATE_PRIORITY
            else -> fallbackPriority
        }
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
        const val DISCOVERY_PRIORITY = 10
        const val STATUS_PRIORITY = 20
        const val STATE_PRIORITY = 50
        const val PUBLISH_LIMIT = 100
    }
}
