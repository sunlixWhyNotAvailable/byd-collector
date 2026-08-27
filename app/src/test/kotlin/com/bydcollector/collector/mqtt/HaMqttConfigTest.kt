package com.bydcollector.collector.mqtt

import com.bydcollector.collector.ha.HaEndpointProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HaMqttConfigTest {
    @Test
    fun partialAlternativePairIsInvalidAndDoesNotFallbackSilently() {
        val config = config(alternativeHost = "mqtt-alt.local")

        assertNotNull(config.validateEndpoints())
        assertNotNull(config.validateProfile(HaEndpointProfile.ALTERNATIVE))
    }

    @Test
    fun endpointSelectionClearsAlternativeFieldsForSingleAttempt() {
        val config = config(alternativeHost = "mqtt-alt.local", alternativePort = 1884)

        val selected = config.forProfile(HaEndpointProfile.ALTERNATIVE)

        assertEquals("mqtt-alt.local", selected.host)
        assertEquals(1884, selected.port)
        assertNull(selected.alternativeHost)
        assertNull(selected.alternativePort)
    }

    @Test
    fun whitespaceAndSlashHostsAreRejected() {
        assertNotNull(config(host = "mqtt alt.local").validateEndpoints())
        assertNotNull(config(host = "mqtt://broker").validateEndpoints())
    }

    private fun config(
        host: String = "mqtt.local",
        alternativeHost: String? = null,
        alternativePort: Int? = null
    ): HaMqttConfig {
        return HaMqttConfig(
            enabled = true,
            discoveryEnabled = true,
            host = host,
            port = 1883,
            username = null,
            password = null,
            clientId = "bydcollector",
            topicPrefix = "bydcollector",
            discoveryPrefix = "homeassistant",
            enabledCategories = HaMqttConfig.DEFAULT_CATEGORIES,
            alternativeHost = alternativeHost,
            alternativePort = alternativePort
        )
    }
}
