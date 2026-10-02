package com.bydcollector.collector.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.service.CollectorSettings
import java.util.concurrent.CopyOnWriteArraySet

/** UI cache only. A successful edit means the owner durably committed it. */
class RuntimePreferences private constructor(private val context: Context) : SharedPreferences {
    @Volatile private var values = emptyMap<String, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val main = Handler(Looper.getMainLooper())

    init { refresh() }

    @Synchronized fun refresh() = replace(RuntimeEndpoint.call(context, "preferences"))

    private fun replace(snapshot: Bundle) {
        val next = snapshot.keySet().associateWith { key ->
            @Suppress("DEPRECATION") val value = snapshot.get(key)
            if (value is ArrayList<*>) value.filterIsInstance<String>().toSet() else value
        }
        val previous = values
        values = next
        val changed = (previous.keys + next.keys).filter { previous[it] != next[it] }
        if (changed.isNotEmpty()) main.post {
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } }
        }
    }

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues?.toMutableSet()
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = Bundle()
        private val removals = arrayListOf<String>()
        private fun key(key: String): String { removals.remove(key); return key }
        override fun putString(key: String, value: String?) = apply { if (value == null) remove(key) else changes.putString(key(key), value) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply {
            if (values == null) remove(key) else changes.putStringArrayList(key(key), ArrayList(values))
        }
        override fun putInt(key: String, value: Int) = apply { changes.putInt(key(key), value) }
        override fun putLong(key: String, value: Long) = apply { changes.putLong(key(key), value) }
        override fun putFloat(key: String, value: Float) = apply { changes.putFloat(key(key), value) }
        override fun putBoolean(key: String, value: Boolean) = apply { changes.putBoolean(key(key), value) }
        override fun remove(key: String) = apply { changes.remove(key); if (key !in removals) removals.add(key) }
        override fun clear(): SharedPreferences.Editor = error("The UI cannot clear runtime settings")
        override fun commit(): Boolean = synchronized(this@RuntimePreferences) {
            val response = RuntimeEndpoint.call(context, "preferences.edit", Bundle().apply {
                putBundle("changes", changes)
                putStringArrayList("remove", removals)
            })
            val committed = response.getBoolean("committed")
            if (committed) replace(requireNotNull(response.getBundle("values")))
            committed
        }
        override fun apply() { check(commit()) { "Runtime settings were not persisted" } }
    }

    companion object {
        @Volatile private var client: RuntimePreferences? = null
        fun get(context: Context): SharedPreferences {
            if (!BuildConfig.RUNTIME_CLIENT) return context.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
            return client ?: synchronized(this) {
                client ?: RuntimePreferences(context.applicationContext).also { client = it }
            }
        }
        fun refresh(context: Context) { if (BuildConfig.RUNTIME_CLIENT) (get(context) as RuntimePreferences).refresh() }

        fun snapshot(preferences: SharedPreferences): Bundle = Bundle().apply {
            preferences.all.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
                }
            }
        }

        fun editOwner(preferences: SharedPreferences, arguments: Bundle): Bundle {
            val changes = arguments.getBundle("changes") ?: Bundle()
            val removals = arguments.getStringArrayList("remove").orEmpty()
            require(changes.size() + removals.size <= 256) { "Too many setting changes" }
            val keyPattern = Regex("[a-zA-Z0-9_.-]{1,160}")
            require((changes.keySet() + removals).all(keyPattern::matches)) { "Invalid setting key" }
            var characters = 0
            val editor = preferences.edit()
            removals.forEach(editor::remove)
            changes.keySet().forEach { key ->
                @Suppress("DEPRECATION") val value = changes.get(key)
                when (value) {
                    is String -> { characters += value.length; editor.putString(key, value) }
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> { require(value.isFinite()); editor.putFloat(key, value) }
                    is ArrayList<*> -> {
                        require(value.size <= 128 && value.all { it is String })
                        val strings = value.filterIsInstance<String>().toSet()
                        characters += strings.sumOf { it.length }
                        editor.putStringSet(key, strings)
                    }
                    else -> error("Unsupported setting type")
                }
            }
            require(characters <= 128 * 1024) { "Settings payload is too large" }
            return Bundle().apply {
                putBoolean("committed", editor.commit())
                putBundle("values", snapshot(preferences))
            }
        }
    }
}
