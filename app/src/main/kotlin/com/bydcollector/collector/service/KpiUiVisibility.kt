package com.bydcollector.collector.service

import java.util.Collections
import java.util.IdentityHashMap

/** RAM-only UI demand. Tokens, not Activities, survive until their owner hides/disposes. */
internal object KpiUiVisibility {
    private val lock = Any()
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val listeners = mutableSetOf<() -> Unit>()

    @Volatile var visible: Boolean = false
        private set

    fun update(owner: Any, visible: Boolean) {
        val wake = synchronized(lock) {
            if (visible) owners.add(owner) else owners.remove(owner)
            val next = owners.isNotEmpty()
            if (next == this.visible) return
            this.visible = next
            listeners.toList()
        }
        // A worker can read demand/release its listener from the callback without holding our lock.
        wake.forEach { it() }
    }

    fun addListener(listener: () -> Unit) = synchronized(lock) { listeners.add(listener); Unit }

    fun removeListener(listener: () -> Unit) = synchronized(lock) { listeners.remove(listener); Unit }
}
