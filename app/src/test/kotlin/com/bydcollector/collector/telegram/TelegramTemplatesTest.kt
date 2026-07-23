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
