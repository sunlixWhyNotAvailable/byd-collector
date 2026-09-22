package com.bydcollector.collector.data.energy

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.*
import com.bydcollector.collector.data.polling.LivePollSource
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.influx.InfluxConfig
import com.bydcollector.collector.influx.InfluxLineProtocol
import com.bydcollector.collector.influx.InfluxPendingHistoryRow
import com.bydcollector.collector.mqtt.HaMqttPayloadBuilder
import org.json.JSONObject
import kotlin.test.*

class EnergyTelemetryProjectionTest {

    @Test fun failedLiveAndReplayReceiptsBreakTheAnchorWithoutBridgingEnergy() {
        val liveFailure = EnergyTelemetryProjection.receipt(
            PollSampleSource("live:failed", "boot", 500L), AT, emptyList(), emptyList()
        )
        val replayFailure = EnergyTelemetryProjection.receipt(
            PollSampleSource("helper:boot:generation:2", "boot", 500L), AT, emptyList(), emptyList()
        )
        assertEquals(liveFailure.input, replayFailure.input)
        assertNull(liveFailure.input.powerOn)
        assertNull(liveFailure.input.voltage)
        assertNull(liveFailure.input.current)

        fun integrate(failure: EnergyInput): EnergyIntegrationResult {
            val first = BatteryEnergyIntegrator.integrate(
                null,
                EnergyTotals(),
                EnergyInput("boot", 0L, 400.0, 10.0, true, true, false)
            )
            val broken = BatteryEnergyIntegrator.integrate(first.anchor, first.totals, failure)
            return BatteryEnergyIntegrator.integrate(
                broken.anchor,
                broken.totals,
                EnergyInput("boot", 1_000L, 400.0, 10.0, true, true, false)
            )
        }

        val live = integrate(liveFailure.input)
        val replay = integrate(replayFailure.input)
        assertEquals(live, replay)
        assertEquals(0L, live.totals.coveredMs)
        assertEquals(500L, live.totals.uncoveredMs)
        assertEquals(0.0, live.totals.dischargedKwh)
        assertTrue(live.totals.partial)
        assertEquals(EnergyIntegrationQuality.ANCHOR_ONLY, live.quality)
    }

    @Test fun liveIdentitiesDoNotRepeatWhenMainRowIdsRestart() {
        val before = LivePollSource("boot", "before-archive")
        val after = LivePollSource("boot", "after-archive")
        assertNotEquals(before.capture(500).identity, before.capture(500).identity)
        assertNotEquals(before.capture(500).identity, after.capture(500).identity)
        assertEquals(1234L, after.capture(1234).capturedElapsedMs)
    }

    @Test fun sourceClockAndEvidenceAreNotReplacedByImportTimeOrGuessedGunState() {
        val source = PollSampleSource("original-source", "original-boot", 850)
        val observations = listOf(
            number(NormalizedFieldCatalog.hvBatteryVoltage, 550.0),
            number(NormalizedFieldCatalog.chargeCurrent, -10.0),
            NormalizedObservation(NormalizedFieldCatalog.chargeGunConnected,
                NormalizedValue(NormalizedValueType.BOOLEAN, bool = false), NormalizedQuality.OK, null, null, AT)
        )
        val raw = listOf(PollReading("bodywork_power_level", "2", "2"))
        val receipt = EnergyTelemetryProjection.receipt(source, AT, raw, observations)
        assertEquals("original-source", receipt.sourceIdentity)
        assertEquals(AT, receipt.observedAt)
        assertEquals(850L, receipt.input.elapsedMs)
        assertEquals("original-boot", receipt.input.bootId)
        assertEquals(true, receipt.input.powerOn)
        assertEquals(true, receipt.input.gunDisconnected)
        assertEquals(false, receipt.input.externalCharging)
        val missingGun = EnergyTelemetryProjection.receipt(source, AT, raw, observations.take(2))
        assertNull(missingGun.input.gunDisconnected)
        val invalidVoltage = observations.map { if (it.field == NormalizedFieldCatalog.hvBatteryVoltage) it.copy(quality = NormalizedQuality.INVALID) else it }
        assertNull(EnergyTelemetryProjection.receipt(source, AT, raw, invalidVoltage).input.voltage)
        val charging = observations + NormalizedObservation(NormalizedFieldCatalog.chargingBatteryDeviceState,
            NormalizedValue(NormalizedValueType.TEXT, text = "charging"), NormalizedQuality.OK, null, null, AT)
        assertTrue(EnergyTelemetryProjection.receipt(source, AT, raw, charging).input.externalCharging == true)
        for (label in listOf("discharg", "discharg_cbu")) {
            val discharge = observations + charging.last().copy(value = NormalizedValue(NormalizedValueType.TEXT, text = label))
            assertFalse(EnergyTelemetryProjection.receipt(source, AT, raw, discharge).input.externalCharging == true)
        }
        val v2l = observations + NormalizedObservation(NormalizedFieldCatalog.v2lDischargeActive,
            NormalizedValue(NormalizedValueType.BOOLEAN, bool = true), NormalizedQuality.OK, null, null, AT)
        assertTrue(EnergyTelemetryProjection.receipt(source, AT, raw, v2l).input.externalCharging == true)
    }

