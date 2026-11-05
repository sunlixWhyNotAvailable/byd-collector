package com.bydcollector.collector.ha

import com.bydcollector.collector.ha.tailscale.HaTransportGate
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HaTransportGateTest {
    @Test fun handoverWaitsForBothChannelAcknowledgements() {
        val directory = Files.createTempDirectory("ha-transport-gate")
        val executor = Executors.newFixedThreadPool(3)
        val active = CountDownLatch(2)
        val finishMqtt = CountDownLatch(1)
        val finishInflux = CountDownLatch(1)
        val switched = CountDownLatch(1)
        try {
            val mqtt = executor.submit {
                HaTransportGate.operation {
                    active.countDown(); check(finishMqtt.await(5, TimeUnit.SECONDS))
                    directory.resolve("mqtt-ack").toFile().writeText("committed")
                }
            }
            val influx = executor.submit {
                HaTransportGate.operation {
                    active.countDown(); check(finishInflux.await(5, TimeUnit.SECONDS))
                    directory.resolve("influx-ack").toFile().writeText("committed")
                }
            }
            assertTrue(active.await(5, TimeUnit.SECONDS))
            val switch = executor.submit {
                HaTransportGate.switch {
                    assertEquals("committed", directory.resolve("mqtt-ack").toFile().readText())
                    assertEquals("committed", directory.resolve("influx-ack").toFile().readText())
                    switched.countDown()
                }
            }
            assertFalse(switched.await(100, TimeUnit.MILLISECONDS))
            finishMqtt.countDown(); mqtt.get(5, TimeUnit.SECONDS)
            assertFalse(switched.await(100, TimeUnit.MILLISECONDS))
            finishInflux.countDown(); influx.get(5, TimeUnit.SECONDS)
            switch.get(5, TimeUnit.SECONDS)
            assertTrue(switched.await(1, TimeUnit.SECONDS))
        } finally {
            finishMqtt.countDown(); finishInflux.countDown(); executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            Files.deleteIfExists(directory.resolve("mqtt-ack"))
            Files.deleteIfExists(directory.resolve("influx-ack"))
            Files.deleteIfExists(directory)
        }
    }
}
