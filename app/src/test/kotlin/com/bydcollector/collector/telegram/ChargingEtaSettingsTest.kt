package com.bydcollector.collector.telegram

import android.content.Context
import com.bydcollector.collector.service.CollectorSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChargingEtaSettingsTest {
    @Test
    fun independentSelectionsSurviveReopenAndUnknownStoredValueUsesDefault() {
        val context = RuntimeEnvironment.getApplication()
        val settings = CollectorSettings(context)
        assertEquals(ChargingEtaSelection.CALCULATED, settings.telegramAcEtaSelection())
        assertEquals(ChargingEtaSelection.CALCULATED, settings.telegramDcEtaSelection())
        settings.setTelegramAcEtaSelection(ChargingEtaSelection.OEM_ONLY)
        val reopened = CollectorSettings(context)
        assertEquals(ChargingEtaSelection.OEM_ONLY, reopened.telegramAcEtaSelection())
        assertEquals(ChargingEtaSelection.CALCULATED, reopened.telegramDcEtaSelection())
        reopened.setTelegramDcEtaSelection(ChargingEtaSelection.OEM_ONLY)
        reopened.setTelegramAcEtaSelection(ChargingEtaSelection.CALCULATED)
        assertEquals(ChargingEtaSelection.CALCULATED, CollectorSettings(context).telegramAcEtaSelection())
        assertEquals(ChargingEtaSelection.OEM_ONLY, CollectorSettings(context).telegramDcEtaSelection())
        context.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(CollectorSettings.KEY_TELEGRAM_DC_ETA, "removed-option").commit()
        assertEquals(ChargingEtaSelection.CALCULATED, CollectorSettings(context).telegramDcEtaSelection())
    }

    @Test
    fun existingBuiltInsUpgradeAfterMigrationWhileCustomTextSurvives() {
        val context = RuntimeEnvironment.getApplication()
        val settings = CollectorSettings(context)
        val event = TelegramEventType.CHARGING_PROGRESS
        val oldTemplates = listOf(
            "uk" to "🔋 Заряд: {soc}%\n⚡ Потужність: {battery_power_kw} кВт\n📈 За крок: +{charge_step_added_percent}% / +{charge_step_added_kwh} кВт·год\n⏱️ Час кроку: {charge_step_duration}\n📊 За сесію: +{charge_added_percent}% / +{charge_added_kwh} кВт·год\n⏱️ Час сесії: {charge_duration}\n🕒 Час: {time}",
            "en" to "🔋 Charge: {soc}%\n⚡ Power: {battery_power_kw} kW\n📈 This step: +{charge_step_added_percent}% / +{charge_step_added_kwh} kWh\n⏱️ Step time: {charge_step_duration}\n📊 Session total: +{charge_added_percent}% / +{charge_added_kwh} kWh\n⏱️ Session time: {charge_duration}\n🕒 Time: {time}"
        )
        oldTemplates.forEach { (language, oldTemplate) ->
            settings.setUiLanguageCode(language)
            settings.setTelegramTemplate(event.key, oldTemplate)
            assertEquals(
                if (language == "en") TelegramBuiltInTemplates.CHARGING_PROGRESS_EN
                else TelegramBuiltInTemplates.CHARGING_PROGRESS_UK,
                CollectorSettings(context).telegramTemplate(event.key)
            )
        }
        val custom = "My charging: {soc}%\nKeep this exact text."
        settings.setTelegramTemplate(event.key, custom)
        settings.setUiLanguageCode("uk")
        assertEquals(custom, CollectorSettings(context).telegramTemplate(event.key))
    }

    @Test
    fun bothTemplatesRenderUnavailableEtaWithoutBlockingTheMessage() {
        val values = mapOf(
            "charge_type" to "AC", "soc" to "55", "battery_power_kw" to "7.2",
            "charge_step_added_percent" to "5", "charge_step_added_kwh" to "4.1",
            "charge_step_duration" to "34m", "charge_added_percent" to "15",
            "charge_added_kwh" to "12.3", "charge_duration" to "1h 42m",
            "charge_time_to_full" to "—", "charge_full_time" to "—",
            "time" to "17.10.2026 14:00"
        )
        listOf(TelegramBuiltInTemplates.CHARGING_PROGRESS_UK, TelegramBuiltInTemplates.CHARGING_PROGRESS_EN)
            .forEach { template ->
                val result = TelegramTemplateRenderer.render(TelegramEventType.CHARGING_PROGRESS, template, values)
                assertTrue(result.errors.isEmpty(), result.errors.toString())
                val text = assertNotNull(result.text)
                assertTrue(text.contains("AC"))
                assertTrue(text.contains("55%"))
                assertTrue(text.contains("—"))
                assertTrue(text.endsWith("17.10.2026 14:00"))
                assertTrue(!text.contains('{'))
            }
    }
}
