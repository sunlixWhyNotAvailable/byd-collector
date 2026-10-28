package com.bydcollector.collector.data.normalized

import android.app.Application
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.influx.InfluxConfig
import com.bydcollector.collector.influx.InfluxLineProtocol
import com.bydcollector.collector.mqtt.HaMqttPayloadBuilder
import com.bydcollector.collector.service.KpiFreshness
import com.bydcollector.collector.ui.VehicleKpiLanguage
import com.bydcollector.collector.ui.VehicleKpiMapper
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CompositeTelemetryNormalizationTest {
    private val fields = listOf(NormalizedFieldCatalog.batteryPower,
        NormalizedFieldCatalog.batteryChargePower, NormalizedFieldCatalog.batteryDischargePower,
        NormalizedFieldCatalog.chargingTimeRemaining)
    private val normalizer = VehicleStateNormalizer(fields)
    private val voltage = fields[0].sourceKeys[0]
    private val current = fields[0].sourceKeys[1]
    private val hours = fields[3].sourceKeys[0]
    private val minutes = fields[3].sourceKeys[1]

    // Controlled raw telemetry enters below Binder; all calculations, retention and storage are production code.
    private fun input(key: String, value: Double, elapsed: Long, sequence: Long = elapsed,
                      boot: String = "boot") = NormalizedSourceInput(
        PollReading(key, if (key == hours || key == minutes) value.toInt().toString()
            else value.toFloat().toRawBits().toString(), value.toString()),
        NormalizedSourceStamp(NormalizedSourceKind.CALLBACK, "$boot:$key:$sequence", boot,
            "$boot:$key", sequence, BASE_MS + elapsed, elapsed, 1), null
    )

    @Test
    fun eitherPowerInputUpdatesHeldKpiIncludingSameMillisecondButReplayCannotRollItBack() {
        val inputs = linkedMapOf(voltage to input(voltage, 558.0, 100), current to input(current, 2.7, 110))
        val held = KpiFreshness("boot")
        fun accept(key: String): List<NormalizedObservation> = normalizer.normalizeSparse(inputs, setOf(key))
            .also { held.accept(it, 10_000) }
        fun number(key: String) = held.observations().single { it.field.fieldKey == key }.value.number!!

        val first = accept(current)
        assertEquals(1.5066, number("battery_discharge_power_kw"), 0.00001)
        inputs[current] = input(current, 0.8, 200)
        val changedCurrent = accept(current)
        assertEquals(0.4464, number("battery_discharge_power_kw"), 0.00001)
        assertEquals(100L, changedCurrent.first().sourceStamp!!.contributors.getValue(voltage).elapsedMs)
        assertEquals(200L, changedCurrent.first().sourceStamp!!.elapsedMs)
        assertEquals("-0.4 kW", VehicleKpiMapper.fromObservations(held.observations(), VehicleKpiLanguage.EN).batteryPowerKw)

        inputs[voltage] = input(voltage, 600.0, 200)
        val changedVoltage = accept(voltage)
        assertEquals(0.48, number("battery_discharge_power_kw"), 0.00001)
        assertEquals(NormalizedSourceOrder.NEWER, NormalizedSourceOrdering.compare(
            changedVoltage.first().sourceStamp!!, changedCurrent.first().sourceStamp!!))

        inputs[current] = input(current, -1.2, 200, sequence = 201)
        val charging = accept(current)
        assertEquals(-0.72, charging.single { it.field.fieldKey == "battery_power_kw" }.value.number!!, 0.00001)
        assertEquals(0.72, number("battery_charge_power_kw"), 0.00001)
        assertEquals(0.0, number("battery_discharge_power_kw"))
        assertTrue(VehicleKpiMapper.fromObservations(held.observations()).batteryPowerCharging)
        assertFalse(held.accept(charging, 10_000))
        assertFalse(held.accept(first + changedCurrent + changedVoltage, 10_000))
        assertEquals(0.72, number("battery_charge_power_kw"), 0.00001)

        inputs[voltage] = input(voltage, 610.0, 300)
        inputs[current] = input(current, 2.7, 110)
        assertFalse(held.accept(normalizer.normalizeSparse(inputs, inputs.keys), 10_000))
        assertEquals(0.72, number("battery_charge_power_kw"), 0.00001)
        inputs[current] = input(current, 0.0, 400)
        accept(current)
        assertEquals(0.0, number("battery_charge_power_kw"))
        assertEquals(0.0, number("battery_discharge_power_kw"))

        inputs[current] = input(current, 0.0, 500).let {
            it.copy(reading = PollReading(current, null, null))
        }
        assertTrue(accept(current).all { it.quality != NormalizedQuality.OK && it.value.number == null })
        inputs.remove(current)
        assertTrue(normalizer.normalizeSparse(inputs, setOf(current)).isEmpty())
        inputs[current] = input(current, 0.8, 1, boot = "next-boot")
        assertTrue(normalizer.normalizeSparse(inputs, setOf(current)).isEmpty())
        inputs[voltage] = input(voltage, 558.0, 2, boot = "next-boot")
        val rebooted = normalizer.normalizeSparse(inputs, inputs.keys)
        assertFalse(held.accept(rebooted, 10_000))
        val afterBoot = KpiFreshness("next-boot")
        assertTrue(afterBoot.accept(rebooted, 10_000))
        assertEquals(0.4464, afterBoot.observations().single {
            it.field.fieldKey == "battery_discharge_power_kw"
        }.value.number!!, 0.00001)
    }

    @Test
    fun allFourFieldsPersistNewAcquisitionTimesAndExportThemAfterReopen() {
        val context = RuntimeEnvironment.getApplication()
        val name = "derived_${System.nanoTime()}.db"
        var helper = TelemetryDatabaseHelper(context, name)
        try {
            val store = NormalizedStateStore(helper)
            store.upsertCatalog(fields)
            val inputs = linkedMapOf(voltage to input(voltage, 558.0, 100), current to input(current, 2.7, 110),
                hours to input(hours, 1.0, 100), minutes to input(minutes, 20.0, 110))
            store.applyObservations(normalizer.normalizeSparse(inputs, inputs.keys))
            inputs[current] = input(current, -0.8, 200)
            inputs[minutes] = input(minutes, 19.0, 200)
            store.applyObservations(normalizer.normalizeSparse(inputs, setOf(current, minutes)))
            inputs[voltage] = input(voltage, 600.0, 300)
            inputs[hours] = input(hours, 2.0, 300)
            val latest = normalizer.normalizeSparse(inputs, setOf(voltage, hours))
            store.applyObservations(latest)
            assertEquals(0, store.applyObservations(latest).historyInsertedCount)
            helper.close()
            helper = TelemetryDatabaseHelper(context, name)
            val reopened = NormalizedStateStore(helper).currentState().associateBy { it.fieldKey }
            assertEquals(-0.48, reopened.getValue("battery_power_kw").valueNumber!!, 0.00001)
            assertEquals(0.48, reopened.getValue("battery_charge_power_kw").valueNumber!!, 0.00001)
            assertEquals(0.0, reopened.getValue("battery_discharge_power_kw").valueNumber)
            assertEquals("02:19:00", reopened.getValue("charging_time_remaining").valueText)
            assertTrue(reopened.values.all { Instant.parse(it.observedAt).toEpochMilli() == BASE_MS + 300 })
            assertEquals(BASE_MS + 200, Instant.parse(reopened.getValue("battery_discharge_power_kw").changedAt).toEpochMilli())
            val mqtt = JSONObject(HaMqttPayloadBuilder.categoryState("battery", "2026-10-28T12:00:00Z", reopened.values.toList()))
            assertEquals(-0.48, mqtt.getJSONObject("fields").getDouble("battery_power_kw"), 0.00001)
            assertEquals("02:19:00", mqtt.getJSONObject("fields").getString("charging_time_remaining"))
            reopened.forEach { (key, row) ->
                assertEquals(row.observedAt, mqtt.getJSONObject("observed_at").getString(key))
            }

            val telemetry = TelemetryStore(context, helper, operationalEventJournal = OperationalEventJournal(context))
            val rows = telemetry.pendingInfluxRows(fields.map { it.fieldKey }.toSet(), 100)
            val config = InfluxConfig(false, "localhost", 8086, "test", null, null, "state", setOf("battery"))
            for (field in fields) {
                val history = rows.filter { it.fieldKey == field.fieldKey }
                val expected = if (field == NormalizedFieldCatalog.batteryDischargePower) listOf(110L, 200L)
                    else listOf(110L, 200L, 300L)
                assertEquals(expected.map { (BASE_MS + it) * 1_000_000 }, history.map {
                    InfluxLineProtocol.toLine(it, config).substringAfterLast(' ').toLong()
                })
                assertTrue(history.all { it.observedAt == it.changedAt })
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    companion object { private const val BASE_MS = 1_793_188_800_000L }
}
