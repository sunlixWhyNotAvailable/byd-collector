package com.bydcollector.collector.maintenance

import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

class DatabaseMaintenanceGate {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> withRead(action: () -> T): T = lock.readLock().withLock(action)

    /** Diagnostics must not wait behind active or queued database maintenance. */
    fun <T : Any> tryRead(action: () -> T): T? {
        val read = lock.readLock()
        if (!read.tryLock(0, TimeUnit.MILLISECONDS)) return null
        return try { action() } finally { read.unlock() }
    }

    /** File maintenance fails closed instead of moving a database beneath a stuck writer. */
    fun <T : Any> tryWithExclusive(timeoutMs: Long, action: () -> T): T? {
        require(timeoutMs >= 0)
        val write = lock.writeLock()
        val acquired = try {
            write.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null
        return try { action() } finally { write.unlock() }
    }

    fun <T> withExclusive(action: () -> T): T = lock.writeLock().withLock(action)
}
