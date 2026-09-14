package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.data.trips.TripDayGroup
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripSummary
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripTime
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
        assertEquals(2.5, mapped.dischargedKwh)
        assertEquals(0.5, mapped.regeneratedKwh)
        assertEquals(2.0, mapped.netKwh)
        assertEquals(16.0, mapped.averageConsumptionKwhPer100Km)
        assertEquals(TripEnergyCompleteness.COMPLETE, mapped.energyCompleteness)
        assertEquals(50.45, mapped.route.single().latitude)
        assertEquals(0L, mapped.route.single().sequence)
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

    @Test
    fun displaysMixedZoneTimesAndDerivesOffsetSafeDuration() {
        val start = "2026-08-23T09:00:00Z"
        val end = "2026-08-23T12:30:00+03:00"
        val mapped = TripsUiMapper.years(
            listOf(TripDayGroup(2026, 8, 23, listOf(summary("trip-1").copy(startedAt = start, endedAt = end, durationMs = null)))),
            UiLanguage.EN
        ).single().months.single().days.single().trips.single()

        assertEquals(TripTime.localTime(start)?.withNano(0).toString(), mapped.startAt)
        assertEquals(TripTime.localTime(end)?.withNano(0).toString(), mapped.endAt)
        assertEquals("0:30", mapped.duration)
    }

    @Test
    fun legacyEnergyIsNotSubstitutedForNewGrossAndNetFields() {
        val legacy = summary("legacy").copy(
            energyKwh = 7.5,
            averageConsumptionKwhPer100Km = 60.0,
            dischargedKwh = null,
            regeneratedKwh = null,
            netKwh = null,
            energyPartial = null
        )

        val year = TripsUiMapper.years(
            listOf(TripDayGroup(2026, 8, 17, listOf(legacy))),
            UiLanguage.EN
        ).single()
        val mapped = year.months.single().days.single().trips.single()

        assertEquals(null, mapped.dischargedKwh)
        assertEquals(null, mapped.netKwh)
        assertEquals(null, mapped.averageConsumptionKwhPer100Km)
        assertEquals(TripEnergyCompleteness.UNAVAILABLE, mapped.energyCompleteness)
        assertEquals(null, year.netKwh)
        assertEquals(TripEnergyCompleteness.UNAVAILABLE, year.energyCompleteness)
    }

    @Test
    fun groupAverageUsesOnlyDistanceCorrespondingToNewNetAndMarksMixedHistoryPartial() {
        val downhill = summary("new").copy(
            distanceKm = 10.0,
            dischargedKwh = 1.0,
            regeneratedKwh = 2.0,
            netKwh = -1.0,
            energyPartial = false
        )
        val legacy = summary("legacy").copy(
            distanceKm = 20.0,
            dischargedKwh = null,
            regeneratedKwh = null,
            netKwh = null,
            energyPartial = null
        )

        val year = TripsUiMapper.years(
            listOf(TripDayGroup(2026, 8, 17, listOf(downhill, legacy))),
            UiLanguage.EN
        ).single()

        assertEquals(30.0, year.distanceKm)
        assertEquals(-1.0, year.netKwh)
        assertEquals(-10.0, year.averageConsumptionKwhPer100Km)
        assertEquals(TripEnergyCompleteness.PARTIAL, year.energyCompleteness)
    }

    @Test
    fun mapsCurrentSessionWithoutInventingAnEndAndKeepsIncrementalSequence() {
        val session = TripSession(
            tripId = "open-trip",
            startedAt = "2026-08-17T12:00:00Z",
            durationMs = 60_000L,
            distanceKm = 2.0,
            startSoc = 70.0,
            endSoc = 69.0,
            dischargedKwh = 0.8,
            regeneratedKwh = 0.2,
            netKwh = 0.6,
            energyPartial = true,
            energyObservedAt = "2026-08-17T12:01:00Z"
        )
        val route = listOf(
            RoutePoint("open-trip", 3L, observedAt = "2026-08-17T12:01:00Z", latitude = 50.45, longitude = 30.52)
        )

        val current = TripsUiMapper.current(session, UiLanguage.EN, route)

        assertTrue(current.trip.open)
        assertEquals("—", current.trip.endAt)
        assertEquals(TripEnergyCompleteness.PARTIAL, current.trip.energyCompleteness)
        assertEquals(30.0, current.trip.averageConsumptionKwhPer100Km)
        assertEquals(4L, current.nextRouteSequence)
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
        movementObserved = true,
        dischargedKwh = 2.5,
        regeneratedKwh = 0.5,
        netKwh = 2.0,
        energyPartial = false
    )
}
