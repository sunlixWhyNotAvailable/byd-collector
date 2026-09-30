package com.bydcollector.collector.data.local

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcCurrentMeanProviderTest {
    @Test
    fun weightedMeanUsesOnlyCurrentNondeletedPositiveDistanceFinitePairs() {
        val snapshot = EcCurrentMeanReader.meanFromRows(
            listOf(
                EcMeanRow(0, 2_600.0, 600.0),
                EcMeanRow(0, 2_533.0, 624.7),
                EcMeanRow(1, 100.0, 50.0),
                EcMeanRow(0, 0.0, 50.0),
                EcMeanRow(0, -1.0, 10.0),
                EcMeanRow(0, Double.NaN, 1.0),
                EcMeanRow(0, 10.0, null),
                EcMeanRow(0, 10.0, Double.POSITIVE_INFINITY)
            ),
            "/energydata/EC_database.db"
        )

        assertEquals(EcMeanStatus.AVAILABLE, snapshot.status)
        assertEquals(23.85934, snapshot.meanKwhPer100Km!!, 0.00001)
        assertEquals("/energydata/EC_database.db", snapshot.sourcePath)
    }

    @Test
    fun finiteNegativeNetTripEnergyRemainsPairedInWeightedMean() {
        val snapshot = EcCurrentMeanReader.meanFromRows(
            listOf(EcMeanRow(0, 1.0, 1.0), EcMeanRow(0, 1.0, -0.2))
        )

        assertEquals(EcMeanStatus.AVAILABLE, snapshot.status)
        assertEquals(40.0, snapshot.meanKwhPer100Km)
    }

    @Test
    fun emptyOrInvalidRowsDoNotProduceAnAverage() {
        val noRows = EcCurrentMeanReader.meanFromRows(emptyList())
        val invalidRows = EcCurrentMeanReader.meanFromRows(
            listOf(
                EcMeanRow(1, 10.0, 2.0),
                EcMeanRow(0, 0.0, 0.0),
                EcMeanRow(0, Double.POSITIVE_INFINITY, 2.0),
                EcMeanRow(0, 10.0, Double.NaN)
            )
        )
        val zeroAggregate = EcCurrentMeanReader.meanFromRows(listOf(EcMeanRow(0, 10.0, 0.0)))

        assertEquals(EcMeanStatus.NO_VALID_TRIPS, noRows.status)
        assertNull(noRows.meanKwhPer100Km)
        assertEquals(EcMeanStatus.NO_VALID_TRIPS, invalidRows.status)
        assertEquals(EcMeanStatus.INVALID_MEAN, zeroAggregate.status)
        assertNull(zeroAggregate.meanKwhPer100Km)
    }

    @Test
    fun refreshIsCoalescedAndFailureClearsThePreviousEcMean() {
        val results = ArrayDeque(
            listOf(
                EcMeanSnapshot(24.0, EcMeanStatus.AVAILABLE),
                EcMeanSnapshot(null, EcMeanStatus.READ_FAILED)
            )
        )
        val executor = QueuedExecutor()
        val provider = EcCurrentMeanProvider(EcMeanReader { results.removeFirst() }, executor) { 1_000L }

        assertTrue(provider.refreshAsync())
        assertFalse(provider.refreshAsync())
        assertEquals(1, executor.size)
        executor.runNext()
        assertEquals(24.0, provider.snapshot().meanKwhPer100Km)

        assertTrue(provider.refreshAsync())
        executor.runNext()
        assertEquals(EcMeanStatus.READ_FAILED, provider.snapshot().status)
        assertNull(provider.snapshot().meanKwhPer100Km)
        provider.close()
    }

    @Test
    fun cachedMeanExpiresAfterFiveMinutesAndClosePreventsQueuedReads() {
        val executor = QueuedExecutor()
        val now = longArrayOf(10L)
        var reads = 0
        val provider = EcCurrentMeanProvider(
            EcMeanReader {
                reads++
                EcMeanSnapshot(24.0, EcMeanStatus.AVAILABLE)
            },
            executor
        ) { now[0] }

        assertTrue(provider.refreshAsync())
        executor.runNext()
        now[0] = 10L + EC_MEAN_MAX_AGE_MS
        assertEquals(EcMeanStatus.STALE, provider.snapshot().status)
        assertNull(provider.snapshot().meanKwhPer100Km)

        assertTrue(provider.refreshAsync())
        provider.close()
        executor.runNext()
        assertEquals(1, reads)
        assertEquals(EcMeanStatus.CLOSED, provider.snapshot().status)
        assertFalse(provider.refreshAsync())
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()
    }
}
