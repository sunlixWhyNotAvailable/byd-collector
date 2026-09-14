package com.bydcollector.collector.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramTemplatesTest {
    @Test
    fun previousReleaseDefaultsMigrateButCustomLegacyEnergyKeepsItsMeaning() {
        val uk = "Поїздку завершено\nПоточна поїздка: {trip_distance_km} км / {trip_duration}\nПоточна витрата: {trip_energy_kwh} кВт·год ({trip_avg_kwh_per_100km} кВт·год/100 км), SOC: {soc_start}% -> {soc_end}%\nЗагалом: {total_distance_km} км / {total_duration}\nЗагальна витрата: {total_energy_kwh} кВт·год ({total_avg_kwh_per_100km} кВт·год/100 км), SOC: {total_soc_start}% -> {total_soc_end}%"
        val en = "Trip complete\nCurrent trip: {trip_distance_km} km / {trip_duration}\nCurrent energy used: {trip_energy_kwh} kWh ({trip_avg_kwh_per_100km} kWh/100 km), SOC: {soc_start}% -> {soc_end}%\nTotal: {total_distance_km} km / {total_duration}\nTotal energy used: {total_energy_kwh} kWh ({total_avg_kwh_per_100km} kWh/100 km), SOC: {total_soc_start}% -> {total_soc_end}%"
        val type = TelegramEventType.TRIP_SUMMARY
        assertEquals(TelegramBuiltInTemplates.TRIP_SUMMARY_UK, TelegramBuiltInTemplates.migrateKnownSaved(type.key, uk))
        assertEquals(TelegramBuiltInTemplates.TRIP_SUMMARY_EN, TelegramBuiltInTemplates.migrateKnownSaved(type.key, en))
        val custom = "Stock: {trip_energy_kwh}; new: {trip_net_kwh}"
        assertEquals(custom, TelegramBuiltInTemplates.migrateKnownSaved(type.key, custom))
        assertEquals("Stock: 3.4; new: -1.2", TelegramTemplateRenderer.render(type, custom,
            mapOf("trip_energy_kwh" to "3.4", "trip_net_kwh" to "-1.2")).text)
        val fields = TelegramTemplateCatalog.spec(type).allowedVariables
        for (prefix in listOf("trip", "total")) {
            for (suffix in listOf("discharged_kwh", "regenerated_kwh", "net_kwh", "net_kwh_per_100km")) {
                assertTrue("${prefix}_$suffix" in fields)
            }
        }
    }

    @Test
    fun catalogContainsNineEventsWithValidDefaults() {
        assertEquals(TelegramEventType.entries.toSet(), TelegramTemplateCatalog.events)
        TelegramTemplateCatalog.events.forEach { event ->
            assertTrue(TelegramTemplateRenderer.validate(event, TelegramTemplateCatalog.spec(event).defaultTemplate).isEmpty())
        }
    }

    @Test
    fun approvedDefaultsAndVariablesMatchProductionContract() {
        val started = TelegramTemplateCatalog.spec(TelegramEventType.CHARGING_STARTED)
        val progress = TelegramTemplateCatalog.spec(TelegramEventType.CHARGING_PROGRESS)
        val trip = TelegramTemplateCatalog.spec(TelegramEventType.TRIP_SUMMARY)

        assertTrue("battery_power_kw" in started.allowedVariables)
        assertTrue("time" in progress.allowedVariables)
        assertEquals(TelegramBuiltInTemplates.CHARGING_PROGRESS_EN, progress.defaultTemplate)
        assertEquals(TelegramBuiltInTemplates.TRIP_SUMMARY_EN, trip.defaultTemplate)
        assertTrue("charge_step_added_percent" in progress.allowedVariables)
        assertTrue("charge_step_added_kwh" in progress.allowedVariables)
        assertTrue("total_distance_km" in trip.allowedVariables)
        assertTrue("total_energy_kwh" in trip.allowedVariables)
        assertTrue("trip_avg_kwh_per_100km" in trip.allowedVariables)
        assertTrue("total_avg_kwh_per_100km" in trip.allowedVariables)
        assertTrue("total_duration" in trip.allowedVariables)
        assertTrue("charge_step_duration" in progress.allowedVariables)
        assertEquals(
            TelegramBuiltInTemplates.CHARGING_PROGRESS_UK,
            TelegramTemplateCatalog.defaultTemplate(TelegramEventType.CHARGING_PROGRESS, TelegramTemplateLanguage.UK)
        )
        assertEquals(
            TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
            TelegramTemplateCatalog.defaultTemplate(TelegramEventType.TRIP_SUMMARY, TelegramTemplateLanguage.EN)
        )
        val full = TelegramTemplateCatalog.spec(TelegramEventType.CHARGED_TO_100)
        assertTrue(
            full.allowedVariables.containsAll(
                setOf(
                    "charge_added_percent",
                    "charge_added_kwh",
                    "charge_start_time",
                    "charge_end_time",
                    "charge_duration_hhmm"
                )
            )
        )
    }

    @Test
    fun knownHistoricBuiltInsMigrate() {
        assertEquals(
            TelegramBuiltInTemplates.CHARGING_PROGRESS_UK,
            TelegramBuiltInTemplates.migrateKnownSaved(
                TelegramEventType.CHARGING_PROGRESS.key,
                "Заряд: {soc}%\nДодано: {charge_added_percent}% / {charge_added_kwh} кВт·год"
            )
        )
        val legacyTrip = "Trip complete: {trip_distance_km} km, {trip_energy_kwh} kWh at {time}."
        assertEquals(
            TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
            TelegramBuiltInTemplates.migrateKnownSaved(TelegramEventType.TRIP_SUMMARY.key, legacyTrip)
        )
        assertTrue(
            TelegramBuiltInTemplates.isHistoricBuiltIn(
                TelegramEventType.CHARGED_TO_100,
                "Авто заряджено до 100%\nЕнергія: {remaining_energy_kwh} кВт·год\nЗапас ходу: {range_km} км"
            )
        )
        assertEquals(
            TelegramBuiltInTemplates.CHARGED_TO_100_EN,
            TelegramBuiltInTemplates.migrateKnownSaved(
                TelegramEventType.CHARGED_TO_100.key,
                "Vehicle charged to 100%\nEnergy: {remaining_energy_kwh} kWh\nRange: {range_km} km"
            )
        )
    }

    @Test
    fun chargedTo100BuiltInsRenderTheAcceptedFiveLineTextExactly() {
        val values = mapOf(
            "charge_added_percent" to "20",
            "charge_added_kwh" to "10",
            "charge_start_time" to "08:03",
            "charge_end_time" to "09:04",
            "charge_duration_hhmm" to "25:07",
            "remaining_energy_kwh" to "40",
            "range_km" to "300"
        )
        assertEquals(
            "Авто заряджено до 100%\nЗаряджено: 20% / 10 кВт·год\n" +
                "Час заряджання: 08:03 → 09:04 (25:07)\nЕнергія: 40 кВт·год\nЗапас ходу: 300 км",
            TelegramTemplateRenderer.render(
                TelegramEventType.CHARGED_TO_100,
                TelegramBuiltInTemplates.CHARGED_TO_100_UK,
                values
            ).text
        )
        assertEquals(
            "Vehicle charged to 100%\nCharged: 20% / 10 kWh\n" +
                "Charging time: 08:03 → 09:04 (25:07)\nEnergy: 40 kWh\nRange: 300 km",
            TelegramTemplateRenderer.render(
                TelegramEventType.CHARGED_TO_100,
                TelegramBuiltInTemplates.CHARGED_TO_100_EN,
                values
            ).text
        )
    }

    @Test
    fun approvedTripBuiltInsRenderExactUkrainianAndEnglishOutput() {
        val values = mapOf(
            "trip_distance_km" to "12.3",
            "trip_duration" to "00:24:18",
            "trip_energy_kwh" to "3.4",
            "trip_avg_kwh_per_100km" to "27.64",
            "soc_start" to "81",
            "soc_end" to "76",
            "total_soc_start" to "84",
            "total_soc_end" to "75",
            "total_distance_km" to "456.7",
            "total_duration" to "12:34:56",
            "total_energy_kwh" to "98.7",
            "total_avg_kwh_per_100km" to "21.62",
            "trip_discharged_kwh" to "4.0", "trip_regenerated_kwh" to "0.6",
            "trip_net_kwh" to "3.4", "trip_net_kwh_per_100km" to "27.64",
            "total_discharged_kwh" to "100.0", "total_regenerated_kwh" to "1.3",
            "total_net_kwh" to "98.7", "total_net_kwh_per_100km" to "21.62"
        )
        assertEquals(
            "Поїздку завершено\nПоточна поїздка: 12.3 км / 00:24:18\n" +
                "\nПоточна статистика.\nВитрачено: 4.0 кВт·год\nРекуперовано: 0.6 кВт·год\n" +
                "Баланс АКБ: 3.4 кВт·год (27.64 кВт·год/100 км), SOC: 81% -> 76%\n" +
                "Загалом: 456.7 км / 12:34:56\n\nЗагальна статистика.\nВитрачено: 100.0 кВт·год\nРекуперовано: 1.3 кВт·год\n" +
                "Баланс АКБ: 98.7 кВт·год (21.62 кВт·год/100 км), SOC: 84% -> 75%",
            TelegramTemplateRenderer.render(
                TelegramEventType.TRIP_SUMMARY,
                TelegramBuiltInTemplates.TRIP_SUMMARY_UK,
                values
            ).text
        )
        assertEquals(
            "Trip complete\nCurrent trip: 12.3 km / 00:24:18\n" +
                "\nCurrent statistics.\nUsed: 4.0 kWh\nRecovered: 0.6 kWh\n" +
                "Battery net: 3.4 kWh (27.64 kWh/100 km), SOC: 81% -> 76%\n" +
                "Total: 456.7 km / 12:34:56\n\nTotal statistics.\nUsed: 100.0 kWh\nRecovered: 1.3 kWh\n" +
                "Battery net: 98.7 kWh (21.62 kWh/100 km), SOC: 84% -> 75%",
            TelegramTemplateRenderer.render(
                TelegramEventType.TRIP_SUMMARY,
                TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
                values
            ).text
        )
    }

    @Test
    fun oneStopBuiltInTripTemplatesOmitOnlyTheOverallBlock() {
        val values = mapOf(
            "trip_distance_km" to "12.3",
            "trip_duration" to "00:24:18",
            "trip_energy_kwh" to "3.4",
            "trip_avg_kwh_per_100km" to "27.64",
            "soc_start" to "81",
            "soc_end" to "76",
            "total_soc_start" to "81",
            "total_soc_end" to "76",
            "total_distance_km" to "12.3",
            "total_duration" to "00:24:18",
            "total_energy_kwh" to "3.4",
            "total_avg_kwh_per_100km" to "27.64",
            "trip_discharged_kwh" to "4.0", "trip_regenerated_kwh" to "0.6",
            "trip_net_kwh" to "3.4", "trip_net_kwh_per_100km" to "27.64",
            "total_discharged_kwh" to "4.0", "total_regenerated_kwh" to "0.6",
            "total_net_kwh" to "3.4", "total_net_kwh_per_100km" to "27.64"
        )
        assertEquals(
            "Поїздку завершено\nПоточна поїздка: 12.3 км / 00:24:18\n" +
                "\nПоточна статистика.\nВитрачено: 4.0 кВт·год\nРекуперовано: 0.6 кВт·год\n" +
                "Баланс АКБ: 3.4 кВт·год (27.64 кВт·год/100 км), SOC: 81% -> 76%",
            TelegramTemplateRenderer.render(
                TelegramEventType.TRIP_SUMMARY,
                TelegramBuiltInTemplates.tripSummaryTemplate(TelegramTemplateLanguage.UK, includeOverall = false),
                values
            ).text
        )
        assertEquals(
            "Trip complete\nCurrent trip: 12.3 km / 00:24:18\n" +
                "\nCurrent statistics.\nUsed: 4.0 kWh\nRecovered: 0.6 kWh\n" +
                "Battery net: 3.4 kWh (27.64 kWh/100 km), SOC: 81% -> 76%",
            TelegramTemplateRenderer.render(
                TelegramEventType.TRIP_SUMMARY,
                TelegramBuiltInTemplates.tripSummaryTemplate(TelegramTemplateLanguage.EN, includeOverall = false),
                values
            ).text
        )
    }

    @Test
    fun dynamicRenderingLeavesCustomTripTemplateExact() {
        val custom = "Custom\r\n{trip_distance_km}  {total_distance_km}"
        assertEquals(
            custom,
            TelegramTemplateCatalog.templateForRendering(
                TelegramEventType.TRIP_SUMMARY,
                custom,
                TelegramTemplateLanguage.EN,
                omitOverall = true
            )
        )
    }

    @Test
    fun customTemplateRemainsByteForByteUnchangedDuringMigration() {
        val custom = "Custom\r\n{trip_distance_km}  \n"

        assertEquals(
            custom,
            TelegramBuiltInTemplates.migrateKnownSaved(TelegramEventType.TRIP_SUMMARY.key, custom)
        )
    }

    @Test
    fun previousOverallSocBuiltInsMigrateToDistinctTotalSocVariables() {
        val previousUk =
            "Поїздку завершено\nПоточна поїздка: {trip_distance_km} км / {trip_duration}\n" +
                "Витрата: {trip_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%\n" +
                "Загалом: {total_distance_km} км / {total_duration}\n" +
                "Витрата: {total_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%"
        val previousEn =
            "Trip complete\nCurrent trip: {trip_distance_km} km / {trip_duration}\n" +
                "Energy used: {trip_energy_kwh} kWh, SOC: {soc_start}% -> {soc_end}%\n" +
                "Total: {total_distance_km} km / {total_duration}\n" +
                "Energy used: {total_energy_kwh} kWh, SOC: {soc_start}% -> {soc_end}%"

        assertEquals(
            TelegramBuiltInTemplates.TRIP_SUMMARY_UK,
            TelegramBuiltInTemplates.migrateKnownSaved(TelegramEventType.TRIP_SUMMARY.key, previousUk)
        )
        assertEquals(
            TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
            TelegramBuiltInTemplates.migrateKnownSaved(TelegramEventType.TRIP_SUMMARY.key, previousEn)
        )
    }

    @Test
    fun approvedChargingDefaultsRetainOptionalPowerAndSupportProgressTime() {
        TelegramTemplateLanguage.entries.forEach { language ->
            val started = TelegramTemplateCatalog.defaultTemplate(TelegramEventType.CHARGING_STARTED, language)
            assertTrue(started.contains("{time}"))
            assertFalse(started.contains("{battery_power_kw}"))
            val progress = TelegramTemplateCatalog.defaultTemplate(TelegramEventType.CHARGING_PROGRESS, language)
            assertEquals(7, progress.lines().size)
            assertTrue(progress.lines()[1].contains("{battery_power_kw}"))
            assertTrue(TelegramTemplateRenderer.validate(TelegramEventType.CHARGING_PROGRESS, progress).isEmpty())
            assertTrue(TelegramTemplateCatalog.defaultTemplate(TelegramEventType.CHARGING_STOPPED, language).contains("{time}"))
        }
        assertTrue("battery_power_kw" in TelegramTemplateCatalog.spec(TelegramEventType.CHARGING_STARTED).allowedVariables)
    }

    @Test
    fun currentAndHistoricBuiltInsAreRecognizedWithoutMatchingCustomText() {
        val type = TelegramEventType.TRIP_SUMMARY
        assertTrue(TelegramBuiltInTemplates.isKnownBuiltIn(type, TelegramBuiltInTemplates.TRIP_SUMMARY_UK))
        assertTrue(TelegramBuiltInTemplates.isCurrentBuiltIn(type.key, TelegramBuiltInTemplates.TRIP_SUMMARY_EN))
        assertTrue(TelegramBuiltInTemplates.isHistoricBuiltIn(type.key, "Trip complete: {trip_distance_km} km, {trip_energy_kwh} kWh at {time}."))
        assertFalse(TelegramBuiltInTemplates.isKnownBuiltIn(type.key, "Trip complete {trip_distance_km}"))
    }

    @Test
    fun parserAndRendererReplaceOnlyAllowedVariables() {
        val parsed = TelegramTemplateParser.parse("Charge {soc}% at {time}")
        val rendered = TelegramTemplateRenderer.render(
            TelegramEventType.CHARGING_STARTED,
            "Charge {soc}% at {time}",
            mapOf("soc" to "81", "time" to "12:30")
        )

        assertTrue(parsed.isValid)
        assertEquals("Charge 81% at 12:30", rendered.text)
        assertTrue(rendered.isSuccess)
    }

    @Test
    fun malformedUnknownAndMissingVariablesStayVisibleAsErrors() {
        val malformed = TelegramTemplateRenderer.validate(
            TelegramEventType.CHARGING_STARTED,
            "Charge {soc"
        )
        val unknown = TelegramTemplateRenderer.validate(
            TelegramEventType.CHARGING_STARTED,
            "Charge {trip_distance_km}"
        )
        val missing = TelegramTemplateRenderer.render(
            TelegramEventType.CHARGING_STARTED,
            "Charge {soc}",
            emptyMap()
        )

        assertEquals(TelegramTemplateErrorKind.MALFORMED_PLACEHOLDER, malformed.single().kind)
        assertEquals(TelegramTemplateErrorKind.VARIABLE_NOT_ALLOWED, unknown.single().kind)
        assertEquals("trip_distance_km", unknown.single().variable)
        assertNull(missing.text)
        assertEquals(TelegramTemplateErrorKind.VALUE_MISSING, missing.errors.single().kind)
    }

    @Test
    fun validatesTemplateAndRenderedUnicodeCharacterLimits() {
        val emoji = "\uD83D\uDE97"
        val exact = TelegramTemplateRenderer.validate(
            TelegramEventType.CHARGING_STARTED,
            emoji.repeat(TELEGRAM_MESSAGE_MAX_CHARS)
        )
        val tooLong = TelegramTemplateRenderer.validate(
            TelegramEventType.CHARGING_STARTED,
            emoji.repeat(TELEGRAM_MESSAGE_MAX_CHARS + 1)
        )
        val expanded = TelegramTemplateRenderer.render(
            TelegramEventType.CHARGING_STARTED,
            "{soc}",
            mapOf("soc" to "x".repeat(TELEGRAM_MESSAGE_MAX_CHARS + 1))
        )

        assertTrue(exact.isEmpty())
        assertEquals(TELEGRAM_MESSAGE_MAX_CHARS + 1, tooLong.single().actualLength)
        assertFalse(expanded.isSuccess)
        assertEquals(TelegramTemplateErrorKind.TOO_LONG, expanded.errors.single().kind)
    }
}
