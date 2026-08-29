package com.bydcollector.collector.service

import android.content.SharedPreferences
import com.bydcollector.collector.telegram.TelegramBuiltInTemplates
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramTemplateCatalog
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import com.bydcollector.collector.telegram.TelegramTemplateRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorSettingsSecurityContractTest {
    @Test
    fun legacyTripDelayMigratesToBoundedSeconds() {
        assertEquals(120, CollectorSettings.legacyTripDelaySeconds(2))
        assertEquals(300, CollectorSettings.legacyTripDelaySeconds(60))
        assertEquals(5, CollectorSettings.legacyTripDelaySeconds(0))
        assertEquals(10, CollectorSettings.DEFAULT_TELEGRAM_TRIP_END_DELAY_SECONDS)
    }

    @Test
    fun keystoreReadsNeverFallBackToLegacyPlaintext() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val secretValue = source.substringAfter("private fun secretValue")
            .substringBefore("private fun writeSecret")
        val migration = source.substringAfter("private fun migrateLegacySecret")
            .substringBefore("private fun migrateTripEndDelayToSeconds")

        assertFalse(secretValue.contains("prefs."))
        assertTrue(migration.contains("!existing.isNullOrBlank() -> true"))
        assertTrue(migration.contains("remove(preferenceKey)"))
        assertTrue(migration.contains("putBoolean(integrationEnabledKey, false)"))
        assertTrue(source.contains("legacyPreferenceKey?.let(editor::remove)"))
        assertTrue(source.contains("integrationEnabledKey?.let { editor.putBoolean(it, false) }"))
    }

    @Test
    fun legacyLocationMigrationPreservesChannelPrivacy() {
        val mqtt = setOf("battery")
        val influx = setOf("motion")

        assertEquals(
            CollectorSettings.LegacyLocationMigration(setOf("battery", "location"), setOf("motion", "location"), true),
            CollectorSettings.migrateLegacyLocationCategories(mqtt, influx, true, true, true)
        )
        assertEquals(
            CollectorSettings.LegacyLocationMigration(setOf("battery"), setOf("motion"), true),
            CollectorSettings.migrateLegacyLocationCategories(mqtt, influx, true, false, false)
        )
        assertEquals(
            CollectorSettings.LegacyLocationMigration(setOf("battery", "location"), setOf("battery"), false),
            CollectorSettings.migrateLegacyLocationCategories(mqtt, influx, true, true, false)
        )
        assertEquals(
            CollectorSettings.LegacyLocationMigration(setOf("battery", "location"), setOf("battery"), false),
            CollectorSettings.migrateLegacyLocationCategories(mqtt, influx, true, true, null)
        )
        assertEquals(
            CollectorSettings.LegacyLocationMigration(mqtt, setOf("motion"), true),
            CollectorSettings.migrateLegacyLocationCategories(mqtt, influx, true, null, false)
        )
        assertEquals(
            CollectorSettings.LegacyLocationMigration(setOf("battery"), setOf("motion"), true),
            CollectorSettings.migrateLegacyLocationCategories(setOf("battery", "location"), setOf("motion", "location"), true, false, null)
        )
    }

    @Test
    fun legacyLocationKeysAreRemovedByOneTimeMigration() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val migration = source.substringAfter("private fun migrateLegacyLocationCategories()")
            .substringBefore("private fun migrateTelegramBuiltInTemplates")
        assertTrue(migration.contains("remove(KEY_MQTT_LOCATION)"))
        assertTrue(migration.contains("remove(KEY_INFLUX_LOCATION)"))
        assertTrue(source.contains("migrateLegacyLocationCategories()"))
    }

    @Test
    fun tripSummaryMigrationPreservesCustomTextWhenLegacyMarkerIsUnset() {
        val tripKey = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}trip-summary"
        val otherKey = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}charging-started"

        listOf("arbitrary custom value", "").forEach { savedTrip ->
            val prefs = InMemorySharedPreferences(mapOf(tripKey to savedTrip, otherKey to "keep me"))

            CollectorSettings.migrateTelegramBuiltInTemplates(prefs)

            assertTrue(prefs.contains(tripKey))
            assertEquals(savedTrip, prefs.getString(tripKey, null))
            assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_TRIP_SUMMARY_MIGRATION_DONE, false))
            assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE, false))
            assertEquals("keep me", prefs.getString(otherKey, null))
            assertEquals(1, prefs.commitCount)
            assertEquals(0, prefs.applyCount)

            prefs.putDirect(tripKey, "later user edit")
            CollectorSettings.migrateTelegramBuiltInTemplates(prefs)
            assertEquals("later user edit", prefs.getString(tripKey, null))
            assertEquals(1, prefs.commitCount)
        }
    }

    @Test
    fun absentTripSummaryKeepsLocalizedFallbackAndConsumesMarker() {
        val prefs = InMemorySharedPreferences()
        val tripKey = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}trip-summary"

        CollectorSettings.migrateTelegramBuiltInTemplates(prefs)

        assertFalse(prefs.contains(tripKey))
        assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_TRIP_SUMMARY_MIGRATION_DONE, false))
        assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE, false))
        assertEquals(1, prefs.commitCount)
    }

    @Test
    fun recognizedLegacyTripTemplateMigratesToTheLocalizedExpandedDefault() {
        val tripKey = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}trip-summary"
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
            "total_avg_kwh_per_100km" to "21.62"
        )
        val expected = mapOf(
            TelegramTemplateLanguage.UK to "Поїздку завершено\nПоточна поїздка: 12.3 км / 00:24:18\nПоточна витрата: 3.4 кВт·год (27.64 кВт·год/100 км), SOC: 81% -> 76%\nЗагалом: 456.7 км / 12:34:56\nЗагальна витрата: 98.7 кВт·год (21.62 кВт·год/100 км), SOC: 84% -> 75%",
            TelegramTemplateLanguage.EN to "Trip complete\nCurrent trip: 12.3 km / 00:24:18\nCurrent energy used: 3.4 kWh (27.64 kWh/100 km), SOC: 81% -> 76%\nTotal: 456.7 km / 12:34:56\nTotal energy used: 98.7 kWh (21.62 kWh/100 km), SOC: 84% -> 75%"
        )

        expected.forEach { (language, renderedExpected) ->
            val prefs = InMemorySharedPreferences(
                mapOf(
                    tripKey to "Поїздку завершено\nПоточна поїздка: {trip_distance_km} км / {trip_duration}\nВитрата: {trip_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%\nЗагалом: {total_distance_km} км / {total_duration}\nВитрата: {total_energy_kwh} кВт·год, SOC: {total_soc_start}% -> {total_soc_end}%"
                )
            )
            CollectorSettings.migrateTelegramBuiltInTemplates(prefs)
            val template = prefs.getString(tripKey, null)
                ?: TelegramTemplateCatalog.defaultTemplate(TelegramEventType.TRIP_SUMMARY, language)
            assertEquals(renderedExpected, TelegramTemplateRenderer.render(TelegramEventType.TRIP_SUMMARY, template, values).text)
        }
    }

    @Test
    fun priorStockDefaultsAreMigratedInBothLanguagesButCustomTextAndMarkersArePreserved() {
        val stockByLanguage = listOf(
            mapOf(
                "charging-started" to "Заряджання розпочато\nSOC: {soc}%\nПотужність: {battery_power_kw} кВт",
                "charging-progress" to "Заряд: {soc}%\nЗа крок: +{charge_step_added_percent}% / +{charge_step_added_kwh} кВт·год\nЧас кроку: {charge_step_duration}\nЗа сесію: +{charge_added_percent}% / +{charge_added_kwh} кВт·год\nЧас сесії: {charge_duration}",
                "charging-stopped" to "Заряджання зупинено\nSOC: {soc}%\nТривалість: {charge_duration}",
                "low-12v" to "Низька напруга 12V\nНапруга: {battery_12v} В\nЧас: {time}",
                "trip-summary" to "Поїздку завершено\nПоточна поїздка: {trip_distance_km} км / {trip_duration}\nВитрата: {trip_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%\nЗагалом: {total_distance_km} км / {total_duration}\nВитрата: {total_energy_kwh} кВт·год, SOC: {total_soc_start}% -> {total_soc_end}%"
            ),
            mapOf(
                "charging-started" to "Charging started\nSOC: {soc}%\nPower: {battery_power_kw} kW",
                "charging-progress" to "Charge: {soc}%\nThis step: +{charge_step_added_percent}% / +{charge_step_added_kwh} kWh\nStep time: {charge_step_duration}\nSession total: +{charge_added_percent}% / +{charge_added_kwh} kWh\nSession time: {charge_duration}",
                "charging-stopped" to "Charging stopped\nSOC: {soc}%\nDuration: {charge_duration}",
                "low-12v" to "Low 12V voltage\nVoltage: {battery_12v} V\nTime: {time}",
                "trip-summary" to "Trip complete\nCurrent trip: {trip_distance_km} km / {trip_duration}\nEnergy used: {trip_energy_kwh} kWh, SOC: {soc_start}% -> {soc_end}%\nTotal: {total_distance_km} km / {total_duration}\nEnergy used: {total_energy_kwh} kWh, SOC: {total_soc_start}% -> {total_soc_end}%"
            )
        )

        stockByLanguage.forEach { stock ->
            listOf(false, true).forEach { alreadyMigrated ->
                val customKey = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}telemetry-unavailable"
                val custom = "My custom\r\ntext  "
                val prefs = InMemorySharedPreferences(
                    stock.mapKeys { "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}${it.key}" } + mapOf(
                        customKey to custom,
                        CollectorSettings.KEY_TELEGRAM_TRIP_SUMMARY_MIGRATION_DONE to alreadyMigrated,
                        CollectorSettings.KEY_TELEGRAM_CHARGED_TO_100_MIGRATION_DONE to alreadyMigrated,
                        CollectorSettings.KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE to alreadyMigrated,
                        CollectorSettings.KEY_TELEGRAM_LOW_VOLTAGE to 12.9f
                    )
                )
                CollectorSettings.migrateTelegramBuiltInTemplates(prefs)
                stock.forEach { (eventKey, saved) ->
                    val value = prefs.getString("${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}$eventKey", null)
                    if (alreadyMigrated) {
                        assertEquals(saved, value)
                        // The normal settings getter resolves recognized text to the current UI-language default.
                        assertTrue(TelegramBuiltInTemplates.isKnownBuiltIn(eventKey, saved))
                    } else {
                        assertEquals(null, value)
                    }
                }
                assertEquals(custom, prefs.getString(customKey, null))
                assertEquals(12.9f, prefs.getFloat(CollectorSettings.KEY_TELEGRAM_LOW_VOLTAGE, 0f))
                assertEquals(12.5f, CollectorSettings.DEFAULT_TELEGRAM_LOW_VOLTAGE)
                assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_TRIP_SUMMARY_MIGRATION_DONE, false))
                assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_CHARGED_TO_100_MIGRATION_DONE, false))
                assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE, false))
            }
        }
    }

    @Test
    fun builtInTemplatesBecomeDefaultsButUnknownValuesStayExact() {
        val progressKey = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}charging-progress"
        val customKey = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}charging-started"
        val custom = "Custom\r\nvalue  "
        val prefs = InMemorySharedPreferences(
            mapOf(
                progressKey to TelegramBuiltInTemplates.CHARGING_PROGRESS_EN,
                customKey to custom
            )
        )

        CollectorSettings.migrateTelegramBuiltInTemplates(prefs)

        assertFalse(prefs.contains(progressKey))
        assertEquals(custom, prefs.getString(customKey, null))
    }

    @Test
    fun chargedTo100MigrationRunsAfterGeneralMarkerAndIsIdempotent() {
        val key = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}${TelegramEventType.CHARGED_TO_100.key}"
        val old = "Vehicle charged to 100%\nEnergy: {remaining_energy_kwh} kWh\nRange: {range_km} km"
        val prefs = InMemorySharedPreferences(
            mapOf(
                key to old,
                CollectorSettings.KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE to true
            )
        )

        CollectorSettings.migrateTelegramBuiltInTemplates(prefs)

        assertFalse(prefs.contains(key))
        assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_CHARGED_TO_100_MIGRATION_DONE, false))
        assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE, false))
        assertEquals(1, prefs.commitCount)

        CollectorSettings.migrateTelegramBuiltInTemplates(prefs)
        assertEquals(1, prefs.commitCount)
    }

    @Test
    fun chargedTo100MigrationLeavesCustomTextByteExact() {
        val key = "${CollectorSettings.KEY_TELEGRAM_TEMPLATE_PREFIX}${TelegramEventType.CHARGED_TO_100.key}"
        val custom = "Vehicle charged to 100%\nEnergy: custom\nRange: custom"
        val prefs = InMemorySharedPreferences(mapOf(key to custom))

        CollectorSettings.migrateTelegramBuiltInTemplates(prefs)

        assertEquals(custom, prefs.getString(key, null))
        assertTrue(prefs.getBoolean(CollectorSettings.KEY_TELEGRAM_CHARGED_TO_100_MIGRATION_DONE, false))
    }

    @Test
    fun languageNavigatorAndTemplateResetApisArePersistedBySettingsFacade() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val init = source.substringAfter("init {").substringBefore("}")
        val navigatorGetter = source.substringAfter("fun telegramNavigatorMask(): Int")
            .substringBefore("fun setTelegramNavigatorMask")
        assertTrue(source.contains("fun uiLanguageCode(): String"))
        assertTrue(source.contains("fun setUiLanguageCode(code: String)"))
        assertTrue(source.contains("DEFAULT_UI_LANGUAGE_CODE = \"uk\""))
        assertTrue(source.contains("fun telegramNavigatorMask(): Int"))
        assertTrue(source.contains("fun setTelegramNavigatorMask(mask: Int)"))
        assertTrue(source.contains("TelegramNavigatorMask.sanitize"))
        assertTrue(navigatorGetter.contains("DEFAULT_TELEGRAM_NAVIGATOR_MASK"))
        assertTrue(source.contains("DEFAULT_TELEGRAM_NAVIGATOR_MASK = TelegramNavigatorMask.NONE"))
        assertFalse(init.contains("NavigatorMask"))
        assertTrue(source.contains("fun clearTelegramTemplate(eventKey: String)"))
    }

    @Test
    fun postMigrationUserTemplateIsStoredExactlyEvenWhenItMatchesABuiltIn() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val setter = source.substringAfter("fun setTelegramTemplate(eventKey: String, template: String)")
            .substringBefore("fun clearTelegramTemplate")

        assertTrue(setter.contains("putString"))
        assertFalse(setter.contains("isKnownBuiltIn"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private class InMemorySharedPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        private val values = initial.toMutableMap()
        var commitCount: Int = 0
            private set
        var applyCount: Int = 0
            private set

        fun putDirect(key: String, value: Any) {
            values[key] = value
        }

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = update(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = update(key, values?.toSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = update(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = update(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = update(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = update(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = update(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
            override fun commit(): Boolean {
                flush()
                commitCount += 1
                return true
            }
            override fun apply() {
                flush()
                applyCount += 1
            }

            private fun update(key: String?, value: Any?): SharedPreferences.Editor = apply {
                key?.let { updates[it] = value }
            }

            private fun flush() {
                if (clearRequested) values.clear()
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
