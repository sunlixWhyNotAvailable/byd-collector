package com.bydcollector.collector.util

//keeps repeated UI/status probes bounded without changing their callers' data model
class TimedCache<T>(
    private val ttlMs: Long
) {
    private var entry: Entry<T>? = null

    fun get(nowMs: Long, force: Boolean = false, load: () -> T): T {
        val current = entry
        if (!force && current != null && nowMs - current.loadedAtMs < ttlMs) {
            return current.value
        }
        return load().also { value -> entry = Entry(value, nowMs) }
    }

    fun clear() {
        entry = null
    }

    private data class Entry<T>(
        val value: T,
        val loadedAtMs: Long
    )
}
