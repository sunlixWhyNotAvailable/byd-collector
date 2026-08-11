package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.telegram.TelegramBuiltInTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BydCollectorStringsTest {
    @Test
    fun localizedStringsAreSingletonInstances() {
        assertSame(strings(UiLanguage.UK), strings(UiLanguage.UK))
        assertSame(strings(UiLanguage.EN), strings(UiLanguage.EN))
    }

    @Test
    fun influxCountersUsePointsInsteadOfMainDatabaseRows() {
        assertEquals("точок", strings(UiLanguage.UK).points)
        assertEquals("points", strings(UiLanguage.EN).points)
    }

    @Test
    fun v130UsesSimpleProductionLabels() {
        val uk = strings(UiLanguage.UK)
        val en = strings(UiLanguage.EN)

        assertEquals("Всі дані", uk.allTab)
        assertEquals("Сховище", uk.storageTab)
        assertEquals("Налаштування", uk.extraTab)
        assertEquals("Всі дані", uk.allParameters)
        assertEquals("All data", en.allTab)
        assertEquals("Storage", en.storageTab)
        assertEquals("Options", en.extraTab)
        assertEquals("All data", en.allParameters)
    }

    @Test
    fun databaseMaintenanceLabelsAreLocalized() {
        val uk = strings(UiLanguage.UK)
        val en = strings(UiLanguage.EN)

        assertEquals("Архівація бази", uk.archiveDatabase)
        assertEquals("Так", uk.yes)
        assertEquals("Ні", uk.no)
        assertEquals("Скасувати", uk.cancel)
        assertEquals("Крок", uk.step)
        assertEquals("Буде тимчасово зупинено основний збір, всі дані, MQTT та InfluxDB.", uk.dbMaintenanceStopWarning)
        assertEquals("У черзі: Telegram %d, MQTT %d, InfluxDB %d.", uk.dbMaintenancePendingTemplate)
        assertEquals("У Telegram є незавершений стан подій або приглушення повторів.", uk.dbMaintenanceTelegramDeferredWarning)
        assertEquals("Архівація з активною чергою може залишити частину даних тільки в архіві.", uk.dbMaintenanceArchivePendingWarning)
        assertEquals("Виконати операцію %s?", uk.dbMaintenanceConfirmTemplate)
        assertEquals("Після початку зупинити операцію із застосунку неможливо.", uk.operationCannotBeStopped)
        assertEquals("Переривання процесу створює ризик втрати даних!", uk.interruptionDataLossRisk)
        assertEquals("Операцію завершено", uk.dbMaintenanceComplete)
        assertEquals("Операцію не виконано", uk.dbMaintenanceFailed)
        assertEquals("Операцію скасовано.", uk.dbMaintenanceCancelled)
        assertEquals("Архів:", uk.dbMaintenanceArchivePath)

        assertEquals("Archive database", en.archiveDatabase)
        assertEquals("Yes", en.yes)
        assertEquals("No", en.no)
        assertEquals("Cancel", en.cancel)
        assertEquals("Step", en.step)
        assertEquals("Main collection, all data, MQTT, and InfluxDB will be temporarily stopped.", en.dbMaintenanceStopWarning)
        assertEquals("Queued: Telegram %d, MQTT %d, InfluxDB %d.", en.dbMaintenancePendingTemplate)
        assertEquals("Telegram has unfinished event or repeat-suppression state.", en.dbMaintenanceTelegramDeferredWarning)
        assertEquals("Archiving with an active queue can leave some data only inside the archive.", en.dbMaintenanceArchivePendingWarning)
        assertEquals("Run operation %s?", en.dbMaintenanceConfirmTemplate)
        assertEquals("After the operation starts, it cannot be stopped from the app.", en.operationCannotBeStopped)
        assertEquals("Process interruption creates data corruption risk!", en.interruptionDataLossRisk)
        assertEquals("Operation complete", en.dbMaintenanceComplete)
        assertEquals("Operation failed", en.dbMaintenanceFailed)
        assertEquals("The operation was cancelled.", en.dbMaintenanceCancelled)
        assertEquals("Archive:", en.dbMaintenanceArchivePath)
    }

    @Test
    fun shutdownAndRuntimeLabelsAreLocalized() {
        val uk = strings(UiLanguage.UK)
        val en = strings(UiLanguage.EN)

        assertEquals("Підтримка роботи", uk.keepAlive)
        assertEquals("Робота застосунку", uk.appRuntime)
        assertEquals("Виключити", uk.shutdown)
        assertEquals("Завершити роботу застосунку до наступного відкриття", uk.shutdownDescription)

        assertEquals("Keep alive", en.keepAlive)
        assertEquals("App runtime", en.appRuntime)
        assertEquals("Shutdown", en.shutdown)
        assertEquals("Stop the app until it is opened again", en.shutdownDescription)
    }

    @Test
    fun kpiLabelsLiveInUiStrings() {
        val uk = strings(UiLanguage.UK)
        val en = strings(UiLanguage.EN)

        assertEquals("Пробіг", uk.kpiOdometer)
        assertEquals("Odometer", en.kpiOdometer)
        assertEquals("Запас ходу", uk.kpiRange)
        assertEquals("Range", en.kpiRange)
        assertEquals("Заряджання", uk.kpiCharging)
        assertEquals("Розряджання", uk.kpiDischarging)
        assertEquals("Charging", en.kpiCharging)
        assertEquals("Discharging", en.kpiDischarging)
        assertEquals("Немає з'єднання. Перевірте мережу або VPN.", uk.updateNetworkUnavailable)
        assertEquals("No connection. Check the network or VPN.", en.updateNetworkUnavailable)
        assertEquals("Операцію перервано до завершення.", uk.dbMaintenanceInterrupted)
        assertEquals("The operation was interrupted before completion.", en.dbMaintenanceInterrupted)
    }

    @Test
    fun storageLabelsAreLocalized() {
        val uk = strings(UiLanguage.UK)
        val en = strings(UiLanguage.EN)

        assertEquals("Поточні бази", uk.activeDatabase)
        assertEquals("%s: основна %s + тестова %s", uk.activeDatabaseSizeTemplate)
        assertEquals("Main DB v2: перехід відкладено до ручної архівації.", uk.mainStorageCutoverDeferred)
        assertEquals("Архіви", uk.archiveStorage)
        assertEquals("Архівів немає", uk.archiveStorageEmpty)
        assertEquals("Сховище сканується...", uk.archiveStorageScanning)
        assertEquals("Ліміт архівів, ГБ", uk.archiveStorageLimit)
        assertEquals("Тека архівів", uk.archiveRoot)
        assertEquals("Поділитися вибраними архівами", uk.shareSelectedArchives)
        assertEquals("Не вдалося поділитися вибраними архівами", uk.archiveShareFailed)
        assertEquals("Видалити вибране", uk.deleteSelected)
        assertEquals("Видалити вибрані архіви?", uk.deleteArchivesQuestion)
        assertEquals("МБ", uk.megabytesUnit)
        assertEquals("ГБ", uk.gigabytesUnit)
        assertEquals("обчислюємо...", uk.archiveCalculating)
        assertEquals("Нові спочатку", uk.archiveSortNewestFirst)
        assertEquals("Старі спочатку", uk.archiveSortOldestFirst)
        assertEquals("(%d арх.)", uk.archiveCountShortTemplate)

        assertEquals("Active databases", en.activeDatabase)
        assertEquals("%s: main %s + test %s", en.activeDatabaseSizeTemplate)
        assertEquals("Main DB v2: transition deferred until manual archive.", en.mainStorageCutoverDeferred)
        assertEquals("Archives", en.archiveStorage)
        assertEquals("No archives", en.archiveStorageEmpty)
        assertEquals("Scanning storage...", en.archiveStorageScanning)
        assertEquals("Archive limit, GB", en.archiveStorageLimit)
        assertEquals("Archive folder", en.archiveRoot)
        assertEquals("Share selected archives", en.shareSelectedArchives)
        assertEquals("Could not share selected archives", en.archiveShareFailed)
        assertEquals("Delete selected", en.deleteSelected)
        assertEquals("Delete selected archives?", en.deleteArchivesQuestion)
        assertEquals("MB", en.megabytesUnit)
        assertEquals("GB", en.gigabytesUnit)
        assertEquals("calculating...", en.archiveCalculating)
        assertEquals("Newest first", en.archiveSortNewestFirst)
        assertEquals("Oldest first", en.archiveSortOldestFirst)
        assertEquals("(%d arch.)", en.archiveCountShortTemplate)
    }

    @Test
    fun telegramDefaultsAndVariableDescriptionsMatchApprovedPreview() {
        val uk = strings(UiLanguage.UK).telegram
        val en = strings(UiLanguage.EN).telegram

        assertEquals(
            TelegramBuiltInTemplates.CHARGING_PROGRESS_UK,
            uk.messages.getValue(TelegramMessageType.CHARGING_PROGRESS).defaultTemplate
        )
        assertEquals(
            TelegramBuiltInTemplates.CHARGING_PROGRESS_EN,
            en.messages.getValue(TelegramMessageType.CHARGING_PROGRESS).defaultTemplate
        )
        assertEquals(
            TelegramBuiltInTemplates.TRIP_SUMMARY_UK,
            uk.messages.getValue(TelegramMessageType.TRIP_SUMMARY).defaultTemplate
        )
        assertEquals(
            TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
            en.messages.getValue(TelegramMessageType.TRIP_SUMMARY).defaultTemplate
        )
        assertEquals("Доданий заряд за поточний крок, %", uk.variableDescriptions["charge_step_added_percent"])
        assertEquals("Total trip duration", en.variableDescriptions["total_duration"])
    }
}
