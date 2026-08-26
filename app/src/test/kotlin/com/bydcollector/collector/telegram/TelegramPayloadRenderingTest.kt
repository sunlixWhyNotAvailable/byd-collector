package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.service.TelegramDetectedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramPayloadRenderingTest {
    @Test
    fun invalidCustomEventProducesExactlyOneFallbackInAnAtomicBatch() {
        val event = event()
        val messages = renderTelegramBatch(listOf(event)) {
            renderTelegramPayload(it, "{not_allowed}", TelegramTemplateLanguage.EN).text?.let { payload ->
                TelegramOutboxMessage(it.dedupeKey, it.type.key, payload)
            }
        }

        assertEquals(1, messages?.size)
        assertEquals(event.dedupeKey, messages?.single()?.dedupeKey)
        assertNull(renderTelegramBatch(listOf(event, event.copy(dedupeKey = "second"))) {
            if (it.dedupeKey == "second") null else messages?.single()
        })
    }

    @Test
    fun invalidCustomTemplateFallsBackToTheLocalizedBuiltIn() {
        val result = renderTelegramPayload(
            event = event(),
            savedTemplate = "{not_allowed}",
            language = TelegramTemplateLanguage.EN
        )

        assertTrue(result.isSuccess)
        assertTrue(result.usedFallback)
        assertEquals(
            TelegramTemplateRenderer.render(
                TelegramEventType.LOW_12V_VOLTAGE,
                TelegramTemplateCatalog.defaultTemplate(
                    TelegramEventType.LOW_12V_VOLTAGE,
                    TelegramTemplateLanguage.EN
                ),
                event().variables
            ).text,
            result.text
        )
    }

    @Test
    fun mixedLengthAndValidationErrorsStillRecordThatFallbackWasNeeded() {
        val result = renderTelegramPayload(
            event = event(),
            savedTemplate = "x".repeat(TELEGRAM_MESSAGE_MAX_CHARS + 1) + "{not_allowed}",
            language = TelegramTemplateLanguage.EN
        )

        assertTrue(result.isSuccess)
        assertTrue(result.usedFallback)
        assertEquals(TelegramPayloadLimitState.TEMPLATE, result.limitState)
    }

    @Test
    fun navigatorLinksRespectUnicodeBoundariesAndAreDroppedAsOneSuffix() {
        val suffix = "\nGoogle: https://maps.google.com/?q=50,30" +
            "\nWaze: https://waze.com/ul?ll=50,30" +
            "\nApple: https://maps.apple.com/?ll=50,30" +
            "\nOSM: https://www.openstreetmap.org/?mlat=50&mlon=30"
        val suffixLength = suffix.codePointCount(0, suffix.length)
        val emoji = "🚙"

        fun renderFinalLength(length: Int) = renderTelegramPayload(
            event = event(textSuffix = suffix),
            savedTemplate = emoji.repeat(length - suffixLength),
            language = TelegramTemplateLanguage.EN
        )

        val at4_095 = renderFinalLength(4_095).text!!
        val at4_096 = renderFinalLength(4_096).text!!
        assertEquals(4_095, at4_095.codePointCount(0, at4_095.length))
        assertEquals(4_096, at4_096.codePointCount(0, at4_096.length))
        assertTrue(!renderFinalLength(4_097).text!!.contains("Google:"))
        assertEquals(TelegramPayloadLimitState.WITH_LOCATION, renderFinalLength(4_097).limitState)

        listOf(4_095, 4_096).forEach { baseLength ->
            val base = emoji.repeat(baseLength)
            val rendered = renderTelegramPayload(
                event = event(textSuffix = suffix),
                savedTemplate = base,
                language = TelegramTemplateLanguage.EN
            )
            assertEquals(base, rendered.text)
            assertEquals(TelegramPayloadLimitState.WITH_LOCATION, rendered.limitState)
        }
        val invalid = renderTelegramPayload(
                event = event(textSuffix = suffix),
                savedTemplate = "{not_allowed}",
                language = TelegramTemplateLanguage.EN
            )
        val oversized = renderTelegramPayload(
                event = event(textSuffix = suffix),
                savedTemplate = emoji.repeat(4_097),
                language = TelegramTemplateLanguage.EN
            )
        assertEquals(invalid.text, oversized.text)
        assertEquals(TelegramPayloadLimitState.TEMPLATE, oversized.limitState)
    }

    @Test
    fun tripLimitStateUsesTheExactRuntimeRender() {
        val exact = renderTelegramPayload(
            event = tripEvent(),
            savedTemplate = "x".repeat(4_080) + "{time}",
            language = TelegramTemplateLanguage.EN
        )
        val oversized = renderTelegramPayload(
            event = tripEvent(),
            savedTemplate = "x".repeat(4_081) + "{time}",
            language = TelegramTemplateLanguage.EN
        )
        val oversizedDefault = renderTelegramPayload(
            event = tripEvent().copy(
                variables = tripEvent().variables +
                    ("trip_distance_km" to "x".repeat(TELEGRAM_MESSAGE_MAX_CHARS + 1))
            ),
            savedTemplate = null,
            language = TelegramTemplateLanguage.EN
        )

        assertEquals(TELEGRAM_MESSAGE_MAX_CHARS, exact.text?.codePointCount(0, exact.text.length))
        assertEquals(TelegramPayloadLimitState.NONE, exact.limitState)
        assertEquals(TelegramPayloadLimitState.TEMPLATE, oversized.limitState)
        assertNull(oversizedDefault.text)
        assertEquals(TelegramPayloadLimitState.TEMPLATE, oversizedDefault.limitState)
    }

    @Test
    fun oversizedLocationOnlyPayloadIsRejectedBeforeTheOutbox() {
        val result = renderTelegramPayload(
            event = event(textSuffix = "🚙".repeat(4_097), locationOnly = true),
            savedTemplate = null,
            language = TelegramTemplateLanguage.EN
        )

        assertNull(result.text)
        assertEquals(TelegramTemplateErrorKind.TOO_LONG, result.errors.single().kind)
        assertEquals(TelegramPayloadLimitState.WITH_LOCATION, result.limitState)
    }

    private fun event(
        textSuffix: String? = null,
        locationOnly: Boolean = false
    ) = TelegramDetectedEvent(
        type = TelegramEventType.LOW_12V_VOLTAGE,
        dedupeKey = "low-voltage",
        variables = mapOf("battery_12v" to "11.8", "time" to "08:15"),
        textSuffix = textSuffix,
        locationOnly = locationOnly
    )

    private fun tripEvent() = TelegramDetectedEvent(
        type = TelegramEventType.TRIP_SUMMARY,
        dedupeKey = "trip:summary",
        variables = mapOf(
            "trip_distance_km" to "12.3",
            "trip_energy_kwh" to "3.4",
            "trip_duration" to "0:24",
            "soc_start" to "81",
            "soc_end" to "76",
            "total_soc_start" to "81",
            "total_soc_end" to "76",
            "total_distance_km" to "12.3",
            "total_energy_kwh" to "3.4",
            "total_duration" to "0:24",
            "time" to "25.08.2026 12:34"
        )
    )
}
