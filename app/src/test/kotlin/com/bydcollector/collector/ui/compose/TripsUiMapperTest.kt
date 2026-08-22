package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.data.trips.TripDayGroup
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TripsUiMapperTest {
    @Test
    fun mapsStableHierarchyIdsAndLocalizedTitles() {
        val group = TripDayGroup(2026, 8, 17, listOf(summary("trip-1")))

        val uk = TripsUiMapper.years(listOf(group), UiLanguage.UK).single()
        assertEquals("2026", uk.id)
        assertEquals("2026-08", uk.months.single().id)
        assertEquals("2026-08-17", uk.months.single().days.single().id)
        assertEquals("Серпень 2026", uk.months.single().title)
        assertEquals("17 серпня, понеділок", uk.months.single().days.single().title)

        val en = TripsUiMapper.years(listOf(group), UiLanguage.EN).single()
        assertEquals("August 2026", en.months.single().title)
        assertEquals("August 17, Monday", en.months.single().days.single().title)
    }

    @Test
    fun malformedDateKeysUseStableFallbacksWithoutDroppingTrips() {
        val group = TripDayGroup(2026, 13, 40, listOf(summary("trip-1")))

        val month = TripsUiMapper.years(listOf(group), UiLanguage.EN).single().months.single()
        val day = month.days.single()
        assertEquals("2026-13", day.id.substringBeforeLast('-'))
        assertEquals("13 2026", month.title)
        assertEquals("40", day.title)
        assertTrue(day.trips.single().id == "trip-1")
    }

    @Test
    fun preservesTripMetricsAndRouteMapping() {
        val trip = summary("trip-1")
        val mapped = TripsUiMapper.years(
            listOf(TripDayGroup(2026, 8, 17, listOf(trip))),
            UiLanguage.EN,
            routes = mapOf(
                "trip-1" to listOf(
                    RoutePoint(
                        tripId = "trip-1",
                        sequence = 0,
                        observedAt = "2026-08-17T12:00:00Z",
                        latitude = 50.45,
                        longitude = 30.52,
                        speedKmh = 42.0
                    )
                )
            )
        ).single().months.single().days.single().trips.single()

        assertEquals(trip.tripId, mapped.id)
        assertEquals(trip.distanceKm, mapped.distanceKm)
        assertEquals(trip.startSoc, mapped.socStart)
        assertEquals(trip.endSoc, mapped.socEnd)
        assertEquals(50.45, mapped.route.single().latitude)
        assertEquals(42.0, mapped.route.single().speedKmh)
    }

    @Test
    fun mapsUntrustedRoutePointsAsGaps() {
        val mapped = TripsUiMapper.years(
            listOf(TripDayGroup(2026, 8, 17, listOf(summary("trip-1")))),
            UiLanguage.EN,
            routes = mapOf("trip-1" to listOf(
                RoutePoint("trip-1", 0, RoutePoint.KIND_UNTRUSTED, "2026-08-17T12:00:00Z", latitude = 50.45, longitude = 30.52, quality = "untrusted:mock_source")
            ))
        ).single().months.single().days.single().trips.single()

        assertTrue(mapped.route.single().gap)
    }

    private fun summary(id: String) = TripSummary(
        tripId = id,
        startedAt = "2026-08-17T12:00:00Z",
        endedAt = "2026-08-17T12:30:00Z",
        durationMs = 1_800_000L,
        distanceKm = 12.5,
        startSoc = 70.0,
        endSoc = 68.0,
        energyKwh = 2.0,
        averageConsumptionKwhPer100Km = 16.0,
        quality = "ok",
        movementObserved = true
    )
}
