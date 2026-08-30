package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import com.bydcollector.collector.ha.HaEndpointProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttPublishCoordinatorTest {
    @Test
    fun stoppedStatusReadDoesNotFreezeDraftAndOwnedSessionFreezesDestinationsUntilStop() {
        var live = config(enabled = false).copy(host = "before.local")
        val client = FakeMqttClient()
        val coordinator = coordinator(client = client, configProvider = { live })
        assertEquals(null, coordinator.retryDelayMs())
        live = live.copy(enabled = true, host = "start.local", topicPrefix = "start", discoveryPrefix = "ha-start")
        assertTrue(coordinator.startLiveExport().ok)
        assertEquals("start.local", client.connectConfigs.last().host)
        live = live.copy(host = "later.local", topicPrefix = "later", discoveryPrefix = "ha-later")
        client.published.clear()
        assertTrue(coordinator.queueFullResyncAndFlush().ok)
        assertTrue(client.published.all { it.topic.startsWith("start/") })
        assertTrue(coordinator.disconnectOffline().ok)
        assertTrue(coordinator.startLiveExport().ok)
        assertEquals("later.local", client.connectConfigs.last().host)
    }

    @Test
    fun offlineDisconnectClosesWorkingFacadeWithoutReconnecting() {
        val client = FakeMqttClient(connectResult = MqttActionResult.fail("mqtt_error", "broker down"))
        val coordinator = coordinator(client = client)

        val result = coordinator.disconnectOffline()

        assertTrue(result.ok)
        assertEquals(0, client.connectCount)
        assertEquals(1, client.disconnectCount)
        assertEquals(listOf<HaMqttMessage?>(null), client.disconnectMessages)
    }

    @Test
    fun transportConnectFailureFallsBackToAlternativeAndKeepsActualRoute() {
        val client = FakeMqttClient(
            connectResults = mutableListOf(
                MqttActionResult.fail("mqtt_error", "primary down", MqttFailureKind.TRANSPORT),
                MqttActionResult.ok()
            )
        )
        val outbox = FakeOutboxStore().also {
            it.upsertPending(message("bydcollector/state/battery", "payload"), "state", 50)
        }
        val coordinator = coordinator(
            client = client,
            outbox = outbox,
            config = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)
        )

        val result = coordinator.flushPending(force = true)

        assertTrue(result.ok)
        assertEquals(listOf("mqtt.local", "mqtt-alt.local"), client.connectConfigs.map { it.host })
        assertEquals(HaEndpointProfile.ALTERNATIVE, coordinator.activeRoute)
        assertEquals(emptyList<PendingRow>(), outbox.pendingRows())
    }

    @Test
    fun transportPublishFailureRetriesSameRowOnOtherEndpoint() {
        val client = FakeMqttClient(
            connectResults = mutableListOf(MqttActionResult.ok(), MqttActionResult.ok()),
            publishResults = mutableListOf(
                MqttActionResult.fail("mqtt_error", "primary publish down", MqttFailureKind.TRANSPORT),
                MqttActionResult.ok()
            )
        )
        val outbox = FakeOutboxStore().also {
            it.upsertPending(message("bydcollector/state/battery", "payload"), "state", 50)
        }
        val coordinator = coordinator(
            client = client,
            outbox = outbox,
            config = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)
        )

        val result = coordinator.flushPending(force = true)

        assertTrue(result.ok)
        assertEquals(listOf("mqtt.local", "mqtt-alt.local"), client.connectConfigs.map { it.host })
        assertEquals(2, client.published.size)
        assertEquals(HaEndpointProfile.ALTERNATIVE, coordinator.activeRoute)
        assertEquals(emptyList<PendingRow>(), outbox.pendingRows())
    }

    @Test
    fun authenticationFailureDoesNotTryAlternativeEndpoint() {
        val client = FakeMqttClient(
            connectResults = mutableListOf(
                MqttActionResult.fail("mqtt_auth", "not authorized", MqttFailureKind.AUTHENTICATION)
            )
        )
        val outbox = FakeOutboxStore().also {
            it.upsertPending(message("bydcollector/state/battery", "payload"), "state", 50)
        }
        val coordinator = coordinator(
            client = client,
            outbox = outbox,
            config = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)
        )

        val result = coordinator.flushPending(force = true)

        assertFalse(result.ok)
        assertEquals(1, client.connectCount)
        assertEquals(listOf("mqtt.local"), client.connectConfigs.map { it.host })
        assertEquals(null, coordinator.activeRoute)
    }

    @Test
    fun connectedAlternativeIsStickyUntilFacadeReportsDisconnect() {
        val client = FakeMqttClient(
            connectResults = mutableListOf(
                MqttActionResult.fail("mqtt_error", "primary down", MqttFailureKind.TRANSPORT),
                MqttActionResult.ok(),
                MqttActionResult.ok()
            )
        )
        val outbox = FakeOutboxStore().also {
            it.upsertPending(message("bydcollector/state/battery", "first"), "state", 50)
        }
        val coordinator = coordinator(
            client = client,
            outbox = outbox,
            config = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)
        )

        assertTrue(coordinator.flushPending(force = true).ok)
        assertEquals(2, client.connectCount)

        outbox.upsertPending(message("bydcollector/state/battery", "second"), "state", 50)
        assertTrue(coordinator.flushPending(force = true).ok)
        assertEquals(2, client.connectCount)

        client.connectedState = false
        outbox.upsertPending(message("bydcollector/state/battery", "third"), "state", 50)
        assertTrue(coordinator.flushPending(force = true).ok)
        assertEquals(listOf("mqtt.local", "mqtt-alt.local", "mqtt.local"), client.connectConfigs.map { it.host })
    }

    @Test
    fun secondTransportPublishFailureClosesRouteWithoutTryingPrimaryAgain() {
        val client = FakeMqttClient(
            connectResults = mutableListOf(MqttActionResult.ok(), MqttActionResult.ok()),
            publishResults = mutableListOf(
                MqttActionResult.fail("mqtt_error", "primary publish down", MqttFailureKind.TRANSPORT),
                MqttActionResult.fail("mqtt_error", "alternative publish down", MqttFailureKind.TRANSPORT)
            )
        )
        val outbox = FakeOutboxStore().also {
            it.upsertPending(message("bydcollector/state/battery", "payload"), "state", 50)
        }
        val coordinator = coordinator(
            client = client,
            outbox = outbox,
            config = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)
        )

        val result = coordinator.flushPending(force = true)

        assertFalse(result.ok)
        assertEquals(listOf("mqtt.local", "mqtt-alt.local"), client.connectConfigs.map { it.host })
        assertEquals(2, client.published.size)
        assertEquals(null, coordinator.activeRoute)
        assertEquals(2, client.disconnectCount)
        assertEquals(1, outbox.pendingRows().size)
    }

    @Test
    fun testConnectionOnlyDoesNotPublishDiscoveryOrState() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        val existingRetry = MqttRetryState(
            failureCount = 3,
            nextAttemptAt = "2026-06-14T12:05:00+03:00",
            lastFailureAt = "2026-06-14T12:00:00+03:00",
            lastSuccessAt = null,
            lastError = "runtime broker error"
        )
        val retry = FakeRetryStateStore(existingRetry)
        val coordinator = coordinator(
            client = client,
            outbox = outbox,
            retry = retry,
            config = config(enabled = false)
        )

        val result = coordinator.testConnectionOnly()

        assertTrue(result.ok)
        assertEquals(1, client.connectCount)
        assertEquals(emptyList(), client.published)
        assertEquals(emptyList(), outbox.pendingRows())
        assertEquals(existingRetry, retry.state)
        assertEquals(emptyList(), retry.failures)
        assertEquals(emptyList(), retry.successes)
    }

    @Test
    fun testConnectionOverrideUsesOnlySelectedAlternativeAndLeavesRuntimeRouteUntouched() {
        val client = FakeMqttClient()
        val outbox = FakeOutboxStore()
        val coordinator = coordinator(
            client = client,
            outbox = outbox,
            config = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)
        )

        val selected = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)
            .forProfile(HaEndpointProfile.ALTERNATIVE)
        val result = coordinator.testConnectionOnly(selected)

        assertTrue(result.ok)
        assertEquals(listOf("mqtt-alt.local"), client.connectConfigs.map { it.host })
        assertEquals(null, coordinator.activeRoute)
        assertEquals(emptyList<PendingRow>(), outbox.pendingRows())
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
    fun locationOptInPublishesCoordinates() {
        val provider = MutableNormalizedProvider(
            listOf(storedState("location_latitude", "location", valueNumber = 50.0))
        )
        val result = HaMqttMessageFactory(
            normalizedProvider = provider,
            configProvider = { config(enabledCategories = setOf("location")) },
            clock = FakeClock()
        ).fullResyncMessages() as MqttMessageBuildResult.Success

        assertEquals(setOf("location"), provider.requestedCategories.single())
        assertTrue(result.messages.any { it.topic == "bydcollector/state/location" })
    }

    @Test
    fun locationCategoryOffSuppressesCoordinates() {
        val provider = MutableNormalizedProvider(
            listOf(storedState("location_latitude", "location", valueNumber = 50.0))
        )
        val result = HaMqttMessageFactory(
            normalizedProvider = provider,
            configProvider = { config(enabledCategories = emptySet()) },
            clock = FakeClock()
        ).fullResyncMessages() as MqttMessageBuildResult.Success

        assertEquals(emptySet(), provider.requestedCategories.single())
        assertFalse(result.messages.any { it.topic == "bydcollector/state/location" })
    }

    @Test
    fun failedStartRetainsCompleteWorkAndReportsPersistedRetryDelay() {
        val client = FakeMqttClient(connectResult = MqttActionResult.fail("mqtt_error", "broker down"))
        val outbox = FakeOutboxStore()
        val coordinator = coordinator(client = client, outbox = outbox)

        val result = coordinator.startLiveExport()

        assertFalse(result.ok)
        assertTrue(outbox.pendingRows().any { it.targetType == "discovery" })
        assertTrue(outbox.pendingRows().any { it.targetType == "state" })
        assertEquals(30_000L, coordinator.retryDelayMs())
    }

    @Test
    fun manualStartAfterFailurePublishesOneFullSnapshot() {
        val client = FakeMqttClient()
        val retry = FakeRetryStateStore(
            MqttRetryState(1, "2026-06-14T12:05:00+03:00", null, null, "broker down")
        )
        val coordinator = coordinator(client = client, retry = retry)

        val result = coordinator.startLiveExport()

        assertTrue(result.ok)
        assertEquals(1, client.published.count { it.topic == "bydcollector/state/battery" })
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
                storedState("charging_state", "battery", valueNumber = 2.0)
            )
        )
        val coordinator = coordinator(client = client, provider = provider)

        val result = coordinator.queueFullResyncAndFlush(force = true)

        assertTrue(result.ok)
        val batteryPayload = client.published.single { it.topic == "bydcollector/state/battery" }.payload
        assertTrue(batteryPayload.contains("soc"))
        assertFalse(batteryPayload.contains("charging_state"))
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
        assertTrue(outbox.pendingRows().any {
            it.message.topic == "homeassistant/binary_sensor/byd_sealion_07/bodywork_sunroof_windoblind_position/config" &&
                it.message.payload.isEmpty()
        })
        assertTrue(outbox.pendingRows().any {
            it.message.topic == "homeassistant/sensor/byd_sealion_07/bodywork_sunroof_windoblind_position/config"
        })
        val tombstonePriority = outbox.pendingRows().single {
            it.message.topic == "homeassistant/binary_sensor/byd_sealion_07/bodywork_sunroof_windoblind_position/config"
        }.priority
        val sensorPriority = outbox.pendingRows().single {
            it.message.topic == "homeassistant/sensor/byd_sealion_07/bodywork_sunroof_windoblind_position/config"
        }.priority
        assertTrue(tombstonePriority < sensorPriority)
    }

    @Test
    fun sunroofTombstonePublishesBeforeReplacementSensorConfig() {
        val client = FakeMqttClient()
        val coordinator = coordinator(client = client)

        assertTrue(coordinator.queueDiscoveryAndFlush(force = true).ok)

        val tombstoneTopic =
            "homeassistant/binary_sensor/byd_sealion_07/bodywork_sunroof_windoblind_position/config"
        val sensorTopic =
            "homeassistant/sensor/byd_sealion_07/bodywork_sunroof_windoblind_position/config"
        val tombstoneIndex = client.published.indexOfFirst { it.topic == tombstoneTopic }
        val sensorIndex = client.published.indexOfFirst { it.topic == sensorTopic }
        assertTrue(tombstoneIndex >= 0)
        assertTrue(sensorIndex >= 0)
        assertTrue(tombstoneIndex < sensorIndex)
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
        config: HaMqttConfig = config(),
        configProvider: () -> HaMqttConfig = { config }
    ): MqttPublishCoordinator {
        return MqttPublishCoordinator(
            client = client,
            outbox = outbox,
            retryStateStore = retry,
            messageFactory = HaMqttMessageFactory(
                normalizedProvider = provider,
                configProvider = configProvider,
                clock = FakeClock()
            ),
            configProvider = configProvider,
            retryPolicy = MqttRetryPolicy(),
            clock = FakeClock()
        )
    }

    private class FakeMqttClient(
        private val connectResult: MqttActionResult = MqttActionResult.ok(),
        private val connectResults: MutableList<MqttActionResult> = mutableListOf(),
        private val publishResults: MutableList<MqttActionResult> = mutableListOf()
    ) : MqttClientFacade {
        val published = mutableListOf<HaMqttMessage>()
        val willMessages = mutableListOf<HaMqttMessage?>()
        val disconnectMessages = mutableListOf<HaMqttMessage?>()
        val connectConfigs = mutableListOf<HaMqttConfig>()
        var connectedState = false
        var connectCount = 0
        var disconnectCount = 0

        override val isConnected: Boolean
            get() = connectedState

        override fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult {
            return connect(config, willMessage, MqttConnectionPurpose.RUNTIME)
        }

        override fun connect(
            config: HaMqttConfig,
            willMessage: HaMqttMessage?,
            purpose: MqttConnectionPurpose
        ): MqttActionResult {
            connectCount += 1
            connectConfigs += config
            willMessages += willMessage
            return (if (connectResults.isEmpty()) connectResult else connectResults.removeAt(0)).also {
                connectedState = it.ok
            }
        }

        override fun publish(message: HaMqttMessage): MqttActionResult {
            published += message
            return if (publishResults.isEmpty()) MqttActionResult.ok() else publishResults.removeAt(0)
        }

        override fun disconnect(gracefulMessage: HaMqttMessage?): MqttActionResult {
            disconnectCount += 1
            disconnectMessages += gracefulMessage
            gracefulMessage?.let { published += it }
            connectedState = false
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
        enabledCategories: Set<String> = HaMqttConfig.DEFAULT_CATEGORIES,
        alternativeHost: String? = null,
        alternativePort: Int? = null
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
            enabledCategories = enabledCategories,
            alternativeHost = alternativeHost,
            alternativePort = alternativePort
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
