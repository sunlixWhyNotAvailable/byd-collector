package com.bydcollector.collector.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeepAliveStopRetryScheduleTest {
    @Test
    fun retriesUseOneFiveAndFifteenMinuteDelaysThenStop() {
        assertEquals(60_000L, KeepAliveStopRetrySchedule.delayMs(0))
        assertEquals(5 * 60_000L, KeepAliveStopRetrySchedule.delayMs(1))
        assertEquals(15 * 60_000L, KeepAliveStopRetrySchedule.delayMs(2))
        assertNull(KeepAliveStopRetrySchedule.delayMs(3))
    }
}
