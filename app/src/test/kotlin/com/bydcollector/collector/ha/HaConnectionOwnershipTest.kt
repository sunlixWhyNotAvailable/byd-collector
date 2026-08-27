package com.bydcollector.collector.ha

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import com.bydcollector.collector.ui.compose.validEndpointDraft

class HaConnectionOwnershipTest {
    @Test fun channelsHaveIndependentOwnershipAndStops() {
        val mqtt = HaConnectionOwnership()
        val influx = HaConnectionOwnership()
        mqtt.reserve()
        assertFalse(influx.owned)
        influx.reserve()
        mqtt.beginStop()
        assertFalse(influx.stopping)
        mqtt.release()
        assertTrue(influx.owned)
    }

    @Test fun ownershipSurvivesOutageAndStopUntilCleanupCompletes() {
        val owner = HaConnectionOwnership()
        assertTrue(owner.reserve())
        owner.publishRoute(HaEndpointProfile.ALTERNATIVE)
        assertFalse(owner.reserve())
        owner.publishRoute(null) // transport outage is not a completed Stop
        assertTrue(owner.owned)
        owner.beginStop()
        assertTrue(owner.stopping)
        assertFalse(owner.reserve())
        owner.stopSubmissionFailed()
        assertTrue(owner.owned)
        assertFalse(owner.stopping)
        owner.beginStop()
        owner.release()
        owner.publishRoute(HaEndpointProfile.PRIMARY) // stale result cannot re-own a stopped session
        assertEquals(HaConnectionState(), owner.state.value)
        assertTrue(owner.reserve())
        assertNull(owner.state.value.activeRoute)
    }

    @Test fun optionalEndpointIsUnsetOnlyWhenBothFieldsAreBlank() {
        assertTrue(validEndpointDraft("", "", optional = true))
        assertTrue(validEndpointDraft("100.1.2.3", "1884", optional = true))
        assertTrue(validEndpointDraft("home.local", "65535"))
        for ((host, port) in listOf("" to "1883", "home.local" to "", "home.local" to "0",
            "home.local" to "65536", "home.local" to "bad", "https://host" to "1883", "bad host" to "1883")) {
            assertFalse(validEndpointDraft(host, port, optional = true), "$host:$port")
        }
    }
}
