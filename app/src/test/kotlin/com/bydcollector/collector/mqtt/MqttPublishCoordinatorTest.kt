package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttPublishCoordinatorTest {
    @Test
    fun testConnectionOnlyDoesNotPublishDiscoveryOrState() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        val coordinator = coordinator(client = client, outbox = outbox, config = config(enabled = false))

        val result = coordinator.testConnectionOnly()

        assertTrue(result.ok)
        assertEquals(1, client.connectCount)
        assertEquals(emptyList(), client.published)
        assertEquals(emptyList(), outbox.pendingRows())
    }

    @Test
    fun startLiveExportQueuesDiscoveryAndFullSnapshotBeforePublishing() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        val coordinator = coordinator(client = client, outbox = outbox)

        val result = coordinator.startLiveExport()

        assertTrue(result.ok)
        assertTrue(client.published.first().topic.endsWith("/config"))
        assertTrue(client.published.any { it.topic == "bydcollector/state/battery" })
        assertEquals(emptyList(), outbox.pendingRows())
    }

    @Test
    fun failedChangedCategoryPublishStoresPendingRowForCategoryTopic() {
        val client = FakeMqttClient(
            publishResults = mutableListOf(MqttActionResult.fail("mqtt_error", "publish failed"))
        )
        val outbox = FakeOutboxStore()
        val coordinator = coordinator(client = client, outbox = outbox)

        val result = coordinator.queueChangedCategoriesAndFlush(setOf("battery"))

        assertFalse(result.ok)
        assertEquals(listOf("bydcollector/state/battery"), client.published.map { it.topic })
        val pending = outbox.pendingRows()
        assertEquals(listOf("bydcollector/state/battery"), pending.map { it.targetKey })
        assertEquals("state", pending.single().targetType)
        assertEquals("publish failed", pending.single().lastError)
    }

    @Test
    fun repeatedSameTopicChangesReplacePayloadInsteadOfAddingRows() {
        val client = FakeMqttClient(connectResult = MqttActionResult.fail("mqtt_error", "broker down"))
        val outbox = FakeOutboxStore()
        val provider = MutableNormalizedProvider(listOf(storedState("soc", "battery", valueNumber = 73.0)))
        val coordinator = coordinator(client = client, outbox = outbox, provider = provider)

        coordinator.queueChangedCategoriesAndFlush(setOf("battery"))
        val firstPayload = outbox.pendingRows().single().message.payload

        provider.rows = listOf(storedState("soc", "battery", valueNumber = 74.0))
        coordinator.queueChangedCategoriesAndFlush(setOf("battery"))

        val pending = outbox.pendingRows()
        assertEquals(1, pending.size)
        assertEquals("bydcollector/state/battery", pending.single().targetKey)
        assertTrue(firstPayload.contains("73"))
        assertTrue(pending.single().message.payload.contains("74"))
    }

    @Test
    fun backoffNotDuePreventsClientCalls() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        outbox.upsertPending(message("bydcollector/state/battery", "payload"), targetType = "state", priority = 50)
        val retry = FakeRetryStateStore(
            state = MqttRetryState(
                failureCount = 1,
                nextAttemptAt = "2026-06-14T12:05:00+03:00",
                lastFailureAt = "2026-06-14T12:00:00+03:00",
                lastSuccessAt = null,
                lastError = "broker down"
            )
        )
        val coordinator = coordinator(client = client, outbox = outbox, retry = retry)

        val result = coordinator.flushPending(force = false)

        assertTrue(result.ok)
        assertEquals("mqtt backoff active", result.message)
        assertEquals(0, client.connectCount)
        assertEquals(emptyList(), client.published)
        assertEquals(1, outbox.pendingRows().size)
    }

    @Test
    fun dueBackoffConnectSuccessPublishesAndRemovesPending() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        outbox.upsertPending(message("bydcollector/state/battery", "payload"), targetType = "state", priority = 50)
        val retry = FakeRetryStateStore(
            state = MqttRetryState(
                failureCount = 0,
                nextAttemptAt = "2026-06-14T12:00:00+03:00",
                lastFailureAt = "2026-06-14T11:59:00+03:00",
                lastSuccessAt = null,
                lastError = "broker down"
            )
        )
        val coordinator = coordinator(client = client, outbox = outbox, retry = retry)

        val result = coordinator.flushPending(force = false)

        assertTrue(result.ok)
        assertTrue(client.connectCount >= 1)
        assertEquals("bydcollector/state/battery", client.published.first().topic)
        assertEquals(emptyList<PendingRow>(), outbox.pendingRows())
        assertTrue(retry.successes.contains("2026-06-14T12:00:10+03:00"))
    }

    @Test
    fun reconnectAfterPriorFailureTriggersFullResyncMessages() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        outbox.upsertPending(message("bydcollector/status", "queued-status"), targetType = "status", priority = 20)
        val retry = FakeRetryStateStore(
            state = MqttRetryState(
                failureCount = 2,
                nextAttemptAt = "2026-06-14T12:00:00+03:00",
                lastFailureAt = "2026-06-14T11:59:00+03:00",
                lastSuccessAt = null,
                lastError = "broker down"
            )
        )
        val provider = MutableNormalizedProvider(listOf(storedState("soc", "battery", valueNumber = 73.0)))
        val coordinator = coordinator(client = client, outbox = outbox, retry = retry, provider = provider)

        val result = coordinator.flushPending(force = false)

        assertTrue(result.ok)
        assertEquals(
            listOf("bydcollector/status", "bydcollector/status", "bydcollector/state/battery"),
            client.published.map { it.topic }
        )
        assertEquals(emptyList<PendingRow>(), outbox.pendingRows())
        assertEquals(listOf<Set<String>?>(HaMqttConfig.DEFAULT_CATEGORIES), provider.requestedCategories)
    }

    @Test
    fun forceFlushBypassesBackoff() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        outbox.upsertPending(message("bydcollector/state/battery", "payload"), targetType = "state", priority = 50)
        val retry = FakeRetryStateStore(
            state = MqttRetryState(
                failureCount = 3,
                nextAttemptAt = "2026-06-14T12:05:00+03:00",
                lastFailureAt = "2026-06-14T12:00:00+03:00",
                lastSuccessAt = null,
                lastError = "broker down"
            )
        )
        val coordinator = coordinator(client = client, outbox = outbox, retry = retry)

        val result = coordinator.flushPending(force = true)

        assertTrue(result.ok)
        assertTrue(client.connectCount >= 1)
        assertEquals("bydcollector/state/battery", client.published.first().topic)
        assertEquals(emptyList<PendingRow>(), outbox.pendingRows())
    }

    @Test
    fun forceFlushIncludesRowsWithFuturePerTopicBackoff() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        outbox.upsertPending(message("bydcollector/state/battery", "payload"), targetType = "state", priority = 50)
        outbox.markFailed(
            targetKey = "bydcollector/state/battery",
            payloadHash = "ignored",
            error = "broker down",
            failedAt = "2026-06-14T12:00:00+03:00",
            nextAttemptAt = "2026-06-14T12:05:00+03:00"
        )
        val coordinator = coordinator(client = client, outbox = outbox)

        val normalFlush = coordinator.flushPending(force = false)

        assertTrue(normalFlush.ok)
        assertEquals(emptyList(), client.published)

        val forceFlush = coordinator.flushPending(force = true)

        assertTrue(forceFlush.ok)
        assertEquals(listOf("bydcollector/state/battery"), client.published.map { it.topic })
        assertEquals(emptyList<PendingRow>(), outbox.pendingRows())
    }

    @Test
    fun fullResyncSkipsNonDefaultRawRowsInEnabledDefaultCategories() {
        val client = FakeMqttClient()
        val provider = MutableNormalizedProvider(
            listOf(
                storedState("soc", "battery", valueNumber = 73.0),
                storedState("low_voltage_warning_raw", "battery", valueNumber = 2.0)
            )
        )
        val coordinator = coordinator(client = client, provider = provider)

        val result = coordinator.queueFullResyncAndFlush(force = true)

        assertTrue(result.ok)
        val batteryPayload = client.published.single { it.topic == "bydcollector/state/battery" }.payload
        assertTrue(batteryPayload.contains("soc"))
        assertFalse(batteryPayload.contains("low_voltage_warning_raw"))
    }

    @Test
    fun nonDefaultRadarRowsAreSkippedEvenWhenSafetyCategoryIsEnabled() {
        val client = FakeMqttClient()
        val provider = MutableNormalizedProvider(
            listOf(
                storedState("tire_pressure_lf_raw", "safety", valueNumber = 260.0),
                storedState("radar_1025_neg_1728053151_5", "safety", valueNumber = 55.0)
            )
        )
        val coordinator = coordinator(
            client = client,
            provider = provider,
            config = config(enabledCategories = setOf("safety"))
        )

        val result = coordinator.queueFullResyncAndFlush(force = true)

        assertTrue(result.ok)
        val safetyPayload = client.published.single { it.topic == "bydcollector/state/safety" }.payload
        assertTrue(safetyPayload.contains("tire_pressure_lf_raw"))
        assertFalse(safetyPayload.contains("radar_1025_neg_1728053151_5"))
    }

    @Test
    fun discoveryMessagesAreQueuedBeforeConnectAndRetained() {
        val client = FakeMqttClient(connectResult = MqttActionResult.fail("mqtt_error", "auth failed"))
        val outbox = FakeOutboxStore()
        val coordinator = coordinator(client = client, outbox = outbox)

        val result = coordinator.queueDiscoveryAndFlush(force = true)

        assertFalse(result.ok)
        assertEquals(1, client.connectCount)
        assertTrue(outbox.pendingRows().isNotEmpty())
        assertTrue(outbox.pendingRows().all { it.targetType == "discovery" })
        assertTrue(outbox.pendingRows().all { it.message.retained })
        assertTrue(outbox.pendingRows().all { it.message.topic.endsWith("/config") })
    }

    @Test
    fun partialPublishFailureRemovesPublishedAndKeepsFailedAndUnpublishedPending() {
        val client = FakeMqttClient(
            publishResults = mutableListOf(
                MqttActionResult.ok(),
                MqttActionResult.fail("mqtt_error", "second failed")
            )
        )
        val outbox = FakeOutboxStore()
        outbox.upsertPending(message("bydcollector/state/battery", "battery"), targetType = "state", priority = 50)
        outbox.upsertPending(message("bydcollector/state/body", "body"), targetType = "state", priority = 50)
        outbox.upsertPending(message("bydcollector/state/climate", "climate"), targetType = "state", priority = 50)
        val coordinator = coordinator(client = client, outbox = outbox)

        val result = coordinator.flushPending(force = true)

        assertFalse(result.ok)
        assertEquals(
            listOf("bydcollector/state/battery", "bydcollector/state/body"),
            client.published.map { it.topic }
        )
        val pending = outbox.pendingRows()
        assertEquals(listOf("bydcollector/state/body", "bydcollector/state/climate"), pending.map { it.targetKey })
        assertEquals("second failed", pending.first { it.targetKey == "bydcollector/state/body" }.lastError)
        assertEquals(null, pending.first { it.targetKey == "bydcollector/state/climate" }.lastError)
    }

    private fun coordinator(
        client: FakeMqttClient = FakeMqttClient(),
        outbox: FakeOutboxStore = FakeOutboxStore(),
        retry: FakeRetryStateStore = FakeRetryStateStore(),
        provider: MutableNormalizedProvider = MutableNormalizedProvider(
            listOf(storedState("soc", "battery", valueNumber = 73.0))
        ),
        config: HaMqttConfig = config()
    ): MqttPublishCoordinator {
        return MqttPublishCoordinator(
            client = client,
            outbox = outbox,
            retryStateStore = retry,
            messageFactory = HaMqttMessageFactory(
                normalizedProvider = provider,
                configProvider = { config },
                clock = FakeClock()
            ),
            configProvider = { config },
            retryPolicy = MqttRetryPolicy(),
            clock = FakeClock()
        )
    }

    private class FakeMqttClient(
        private val connectResult: MqttActionResult = MqttActionResult.ok(),
        private val publishResults: MutableList<MqttActionResult> = mutableListOf()
    ) : MqttClientFacade {
        val published = mutableListOf<HaMqttMessage>()
        val willMessages = mutableListOf<HaMqttMessage?>()
        var connectCount = 0

        override fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult {
            connectCount += 1
            willMessages += willMessage
            return connectResult
        }

        override fun publish(message: HaMqttMessage): MqttActionResult {
            published += message
            return if (publishResults.isEmpty()) MqttActionResult.ok() else publishResults.removeAt(0)
        }

        override fun disconnect(gracefulMessage: HaMqttMessage?): MqttActionResult {
            gracefulMessage?.let { published += it }
            return MqttActionResult.ok()
        }
    }

    private class FakeOutboxStore : MqttOutboxStore {
        private val rows = linkedMapOf<String, PendingRow>()

        override fun upsertPending(message: HaMqttMessage, targetType: String, priority: Int) {
            val existing = rows[message.topic]
            rows[message.topic] = PendingRow(
                targetKey = message.topic,
                targetType = targetType,
                message = message,
                priority = priority,
                attemptCount = existing?.attemptCount ?: 0,
                lastError = existing?.lastError
            )
        }

        override fun dueMessages(nowIso: String, limit: Int): List<PendingMqttMessage> {
            return rows.values
                .filter { it.nextAttemptAt == null || it.nextAttemptAt <= nowIso }
                .sortedWith(compareBy<PendingRow> { it.priority }.thenBy { it.targetKey })
                .take(limit)
                .map { it.toPending() }
        }

        override fun pendingMessages(limit: Int): List<PendingMqttMessage> {
            return rows.values
                .sortedWith(compareBy<PendingRow> { it.priority }.thenBy { it.targetKey })
                .take(limit)
                .map { it.toPending() }
        }

        override fun markAttempt(targetKey: String, payloadHash: String, attemptedAt: String) {
            rows[targetKey]?.let { rows[targetKey] = it.copy(attemptCount = it.attemptCount + 1) }
        }

        override fun markFailed(
            targetKey: String,
            payloadHash: String,
            error: String,
            failedAt: String,
            nextAttemptAt: String?
        ) {
            rows[targetKey]?.let { rows[targetKey] = it.copy(lastError = error, nextAttemptAt = nextAttemptAt) }
        }

        override fun markPublished(targetKey: String, payloadHash: String, publishedAt: String) {
            rows.remove(targetKey)
        }

        override fun pendingCount(): Long = rows.size.toLong()

        fun pendingRows(): List<PendingRow> = rows.values.toList()
    }

    private data class PendingRow(
        val targetKey: String,
        val targetType: String,
        val message: HaMqttMessage,
        val priority: Int,
        val attemptCount: Int,
        val lastError: String?,
        val nextAttemptAt: String? = null
    ) {
        fun toPending(): PendingMqttMessage {
            return PendingMqttMessage(
                targetKey = targetKey,
                targetType = targetType,
                payloadHash = message.payload.hashCode().toString(),
                message = message,
                priority = priority,
                attemptCount = attemptCount
            )
        }
    }

    private class FakeRetryStateStore(
        var state: MqttRetryState = MqttRetryState(
            failureCount = 0,
            nextAttemptAt = null,
            lastFailureAt = null,
            lastSuccessAt = null,
            lastError = null
        )
    ) : MqttRetryStateStore {
        val failures = mutableListOf<Pair<String, String>>()
        val successes = mutableListOf<String>()

        override fun retryState(): MqttRetryState = state

        override fun recordRetryFailure(error: String, failedAt: String, nextAttemptAt: String) {
            failures += error to nextAttemptAt
            state = MqttRetryState(
                failureCount = state.failureCount + 1,
                nextAttemptAt = nextAttemptAt,
                lastFailureAt = failedAt,
                lastSuccessAt = state.lastSuccessAt,
                lastError = error
            )
        }

        override fun recordRetrySuccess(successAt: String) {
            successes += successAt
            state = MqttRetryState(
                failureCount = 0,
                nextAttemptAt = null,
                lastFailureAt = state.lastFailureAt,
                lastSuccessAt = successAt,
                lastError = null
            )
        }
    }

    private class MutableNormalizedProvider(
        var rows: List<StoredNormalizedState>
    ) : NormalizedStateProvider {
        val requestedCategories = mutableListOf<Set<String>?>()

        override fun currentState(categories: Set<String>?): List<StoredNormalizedState> {
            requestedCategories += categories
            return categories?.let { allowed -> rows.filter { allowed.contains(it.category) } } ?: rows
        }
    }

    private class FakeClock : Clock {
        override fun nowIso(): String = "2026-06-14T12:00:10+03:00"
        override fun elapsedRealtimeMs(): Long = 1_000L
    }

    private fun message(topic: String, payload: String): HaMqttMessage {
        return HaMqttMessage(topic = topic, payload = payload, retained = true, qos = 1)
    }

    private fun config(
        enabled: Boolean = true,
        discoveryEnabled: Boolean = true,
        enabledCategories: Set<String> = HaMqttConfig.DEFAULT_CATEGORIES
    ): HaMqttConfig {
        return HaMqttConfig(
            enabled = enabled,
            discoveryEnabled = discoveryEnabled,
            host = "mqtt.local",
            port = 1883,
            username = null,
            password = null,
            clientId = HaMqttConfig.DEFAULT_CLIENT_ID,
            topicPrefix = HaMqttConfig.DEFAULT_TOPIC_PREFIX,
            discoveryPrefix = HaMqttConfig.DEFAULT_DISCOVERY_PREFIX,
            enabledCategories = enabledCategories
        )
    }

    private fun storedState(
        fieldKey: String,
        category: String,
        valueType: String = "NUMBER",
        valueNumber: Double? = null,
        valueBool: Boolean? = null
    ): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = fieldKey,
            category = category,
            valueType = valueType,
            valueText = null,
            valueNumber = valueNumber,
            valueBool = valueBool,
            quality = "OK",
            unit = null,
            sourcePollId = 42L,
            sourceKeys = fieldKey,
            observedAt = "2026-06-14T12:00:00+03:00",
            changedAt = "2026-06-14T12:00:05+03:00"
        )
    }
}
