package com.bydcollector.collector.direct

import android.app.Application
import android.content.Context
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoder
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.PollValueColumns
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.influx.InfluxConfig
import com.bydcollector.collector.influx.InfluxLineProtocol
import com.bydcollector.collector.influx.influxEffectiveFields
import com.bydcollector.collector.mqtt.HaMqttConfig
import com.bydcollector.collector.mqtt.HaMqttMessageFactory
import com.bydcollector.collector.mqtt.MqttMessageBuildResult
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CompressorTelemetryTest {
    @Test
    fun directIntValuesNormalizeToKilowattsAndRejectErrorsAndMissingData() {
        val entry = compressorEntry()
        assertEquals(1005, entry.dev)
        assertEquals(1031798840, entry.fid)
        assertEquals(DirectFidRegistry.TX_GET_INT, entry.tx)
        assertEquals(DirectValueDecoder.INT_RAW, entry.decoder)
        assertEquals("direct_power", entry.groupName)
        assertEquals("climate_environment", entry.prodCategory)
        assertEquals("live20261017", entry.source)
        val normalizer = VehicleStateNormalizer()
        val expected = listOf(0x20 to 0.032, 0x1a0 to 0.416, 0x1e0 to 0.480, 0x420 to 1.056, 0 to 0.0)

        expected.forEach { (raw, kilowatts) ->
            val reading = compressorReading(raw)
            assertEquals(raw.toString(), reading.descValue)
            val observation = normalizer.normalize(1L, "2026-10-17T12:00:00Z", listOf(reading))
                .single { it.field.fieldKey == NormalizedFieldCatalog.acCompressorPowerKw.fieldKey }
            assertEquals(NormalizedQuality.OK, observation.quality)
            assertEquals(kilowatts, requireNotNull(observation.value.number), absoluteTolerance = 1e-12)
        }

        listOf(-10013, -10011, 65535, 1048575).forEach { raw ->
            val reading = compressorReading(raw)
            assertNull(reading.descValue)
            val observation = normalizer.normalize(2L, "2026-10-17T12:00:01Z", listOf(reading))
                .single { it.field.fieldKey == NormalizedFieldCatalog.acCompressorPowerKw.fieldKey }
            assertEquals(NormalizedQuality.INVALID, observation.quality)
            assertNull(observation.value.number)
        }

        val missing = normalizer.normalize(3L, "2026-10-17T12:00:02Z", emptyList())
            .single { it.field.fieldKey == NormalizedFieldCatalog.acCompressorPowerKw.fieldKey }
        assertEquals(NormalizedQuality.MISSING, missing.quality)
        assertNull(missing.value.number)
    }

    @Test
    fun rawAndNormalizedHistorySurviveReopenAndReachSelectedMqttAndInfluxOutputs() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "compressor_${UUID.randomUUID()}.db"
        var helper = TelemetryDatabaseHelper(context, databaseName)
        var store = newStore(context, helper)
        try {
            val sessionId = store.openSession()
            val entry = compressorEntry()
            val parameter = store.getActiveCatalogParameters().single { it.key == entry.key }
            val raw = 0x420
            val reading = compressorReading(raw)
            val observedAt = "2026-10-17T12:00:00Z"
            val pollId = store.insertPoll(
                sessionId,
                pollInput(observedAt, reading),
                listOf(parameter)
            )
            val errorPollId = store.insertPoll(
                sessionId,
                pollInput(
                    "2026-10-17T12:00:01Z",
                    compressorReading(65535),
                    errors = "power_compressor_consume_power returned sentinel 65535"
                ),
                listOf(parameter)
            )
            val observation = VehicleStateNormalizer().normalize(pollId, observedAt, listOf(reading))
                .single { it.field.fieldKey == NormalizedFieldCatalog.acCompressorPowerKw.fieldKey }
            assertEquals(NormalizedQuality.OK, observation.quality)
            assertEquals(1.056, observation.value.number)
            store.applyNormalizedObservations(listOf(observation))

            store.close()
            helper = TelemetryDatabaseHelper(context, databaseName)
            store = newStore(context, helper)

            fun persistedRaw(poll: Long): Pair<String?, String?> = helper.readableDatabase.rawQuery(
                "SELECT pv.${PollValueColumns.raw(entry.key)}, p.errors FROM poll_values AS pv " +
                    "JOIN polls AS p ON p.id = pv.poll_id WHERE pv.poll_id = ?",
                arrayOf(poll.toString())
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0) to cursor.getString(1)
            }

            assertEquals("1056", persistedRaw(pollId).first)
            assertEquals("65535", persistedRaw(errorPollId).first)
            assertEquals("power_compressor_consume_power returned sentinel 65535", persistedRaw(errorPollId).second)

            val current = store.normalizedCurrentState(setOf("climate"))
                .single { it.fieldKey == NormalizedFieldCatalog.acCompressorPowerKw.fieldKey }
            assertEquals("OK", current.quality)
            assertEquals(1.056, current.valueNumber)

            val mqttConfig = HaMqttConfig(
                enabled = true,
                discoveryEnabled = true,
                host = "localhost",
                port = 1883,
                username = null,
                password = null,
                clientId = "compressor-test",
                topicPrefix = "bydcollector",
                discoveryPrefix = "homeassistant",
                enabledCategories = setOf("climate")
            )
            val mqttFactory = HaMqttMessageFactory(store, { mqttConfig })
            val mqttMessages = (mqttFactory.fullResyncMessages(mqttConfig) as MqttMessageBuildResult.Success).messages
            val climatePayload = JSONObject(mqttMessages.single { it.topic == "bydcollector/state/climate" }.payload)
            assertEquals(1.056, climatePayload.getJSONObject("fields").getDouble("ac_compressor_power_kw"))
            assertEquals("ok", climatePayload.getJSONObject("quality").getString("ac_compressor_power_kw"))
            assertFalse(
                (mqttFactory.fullResyncMessages(mqttConfig.copy(enabledCategories = setOf("battery")))
                    as MqttMessageBuildResult.Success).messages.any { it.topic == "bydcollector/state/climate" }
            )
            val discovery = (mqttFactory.discoveryMessages(mqttConfig) as MqttMessageBuildResult.Success)
                .messages.single { it.topic.endsWith("/ac_compressor_power_kw/config") }
            val discoveryPayload = JSONObject(discovery.payload)
            assertEquals("power", discoveryPayload.getString("device_class"))
            assertEquals("measurement", discoveryPayload.getString("state_class"))
            assertEquals("kW", discoveryPayload.getString("unit_of_measurement"))
            assertEquals("bydcollector/state/climate", discoveryPayload.getString("state_topic"))

            val influxConfig = InfluxConfig(
                enabled = true,
                host = "localhost",
                port = 8086,
                database = "bydcollector",
                username = null,
                password = null,
                measurement = "byd_state",
                enabledCategories = setOf("climate")
            )
            val selectedInfluxFields = influxEffectiveFields(influxConfig)
            assertTrue(NormalizedFieldCatalog.acCompressorPowerKw.fieldKey in selectedInfluxFields)
            assertFalse(
                NormalizedFieldCatalog.acCompressorPowerKw.fieldKey in
                    influxEffectiveFields(influxConfig.copy(enabledCategories = setOf("battery")))
            )
            val historyRow = store.pendingInfluxRows(selectedInfluxFields, limit = 100)
                .single { it.fieldKey == NormalizedFieldCatalog.acCompressorPowerKw.fieldKey }
            assertEquals(1.056, historyRow.valueNumber)
            val line = InfluxLineProtocol.toLine(historyRow, influxConfig)
            assertTrue(line.contains("field_key=ac_compressor_power_kw"))
            assertTrue(line.contains("category=climate"))
            assertTrue(line.contains("unit=kW"))
            assertTrue(line.contains("value_num=1.056"))
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun compressorEntry() = DirectFidRegistry.entries.single { it.key == "power_compressor_consume_power" }

    private fun compressorReading(raw: Int) = PollReading(
        rawKey = compressorEntry().key,
        rawValue = DirectValueDecoders.rawString(raw),
        descValue = DirectValueDecoders.decode(compressorEntry(), raw)
    )

    private fun pollInput(timestamp: String, reading: PollReading, errors: String? = null) = PersistedPollInput(
        timestamp = timestamp,
        ok = true,
        elapsedMs = 1L,
        requestCount = 1,
        errors = errors,
        rawResponseBody = null,
        readings = listOf(reading)
    )

    private fun newStore(context: Context, helper: TelemetryDatabaseHelper) = TelemetryStore(
        context = context,
        helper = helper,
        operationalEventJournal = OperationalEventJournal(context)
    )
}
