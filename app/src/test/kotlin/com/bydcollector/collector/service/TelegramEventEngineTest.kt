package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedFieldDefinition
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.telegram.TelegramEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramEventEngineTest {
    private val config = TelegramEventConfig(
        enabledEvents = TelegramEventType.entries.toSet(),
        chargeStepPercent = 5,
        lowVoltageThreshold = 12.0,
        unavailableDelayMs = 60_000L,
        tripEndDelayMs = 120_000L
    )

    @Test
    fun chargingTransitionsAreConfirmedAndProgressUsesAbsoluteThresholds() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(charging = "ready", soc = 63.0), config, 0L)
        assertTrue(engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 63.0), config, 500L).events.isEmpty())
        val started = engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 63.0), config, 1_000L)
        assertEquals(listOf(TelegramEventType.CHARGING_STARTED), started.events.map { it.type })

        assertTrue(engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 64.0), config, 1_250L).events.isEmpty())

        val progress = engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 65.0), config, 1_500L)
        assertEquals(TelegramEventType.CHARGING_PROGRESS, progress.events.single().type)
        assertTrue(progress.events.single().dedupeKey.endsWith(":progress:65"))

        engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 99.6), config, 2_000L)
        val full = engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 99.6), config, 2_500L)
        assertEquals(TelegramEventType.CHARGED_TO_100, full.events.single().type)
    }

    @Test
    fun fullChargeFallsBackToProgress100WhenFullEventIsDisabled() {
        val engine = TelegramEventEngine()
        val progressOnly = config.copy(
            enabledEvents = setOf(TelegramEventType.CHARGING_PROGRESS),
            chargeStepPercent = 6
        )
        engine.onSuccessfulPoll(snapshot(charging = "ready", soc = 95.0), progressOnly, 0L)
        engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 95.0), progressOnly, 500L)
        engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 95.0), progressOnly, 1_000L)
        engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 99.6), progressOnly, 1_500L)
        val full = engine.onSuccessfulPoll(snapshot(charging = "charging", soc = 99.6), progressOnly, 2_000L)

        assertEquals(TelegramEventType.CHARGING_PROGRESS, full.events.single().type)
        assertTrue(full.events.single().dedupeKey.endsWith(":progress:100"))
    }

    @Test
    fun lowVoltageRequiresOneMinuteAndHysteresisRearmsIt() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(auxVoltage = 12.5), config, 0L)
        engine.onSuccessfulPoll(snapshot(auxVoltage = 11.9), config, 1_000L)
        assertTrue(engine.onSuccessfulPoll(snapshot(auxVoltage = 11.8), config, 60_999L).events.isEmpty())
        assertEquals(
            TelegramEventType.LOW_12V_VOLTAGE,
            engine.onSuccessfulPoll(snapshot(auxVoltage = 11.8), config, 61_000L).events.single().type
        )
        engine.onSuccessfulPoll(snapshot(auxVoltage = 12.2), config, 62_000L)
        assertTrue(engine.state.lowVoltageSent)
        engine.onSuccessfulPoll(snapshot(auxVoltage = 12.3), config, 62_500L)
        assertTrue(!engine.state.lowVoltageSent)
    }

    @Test
    fun tripEndsOnlyAfterParkDelayAndTelemetryOutageNeedsExpectedCollection() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0), config, 500L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.1), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0), config, 2_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0), config, 2_500L)
        assertTrue(engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0), config, 122_499L).events.isEmpty())
        assertEquals(
            TelegramEventType.TRIP_SUMMARY,
            engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0), config, 122_500L).events.single().type
        )

        assertTrue(engine.onTick(config, mainCollectionExpected = false, lastError = "offline", nowMs = 200_000L).events.isEmpty())
        assertTrue(engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 200_000L).events.isEmpty())
        assertEquals(
            TelegramEventType.TELEMETRY_UNAVAILABLE,
            engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 260_000L).events.single().type
        )
    }

    @Test
    fun telemetryOutageCanBeReportedBeforeTheFirstSuccessfulPoll() {
        val engine = TelegramEventEngine()

        assertTrue(engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 1_000L).events.isEmpty())
        assertTrue(engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 60_999L).events.isEmpty())
        assertEquals(
            TelegramEventType.TELEMETRY_UNAVAILABLE,
            engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 61_000L).events.single().type
        )
    }

    private fun snapshot(
        charging: String = "ready",
        soc: Double = 50.0,
        auxVoltage: Double = 12.5,
        gear: String = "P",
        odometer: Double = 100.0
    ): List<NormalizedObservation> = listOf(
        text(NormalizedFieldCatalog.chargingState, charging),
        number(NormalizedFieldCatalog.soc, soc),
        number(NormalizedFieldCatalog.batteryRemainingEnergy, 40.0),
        number(NormalizedFieldCatalog.batteryPower, 10.0),
        number(NormalizedFieldCatalog.auxVoltage, auxVoltage),
        number(NormalizedFieldCatalog.odometerKm, odometer),
        number(NormalizedFieldCatalog.tripEnergy, 2.5),
        number(NormalizedFieldCatalog.remainingRangeKm, 300.0),
        text(NormalizedFieldCatalog.gearAutoMode, gear),
        bool(NormalizedFieldCatalog.chargeGunConnected, false)
    )

    private fun number(field: NormalizedFieldDefinition, value: Double) = observation(
        field,
        NormalizedValue(NormalizedValueType.NUMBER, number = value)
    )

    private fun text(field: NormalizedFieldDefinition, value: String) = observation(
        field,
        NormalizedValue(NormalizedValueType.TEXT, text = value)
    )

    private fun bool(field: NormalizedFieldDefinition, value: Boolean) = observation(
        field,
        NormalizedValue(NormalizedValueType.BOOLEAN, bool = value)
    )

    private fun observation(field: NormalizedFieldDefinition, value: NormalizedValue) = NormalizedObservation(
        field = field,
        value = value,
        quality = NormalizedQuality.OK,
        sourcePollId = 1L,
        sourceKey = field.sourceKeys.firstOrNull(),
        observedAt = "2026-07-22T00:00:00Z"
    )
}
