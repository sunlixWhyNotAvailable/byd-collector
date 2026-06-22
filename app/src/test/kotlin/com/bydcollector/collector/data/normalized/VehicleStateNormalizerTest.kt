package com.bydcollector.collector.data.normalized

import com.bydcollector.collector.data.local.PollReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

class VehicleStateNormalizerTest {
    @Test
    fun normalizesDisplayAndEstimateSocAndSpeedFromDirectReadings() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(
                fieldsByKey.getValue("soc"),
                fieldsByKey.getValue("soc_estimate"),
                NormalizedFieldCatalog.speedKmh
            )
        ).normalize(
            pollId = 42L,
            observedAt = "2026-06-12T12:00:00+03:00",
            readings = listOf(
                PollReading("statistic_1014_1145045040_5", "96"),
                PollReading("statistic_1014_1134559272_5", "93"),
                PollReading(
                    rawKey = "speed_1013_-1807745016_7",
                    rawValue = java.lang.Float.floatToIntBits(12.5f).toString(),
                    descValue = "12.5"
                )
            )
        )

        val soc = output.single { it.field.fieldKey == "soc" }
        assertEquals(NormalizedQuality.OK, soc.quality)
        assertEquals("statistic_1014_1145045040_5", soc.sourceKey)
        assertEquals(96.0, soc.value.number)

        val estimateSoc = output.single { it.field.fieldKey == "soc_estimate" }
        assertEquals(NormalizedQuality.OK, estimateSoc.quality)
        assertEquals("statistic_1014_1134559272_5", estimateSoc.sourceKey)
        assertEquals(93.0, estimateSoc.value.number)

        val speed = output.single { it.field.fieldKey == "speed_kmh" }
        assertEquals(NormalizedQuality.OK, speed.quality)
        assertEquals(12.5, speed.value.number)
    }

    @Test
    fun speedUsesDecodedValueInsteadOfRawFloatBits() {
        val output = VehicleStateNormalizer(
            catalog = listOf(NormalizedFieldCatalog.speedKmh)
        ).normalize(
            pollId = 51L,
            observedAt = "2026-06-14T12:00:00+03:00",
            readings = listOf(
                PollReading(
                    rawKey = "speed_1013_-1807745016_7",
                    rawValue = "1101791232",
                    descValue = "21.5"
                )
            )
        )

        val speed = output.single()
        assertEquals(NormalizedQuality.OK, speed.quality)
        assertEquals(21.5, speed.value.number)
    }

    @Test
    fun chargeCurrentUsesDecodedValueInsteadOfRawFloatBits() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(fieldsByKey.getValue("hv_battery_current_a"))
        ).normalize(
            pollId = 56L,
            observedAt = "2026-06-15T12:00:00+03:00",
            readings = listOf(
                PollReading(
                    rawKey = "charging_charging_charge_current_not_convert",
                    rawValue = java.lang.Float.floatToIntBits(81.5f).toString(),
                    descValue = "81.5"
                )
            )
        )

        val chargeCurrent = output.single()
        assertEquals(NormalizedQuality.OK, chargeCurrent.quality)
        assertEquals(81.5, chargeCurrent.value.number)
    }

    @Test
    fun decodedFloatSourceWithoutDecodedValueIsInvalidInsteadOfRawBits() {
        val output = VehicleStateNormalizer(
            catalog = listOf(NormalizedFieldCatalog.speedKmh)
        ).normalize(
            pollId = 57L,
            observedAt = "2026-06-15T12:00:01+03:00",
            readings = listOf(
                PollReading(
                    rawKey = "speed_1013_-1807745016_7",
                    rawValue = java.lang.Float.floatToIntBits(21.5f).toString(),
                    descValue = null
                )
            )
        )

        val speed = output.single()
        assertEquals(NormalizedQuality.INVALID, speed.quality)
        assertEquals(null, speed.value.number)
    }

    @Test
    fun odometerConvertsDeciKilometersToKilometers() {
        val output = VehicleStateNormalizer(
            catalog = listOf(NormalizedFieldCatalog.odometerKm)
        ).normalize(
            pollId = 58L,
            observedAt = "2026-06-15T12:00:02+03:00",
            readings = listOf(PollReading("statistic_1014_1246765072_5", "112000", "112000"))
        )

        val odometer = output.single()
        assertEquals(NormalizedQuality.OK, odometer.quality)
        assertEquals(11200.0, odometer.value.number)
    }

    @Test
    fun referenceRawIntFieldsNormalizeToPhysicalUnitsForExports() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(
                fieldsByKey.getValue("battery_lowest_cell_voltage_raw"),
                fieldsByKey.getValue("battery_lowest_temp_raw"),
                NormalizedFieldCatalog.tirePressureLf,
                NormalizedFieldCatalog.outsideTemp
            )
        ).normalize(
            pollId = 59L,
            observedAt = "2026-06-15T12:00:03+03:00",
            readings = listOf(
                PollReading("statistic_lowest_battery_voltage", "3312", "3.312"),
                PollReading("statistic_1014_1148190736_5", "65", "25"),
                PollReading("tyre_1016_-1728052956_5", "260", "260"),
                PollReading("ac_1000_1077936184_5", "18", "18")
            )
        )

        assertEquals(3.312, output.single { it.field.fieldKey == "battery_lowest_cell_voltage_raw" }.value.number)
        assertEquals(25.0, output.single { it.field.fieldKey == "battery_lowest_temp_raw" }.value.number)
        assertEquals(260.0, output.single { it.field.fieldKey == "tire_pressure_lf_raw" }.value.number)
        assertEquals(18.0, output.single { it.field.fieldKey == "outside_temp_c_raw" }.value.number)
        output.forEach { assertEquals(NormalizedQuality.OK, it.quality) }
    }

    @Test
    fun estimateSocFallsBackToRawIntValueWhenDecodedIsMissing() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(fieldsByKey.getValue("soc_estimate"))
        ).normalize(
            pollId = 52L,
            observedAt = "2026-06-14T12:00:01+03:00",
            readings = listOf(PollReading("statistic_1014_1134559272_5", "74"))
        )

        val soc = output.single()
        assertEquals(NormalizedQuality.OK, soc.quality)
        assertEquals(74.0, soc.value.number)
    }

    @Test
    fun booleanFieldsUseRawNumericValueWhenDecodedTextIsNonnumeric() {
        val output = VehicleStateNormalizer(
            catalog = listOf(NormalizedFieldCatalog.driverDoor)
        ).normalize(
            pollId = 55L,
            observedAt = "2026-06-14T12:00:04+03:00",
            readings = listOf(
                PollReading(
                    rawKey = "bodywork_left_hand_front_door",
                    rawValue = "1",
                    descValue = "some text"
                )
            )
        )

        val door = output.single()
        assertEquals(NormalizedQuality.OK, door.quality)
        assertEquals(true, door.value.bool)
    }

    @Test
    fun doorLockEnumPreservesLockedVsUnlockedSemantics() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val field = fieldsByKey.getValue("ota_lf_door_lock")

        val unlocked = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 62L,
            observedAt = "2026-06-22T12:00:01+03:00",
            readings = listOf(PollReading("ota_lf_door_lock", "1"))
        ).single()
        val locked = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 63L,
            observedAt = "2026-06-22T12:00:02+03:00",
            readings = listOf(PollReading("ota_lf_door_lock", "2"))
        ).single()
        val unknown = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 64L,
            observedAt = "2026-06-22T12:00:03+03:00",
            readings = listOf(PollReading("ota_lf_door_lock", "0"))
        ).single()
        val invalid = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 65L,
            observedAt = "2026-06-22T12:00:04+03:00",
            readings = listOf(PollReading("ota_lf_door_lock", "3"))
        ).single()

        assertEquals(NormalizedQuality.OK, unlocked.quality)
        assertEquals(false, unlocked.value.bool)
        assertEquals(NormalizedQuality.OK, locked.quality)
        assertEquals(true, locked.value.bool)
        assertEquals(NormalizedQuality.MISSING, unknown.quality)
        assertEquals(null, unknown.value.bool)
        assertEquals(NormalizedQuality.INVALID, invalid.quality)
        assertEquals(null, invalid.value.bool)
    }

    @Test
    fun invalidAndMissingDecodedPreferredValuesKeepExistingQualityBehavior() {
        val invalid = VehicleStateNormalizer(
            catalog = listOf(NormalizedFieldCatalog.speedKmh)
        ).normalize(
            pollId = 53L,
            observedAt = "2026-06-14T12:00:02+03:00",
            readings = listOf(
                PollReading(
                    rawKey = "speed_1013_-1807745016_7",
                    rawValue = "1101791232",
                    descValue = "not-a-number"
                )
            )
        ).single()

        val missing = VehicleStateNormalizer(
            catalog = listOf(NormalizedFieldCatalog.speedKmh)
        ).normalize(
            pollId = 54L,
            observedAt = "2026-06-14T12:00:03+03:00",
            readings = listOf(PollReading("speed_1013_-1807745016_7", null))
        ).single()

        assertEquals(NormalizedQuality.INVALID, invalid.quality)
        assertEquals(null, invalid.value.number)
        assertEquals(NormalizedQuality.MISSING, missing.quality)
        assertEquals(null, missing.value.number)
    }

    @Test
    fun batterySohNormalizesAsBatteryPercent() {
        val field = NormalizedFieldCatalog.fields.single { it.fieldKey == "battery_soh_percent" }
        val output = VehicleStateNormalizer(
            catalog = listOf(field)
        ).normalize(
            pollId = 60L,
            observedAt = "2026-06-19T12:00:00+03:00",
            readings = listOf(PollReading("statistic_1014_1145045032_5", "95", "95"))
        ).single()

        assertEquals(NormalizedCategory.BATTERY, field.category)
        assertEquals("%", field.unit)
        assertEquals("battery", field.deviceClass)
        assertEquals(listOf("statistic_1014_1145045032_5"), field.sourceKeys)
        assertEquals(NormalizedQuality.OK, output.quality)
        assertEquals(95.0, output.value.number)
    }

    @Test
    fun catalogVersionAndExpansionWaveExposeRepresentativeFields() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }

        assertEquals("normalized-direct-v6-20260622-soc-display", NormalizedFieldCatalog.CATALOG_VERSION)
        assertEquals(83, NormalizedFieldCatalog.fields.size)
        assertEquals(emptyList(), NormalizedFieldCatalog.fields.filter { field ->
            field.sourceKeys.any {
                it.startsWith("adas_") ||
                    it == "charging_1009_842006552_7" ||
                    it == "ac_cycle_mode" ||
                    it == "ac_wind_mode" ||
                    it == "wiper_front_wiper_level"
            }
        })
        assertEquals(NormalizedCategory.BATTERY, fieldsByKey.getValue("battery_soh_percent").category)
        assertEquals(listOf("statistic_1014_1145045040_5"), fieldsByKey.getValue("soc").sourceKeys)
        assertEquals(listOf("statistic_1014_1134559272_5"), fieldsByKey.getValue("soc_estimate").sourceKeys)
        assertEquals(listOf("ota_battery_voltage"), fieldsByKey.getValue("aux_voltage_v").sourceKeys)
        assertEquals(listOf("charging_charge_battery_volt"), fieldsByKey.getValue("hv_battery_voltage_v").sourceKeys)
        assertEquals(
            listOf("charging_charging_charge_current_not_convert"),
            fieldsByKey.getValue("hv_battery_current_a").sourceKeys
        )
        assertEquals("binary_sensor", fieldsByKey.getValue("left_rear_door_open").entityPlatform)
        assertEquals("sensor", fieldsByKey.getValue("front_motor_torque").entityPlatform)
        assertEquals(listOf("tyre_1016_-1728052952_5"), fieldsByKey.getValue("tire_pressure_rf_raw").sourceKeys)
        assertEquals("door_lock_state_locked", fieldsByKey.getValue("ota_lf_door_lock").normalizerId)
        assertEquals(null, fieldsByKey.getValue("low_voltage_warning_raw").unit)
        assertEquals(null, fieldsByKey.getValue("low_voltage_warning_raw").deviceClass)
        assertEquals(null, fieldsByKey.getValue("low_voltage_warning_raw").stateClass)
        assertFalse(fieldsByKey.getValue("radar_1025_neg_1728053151_5").mqttDefaultEnabled)
    }

    @Test
    fun rawEnumAndDriverAssistFieldsAreNotMqttDefaultEnabled() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }

        listOf(
            "gear_auto_mode_raw",
            "tyre_state_lf",
            "tyre_state_rf",
            "tyre_state_lr",
            "tyre_state_rr",
            "low_voltage_warning_raw",
            "radar_1025_neg_1728053151_5"
        ).forEach { fieldKey ->
            assertFalse(fieldsByKey.getValue(fieldKey).mqttDefaultEnabled, fieldKey)
        }
    }

    @Test
    fun catalogFieldKeysAreUnique() {
        val duplicateKeys = NormalizedFieldCatalog.fields
            .groupBy { it.fieldKey }
            .filterValues { it.size > 1 }
            .keys

        assertEquals(emptySet(), duplicateKeys)
    }

    @Test
    fun usesFallbackSourceWhenPrimaryIsMissing() {
        val output = VehicleStateNormalizer(
            catalog = listOf(syntheticPercentField())
        ).normalize(
            pollId = 43L,
            observedAt = "2026-06-12T12:00:01+03:00",
            readings = listOf(PollReading("soc_backup", "71"))
        )

        val soc = output.single()
        assertEquals("soc_backup", soc.sourceKey)
        assertEquals(71.0, soc.value.number)
    }

    @Test
    fun usesFallbackSourceWhenPrimaryIsInvalid() {
        val output = VehicleStateNormalizer(
            catalog = listOf(syntheticPercentField())
        ).normalize(
            pollId = 44L,
            observedAt = "2026-06-12T12:00:02+03:00",
            readings = listOf(
                PollReading("soc_primary", "255"),
                PollReading("soc_backup", "71")
            )
        )

        val soc = output.single()
        assertEquals(NormalizedQuality.OK, soc.quality)
        assertEquals("soc_backup", soc.sourceKey)
        assertEquals(71.0, soc.value.number)
    }

    @Test
    fun invalidPercentBecomesInvalidQuality() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(fieldsByKey.getValue("soc"))
        ).normalize(
            pollId = 45L,
            observedAt = "2026-06-12T12:00:03+03:00",
            readings = listOf(PollReading("statistic_1014_1145045040_5", "255"))
        )

        val soc = output.single()
        assertEquals(NormalizedQuality.INVALID, soc.quality)
        assertEquals(null, soc.value.number)
    }

    @Test
    fun allInvalidPresentSourcesKeepFirstPresentSourceKey() {
        val output = VehicleStateNormalizer(
            catalog = listOf(syntheticPercentField())
        ).normalize(
            pollId = 46L,
            observedAt = "2026-06-12T12:00:04+03:00",
            readings = listOf(
                PollReading("soc_primary", "255"),
                PollReading("soc_backup", "not-a-number")
            )
        )

        val soc = output.single()
        assertEquals(NormalizedQuality.INVALID, soc.quality)
        assertEquals("soc_primary", soc.sourceKey)
        assertEquals(null, soc.value.number)
    }

    @Test
    fun knownSourceWithNullRawValueBecomesMissingQuality() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(fieldsByKey.getValue("soc_estimate"))
        ).normalize(
            pollId = 47L,
            observedAt = "2026-06-12T12:00:05+03:00",
            readings = listOf(PollReading("statistic_1014_1134559272_5", null))
        )

        val soc = output.single()
        assertEquals(NormalizedQuality.MISSING, soc.quality)
        assertEquals("statistic_1014_1134559272_5", soc.sourceKey)
        assertEquals(null, soc.value.number)
    }

    @Test
    fun missingSourceProducesMissingQuality() {
        val output = VehicleStateNormalizer(
            catalog = listOf(NormalizedFieldCatalog.driverDoor)
        ).normalize(
            pollId = 48L,
            observedAt = "2026-06-12T12:00:06+03:00",
            readings = emptyList()
        )

        val door = output.single()
        assertEquals(NormalizedQuality.MISSING, door.quality)
        assertNotNull(door.field.sourceKeys)
    }

    @Test
    fun observationSemanticKeySeparatesMissingAndInvalidEmptyValues() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val missing = VehicleStateNormalizer(
            catalog = listOf(fieldsByKey.getValue("soc_estimate"))
        ).normalize(
            pollId = 49L,
            observedAt = "2026-06-12T12:00:07+03:00",
            readings = listOf(PollReading("statistic_1014_1134559272_5", null))
        ).single()

        val invalid = VehicleStateNormalizer(
            catalog = listOf(fieldsByKey.getValue("soc_estimate"))
        ).normalize(
            pollId = 50L,
            observedAt = "2026-06-12T12:00:08+03:00",
            readings = listOf(PollReading("statistic_1014_1134559272_5", "255"))
        ).single()

        assertEquals("null", missing.value.semanticKey())
        assertEquals("null", invalid.value.semanticKey())
        assertNotEquals(missing.semanticKey(), invalid.semanticKey())
    }

    @Test
    fun derivesSignedHvBatteryPowerAndChargeDischargeSplit() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(
                fieldsByKey.getValue("battery_power_kw"),
                fieldsByKey.getValue("battery_charge_power_kw"),
                fieldsByKey.getValue("battery_discharge_power_kw")
            )
        ).normalize(
            pollId = 61L,
            observedAt = "2026-06-22T12:00:00+03:00",
            readings = listOf(
                PollReading("charging_charge_battery_volt", "560", "560"),
                PollReading("charging_charging_charge_current_not_convert", "640", "640")
            )
        )

        assertEquals(358.4, output.single { it.field.fieldKey == "battery_power_kw" }.value.number)
        assertEquals(0.0, output.single { it.field.fieldKey == "battery_charge_power_kw" }.value.number)
        assertEquals(358.4, output.single { it.field.fieldKey == "battery_discharge_power_kw" }.value.number)
        output.forEach {
            assertEquals(NormalizedQuality.OK, it.quality)
            assertEquals(
                "charging_charge_battery_volt+charging_charging_charge_current_not_convert",
                it.sourceKey
            )
        }
    }

    @Test
    fun derivedHvBatteryPowerSeparatesMissingAndInvalidInputs() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val field = fieldsByKey.getValue("battery_power_kw")

        val missing = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 66L,
            observedAt = "2026-06-22T12:00:05+03:00",
            readings = listOf(PollReading("charging_charge_battery_volt", "560", "560"))
        ).single()
        val invalidText = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 67L,
            observedAt = "2026-06-22T12:00:06+03:00",
            readings = listOf(
                PollReading("charging_charge_battery_volt", "560", "560"),
                PollReading("charging_charging_charge_current_not_convert", "bad", "bad")
            )
        ).single()
        val missingDecodedFloat = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 68L,
            observedAt = "2026-06-22T12:00:07+03:00",
            readings = listOf(
                PollReading("charging_charge_battery_volt", "560", "560"),
                PollReading(
                    rawKey = "charging_charging_charge_current_not_convert",
                    rawValue = java.lang.Float.floatToIntBits(640f).toString(),
                    descValue = null
                )
            )
        ).single()

        assertEquals(NormalizedQuality.MISSING, missing.quality)
        assertEquals(NormalizedQuality.INVALID, invalidText.quality)
        assertEquals(NormalizedQuality.INVALID, missingDecodedFloat.quality)
    }

    private fun syntheticPercentField(): NormalizedFieldDefinition {
        return NormalizedFieldDefinition(
            fieldKey = "synthetic_soc",
            category = NormalizedCategory.BATTERY,
            valueType = NormalizedValueType.NUMBER,
            unit = "%",
            displayName = "Synthetic SOC",
            deviceClass = "battery",
            stateClass = "measurement",
            entityPlatform = "sensor",
            sourceKeys = listOf("soc_primary", "soc_backup"),
            normalizerId = "decoded_percent_0_100"
        )
    }
}
