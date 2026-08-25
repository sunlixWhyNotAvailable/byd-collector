package com.bydcollector.collector.maintenance

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseMaintenanceGateTest {
    @Test
    fun queuedMainAndDebugReadersOpenOnlyAfterExclusiveMaintenanceCompletes() {
        val gate = DatabaseMaintenanceGate()
        val executor = Executors.newFixedThreadPool(3)
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        val maintenanceComplete = CountDownLatch(1)
        val readersQueued = CountDownLatch(2)
        val mainEntered = CountDownLatch(1)
        val debugEntered = CountDownLatch(1)
        val openAttempts = AtomicInteger(0)
        val events = Collections.synchronizedList(mutableListOf<String>())

        try {
            executor.execute {
                gate.withExclusive {
                    events += "close"
                    maintenanceEntered.countDown()
                    check(releaseMaintenance.await(2, TimeUnit.SECONDS))
                    events += "move"
                    events += "recreate"
                }
                maintenanceComplete.countDown()
            }
            assertTrue(maintenanceEntered.await(1, TimeUnit.SECONDS))

            fun queueReader(name: String, entered: CountDownLatch) {
                executor.execute {
                    readersQueued.countDown()
                    gate.withRead {
                        openAttempts.incrementAndGet()
                        events += name
                        entered.countDown()
                    }
                }
            }

            queueReader("main_open", mainEntered)
            queueReader("debug_open", debugEntered)
            assertTrue(readersQueued.await(1, TimeUnit.SECONDS))
            assertFalse(mainEntered.await(100, TimeUnit.MILLISECONDS))
            assertFalse(debugEntered.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, openAttempts.get())

            releaseMaintenance.countDown()
            assertTrue(maintenanceComplete.await(1, TimeUnit.SECONDS))
            assertTrue(mainEntered.await(1, TimeUnit.SECONDS))
            assertTrue(debugEntered.await(1, TimeUnit.SECONDS))
            assertEquals(2, openAttempts.get())
            assertTrue(events.indexOf("main_open") > events.indexOf("recreate"))
            assertTrue(events.indexOf("debug_open") > events.indexOf("recreate"))
        } finally {
            releaseMaintenance.countDown()
            executor.shutdownNow()
        }
    }
}
