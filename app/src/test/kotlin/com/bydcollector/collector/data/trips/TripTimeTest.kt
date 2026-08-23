package com.bydcollector.collector.data.trips

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TripTimeTest {
    @Test
    fun zAndExplicitOffsetShareInstantAndLocalDate() {
        val z = "2026-08-23T09:00:00Z"
        val offset = "2026-08-23T12:00:00+03:00"

        assertEquals(TripTime.instant(z), TripTime.instant(offset))
        assertEquals(TripTime.localDate(z), TripTime.localDate(offset))
        assertEquals(TripTime.durationMs(z, "2026-08-23T10:00:00Z"), TripTime.durationMs(offset, "2026-08-23T13:00:00+03:00"))
    }

    @Test
    fun localDateChangesAtTheSystemZoneMidnight() {
        val midnight = LocalDate.of(2026, 8, 23).atStartOfDay(ZoneId.systemDefault()).toInstant()

        assertEquals(LocalDate.of(2026, 8, 22), TripTime.localDate(midnight.minusSeconds(1).toString()))
        assertEquals(LocalDate.of(2026, 8, 23), TripTime.localDate(midnight.toString()))
    }
}
