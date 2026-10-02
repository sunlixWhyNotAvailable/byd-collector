package com.bydcollector.collector.ha

enum class HaExportChannel { MQTT, INFLUX }

/** Manual Start lasts through OFF until the next observed OFF -> ON. */
class HaRunSession {
    private val manual = mutableSetOf<HaExportChannel>()
    private var powerOn: Boolean? = null

    @Synchronized fun start(channel: HaExportChannel): Boolean = manual.add(channel)
    @Synchronized fun stop(channel: HaExportChannel) { manual.remove(channel) }
    @Synchronized fun clear() { manual.clear() }
    @Synchronized fun observedPowerOn(): Boolean? = powerOn
    @Synchronized fun restoreAfterTaskRemoval(mqtt: Boolean, influx: Boolean, lastPowerOn: Boolean?) {
        manual.clear()
        if (mqtt) manual.add(HaExportChannel.MQTT)
        if (influx) manual.add(HaExportChannel.INFLUX)
        powerOn = lastPowerOn
    }
    @Synchronized fun allows(channel: HaExportChannel, autoStart: Boolean): Boolean =
        autoStart || channel in manual

    @Synchronized fun observePower(raw: Long?): Boolean {
        if (raw == null || raw < 0) return false
        val on = raw > 0
        val newSession = powerOn == false && on
        powerOn = on
        if (newSession) manual.clear()
        return newSession
    }

    companion object {
        val process = HaRunSession()
    }
}
