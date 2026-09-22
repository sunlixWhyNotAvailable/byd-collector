package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.telegram.TelegramBuiltInTemplates
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BydCollectorStringsTest {
    @Test
    fun telegramEditorUsesTheSameDefaultsAsTheSender() {
        val uk = strings(UiLanguage.UK).telegram
        val en = strings(UiLanguage.EN).telegram
        assertEquals(TelegramBuiltInTemplates.CHARGING_PROGRESS_UK,
            uk.messages.getValue(TelegramMessageType.CHARGING_PROGRESS).defaultTemplate)
        assertEquals(TelegramBuiltInTemplates.CHARGING_PROGRESS_EN,
            en.messages.getValue(TelegramMessageType.CHARGING_PROGRESS).defaultTemplate)
        assertEquals(TelegramBuiltInTemplates.TRIP_SUMMARY_UK,
            uk.messages.getValue(TelegramMessageType.TRIP_SUMMARY).defaultTemplate)
        assertEquals(TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
            en.messages.getValue(TelegramMessageType.TRIP_SUMMARY).defaultTemplate)
    }

    @Test
    fun localizedTemplatesFormatRuntimeValuesWithoutLosingThem() {
        UiLanguage.entries.forEach { language ->
            val copy = strings(language)
            val sizes = listOf("111 MiB", "222 MiB", "333 MiB", "444 MiB")
            val storage = String.format(Locale.ROOT, copy.activeDatabaseSizeTemplate, *sizes.toTypedArray())
            sizes.forEach { assertTrue(storage.contains(it), "$language: $storage") }
            val queue = String.format(Locale.ROOT, copy.dbMaintenancePendingTemplate, 123L, 456L)
            assertTrue(queue.contains("123") && queue.contains("456"), "$language: $queue")
            val error = String.format(Locale.ROOT, copy.mainStorageCutoverErrorTemplate, "disk_full")
            assertTrue(error.contains("disk_full"), "$language: $error")
        }
    }
}
