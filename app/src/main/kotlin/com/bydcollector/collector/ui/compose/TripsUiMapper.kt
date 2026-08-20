package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripDayGroup
import com.bydcollector.collector.data.trips.TripMetrics
import com.bydcollector.collector.data.trips.TripSummary
import java.time.Duration
import java.time.Month
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale

object TripsUiMapper {
    fun years(
        groups: List<TripDayGroup>,
        language: UiLanguage,
        routes: Map<String, List<RoutePoint>> = emptyMap()
    ): List<TripYearUi> {
        val locale = if (language == UiLanguage.UK) Locale("uk") else Locale.ENGLISH
        return groups.groupBy { it.year }.toSortedMap(compareByDescending { it }).map { (year, yearDays) ->
            val months = yearDays.groupBy { it.month }.toSortedMap(compareByDescending { it }).map { (month, monthDays) ->
                val days = monthDays.sortedByDescending { it.day }.map { day ->
                    val trips = day.trips.map { summary -> summary.toUi(routes[summary.tripId].orEmpty()) }
                    TripDayUi(
                        title = day.day.toString().padStart(2, '0'),
                        distanceKm = trips.sumOrNull { it.distanceKm },
                        energyKwh = trips.sumOrNull { it.energyKwh },
                        averageConsumptionKwhPer100Km = average(trips),
                        trips = trips
                    )
                }
                val monthTrips = days.flatMap { it.trips }
                TripMonthUi(
                    title = runCatching { Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale) }
                        .getOrDefault(month.toString().padStart(2, '0')),
                    distanceKm = monthTrips.sumOrNull { it.distanceKm },
                    energyKwh = monthTrips.sumOrNull { it.energyKwh },
                    averageConsumptionKwhPer100Km = average(monthTrips),
                    days = days
                )
            }
            val yearTrips = months.flatMap { month -> month.days.flatMap { it.trips } }
            TripYearUi(
                title = year.toString(),
                distanceKm = yearTrips.sumOrNull { it.distanceKm },
                energyKwh = yearTrips.sumOrNull { it.energyKwh },
                averageConsumptionKwhPer100Km = average(yearTrips),
                months = months
            )
        }
    }

    private fun TripSummary.toUi(route: List<RoutePoint>): TripSummaryUi {
        val start = parse(startedAt)
        val end = endedAt?.let(::parse)
        return TripSummaryUi(
            id = tripId,
            startAt = start?.toLocalTime()?.withNano(0)?.toString() ?: startedAt,
            endAt = end?.toLocalTime()?.withNano(0)?.toString() ?: "—",
            duration = formatDuration(durationMs ?: durationBetween(start, end)),
            distanceKm = distanceKm,
            socStart = startSoc,
            socEnd = endSoc,
            energyKwh = energyKwh,
            averageConsumptionKwhPer100Km = averageConsumptionKwhPer100Km,
            route = route.map { point ->
                TripRoutePointUi(
                    latitude = point.latitude ?: 0.0,
                    longitude = point.longitude ?: 0.0,
                    speedKmh = point.speedKmh,
                    consumptionKwhPer100Km = point.instantaneousConsumptionKwhPer100Km,
                    gap = point.kind == RoutePoint.KIND_GAP
                )
            }
        )
    }

    private fun parse(value: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(value) }.getOrNull()

    private fun durationBetween(start: OffsetDateTime?, end: OffsetDateTime?): Long? =
        if (start == null || end == null) null else Duration.between(start, end).toMillis().coerceAtLeast(0L)

    private fun formatDuration(durationMs: Long?): String {
        if (durationMs == null) return "—"
        val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
        return "%d:%02d".format(Locale.US, totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun average(trips: List<TripSummaryUi>): Double? = TripMetrics.averageConsumptionKwhPer100Km(
        trips.sumOrNull { it.energyKwh },
        trips.sumOrNull { it.distanceKm }
    )

    private inline fun List<TripSummaryUi>.sumOrNull(value: (TripSummaryUi) -> Double?): Double? {
        val values = mapNotNull(value)
        return values.takeIf { it.isNotEmpty() }?.sum()
    }
}
