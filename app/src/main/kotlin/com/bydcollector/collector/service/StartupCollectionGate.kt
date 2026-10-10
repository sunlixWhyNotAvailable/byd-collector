package com.bydcollector.collector.service

/** Optional startup SQL waits for the requested sessions, not merely their async dispatch. */
internal class StartupCollectionGate {
    private var commandsHandled = false
    @Volatile var open = false
        private set

    @Synchronized fun commandsHandled() { commandsHandled = true }

    @Synchronized fun releaseIfReady(mainStarting: Boolean, secondaryStarting: Boolean): Boolean {
        if (open || !commandsHandled || mainStarting || secondaryStarting) return false
        open = true
        return true
    }
}
