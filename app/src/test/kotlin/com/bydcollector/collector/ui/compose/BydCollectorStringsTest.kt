package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class BydCollectorStringsTest {
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
        assertEquals("Налаштування", uk.extraTab)
        assertEquals("Всі дані", uk.allParameters)
        assertEquals("All data", en.allTab)
        assertEquals("Options", en.extraTab)
        assertEquals("All data", en.allParameters)
    }

    @Test
    fun databaseMaintenanceLabelsAreLocalized() {
        val uk = strings(UiLanguage.UK)
        val en = strings(UiLanguage.EN)

        assertEquals("Стиснення бази", uk.compactDatabase)
        assertEquals("Архівація бази", uk.archiveDatabase)
        assertEquals("Так", uk.yes)
        assertEquals("Ні", uk.no)
        assertEquals("Скасувати", uk.cancel)
        assertEquals("Крок", uk.step)
        assertEquals("Буде тимчасово зупинено основний збір, всі дані, MQTT та InfluxDB.", uk.dbMaintenanceStopWarning)
        assertEquals("У черзі: MQTT %d, InfluxDB %d.", uk.dbMaintenancePendingTemplate)
        assertEquals("Архівація з активною чергою може залишити частину даних тільки в архіві.", uk.dbMaintenanceArchivePendingWarning)
        assertEquals("Виконати операцію %s?", uk.dbMaintenanceConfirmTemplate)
        assertEquals("Після початку зупинити операцію із застосунку неможливо.", uk.operationCannotBeStopped)
        assertEquals("Переривання процесу створює ризик втрати даних!", uk.interruptionDataLossRisk)
        assertEquals("Операцію завершено", uk.dbMaintenanceComplete)
        assertEquals("Операцію не виконано", uk.dbMaintenanceFailed)
        assertEquals("Операцію скасовано.", uk.dbMaintenanceCancelled)
        assertEquals("Архів:", uk.dbMaintenanceArchivePath)

        assertEquals("Database compact", en.compactDatabase)
        assertEquals("Database archive", en.archiveDatabase)
        assertEquals("Yes", en.yes)
        assertEquals("No", en.no)
        assertEquals("Cancel", en.cancel)
        assertEquals("Step", en.step)
        assertEquals("Main collection, all data, MQTT, and InfluxDB will be temporarily stopped.", en.dbMaintenanceStopWarning)
        assertEquals("Queued: MQTT %d, InfluxDB %d.", en.dbMaintenancePendingTemplate)
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
}
