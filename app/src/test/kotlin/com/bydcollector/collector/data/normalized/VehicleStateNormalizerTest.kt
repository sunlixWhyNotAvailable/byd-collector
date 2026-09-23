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
                fieldsByKey.getValue("tyre_state_lf")
            )
        ).normalize(
            pollId = 69L,
            observedAt = "2026-07-10T12:00:00+03:00",
            readings = listOf(
                PollReading("gearbox_1011_555745336_5", "4"),
                PollReading("tyre_1016_-1728052957_5", "2")
            )
        )

        assertEquals("D", output.single { it.field.fieldKey == "gear_auto_mode_raw" }.value.text)
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
    fun chargingEnumsExposeExactStableTextAndRejectUnknownCodes() {
        val cases = listOf(
            Triple(NormalizedFieldCatalog.chargingGunType, "charging_1009_876609586_5", mapOf(1 to "none", 2 to "ac", 3 to "dc", 4 to "ac_dc", 5 to "vtol")),
            Triple(NormalizedFieldCatalog.chargingType, "charging_1009_876609592_5", mapOf(1 to "default", 2 to "ac", 3 to "vtog", 4 to "gb_dc", 5 to "gb_non_dc")),
            Triple(
                NormalizedFieldCatalog.chargingBatteryDeviceState,
                "charging_1009_876609560_5",
                mapOf(
                    0 to "ready",
                    1 to "charging",
                    2 to "finished",
                    3 to "discharg",
                    4 to "charg_terminate",
                    5 to "breakdown_c10",
                    6 to "breakdown_charging_gun",
                    7 to "breakdown_charger",
                    8 to "breakdown_ac",
                    9 to "schedule",
                    10 to "discharg_cbu",
                    11 to "timeout",
                    12 to "discharg_finish",
                    13 to "charging_pause"
                )
            )
        )

        cases.forEach { (field, sourceKey, mapping) ->
            mapping.forEach { (raw, expected) ->
                val observation = VehicleStateNormalizer(catalog = listOf(field)).normalize(
                    pollId = 80L,
                    observedAt = "2026-09-08T12:00:00+03:00",
                    readings = listOf(PollReading(sourceKey, raw.toString()))
                ).single()
                assertEquals(NormalizedQuality.OK, observation.quality, "${field.fieldKey}:$raw")
                assertEquals(expected, observation.value.text, "${field.fieldKey}:$raw")
                assertEquals("2026-09-08T12:00:00+03:00", observation.observedAt)
            }
            listOf(14, 15, 255).filterNot(mapping::containsKey).forEach { raw ->
                val observation = VehicleStateNormalizer(catalog = listOf(field)).normalize(
                    pollId = 81L,
                    observedAt = "2026-09-08T12:00:01+03:00",
                    readings = listOf(PollReading(sourceKey, raw.toString()))
                ).single()
                assertEquals(NormalizedQuality.INVALID, observation.quality, "${field.fieldKey}:$raw")
                assertEquals(null, observation.value.text)
            }
        }
    }

    @Test
    fun chargingEtaUsesSamePollPairWithoutDayWrapAndRecoversAfterInvalidInput() {
        val field = NormalizedFieldCatalog.chargingTimeRemaining
        val normalizer = VehicleStateNormalizer(catalog = listOf(field))
        val hourKey = field.sourceKeys[0]
        val minuteKey = field.sourceKeys[1]
        val validCases = listOf(
            0 to (0 to "00:00:00"),
            9 to (17 to "09:17:00"),
            36 to (50 to "36:50:00"),
            100 to (5 to "100:05:00")
        )

        validCases.forEachIndexed { index, (hours, minuteAndExpected) ->
            val (minutes, expected) = minuteAndExpected
            val observation = normalizer.normalize(
                pollId = 90L + index,
                observedAt = "2026-09-08T12:00:0$index+03:00",
                readings = listOf(PollReading(hourKey, hours.toString()), PollReading(minuteKey, minutes.toString()))
            ).single()
            assertEquals(NormalizedQuality.OK, observation.quality)
            assertEquals(expected, observation.value.text)
            assertEquals("$hourKey+$minuteKey", observation.sourceKey)
        }

        val invalidCases = listOf(
            listOf(PollReading(hourKey, "255"), PollReading(minuteKey, "17")) to NormalizedQuality.INVALID,
            listOf(PollReading(hourKey, "9"), PollReading(minuteKey, "60")) to NormalizedQuality.INVALID,
            listOf(PollReading(hourKey, "bad"), PollReading(minuteKey, "17")) to NormalizedQuality.INVALID,
            listOf(PollReading(hourKey, "9")) to NormalizedQuality.MISSING,
            listOf(PollReading(hourKey, "9"), PollReading(minuteKey, null)) to NormalizedQuality.MISSING
        )
        invalidCases.forEachIndexed { index, (readings, quality) ->
            val observation = normalizer.normalize(
                pollId = 100L + index,
                observedAt = "2026-09-08T12:01:0$index+03:00",
                readings = readings
            ).single()
            assertEquals(quality, observation.quality)
            assertEquals(null, observation.value.text)
        }

        val recovered = normalizer.normalize(
            pollId = 110L,
            observedAt = "2026-09-08T12:02:00+03:00",
            readings = listOf(PollReading(hourKey, "9"), PollReading(minuteKey, "17"))
        ).single()
        assertEquals(NormalizedQuality.OK, recovered.quality)
        assertEquals("09:17:00", recovered.value.text)
    }

    @Test
    fun step3PercentagesAndInstallationFlagsUseStrictFieldSpecificValidation() {
        val fields = listOf(
            NormalizedFieldCatalog.rightFrontWindowPercent,
            NormalizedFieldCatalog.perfume1RemainingPercent,
            NormalizedFieldCatalog.perfume2RemainingPercent,
            NormalizedFieldCatalog.perfume3RemainingPercent,
            NormalizedFieldCatalog.perfume1Installed,
            NormalizedFieldCatalog.perfume2Installed,
            NormalizedFieldCatalog.perfume3Installed
        )
        val observedAt = "2026-09-08T13:00:00+03:00"
        val output = VehicleStateNormalizer(catalog = fields).normalize(
            pollId = 120L,
            observedAt = observedAt,
            readings = listOf(
                PollReading("bodywork_1001_1267728400_5", "42", "42"),
                PollReading("ac_1000_1242562584_5", "79", "79"),
                PollReading("ac_1000_1242562592_5", "81", "81"),
                PollReading("ac_1000_1242562600_5", "83", "83"),
                PollReading("ac_1000_1242562612_5", "0"),
                PollReading("ac_1000_1242562614_5", "1"),
                PollReading("ac_1000_1242562616_5", "0")
            )
        ).associateBy { it.field.fieldKey }

        assertEquals(42.0, output.getValue("rf_window_percent").value.number)
        assertEquals(79.0, output.getValue("perfume_1_remaining_percent").value.number)
        assertEquals(81.0, output.getValue("perfume_2_remaining_percent").value.number)
        assertEquals(83.0, output.getValue("perfume_3_remaining_percent").value.number)
        assertEquals(false, output.getValue("perfume_1_installed").value.bool)
        assertEquals(true, output.getValue("perfume_2_installed").value.bool)
        assertEquals(false, output.getValue("perfume_3_installed").value.bool)
        output.values.forEach {
            assertEquals(NormalizedQuality.OK, it.quality)
            assertEquals(observedAt, it.observedAt)
        }

        val invalid = VehicleStateNormalizer(
            catalog = listOf(
                NormalizedFieldCatalog.rightFrontWindowPercent,
                NormalizedFieldCatalog.perfume1RemainingPercent,
                NormalizedFieldCatalog.perfume1Installed
            )
        ).normalize(
            pollId = 121L,
            observedAt = observedAt,
            readings = listOf(
                PollReading("bodywork_1001_1267728400_5", "101"),
                PollReading("ac_1000_1242562584_5", "255"),
                PollReading("ac_1000_1242562612_5", "2")
            )
        )
        invalid.forEach { observation ->
            assertEquals(NormalizedQuality.INVALID, observation.quality)
            assertEquals(null, observation.value.number)
            assertEquals(null, observation.value.bool)
        }
    }

    @Test
    fun step3MotorRawValuesPreserveSignedNumbersWithoutUnitsOrScaling() {
        val fields = listOf(
            NormalizedFieldCatalog.frontMotorCurrentRaw,
            NormalizedFieldCatalog.rearMotorCurrentRaw
        )
        val observedAt = "2026-09-08T13:01:00+03:00"
        val output = VehicleStateNormalizer(catalog = fields).normalize(
            pollId = 122L,
            observedAt = observedAt,
            readings = listOf(
                PollReading("charging_1009_1186988040_7", java.lang.Float.floatToRawIntBits(-1.0f).toString(), "-1"),
                PollReading("charging_1009_1186988056_7", java.lang.Float.floatToRawIntBits(-226.7f).toString(), "-226.7")
            )
        ).associateBy { it.field.fieldKey }

        assertEquals(-1.0, output.getValue("front_motor_current_raw").value.number)
        assertEquals(-226.7, output.getValue("rear_motor_current_raw").value.number)
        output.values.forEach {
            assertEquals(NormalizedQuality.OK, it.quality)
            assertEquals(observedAt, it.observedAt)
        }

        val rawBitsOnly = VehicleStateNormalizer(catalog = listOf(fields.first())).normalize(
            pollId = 123L,
            observedAt = observedAt,
            readings = listOf(
                PollReading("charging_1009_1186988040_7", java.lang.Float.floatToRawIntBits(126.9f).toString())
            )
        ).single()
        assertEquals(NormalizedQuality.INVALID, rawBitsOnly.quality)
        assertEquals(null, rawBitsOnly.value.number)
    }

    @Test
    fun catalogVersionAndExpansionWaveExposeRepresentativeFields() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }

        assertEquals("normalized-direct-v15-20260914-energy", NormalizedFieldCatalog.CATALOG_VERSION)
        assertEquals(105, NormalizedFieldCatalog.fields.size)
        assertFalse(fieldsByKey.containsKey("charging_state"))
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
        listOf("charging_gun_type", "charging_type", "charging_battery_device_state", "charging_time_remaining").forEach { key ->
            val field = fieldsByKey.getValue(key)
            assertEquals(NormalizedCategory.BATTERY, field.category)
            assertEquals(NormalizedValueType.TEXT, field.valueType)
            assertTrue(field.mqttDefaultEnabled)
        }
        assertEquals(NormalizedValueType.BOOLEAN, fieldsByKey.getValue("charge_gun_connected_raw").valueType)
        assertEquals(NormalizedValueType.BOOLEAN, fieldsByKey.getValue("rf_window_open_raw").valueType)
        assertEquals(listOf("bodywork_1001_1267728400_5"), fieldsByKey.getValue("rf_window_percent").sourceKeys)
        listOf("rf_window_percent", "perfume_1_remaining_percent", "perfume_2_remaining_percent", "perfume_3_remaining_percent").forEach { key ->
            val field = fieldsByKey.getValue(key)
            assertEquals(NormalizedValueType.NUMBER, field.valueType)
            assertEquals("%", field.unit)
            assertTrue(field.mqttDefaultEnabled)
        }
        listOf("perfume_1_installed", "perfume_2_installed", "perfume_3_installed").forEach { key ->
            val field = fieldsByKey.getValue(key)
            assertEquals(NormalizedCategory.CLIMATE, field.category)
            assertEquals(NormalizedValueType.BOOLEAN, field.valueType)
            assertEquals("strict_binary_flag", field.normalizerId)
            assertTrue(field.mqttDefaultEnabled)
        }
        listOf("front_motor_current_raw", "rear_motor_current_raw").forEach { key ->
            val field = fieldsByKey.getValue(key)
            assertEquals(NormalizedCategory.MOTION, field.category)
            assertEquals(NormalizedValueType.NUMBER, field.valueType)
            assertEquals(null, field.unit)
            assertEquals(null, field.deviceClass)
            assertEquals(null, field.stateClass)
            assertTrue(field.mqttDefaultEnabled)
        }

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
        assertEquals(NormalizedValueType.TEXT, fieldsByKey.getValue("tyre_state_lf").valueType)
    }

    @Test
    fun rawSemanticCorrectionsExposeUnitlessNumericFieldsWithoutDescriptionScaling() {
        val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }
        val maxDischarge = fieldsByKey.getValue("max_discharge_power_allow_raw")
        val sunroof = fieldsByKey.getValue("bodywork_sunroof_windoblind_position")

        assertEquals(NormalizedValueType.NUMBER, maxDischarge.valueType)
        assertEquals(null, maxDischarge.unit)
        assertEquals(null, maxDischarge.deviceClass)
        assertEquals(null, maxDischarge.stateClass)
        assertEquals("number_raw", maxDischarge.normalizerId)
        assertTrue(maxDischarge.mqttDefaultEnabled)
        assertEquals(NormalizedValueType.NUMBER, sunroof.valueType)
        assertEquals(null, sunroof.unit)
        assertEquals(null, sunroof.deviceClass)
        assertEquals(null, sunroof.stateClass)
        assertEquals("sensor", sunroof.entityPlatform)
        assertEquals("raw_integer_enum_0_1_2_4", sunroof.normalizerId)
        assertTrue(sunroof.mqttDefaultEnabled)

        val output = VehicleStateNormalizer(catalog = listOf(maxDischarge, sunroof)).normalize(
            pollId = 74L,
            observedAt = "2026-08-30T12:00:00+03:00",
            readings = listOf(
                PollReading("statistic_1014_877658120_5", "123", "1.23"),
                PollReading("bodywork_sunroof_windoblind_position", "4", "open")
            )
        )

        assertEquals(123.0, output.single { it.field == maxDischarge }.value.number)
        assertEquals(4.0, output.single { it.field == sunroof }.value.number)
        output.forEach { assertEquals(NormalizedQuality.OK, it.quality) }
    }

    @Test
    fun sunroofRejectsUnknownOrNonIntegerCodesWithoutInventingLabels() {
        val field = NormalizedFieldCatalog.sunroofPosition
        listOf("3", "open", "1.5").forEachIndexed { index, raw ->
            val output = VehicleStateNormalizer(catalog = listOf(field)).normalize(
                pollId = 75L + index,
                observedAt = "2026-08-30T12:00:0${index + 1}+03:00",
                readings = listOf(PollReading(field.sourceKeys.single(), raw, "open"))
            ).single()
            assertEquals(NormalizedQuality.INVALID, output.quality)
            assertEquals(null, output.value.number)
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

    @Test
    fun sparseNormalizationUsesSelectedScalarSourceClockAndOmitsUnrelatedFields() {
        val field = syntheticPercentField()
        val inputs = mapOf(
            "soc_primary" to sourceInput("soc_primary", "80", 2_000, pollId = 2),
            "soc_backup" to sourceInput("soc_backup", "75", 1_000, pollId = 1)
        )

        val output = VehicleStateNormalizer(catalog = listOf(field, NormalizedFieldCatalog.speedKmh))
            .normalizeSparse(inputs, setOf("soc_backup"))

        assertEquals(1, output.size)
        assertEquals(80.0, output.single().value.number)
        assertEquals("soc_primary", output.single().sourceKey)
        assertEquals("1970-01-01T00:00:02Z", output.single().observedAt)
        assertEquals(2L, output.single().sourcePollId)
        assertEquals(inputs.getValue("soc_primary").stamp, output.single().sourceStamp)
    }

    @Test
    fun sparseCompositeUsesOldestRequiredContributorClockAndNoMixedPollId() {
        val inputs = mapOf(
            "charging_1009_1146095640_5" to sourceInput(
                "charging_1009_1146095640_5", "2", 10_000, pollId = 9
            ),
            "charging_1009_1146095648_5" to sourceInput(
                "charging_1009_1146095648_5", "5", 8_000, pollId = null
            )
        )

        val output = VehicleStateNormalizer(catalog = listOf(NormalizedFieldCatalog.chargingTimeRemaining))
            .normalizeSparse(inputs, setOf("charging_1009_1146095640_5"))
            .single()

        assertEquals("02:05:00", output.value.text)
        assertEquals("1970-01-01T00:00:08Z", output.observedAt)
        assertEquals(null, output.sourcePollId)
        assertEquals(8_000L, output.sourceStamp?.elapsedMs)
    }

    @Test
    fun sparseIndexPreservesCatalogOrderAndFallbacksWithoutTraversingUnrelatedInputs() {
        val first = syntheticPercentField().copy(fieldKey = "first")
        val second = first.copy(fieldKey = "second", sourceKeys = listOf("soc_backup"))
        val backing = mapOf(
            "soc_primary" to sourceInput("soc_primary", "bad", 2_000, 2),
            "soc_backup" to sourceInput("soc_backup", "75", 1_000, 1)
        )
        val inputs = object : Map<String, NormalizedSourceInput> by backing {
            override val entries: Set<Map.Entry<String, NormalizedSourceInput>>
                get() = error("sparse normalization must not traverse the full input cache")
        }
        val normalizer = VehicleStateNormalizer(listOf(second, NormalizedFieldCatalog.speedKmh, first))
        val result = normalizer.normalizeSparse(inputs, linkedSetOf("soc_primary", "soc_backup", "unknown"))
        assertEquals(listOf("second", "first"), result.map { it.field.fieldKey })
        assertEquals(listOf(75.0, 75.0), result.map { it.value.number })
        assertEquals(listOf("soc_backup", "soc_backup"), result.map { it.sourceKey })
        assertEquals(listOf(1_000L, 1_000L), result.map { it.sourceStamp?.elapsedMs })
        assertEquals(emptyList<NormalizedObservation>(), normalizer.normalizeSparse(inputs, setOf("unknown")))
    }

    private fun sourceInput(key: String, value: String, wallMs: Long, pollId: Long?): NormalizedSourceInput =
        NormalizedSourceInput(
            reading = PollReading(key, value, value),
            stamp = NormalizedSourceStamp(
                kind = if (pollId == null) NormalizedSourceKind.CALLBACK else NormalizedSourceKind.POLL,
                identity = "source-$key-$wallMs",
                bootId = "boot",
                generatorId = "generator",
                sequence = wallMs,
                wallMs = wallMs,
                elapsedMs = wallMs
            ),
            sourcePollId = pollId
        )

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
