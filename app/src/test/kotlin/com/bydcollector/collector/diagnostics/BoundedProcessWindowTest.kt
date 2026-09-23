package com.bydcollector.collector.diagnostics

import org.junit.Assert.*
import org.junit.Test

class BoundedProcessWindowTest {
    @Test fun rotatesAtThirtySecondBoundaryAndKeepsRawCpuDelta() {
        var elapsedMs = 2_000L
        var cpuMs = 3_000L
        val window = BoundedProcessWindow({ elapsedMs }, { cpuMs })

        assertTrue(window.recordPollDuration(BoundedProcessWindow.STREAM_MAIN, 12L))
        assertFalse(window.recordPollDuration(BoundedProcessWindow.STREAM_MAIN, 18L))
        assertTrue(window.recordPollDuration(BoundedProcessWindow.STREAM_SECONDARY, 7L))

        elapsedMs += BoundedProcessWindow.WINDOW_MS - 1L
        cpuMs = 39_000L
        assertNull(window.snapshotIfDue())
        elapsedMs += 1L
        cpuMs = 39_001L

        val first = window.snapshotIfDue()!!
        assertEquals(BoundedProcessWindow.WINDOW_MS, first.windowElapsedMs())
        assertEquals(36_001L, first.processCpuDeltaMs()!!)
        assertFalse(first.terminal())
        assertFalse(first.shortWindow())
        assertEquals(2L, first.main().totalSamples())
        assertEquals(1L, first.secondary().totalSamples())
        assertNull(window.snapshotIfDue())

        assertTrue(window.recordPollDuration(BoundedProcessWindow.STREAM_MAIN, 25L))
        elapsedMs += BoundedProcessWindow.WINDOW_MS
        cpuMs += 100L
        val second = window.snapshotIfDue()!!
        assertEquals(100L, second.processCpuDeltaMs()!!)
        assertEquals(1L, second.main().totalSamples())
        assertEquals(0L, second.secondary().totalSamples())
    }

    @Test fun retainsLastSixtyFourForNearestRankPercentilesButKeepsExactCountAndMaximum() {
        var elapsedMs = 0L
        var cpuMs = 0L
        val window = BoundedProcessWindow({ elapsedMs }, { cpuMs })

        for (sample in 0L until 6L) {
            assertEquals(sample == 0L, window.recordPollDuration(
                BoundedProcessWindow.STREAM_MAIN, 10_000L + sample))
        }
        for (sample in 1L..64L) {
            assertFalse(window.recordPollDuration(BoundedProcessWindow.STREAM_MAIN, sample))
        }
        assertTrue(window.recordPollDuration(BoundedProcessWindow.STREAM_SECONDARY, 40L))
        assertFalse(window.recordPollDuration(BoundedProcessWindow.STREAM_SECONDARY, 60L))
        assertFalse(window.recordPollDuration(BoundedProcessWindow.STREAM_SECONDARY, 50L))

        elapsedMs = 500L
        cpuMs = 800L
        val snapshot = window.currentSnapshot()
        val main = snapshot.main()
        assertEquals(70L, main.totalSamples())
        assertEquals(BoundedProcessWindow.SAMPLE_CAPACITY, main.retainedSamples())
        assertEquals(6L, main.overflowSamples())
        assertEquals(10_005L, main.maxMs()!!)
        assertEquals(32L, main.p50Ms()!!)
        assertEquals(61L, main.p95Ms()!!)

        val secondary = snapshot.secondary()
        assertEquals(3L, secondary.totalSamples())
        assertEquals(3, secondary.retainedSamples())
        assertEquals(0L, secondary.overflowSamples())
        assertEquals(60L, secondary.maxMs()!!)
        assertEquals(50L, secondary.p50Ms()!!)
        assertEquals(60L, secondary.p95Ms()!!)
        assertEquals("retained_last_64_samples_or_all_if_fewer",
            snapshot.toJson().getString("percentile_basis"))
    }

    @Test fun unavailableAndRegressedProcessCpuProduceNullDelta() {
        var elapsedMs = 0L
        var cpuReads = 0
        val unavailableAtBaseline = BoundedProcessWindow({ elapsedMs }, {
            cpuReads++
            if (cpuReads == 1) throw IllegalStateException("CPU clock unavailable")
            200L
        })
        unavailableAtBaseline.recordPollDuration(BoundedProcessWindow.STREAM_MAIN, 5L)
        elapsedMs = 250L
        assertNull(unavailableAtBaseline.currentSnapshot().processCpuDeltaMs())

        elapsedMs = 0L
        var regressedCpuMs = 100L
        val regressed = BoundedProcessWindow({ elapsedMs }, { regressedCpuMs })
        regressedCpuMs = 99L
        elapsedMs = 250L
        assertNull(regressed.currentSnapshot().processCpuDeltaMs())
    }

    @Test fun resetClearsSamplesAndTerminalSnapshotStopsCollectionUntilReset() {
        var elapsedMs = 1_000L
        var cpuMs = 500L
        val window = BoundedProcessWindow({ elapsedMs }, { cpuMs })
        assertTrue(window.recordPollDuration(BoundedProcessWindow.STREAM_MAIN, 15L))

        elapsedMs += 400L
        cpuMs += 700L
        val terminal = window.finishWindow()
        assertTrue(terminal.terminal())
        assertTrue(terminal.shortWindow())
        assertEquals(400L, terminal.windowElapsedMs())
        assertEquals(700L, terminal.processCpuDeltaMs()!!)
        assertSame(terminal, window.finishWindow())
        assertFalse(window.recordPollDuration(BoundedProcessWindow.STREAM_MAIN, 20L))

        window.reset()
        assertTrue(window.recordPollDuration(BoundedProcessWindow.STREAM_SECONDARY, 8L))
        val resetSnapshot = window.currentSnapshot()
        assertFalse(resetSnapshot.terminal())
        assertTrue(resetSnapshot.shortWindow())
        assertEquals(0L, resetSnapshot.main().totalSamples())
        assertNull(resetSnapshot.main().maxMs())
        assertEquals(1L, resetSnapshot.secondary().totalSamples())
    }
}
