package com.bydcollector.collector.mqtt

import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.ha.HaEndpointProfile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MqttPublishCoordinatorTest {
    @Test
    fun acknowledgedRowsStayDeletedAndUnacknowledgedPayloadSurvivesFailoverAndDatabaseReopen() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "mqtt-outbox-recovery.db"
        var config = HaMqttConfig(
            enabled = true,
            discoveryEnabled = true,
            host = "primary-broker",
            port = 1883,
            username = null,
            password = null,
            clientId = "collector-test",
            topicPrefix = "bydcollector",
            discoveryPrefix = "homeassistant",
            deviceId = "test-car",
            enabledCategories = emptySet(),
            alternativeHost = "backup-broker",
            alternativePort = 1884
        )
        val destination = MqttDestinationIdentity.destinationKey(config, NoopMqttPublicationJournalStore)
        val expected = listOf(
            HaMqttMessage("bydcollector/state/first", "payload-one", retained = true),
            HaMqttMessage("bydcollector/state/second", "payload-two", retained = true),
            HaMqttMessage("bydcollector/state/third", "payload-three", retained = true)
        )
        val client = ScriptedMqttClient(mutableListOf(
            MqttActionResult.ok(),
            MqttActionResult.fail("mqtt_transport", "primary publish disconnected", MqttFailureKind.TRANSPORT),
            MqttActionResult.fail("mqtt_transport", "alternative publish disconnected", MqttFailureKind.TRANSPORT)
        ))
        var store = telemetryStore(context, databaseName)
        var coordinator = coordinator(store, client) { config }
        try {
            expected.forEachIndexed { index, message ->
                store.upsertPending(message, "state", index + 1, destination)
            }

            val firstPass = coordinator.flushPending(force = true)

            assertFalse(firstPass.ok)
            assertEquals(2L, store.pendingCount())
            val afterFirstPass = store.dueMessages("9999-12-31T23:59:59Z", 10)
            assertEquals(expected.drop(1).map { it.topic }, afterFirstPass.map { it.message.topic })
            assertEquals(1, afterFirstPass.first().attemptCount)
            assertTrue(client.published.none { it.second.topic == expected[2].topic }, "third row waits after the second row's failed failover")
            assertEquals(
                listOf(
                    HaEndpointProfile.PRIMARY to expected[0],
                    HaEndpointProfile.PRIMARY to expected[1],
                    HaEndpointProfile.ALTERNATIVE to expected[1]
                ),
                client.published.map { it.first to it.second }
            )

            coordinator.disconnectForMaintenance()
            store.close()
            store = telemetryStore(context, databaseName)
            coordinator = coordinator(store, client) { config }
            assertEquals(2L, store.pendingCount(), "unacknowledged rows must remain in SQLite after reopen")
            assertEquals(expected.drop(1), store.dueMessages("9999-12-31T23:59:59Z", 10).map { it.message })

            assertTrue(coordinator.flushPending(force = true).ok)

            assertEquals(0L, store.pendingCount())
            assertTrue(client.published.any { it.first == HaEndpointProfile.PRIMARY && it.second == expected[1] })
            assertTrue(client.published.any { it.first == HaEndpointProfile.ALTERNATIVE && it.second == expected[1] })
            assertTrue(client.published.any { it.second == expected[2] })
            assertTrue(client.published.filter { it.second.topic == expected[1].topic }.all { it.second.payload == expected[1].payload })
            assertNotNull(coordinator.activeRoute)

            // Model the UI's disabled setting before its Stop disconnect, then deliver stale work.
            config = config.copy(enabled = false)
            val stop = coordinator.disconnectOffline()
            assertTrue(stop.ok)
            assertFalse(client.isConnected)
            assertEquals(null, coordinator.activeRoute)
            val lateMessage = HaMqttMessage("bydcollector/state/late", "payload-late", retained = true)
            store.upsertPending(lateMessage, "state", 4, destination)
            val connectCountAfterStop = client.connectedRoutes.size
            val publishedCountAfterStop = client.published.size
            val lateFlush = coordinator.flushPending(force = true)
            assertTrue(lateFlush.ok)
            assertEquals("mqtt disabled", lateFlush.message)
            assertEquals(connectCountAfterStop, client.connectedRoutes.size, "late work must not reconnect after Stop")
            assertEquals(publishedCountAfterStop, client.published.size, "late work must not publish after Stop")
            assertFalse(client.isConnected)
            assertEquals(1L, store.pendingCount())
            assertEquals(lateMessage, store.dueMessages("9999-12-31T23:59:59Z", 10).single().message)
        } finally {
            runCatching { coordinator.disconnectForMaintenance() }
            store.close()
        }
    }

    private fun telemetryStore(context: android.content.Context, databaseName: String) = TelemetryStore(
        context,
        TelemetryDatabaseHelper(context, databaseName),
        operationalEventJournal = OperationalEventJournal(context)
    )

    private fun coordinator(
        store: TelemetryStore,
        client: ScriptedMqttClient,
        configProvider: () -> HaMqttConfig
    ) =
        MqttPublishCoordinator(
            client = client,
            outbox = store,
            retryStateStore = store,
            messageFactory = HaMqttMessageFactory(store, configProvider),
            configProvider = configProvider
        )

    private class ScriptedMqttClient(private val script: MutableList<MqttActionResult>) : MqttClientFacade {
        private var route: HaEndpointProfile? = null
        override var isConnected: Boolean = false
            private set
        val published = mutableListOf<Pair<HaEndpointProfile, HaMqttMessage>>()
        val connectedRoutes = mutableListOf<HaEndpointProfile>()

        override fun connect(config: HaMqttConfig, willMessage: HaMqttMessage?): MqttActionResult {
            val candidate = if (config.host == "backup-broker") HaEndpointProfile.ALTERNATIVE else HaEndpointProfile.PRIMARY
            route = candidate
            connectedRoutes += candidate
            isConnected = true
            return MqttActionResult.ok()
        }

        override fun publish(message: HaMqttMessage): MqttActionResult {
            check(isConnected) { "publish requires an established fake transport" }
            published += (route ?: error("connected route missing")) to message
            return if (script.isEmpty()) MqttActionResult.ok() else script.removeAt(0).also {
                if (!it.ok) isConnected = false
            }
        }

        override fun disconnect(gracefulMessage: HaMqttMessage?): MqttActionResult {
            isConnected = false
            return MqttActionResult.ok()
        }
    }
}
