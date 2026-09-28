package com.bydcollector.collector.ha

import org.junit.Assert.*
import org.junit.Test

class HaRunSessionTest {
    @Test fun manualPermissionEndsAtNextPowerOnNotPowerOff() {
        val session = HaRunSession()
        val channel = HaExportChannel.MQTT
        session.observePower(2)
        session.start(channel)
        assertFalse(session.observePower(0))
        assertTrue(session.allows(channel, false))
        assertFalse(session.observePower(-10011))
        assertFalse(session.observePower(null))
        assertTrue(session.observePower(2))
        assertFalse(session.allows(channel, false))
        assertTrue(session.allows(channel, true))
        // AutoStart kept the owned run alive; disabling it transfers permission to this session.
        session.start(channel)
        assertTrue(session.allows(channel, false))
        session.observePower(0)
        assertTrue(session.allows(channel, false))
        session.observePower(2)
        assertFalse(session.allows(channel, false))
    }

    @Test fun manualStartWhileOffAndColdProcessAreIndependentOfPersistentFlags() {
        val session = HaRunSession()
        session.observePower(0)
        HaExportChannel.entries.forEach { session.start(it) }
        assertTrue(session.allows(HaExportChannel.INFLUX, false))
        val recreatedProcess = HaRunSession()
        assertFalse(recreatedProcess.allows(HaExportChannel.INFLUX, false))
        assertFalse(recreatedProcess.observePower(2)) // Initial sample is only a baseline.
        assertTrue(session.observePower(2))
        HaExportChannel.entries.forEach { assertFalse(session.allows(it, false)) }
        session.start(HaExportChannel.INFLUX)
        session.stop(HaExportChannel.MQTT)
        assertTrue(session.allows(HaExportChannel.INFLUX, false))
        session.clear()
        assertFalse(session.allows(HaExportChannel.INFLUX, false))
        assertTrue(session.allows(HaExportChannel.MQTT, true))
    }
}
