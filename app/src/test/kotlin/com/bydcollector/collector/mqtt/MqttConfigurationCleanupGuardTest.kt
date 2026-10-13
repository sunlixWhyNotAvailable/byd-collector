package com.bydcollector.collector.mqtt

import android.app.Application
import android.content.Context
import com.bydcollector.collector.service.CollectorSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MqttConfigurationCleanupGuardTest {
    @Test fun pendingIdentityRejectsWholeDraftButAllowsDifferentIdOrBrokerScope() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(MqttPublicationJournal.DATABASE_NAME)
        val preferences = context.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = CollectorSettings(context)
        val initial = settings.mqttConfig(includeCredentials = false).copy(
            host = "mqtt-a.example",
            port = 1883,
            deviceId = "vehicle_a"
        )
        assertTrue(settings.saveMqttConfiguration(initial))

        val journal = MqttPublicationJournal(context)
        try {
            val scope = MqttDestinationIdentity.configuredScopes(initial).single().key
            journal.recordDiscoveryAttempt(
                scopeKey = scope,
                deviceId = initial.normalizedDeviceId(),
                topic = "homeassistant/sensor/vehicle_a/state/config",
                payloadHash = "actual-attempt-hash",
                deviceName = "Sea Lion 07",
                at = "2026-10-12T10:00:00Z"
            )
            journal.recordDiscoveryAttempt(
                scopeKey = scope,
                deviceId = "vehicle_previous",
                topic = "homeassistant/sensor/vehicle_previous/state/config",
                payloadHash = "prior-actual-attempt-hash",
                deviceName = "Sea Lion 07",
                at = "2026-10-12T10:00:30Z"
            )
            val currentGroup = journal.cleanupGroups(setOf(scope), initial.normalizedDeviceId())
                .single { it.deviceId == initial.normalizedDeviceId() }
            val operationId = journal.beginCleanup(currentGroup.selection, setOf(scope), "2026-10-12T10:01:00Z")

            val trappedGroup = journal.cleanupGroups(setOf(scope), initial.normalizedDeviceId())
                .single { it.deviceId == initial.normalizedDeviceId() }
            assertEquals("current", trappedGroup.state)
            assertTrue(trappedGroup.current)
            assertEquals("mqtt_cleanup_pending_identity", trappedGroup.error)

            val rejectedDraft = initial.copy(
                clientId = "different-client",
                deviceName = "Unsaved name",
                alternativeHost = "mqtt-b.example",
                alternativePort = 1884
            )
            val rejection = assertFailsWith<IllegalStateException> {
                settings.saveMqttConfiguration(rejectedDraft)
            }
            assertEquals("mqtt_cleanup_pending_identity", rejection.message)
            assertEquals("mqtt-a.example", settings.mqttHost())
            assertEquals("vehicle_a", settings.mqttDeviceId())
            assertEquals(initial.clientId, settings.mqttClientId())
            assertEquals(initial.deviceName, settings.mqttDeviceName())
            assertEquals(null, settings.mqttAlternativeHost())
            assertEquals(null, settings.mqttAlternativePort())

            val sameIdDifferentBroker = initial.copy(host = "mqtt-b.example")
            assertTrue(settings.saveMqttConfiguration(sameIdDifferentBroker))
            assertEquals("vehicle_a", settings.mqttDeviceId())
            assertEquals("mqtt-b.example", settings.mqttHost())

            val escapeTrap = initial.copy(deviceId = "vehicle_previous")
            assertTrue(settings.saveMqttConfiguration(escapeTrap))
            assertEquals("vehicle_previous", settings.mqttDeviceId())
            assertEquals("cleaning", journal.cleanupGroups(setOf(scope), "vehicle_previous")
                .single { it.deviceId == initial.normalizedDeviceId() }.state)
        } finally {
            journal.close()
            context.deleteDatabase(MqttPublicationJournal.DATABASE_NAME)
            preferences.edit().clear().commit()
        }
    }
}
