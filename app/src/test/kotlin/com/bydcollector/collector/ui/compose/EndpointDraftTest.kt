package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointDraftTest {
    @Test
    fun rejectsInvalidHostsAndPortsBeforeStartingAConnection() {
        listOf("mqtt\\local", "influx\\local", "http://influx.local", "mqtt local", "").forEach { host ->
            assertFalse(validEndpointDraft(host, "1883"), host)
            assertFalse(validEndpointDraft(host, "8086", optional = true), host)
        }
        assertTrue(validEndpointDraft(" mqtt.local ", "1883"))
        assertTrue(validEndpointDraft(" influx.local ", "8086"))
        assertTrue(validEndpointDraft("", "", optional = true))
        assertFalse(validEndpointDraft("mqtt.local", ""))
        assertFalse(validEndpointDraft("mqtt.local", "65536"))
        assertFalse(validEndpointDraft("", "1883", optional = true))
    }
}
