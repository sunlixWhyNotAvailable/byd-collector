package com.bydcollector.collector.keepalive

import android.content.SharedPreferences
import com.bydcollector.collector.service.CollectorSettings
import org.json.JSONObject

/** Owner-APP journal. A missing system key is null; a missing journal is not a null value. */
internal class WifiPolicyState(private val prefs: SharedPreferences) {
    data class Snapshot(val sleepPolicy: String?, val offSwitch: String?) {
        fun encode(): String = JSONObject().put("sleep", sleepPolicy ?: JSONObject.NULL)
            .put("off", offSwitch ?: JSONObject.NULL).toString()

        companion object {
            fun decode(value: String): Snapshot {
                val json = JSONObject(value)
                fun read(key: String): String? {
                    require(json.has(key)) { "Wi-Fi policy snapshot is missing $key" }
                    if (json.isNull(key)) return null
                    return json.getString(key).also {
                        require(it.toIntOrNull() != null) { "Invalid saved Wi-Fi policy $key: $it" }
                    }
                }
                return Snapshot(read("sleep"), read("off"))
            }
        }
    }

    data class Request(val generation: Long, val operation: String, val attempt: Int,
                       val captured: Boolean, val snapshot: String?)

    fun pending(): Boolean = prefs.getString(OPERATION, IDLE) in setOf(APPLY, RESTORE)

    fun generation(): Long = prefs.getLong(GENERATION, 0)

    fun needsWork(enabled: Boolean): Boolean = pending() || (enabled && !prefs.getBoolean(INITIALIZED, false))

    fun initializeEnabledUser(enabled: Boolean) = synchronized(LOCK) {
        if (enabled && !prefs.getBoolean(INITIALIZED, false)) {
            val edit = prefs.edit()
            stage(edit, true)
            check(edit.commit()) { "Wi-Fi policy migration journal was not persisted" }
        }
    }

    fun beginAttempt(): Request? = synchronized(LOCK) {
        if (!pending()) return@synchronized null
        val attempt = prefs.getInt(ATTEMPT, 0) + 1
        check(prefs.edit().putInt(ATTEMPT, attempt).commit()) { "Wi-Fi policy attempt was not persisted" }
        Request(prefs.getLong(GENERATION, 0), prefs.getString(OPERATION, IDLE)!!,
            attempt, prefs.getBoolean(CAPTURED, false), prefs.getString(SNAPSHOT, null))
    }

    fun isCurrent(request: Request): Boolean = prefs.getLong(GENERATION, 0) == request.generation &&
        prefs.getString(OPERATION, IDLE) == request.operation

    fun capture(request: Request, snapshot: Snapshot): Boolean = synchronized(LOCK) {
        if (!isCurrent(request)) return@synchronized false
        check(prefs.edit().putString(SNAPSHOT, snapshot.encode()).putBoolean(CAPTURED, true).commit()) {
            "Original Wi-Fi policies were not persisted; system policies were not changed"
        }
        true
    }

    fun finish(request: Request): Boolean = synchronized(LOCK) {
        if (!isCurrent(request)) return@synchronized false
        check(prefs.edit().putString(OPERATION, IDLE).commit()) { "Wi-Fi policy completion was not persisted" }
        true
    }

    private fun stage(editor: SharedPreferences.Editor, enabled: Boolean) {
        editor.putBoolean(INITIALIZED, true)
            .putLong(GENERATION, prefs.getLong(GENERATION, 0) + 1)
            .putString(OPERATION, if (enabled) APPLY else RESTORE)
            .putInt(ATTEMPT, 0)
        if (enabled) editor.remove(SNAPSHOT).putBoolean(CAPTURED, false)
    }

    companion object {
        val LOCK = Any()
        const val APPLY = "apply"
        const val RESTORE = "restore"
        private const val IDLE = "idle"
        private const val INITIALIZED = "wifiPolicyInitialized"
        private const val GENERATION = "wifiPolicyGeneration"
        private const val OPERATION = "wifiPolicyOperation"
        private const val SNAPSHOT = "wifiPolicySnapshot"
        private const val CAPTURED = "wifiPolicyCaptured"
        private const val ATTEMPT = "wifiPolicyAttempt"

        /** Called under LOCK, in the same owner transaction as the explicit toggle edit. */
        fun stageToggle(prefs: SharedPreferences, editor: SharedPreferences.Editor, enabled: Boolean) {
            val previous = prefs.getBoolean(CollectorSettings.KEY_KEEP_WIFI, false) ||
                prefs.getBoolean(CollectorSettings.KEY_KEEP_MOBILE_DATA, false)
            if (previous != enabled) WifiPolicyState(prefs).stage(editor, enabled)
        }
    }
}
