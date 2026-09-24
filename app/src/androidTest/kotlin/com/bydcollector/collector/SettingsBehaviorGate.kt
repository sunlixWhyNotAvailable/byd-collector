package com.bydcollector.collector

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.AdbShellResult
import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.direct.DirectHelperStopResult
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.RuntimeDemand
import com.bydcollector.collector.telegram.TelegramNavigatorMask
import java.io.File

/** Exercises the production settings facade against isolated Android preferences. */
internal object SettingsBehaviorGate {
    fun cases(context: Context, prefix: String): List<Pair<String, () -> Unit>> = listOf(
        "settings_process_death_and_boot_policy" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                for (autoMain in listOf(false, true)) for (autoSecondary in listOf(false, true)) {
                    for (stopMain in listOf(false, true)) for (stopSecondary in listOf(false, true)) {
                        settings.setUserShutdownRequested(false)
                        settings.setAutoStartEnabled(autoMain)
                        settings.setDebugAutoStartEnabled(autoSecondary)
                        settings.setMainManuallyStopped(stopMain)
                        settings.setDebugManuallyStopped(stopSecondary)
                        settings.setPollingEnabled(true)
                        settings.setDebugPollingEnabled(true)
                        settings.clearRuntimeManualStops(includeCollection = false)
                        CollectorSettings.resetCollectionAfterProcessDeath(scoped)
                        val reopened = CollectorSettings(scoped)
                        check(reopened.isPollingEnabled() == (autoMain && !stopMain))
                        check(reopened.isDebugPollingEnabled() == (autoSecondary && !stopSecondary))
                        check(reopened.isMainManuallyStopped() == stopMain)
                        check(reopened.isDebugManuallyStopped() == stopSecondary)
                    }
                }
                settings.setUserShutdownRequested(true)
                CollectorSettings.resetCollectionAfterProcessDeath(scoped)
                check(!settings.isPollingEnabled() && !settings.isDebugPollingEnabled())
            }
        },
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
                check(settings.setUserShutdownPhase(CollectorSettings.SHUTDOWN_PHASE_STOPPING, "test-token", "in progress"))
                check(settings.rememberShutdownListenerStateIfAbsent(0))
                check(settings.rememberShutdownListenerStateIfAbsent(2))
                check(CollectorSettings(scoped).shutdownListenerPreviousState() == 0) {
                    "repeated Shutdown must not overwrite the pre-shutdown listener state"
                }
                check(CollectorSettings(scoped).runtimeDemand() == RuntimeDemand())
                check(settings.clearUserShutdownRequestIfSet())
                check(settings.runtimeDemand() == all.copy(telegram = true, keepAlive = true))
                check(settings.userShutdownPhase() == CollectorSettings.SHUTDOWN_PHASE_IDLE)
                check(settings.userShutdownToken() == null && settings.userShutdownDetail() == null)
                check(settings.shutdownListenerPreviousState() == 0) { "saved state belongs to actual component restoration" }
                check(settings.clearShutdownListenerPreviousState())
                // The result now means durable clear succeeded, including idempotent explicit reopen.
                check(settings.clearUserShutdownRequestIfSet())
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
        "helper_update_marker_is_durable_and_demand_gated" to {
            isolated(context, prefix) { scoped ->
                val settings = CollectorSettings(scoped)
                check(settings.helperReplacementPending(500L)) { "missing confirmed stamp must be stale" }
                check(settings.markHelperReplacementPending())
                check(CollectorSettings(scoped).helperReplacementPending(500L)) {
                    "pending marker did not survive settings recreation"
                }
                check(!settings.helperReplacementAllowed()) { "no collection demand must defer replacement" }

                settings.setPollingEnabled(true)
                check(settings.helperReplacementAllowed())
                settings.setMainManuallyStopped(true)
                check(!settings.helperReplacementAllowed())
                settings.setDebugPollingEnabled(true)
                check(settings.helperReplacementAllowed())
                settings.setUserShutdownRequested(true)
                check(!settings.helperReplacementAllowed()) { "Shutdown must block replacement" }
                settings.setUserShutdownRequested(false)

                check(settings.confirmHelperReplacement(500L))
                check(!settings.helperReplacementPending(500L))
                check(settings.helperReplacementPending(501L)) { "same-version package update timestamp was ignored" }
                check(settings.helperReplacementPending(null)) { "missing installed timestamp must be stale" }
            }
        },
        "helper_update_replacement_stops_absent_launches_pings_then_confirms" to {
            isolated(context, prefix) { scoped ->
                runHelperReplacementSequence(scoped, failLaunch = false, collectionDemand = true)
            }
        },
        "helper_update_incompatible_protocol_is_stopped_before_replacement" to {
            isolated(context, prefix) { scoped ->
                runHelperReplacementSequence(scoped, failLaunch = false, collectionDemand = true, incompatible = true)
            }
        },
        "helper_update_replacement_failure_and_no_demand_remain_pending" to {
            isolated(context, prefix) { scoped ->
                runHelperReplacementSequence(scoped, failLaunch = true, collectionDemand = true)
            }
            isolated(context, "${prefix}_no_demand") { scoped ->
                runHelperReplacementSequence(scoped, failLaunch = false, collectionDemand = false)
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

    private fun runHelperReplacementSequence(context: Context, failLaunch: Boolean, collectionDemand: Boolean, incompatible: Boolean = false) {
        @Suppress("DEPRECATION")
        val updateTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        check(updateTime > 0L) { "installed package update timestamp is unavailable" }
        val settings = CollectorSettings(context)
        settings.setPollingEnabled(collectionDemand)
        check(settings.markHelperReplacementPending())

        val events = mutableListOf<String>()
        val helper = ReplacementSequenceHelper(events, incompatible)
        val result = DirectBridgeManager.ensureRunning(
            context = context,
            adbClient = AdbLocalClient(File(context.filesDir, "unused_helper_update_test_keys")),
            helper = helper,
            ownerMode = DirectHelperOwnerMode.APP,
            shellRunner = { command, _ ->
                when {
                    command == DirectBridgeManager.helperAbsenceCommand() -> {
                        events += "absence"
                        AdbShellResult(ok = true, output = "", error = null, elapsedMs = 0L)
                    }
                    command.contains("setsid app_process") -> {
                        events += "launch"
                        if (!failLaunch) { helper.alive = true; helper.incompatible = false }
                        AdbShellResult(
                            ok = !failLaunch,
                            output = "",
                            error = if (failLaunch) "launch failed" else null,
                            elapsedMs = 0L
                        )
                    }
                    else -> error("unexpected shell command during helper replacement")
                }
            }
        )

        if (!collectionDemand) {
            check(!result.ok)
            check(events == listOf("initial_ping")) { "replacement performed work without collection demand: $events" }
            check(settings.helperReplacementPending(updateTime))
            return
        }
        check(helper.stopMode == DirectHelperOwnerMode.APP_GAP_SPOOL) {
            "replacement did not use the helper's actual owner mode"
        }

        if (failLaunch) {
            check(!result.ok)
            check(events == listOf("initial_ping", "stop", "absence", "launch")) { "unexpected failed replacement order: $events" }
            check(settings.helperReplacementPending(updateTime)) { "failed launch cleared the pending marker" }
        } else {
            check(result.ok) { result.message }
            check(events == listOf("initial_ping", "stop", "absence", "launch", "launch_ping")) {
                "replacement did not follow stop→absence→launch→ping order: $events"
            }
            check(!settings.helperReplacementPending(updateTime)) { "successful ping did not confirm the installed update" }
        }
    }

    private class ReplacementSequenceHelper(private val events: MutableList<String>, var incompatible: Boolean) : DirectVehicleHelper {
        var alive = true
        var stopMode: DirectHelperOwnerMode? = null
        private var pingCount = 0

        override fun isAlive(): Boolean {
            events += if (pingCount++ == 0) "initial_ping" else "launch_ping"
            return alive && !incompatible
        }

        override fun ownerMode(): DirectHelperOwnerMode? = if (alive && !incompatible) DirectHelperOwnerMode.APP_GAP_SPOOL else null

        override fun ownerModeForStop(): DirectHelperOwnerMode? = if (alive) DirectHelperOwnerMode.APP_GAP_SPOOL else null

        override fun requestStop(ownerMode: DirectHelperOwnerMode): DirectHelperStopResult {
            events += "stop"
            stopMode = ownerMode
            alive = false
            return DirectHelperStopResult(status = 0, accepted = true)
        }

        override fun read(entry: DirectFidEntry): DirectHelperReadResult = error("read is not used by replacement")
    }
}
