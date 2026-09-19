package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.data.trips.TripDayGroup
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripSummary
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame

class TripsUiMapperTest {
    @Test
    fun hierarchyRefreshRetainsLoadedRouteIdentityButRefreshesMetricsAndTitles() {
        val groups = listOf(TripDayGroup(2026, 8, 17, listOf(summary("trip-1"))))
        val previous = TripsUiMapper.years(groups, UiLanguage.EN, mapOf("trip-1" to listOf(
            RoutePoint("trip-1", 0, observedAt = "2026-08-17T12:00:00Z", latitude = 50.0, longitude = 30.0)
        )))
        val fresh = TripsUiMapper.years(listOf(groups.single().copy(trips = listOf(
            summary("trip-1").copy(distanceKm = 25.0)
        ))), UiLanguage.UK)
        fun trip(years: List<TripYearUi>) = years.single().months.single().days.single().trips.single()
        val retained = TripsUiMapper.retainRoutes(fresh, previous, null)
        assertSame(trip(previous).route, trip(retained).route)
        assertEquals(25.0, trip(retained).distanceKm)
        assertEquals("Серпень 2026", retained.single().months.single().title)
        assertTrue(trip(TripsUiMapper.retainRoutes(fresh, previous, "trip-1")).route.isEmpty())
        assertTrue(TripsUiMapper.retainRoutes(emptyList(), previous, null).isEmpty())
    }

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
    fun legacyEnergyRestoresNetAndAverageWithoutInventingSplitOrBadge() {
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
        assertEquals(null, mapped.regeneratedKwh)
        assertEquals(7.5, mapped.netKwh)
        assertEquals(60.0, mapped.averageConsumptionKwhPer100Km)
        assertEquals(TripEnergyCompleteness.UNAVAILABLE, mapped.energyCompleteness)
        assertEquals(TripEnergyCompleteness.COMPLETE, mapped.netCompleteness)
        assertEquals(7.5, year.netKwh)
        assertEquals(60.0, year.averageConsumptionKwhPer100Km)
        assertEquals(7.5, year.months.single().netKwh)
        assertEquals(7.5, year.months.single().days.single().netKwh)
        assertEquals(TripEnergyCompleteness.COMPLETE, year.netCompleteness)
        assertEquals(TripEnergyCompleteness.UNAVAILABLE, year.energyCompleteness)
    }

    @Test
    fun mixedHistoryUsesSelectedNetAndMatchingDistanceWithoutLegacyBadge() {
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
        assertEquals(1.0, year.netKwh)
        assertEquals(100.0 / 30.0, year.averageConsumptionKwhPer100Km)
        assertEquals(TripEnergyCompleteness.PARTIAL, year.energyCompleteness)
        assertEquals(TripEnergyCompleteness.COMPLETE, year.netCompleteness)
    }

    @Test
    fun selectedNetKeepsZeroNegativeAndRealPartialValuesAheadOfLegacy() {
        for (net in listOf(0.0, -2.0)) {
            val mapped = map(summary("new").copy(netKwh = net, energyKwh = 50.0, energyPartial = true))
            assertEquals(net, mapped.netKwh)
            assertEquals(net * 100.0 / 12.5, mapped.averageConsumptionKwhPer100Km)
            assertEquals(TripEnergyCompleteness.PARTIAL, mapped.netCompleteness)
        }
    }

    @Test
    fun unavailableEnergyAndInvalidDistanceNeverInventValues() {
        val legacy = summary("legacy").copy(dischargedKwh = null, regeneratedKwh = null, netKwh = null, energyPartial = null)
        for (distance in listOf(null, 0.0, -1.0, Double.NaN)) {
            val mapped = map(legacy.copy(distanceKm = distance))
            assertEquals(2.0, mapped.netKwh)
            assertEquals(null, mapped.averageConsumptionKwhPer100Km)
            assertEquals(TripEnergyCompleteness.COMPLETE, mapped.netCompleteness)
        }
        for (energy in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            val mapped = map(legacy.copy(energyKwh = energy))
            assertEquals(null, mapped.netKwh)
            assertEquals(null, mapped.averageConsumptionKwhPer100Km)
            assertEquals(TripEnergyCompleteness.UNAVAILABLE, mapped.netCompleteness)
        }
        assertEquals(0.0, map(legacy.copy(energyKwh = 0.0)).netKwh)
        assertEquals(2.0, map(legacy.copy(netKwh = Double.NaN)).netKwh)
    }

    @Test
    fun groupExcludesDistanceWithNoEnergyAndKeepsRealMissingCoverage() {
        val missing = summary("missing").copy(energyKwh = null, dischargedKwh = null, regeneratedKwh = null, netKwh = null)
        val year = TripsUiMapper.years(listOf(TripDayGroup(2026, 8, 17, listOf(summary("new"), missing))), UiLanguage.UK).single()
        assertEquals(25.0, year.distanceKm)
        assertEquals(2.0, year.netKwh)
        assertEquals(16.0, year.averageConsumptionKwhPer100Km)
        assertEquals(TripEnergyCompleteness.PARTIAL, year.netCompleteness)
    }

    @Test
    fun currentTripUsesSameLegacyNetFallbackWithoutDatabaseRewrite() {
        val session = TripSession(tripId = "open", startedAt = "2026-08-17T12:00:00Z", distanceKm = 10.0, energyKwh = 1.5)
        val mapped = TripsUiMapper.current(session, UiLanguage.UK).trip
        assertEquals(1.5, mapped.netKwh)
        assertEquals(15.0, mapped.averageConsumptionKwhPer100Km)
        assertEquals(null, mapped.dischargedKwh)
        assertEquals(null, mapped.regeneratedKwh)
        assertEquals(TripEnergyCompleteness.COMPLETE, mapped.netCompleteness)
        assertEquals(null, session.netKwh)
    }

    private fun map(trip: TripSummary): TripSummaryUi = TripsUiMapper.years(
        listOf(TripDayGroup(2026, 8, 17, listOf(trip))), UiLanguage.EN
    ).single().months.single().days.single().trips.single()

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
