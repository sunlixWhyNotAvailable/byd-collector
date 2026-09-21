package com.bydcollector.collector.data.local

/** Per-store initialization, never a cache of cursor positions or queue counts. */
internal class InfluxCursorInitializer {
    private val ensured = mutableSetOf<String>()

    @Synchronized
    fun ensure(fieldKeys: Set<String>, initialize: (String) -> Unit) {
        fieldKeys.forEach { fieldKey ->
            if (fieldKey !in ensured) {
                initialize(fieldKey)
                // A failed initialization must remain retryable.
                ensured += fieldKey
            }
        }
    }

    @Synchronized
    fun clear() {
        ensured.clear()
    }
}