    @Test fun batteryFieldsAreAdditiveAndDoNotGetMissingValuesFromRawNormalizer() {
        assertEquals(listOf("trip_discharged_kwh", "trip_regenerated_kwh", "trip_net_kwh"),
            NormalizedFieldCatalog.energyFields.map { it.fieldKey })
        assertTrue(NormalizedFieldCatalog.energyFields.all { it.category == NormalizedCategory.BATTERY && it.stateClass != "total_increasing" })
        assertTrue(VehicleStateNormalizer().normalize(1, AT, emptyList()).none { it.field in NormalizedFieldCatalog.energyFields })
        assertEquals("trip_energy_kwh", NormalizedFieldCatalog.tripEnergy.fieldKey)
    }

    @Test fun projectionCarriesSignedSubtotalCoverageAndSourceTimeWithoutMainForeignKey() {
        val values = EnergyTelemetryProjection.observations(snapshot())
        assertEquals(listOf(1.0, 2.0, -1.0), values.map { it.value.number })
        assertTrue(values.all { it.sourcePollId == null && it.observedAt == AT && it.reason == "partial" })
        val missing = EnergyTelemetryProjection.observations(snapshot().copy(
            dischargedKwh = null, regeneratedKwh = null, netKwh = null, energyCoveredMs = 0))
        assertTrue(missing.all { it.quality == NormalizedQuality.MISSING && it.value.number == null })
    }

    @Test fun mqttAndInfluxPreserveNumericSignAndCoverageAndOriginalTimestamp() {
        val state = StoredNormalizedState("trip_net_kwh", "battery", "NUMBER", null, -1.0, null,
            "OK", "kWh", null, "charging_charge_battery_volt,charging_charge_current", AT, AT, "partial")
        val payload = JSONObject(HaMqttPayloadBuilder.categoryState("battery", "2026-09-15T10:00:00Z", listOf(state)))
        assertEquals(-1.0, payload.getJSONObject("fields").getDouble("trip_net_kwh"))
        assertEquals("partial", payload.getJSONObject("quality_detail").getString("trip_net_kwh"))
        assertEquals(AT, payload.getJSONObject("observed_at").getString("trip_net_kwh"))
        val row = InfluxPendingHistoryRow(1, "trip_net_kwh", "battery", "NUMBER", null, -1.0, null,
            "OK", "kWh", null, state.sourceKeys, AT, AT, "partial")
        val line = InfluxLineProtocol.toLine(row, InfluxConfig(true, "localhost", 8086,
            "bydcollector", null, null, "byd_state", setOf("battery")))
        assertTrue(line.contains("value_num=-1.0"))
        assertTrue(line.contains("quality_detail=\"partial\""))
        assertTrue(line.endsWith(" ${InfluxLineProtocol.timestampNanos(AT)}"))
        assertTrue(NormalizedStateReducer.decide(state, state).insertHistory.not())
        assertTrue(NormalizedStateReducer.decide(state.copy(qualityDetail = "complete"), state).insertHistory)
    }

    private fun snapshot() = EnergySnapshot("energy:source", "source", "power", AT, AT,
        "boot", 1000, true, 1.0, 2.0, -1.0, 1000, 0, true,
        EnergyIntegrationQuality.COVERED, "INTEGRATED")
    private fun number(field: NormalizedFieldDefinition, value: Double) = NormalizedObservation(
        field, NormalizedValue(NormalizedValueType.NUMBER, number = value), NormalizedQuality.OK, null, null, AT)
    companion object { private const val AT = "2026-09-14T10:00:00Z" }
}
