package com.bydcollector.collector.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramTemplatesTest {
    @Test
    fun catalogContainsNineEventsWithValidDefaults() {
        assertEquals(TelegramEventType.entries.toSet(), TelegramTemplateCatalog.events)
        TelegramTemplateCatalog.events.forEach { event ->
            assertTrue(TelegramTemplateRenderer.validate(event, TelegramTemplateCatalog.spec(event).defaultTemplate).isEmpty())
        }
    }

    @Test
    fun approvedDefaultsAndVariablesMatchProductionContract() {
        val progress = TelegramTemplateCatalog.spec(TelegramEventType.CHARGING_PROGRESS)
        val trip = TelegramTemplateCatalog.spec(TelegramEventType.TRIP_SUMMARY)

        assertEquals(TelegramBuiltInTemplates.CHARGING_PROGRESS_EN, progress.defaultTemplate)
        assertEquals(TelegramBuiltInTemplates.TRIP_SUMMARY_EN, trip.defaultTemplate)
        assertTrue("charge_step_added_percent" in progress.allowedVariables)
        assertTrue("charge_step_added_kwh" in progress.allowedVariables)
        assertTrue("total_distance_km" in trip.allowedVariables)
        assertTrue("total_energy_kwh" in trip.allowedVariables)
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
    }

    @Test
    fun approvedTripBuiltInsRenderExactUkrainianAndEnglishOutput() {
        val values = mapOf(
            "trip_distance_km" to "12.3",
            "trip_duration" to "00:24:18",
            "trip_energy_kwh" to "3.4",
            "soc_start" to "81",
            "soc_end" to "76",
            "total_distance_km" to "456.7",
            "total_duration" to "12:34:56",
            "total_energy_kwh" to "98.7"
        )
        assertEquals(
            "Поїздку завершено\nПоточна поїздка: 12.3 км / 00:24:18\n" +
                "Витрата: 3.4 кВт·год, SOC: 81% -> 76%\n" +
                "Загалом: 456.7 км / 12:34:56\nВитрата: 98.7 кВт·год, SOC: 81% -> 76%",
            TelegramTemplateRenderer.render(
                TelegramEventType.TRIP_SUMMARY,
                TelegramBuiltInTemplates.TRIP_SUMMARY_UK,
                values
            ).text
        )
        assertEquals(
            "Trip complete\nCurrent trip: 12.3 km / 00:24:18\n" +
                "Energy used: 3.4 kWh, SOC: 81% -> 76%\n" +
                "Total: 456.7 km / 12:34:56\nEnergy used: 98.7 kWh, SOC: 81% -> 76%",
            TelegramTemplateRenderer.render(
                TelegramEventType.TRIP_SUMMARY,
                TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
                values
            ).text
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
