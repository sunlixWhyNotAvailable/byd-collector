package com.bydcollector.collector.ha.tailscale

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/** A switch waits for complete operations, including their durable acknowledgements. */
internal object HaTransportGate {
    private val lock = ReentrantReadWriteLock(true)
    fun <T> operation(action: () -> T): T {
        lock.readLock().lockInterruptibly()
        return try { action() } finally { lock.readLock().unlock() }
    }
    fun switch(action: () -> Unit) {
        if (!lock.writeLock().tryLock(30, TimeUnit.SECONDS)) {
            throw IOException("Transport change timed out waiting for active HA operations; previous transport retained")
        }
        try { action() } finally { lock.writeLock().unlock() }
    }
}
