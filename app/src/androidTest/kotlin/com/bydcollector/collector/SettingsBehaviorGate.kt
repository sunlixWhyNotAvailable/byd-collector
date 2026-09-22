package com.bydcollector.collector

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.RuntimeDemand
import com.bydcollector.collector.telegram.TelegramNavigatorMask

/** Exercises the production settings facade against isolated Android preferences. */
internal object SettingsBehaviorGate {
    fun cases(context: Context, prefix: String): List<Pair<String, () -> Unit>> = listOf(
        "settings_independent_collection_matrix" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                check(settings.runtimeDemand() == RuntimeDemand())
                check(!settings.hasActiveAccessWork())
                for (main in listOf(false, true)) for (secondary in listOf(false, true)) {
                    for (stopMain in listOf(false, true)) for (stopSecondary in listOf(false, true)) {
                        settings.setAutoStartEnabled(main)
                        settings.setDebugAutoStartEnabled(secondary)
                        settings.setMainManuallyStopped(stopMain)
                        settings.setDebugManuallyStopped(stopSecondary)
                        val expected = RuntimeDemand(main = main && !stopMain, debug = secondary && !stopSecondary)
                        check(settings.runtimeDemand() == expected) { "main=$main secondary=$secondary stops=$stopMain/$stopSecondary" }
                        check(CollectorSettings(scoped).runtimeDemand() == expected) { "reopen changed collection demand" }
                        settings.setPollingEnabled(main)
                        settings.setDebugPollingEnabled(secondary)
                        check(settings.hasActiveAccessWork() == expected.any)
                    }
                }
            }
        },
        "settings_manual_stops_and_shutdown" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                settings.setAutoStartEnabled(true)
                settings.setDebugAutoStartEnabled(true)
                settings.setMqttAutoStartEnabled(true)
                settings.setInfluxAutoStartEnabled(true)
                val all = settings.runtimeDemand()
                settings.setMainManuallyStopped(true)
                check(settings.runtimeDemand() == all.copy(main = false))
                settings.setDebugManuallyStopped(true)
                check(settings.runtimeDemand() == all.copy(main = false, debug = false))
                settings.setMqttManuallyStopped(true)
                settings.setInfluxManuallyStopped(true)
                check(!settings.runtimeDemand().any)
                settings.clearRuntimeManualStops()
                check(settings.runtimeDemand() == all) { "manual stops changed auto-start preferences" }
                settings.setTelegramEnabled(true)
                settings.setConnectivityRecoveryEnabled(true)
                settings.setUserShutdownRequested(true)
                check(CollectorSettings(scoped).runtimeDemand() == RuntimeDemand())
                check(settings.clearUserShutdownRequestIfSet())
                check(settings.runtimeDemand() == all.copy(telegram = true, keepAlive = true))
                check(!settings.clearUserShutdownRequestIfSet())
            }
        },
        "settings_enabled_exports_recover_independently" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                settings.setMqttEnabled(true)
                settings.setInfluxEnabled(true)
                check(!settings.runtimeDemand().any)
                check(settings.runtimeDemand(includeEnabledExports = true) == RuntimeDemand(mqtt = true, influx = true))
                settings.setMqttManuallyStopped(true)
                check(settings.runtimeDemand(includeEnabledExports = true) == RuntimeDemand(influx = true))
                settings.setInfluxManuallyStopped(true)
                check(!settings.runtimeDemand(includeEnabledExports = true).any)
                check(!settings.isAutoStartEnabled() && !settings.isDebugAutoStartEnabled())
            }
        },
        "settings_connectivity_and_discovery" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                check(!settings.keepAliveConfig().anyEnabled)
                for (enabled in listOf(true, false)) {
                    settings.setConnectivityRecoveryEnabled(enabled)
                    val reopened = CollectorSettings(scoped)
                    check(reopened.keepAliveConfig().keepWifi == enabled)
                    check(reopened.keepAliveConfig().keepMobileData == enabled)
                    check(!reopened.keepAliveConfig().keepBluetooth)
                    check(reopened.mqttConfig(includeCredentials = false).discoveryEnabled)
                }
            }
        },
        "settings_templates_language_and_navigation_survive_reopen" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                val key = "trip-summary"
                val custom = "My trip\r\n{trip_distance_km}  "
                settings.setTelegramTemplate(key, custom)
                settings.setUiLanguageCode(" EN ")
                settings.setTelegramNavigatorMask(-1)
                val reopened = CollectorSettings(scoped)
                check(reopened.telegramTemplate(key) == custom)
                check(reopened.uiLanguageCode() == "en")
                check(reopened.telegramNavigatorMask() == TelegramNavigatorMask.sanitize(-1))
                reopened.clearTelegramTemplate(key)
                check(CollectorSettings(scoped).telegramTemplate(key) == null)
            }
        },
        "settings_never_read_late_plaintext_credentials" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                val preferences = scoped.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
                // A stale writer reintroducing old keys must not bypass the Keystore getter.
                preferences.edit().putString(CollectorSettings.KEY_MQTT_USERNAME, "plaintext-user")
                    .putString(CollectorSettings.KEY_MQTT_PASSWORD, "plaintext-password").commit()
                check(settings.mqttUsername().isEmpty())
                check(settings.mqttPassword().isEmpty())
            }
        }
    )

    private fun isolated(base: Context, prefix: String, run: (Context) -> Unit) {
        val names = linkedSetOf<String>()
        val scoped = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scopedName = "${prefix}_$name"
                names += scopedName
                return base.getSharedPreferences(scopedName, mode)
            }
        }
        try {
            run(scoped)
        } finally {
            names.forEach { check(base.deleteSharedPreferences(it)) { "Could not remove test preferences: $it" } }
        }
    }
}
