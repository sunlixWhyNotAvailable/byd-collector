package com.bydcollector.collector.maintenance

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class DatabaseMaintenanceGate {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> withRead(action: () -> T): T = lock.readLock().withLock(action)

    fun <T> withExclusive(action: () -> T): T = lock.writeLock().withLock(action)
}
