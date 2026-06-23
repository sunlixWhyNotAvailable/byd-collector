package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
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
    fun testConnectionAndMaybeFlush(): MqttActionResult {
        return startLiveExport()
    }

    fun testConnectionOnly(): MqttActionResult {
        val config = configProvider()

        val connect = client.connect(config, messageFactory.offlineMessage())
        if (!connect.ok) {
            recordRetryFailure(connect.message, retryStateStore.retryState())
            return connect
        }

        retryStateStore.recordRetrySuccess(clock.nowIso())
        return MqttActionResult.ok("connected")
    }

    fun startLiveExport(): MqttActionResult {
        val config = configProvider()
        validateEnabled(config)?.let { return it }

        val connect = client.connect(config, messageFactory.offlineMessage())
        if (!connect.ok) {
            recordRetryFailure(connect.message, retryStateStore.retryState())
            return connect
        }

        retryStateStore.recordRetrySuccess(clock.nowIso())
        //publishes discovery before state so ha can attach incoming retained values to known entities
        enqueueBuildResult(messageFactory.discoveryMessages(), DISCOVERY_PRIORITY)?.let { return it }
        enqueueBuildResult(messageFactory.fullResyncMessages(), STATE_PRIORITY)?.let { return it }
        return flushPending(force = true)
    }

    fun queueDiscoveryAndFlush(force: Boolean = true): MqttActionResult {
        val config = configProvider()
        validateEnabled(config)?.let { return it }
        if (!config.discoveryEnabled) {
            return MqttActionResult.fail("ha_discovery_disabled", "Home Assistant MQTT discovery is disabled")
        }
        return enqueueBuildResult(messageFactory.discoveryMessages(), DISCOVERY_PRIORITY)
            ?: flushPending(force)
    }

    fun queueFullResyncAndFlush(force: Boolean = true): MqttActionResult {
        val config = configProvider()
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.fullResyncMessages(), STATE_PRIORITY)
            ?: flushPending(force)
    }

    fun queueStatusAndFlush(status: HaMqttStatus, force: Boolean = false): MqttActionResult {
        val config = configProvider()
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.statusMessage(status), STATUS_PRIORITY)
            ?: flushPending(force)
    }

    fun queueChangedCategoriesAndFlush(categories: Set<String>, force: Boolean = false): MqttActionResult {
        val config = configProvider()
        validateEnabled(config)?.let { return it }
        return enqueueBuildResult(messageFactory.changedCategoryMessages(categories), STATE_PRIORITY)
            ?: flushPending(force)
    }

    fun flushPending(force: Boolean = false): MqttActionResult {
        return flushPendingInternal(force = force, allowFullResyncAfterSuccess = true)
    }

    fun disconnectOffline(): MqttActionResult {
        val config = configProvider()
        val connect = client.connect(config, messageFactory.offlineMessage())
        if (!connect.ok) {
            recordRetryFailure(connect.message, retryStateStore.retryState())
            return connect
        }
        val result = client.disconnect(messageFactory.offlineMessage())
        if (!result.ok) recordRetryFailure(result.message, retryStateStore.retryState())
        return result
    }

    private fun flushPendingInternal(
        force: Boolean,
        allowFullResyncAfterSuccess: Boolean
    ): MqttActionResult {
        val config = configProvider()
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

        val connect = client.connect(config, messageFactory.offlineMessage())
        if (!connect.ok) {
            val nextAttemptAt = recordRetryFailure(connect.message, startingRetryState)
            pending.forEach {
                outbox.markFailed(it.targetKey, it.payloadHash, connect.message, clock.nowIso(), nextAttemptAt)
            }
            return connect
        }

        pending.forEach { pendingMessage ->
            outbox.markAttempt(pendingMessage.targetKey, pendingMessage.payloadHash, clock.nowIso())
            val publish = client.publish(pendingMessage.message)
            if (!publish.ok) {
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
            return enqueueBuildResult(messageFactory.fullResyncMessages(), STATE_PRIORITY)
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
        return if (config.enabled) null else MqttActionResult.fail("mqtt_disabled", "MQTT publishing is disabled")
    }

    private fun recordRetryFailure(error: String, stateBeforeFailure: MqttRetryState): String {
        val nextFailureCount = stateBeforeFailure.failureCount + 1
        val baseDelayMs = retryPolicy.delayForFailure(nextFailureCount)
        val delayMs = retryPolicy.withJitter(baseDelayMs, error)
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
