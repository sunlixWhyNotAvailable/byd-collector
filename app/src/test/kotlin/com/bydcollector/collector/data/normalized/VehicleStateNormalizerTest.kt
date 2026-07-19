package com.bydcollector.collector.data.normalized

import com.bydcollector.collector.data.local.PollReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
            catalog = listOf(fieldsByKey.getValue("charge_current_a"))
        ).normalize(
            pollId = 56L,
            observedAt = "2026-06-15T12:00:00+03:00",
            readings = listOf(
                PollReading(
                    rawKey = "charging_charge_current",
                    rawValue = java.lang.Float.floatToIntBits(81.5f).toString(),
                    descValue = "81.5"
                )
            )
        )

        val chargeCurrent = output.single()
        assertEquals(NormalizedQuality.OK, chargeCurrent.quality)
        assertEquals(81.5, chargeCurrent.value.number)
        assertEquals("charging_charge_current", chargeCurrent.sourceKey)
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
    fun openApiEnumsProduceStableSemanticText() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val output = VehicleStateNormalizer(
            catalog = listOf(
                fieldsByKey.getValue("gear_auto_mode_raw"),
                fieldsByKey.getValue("charging_state"),
                fieldsByKey.getValue("tyre_state_lf")
            )
        ).normalize(
            pollId = 69L,
            observedAt = "2026-07-10T12:00:00+03:00",
            readings = listOf(
                PollReading("gearbox_1011_555745336_5", "4"),
                PollReading("charging_1009_1231032336_5", "3"),
                PollReading("tyre_1016_-1728052957_5", "2")
            )
        )

        assertEquals("D", output.single { it.field.fieldKey == "gear_auto_mode_raw" }.value.text)
        assertEquals("discharging", output.single { it.field.fieldKey == "charging_state" }.value.text)
        assertEquals("underpressure", output.single { it.field.fieldKey == "tyre_state_lf" }.value.text)
        output.forEach {
            assertEquals(NormalizedValueType.TEXT, it.value.type)
            assertEquals(NormalizedQuality.OK, it.quality)
        }
    }

    @Test
    fun openApiConnectionStatesAndFanLevelRejectUnknownValues() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val fields = listOf(
            fieldsByKey.getValue("charge_gun_connected_raw"),
            fieldsByKey.getValue("charger_connected_raw"),
            fieldsByKey.getValue("ac_wind_level_raw")
        )
        val valid = VehicleStateNormalizer(catalog = fields).normalize(
            pollId = 70L,
            observedAt = "2026-07-10T12:00:01+03:00",
            readings = listOf(
                PollReading("charging_1009_876609586_5", "1"),
                PollReading("charging_1009_89128973_5", "1"),
                PollReading("ac_wind_level", "7")
            )
        )
        val invalid = VehicleStateNormalizer(catalog = fields).normalize(
            pollId = 71L,
            observedAt = "2026-07-10T12:00:02+03:00",
            readings = listOf(
                PollReading("charging_1009_876609586_5", "0"),
                PollReading("charging_1009_89128973_5", "2"),
                PollReading("ac_wind_level", "8")
            )
        )

        assertEquals(false, valid.single { it.field.fieldKey == "charge_gun_connected_raw" }.value.bool)
        assertEquals(true, valid.single { it.field.fieldKey == "charger_connected_raw" }.value.bool)
        assertEquals(7.0, valid.single { it.field.fieldKey == "ac_wind_level_raw" }.value.number)
        valid.forEach { assertEquals(NormalizedQuality.OK, it.quality) }
        invalid.forEach { assertEquals(NormalizedQuality.INVALID, it.quality) }
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
    fun normalizesInternalSocAndEnergyWithoutAssumingMonotonicCounters() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val fields = listOf(
            fieldsByKey.getValue("soc_internal"),
            fieldsByKey.getValue("battery_remaining_energy_kwh"),
            fieldsByKey.getValue("trip_energy_kwh"),
            fieldsByKey.getValue("cumulative_energy_kwh")
        )
        val output = VehicleStateNormalizer(catalog = fields).normalize(
            pollId = 72L,
            observedAt = "2026-07-19T12:00:00+03:00",
            readings = listOf(
                PollReading("statistic_remaining_battery_power", "827", "82.7"),
                PollReading("power_battery_remain_electricity", java.lang.Float.floatToIntBits(51.25f).toString(), "51.25"),
                PollReading("statistic_statistic_this_trip_total_elec_consumption", java.lang.Float.floatToIntBits(-0.75f).toString(), "-0.75"),
                PollReading("statistic_total_elec_consumption", java.lang.Float.floatToIntBits(-4.5f).toString(), "-4.5")
            )
        )

        assertEquals(82.7, output.single { it.field.fieldKey == "soc_internal" }.value.number)
        assertEquals(51.25, output.single { it.field.fieldKey == "battery_remaining_energy_kwh" }.value.number)
        assertEquals(-0.75, output.single { it.field.fieldKey == "trip_energy_kwh" }.value.number)
        assertEquals(-4.5, output.single { it.field.fieldKey == "cumulative_energy_kwh" }.value.number)
        output.forEach { assertEquals(NormalizedQuality.OK, it.quality) }

        val invalid = VehicleStateNormalizer(catalog = fields.take(2)).normalize(
            pollId = 73L,
            observedAt = "2026-07-19T12:00:01+03:00",
            readings = listOf(
                PollReading("statistic_remaining_battery_power", "1001", "100.1"),
                PollReading("power_battery_remain_electricity", java.lang.Float.floatToIntBits(-1f).toString(), "-1")
            )
        )
        invalid.forEach { assertEquals(NormalizedQuality.INVALID, it.quality) }
    }

    @Test
    fun catalogVersionAndExpansionWaveExposeRepresentativeFields() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }

        assertEquals("normalized-direct-v10-20260719-energy-soc", NormalizedFieldCatalog.CATALOG_VERSION)
        assertEquals(81, NormalizedFieldCatalog.fields.size)
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
        assertTrue(fieldsByKey.containsKey("charge_current_a"))
        assertEquals(
            listOf("charging_charge_current"),
            fieldsByKey.getValue("charge_current_a").sourceKeys
        )
        assertFalse(fieldsByKey.containsKey("hv_battery_current_a"))
        assertEquals(
            listOf("charging_charge_battery_volt", "charging_charge_current"),
            fieldsByKey.getValue("battery_power_kw").sourceKeys
        )
        assertEquals("binary_sensor", fieldsByKey.getValue("left_rear_door_open").entityPlatform)
        assertEquals("sensor", fieldsByKey.getValue("front_motor_torque").entityPlatform)
        assertEquals(listOf("tyre_1016_-1728052952_5"), fieldsByKey.getValue("tire_pressure_rf_raw").sourceKeys)
        assertEquals("door_lock_state_locked", fieldsByKey.getValue("ota_lf_door_lock").normalizerId)
        assertFalse(fieldsByKey.containsKey("low_voltage_warning_raw"))
        assertFalse(fieldsByKey.containsKey("remaining_battery_power_raw"))
        assertFalse(fieldsByKey.containsKey("max_charge_current_allow_raw"))
        assertFalse(fieldsByKey.getValue("radar_1025_neg_1728053151_5").mqttDefaultEnabled)

        val socInternal = fieldsByKey.getValue("soc_internal")
        assertEquals(NormalizedCategory.BATTERY, socInternal.category)
        assertEquals("%", socInternal.unit)
        assertEquals("battery", socInternal.deviceClass)
        assertEquals("measurement", socInternal.stateClass)
        assertEquals(listOf("statistic_remaining_battery_power"), socInternal.sourceKeys)
        assertEquals("decoded_percent_0_100", socInternal.normalizerId)

        val remainingEnergy = fieldsByKey.getValue("battery_remaining_energy_kwh")
        assertEquals("kWh", remainingEnergy.unit)
        assertEquals("energy_storage", remainingEnergy.deviceClass)
        assertEquals("measurement", remainingEnergy.stateClass)
        assertEquals(listOf("power_battery_remain_electricity"), remainingEnergy.sourceKeys)
        assertEquals("decoded_number_non_negative", remainingEnergy.normalizerId)

        val tripEnergy = fieldsByKey.getValue("trip_energy_kwh")
        assertEquals("energy", tripEnergy.deviceClass)
        assertEquals(null, tripEnergy.stateClass)
        assertEquals("decoded_number_raw", tripEnergy.normalizerId)

        val cumulativeEnergy = fieldsByKey.getValue("cumulative_energy_kwh")
        assertEquals("energy", cumulativeEnergy.deviceClass)
        assertEquals("total", cumulativeEnergy.stateClass)
        assertEquals("decoded_number_raw", cumulativeEnergy.normalizerId)

        listOf(socInternal, remainingEnergy, tripEnergy, cumulativeEnergy).forEach {
            assertEquals(NormalizedCategory.BATTERY, it.category)
            assertTrue(it.mqttDefaultEnabled)
        }
    }

    @Test
    fun semanticEnumAndDriverAssistFieldsAreNotMqttDefaultEnabled() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }

        listOf(
            "gear_auto_mode_raw",
            "tyre_state_lf",
            "tyre_state_rf",
            "tyre_state_lr",
            "tyre_state_rr",
            "radar_1025_neg_1728053151_5"
        ).forEach { fieldKey ->
            assertFalse(fieldsByKey.getValue(fieldKey).mqttDefaultEnabled, fieldKey)
        }
        assertEquals(NormalizedValueType.TEXT, fieldsByKey.getValue("gear_auto_mode_raw").valueType)
        assertEquals(NormalizedValueType.TEXT, fieldsByKey.getValue("charging_state").valueType)
        assertEquals(NormalizedValueType.TEXT, fieldsByKey.getValue("tyre_state_lf").valueType)
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
    fun catalogDoesNotExportKnownRedundantOrDuplicateFields() {
        val fieldKeys = NormalizedFieldCatalog.fields.map { it.fieldKey }

        assertFalse(fieldKeys.contains("max_charge_power_allow_raw"))
        assertFalse(fieldKeys.contains("lr_door_lock_raw"))
        assertFalse(fieldKeys.contains("rr_door_lock_raw"))
        assertTrue(fieldKeys.contains("ota_lf_door_lock"))
        assertTrue(fieldKeys.contains("rf_door_lock_raw"))
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
                PollReading(
                    "charging_charge_current",
                    java.lang.Float.floatToIntBits(640f).toString(),
                    "640"
                )
            )
        )

        assertEquals(358.4, output.single { it.field.fieldKey == "battery_power_kw" }.value.number)
        assertEquals(0.0, output.single { it.field.fieldKey == "battery_charge_power_kw" }.value.number)
        assertEquals(358.4, output.single { it.field.fieldKey == "battery_discharge_power_kw" }.value.number)
        output.forEach {
            assertEquals(NormalizedQuality.OK, it.quality)
            assertEquals(
                "charging_charge_battery_volt+charging_charge_current",
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
                PollReading("charging_charge_current", "bad", "bad")
            )
        ).single()
        val missingDecodedFloat = VehicleStateNormalizer(catalog = listOf(field)).normalize(
            pollId = 68L,
            observedAt = "2026-06-22T12:00:07+03:00",
            readings = listOf(
                PollReading("charging_charge_battery_volt", "560", "560"),
                PollReading(
                    rawKey = "charging_charge_current",
                    rawValue = java.lang.Float.floatToIntBits(640f).toString(),
                    descValue = null
                )
            )
        ).single()

        assertEquals(NormalizedQuality.MISSING, missing.quality)
        assertEquals(NormalizedQuality.INVALID, invalidText.quality)
        assertEquals(NormalizedQuality.INVALID, missingDecodedFloat.quality)
        assertEquals(null, missingDecodedFloat.value.number)
        assertEquals("raw_float_without_decoded_desc", missingDecodedFloat.reason)
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
