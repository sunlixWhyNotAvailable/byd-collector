package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripDayGroup
import com.bydcollector.collector.data.trips.TripMetrics
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripSummary
import com.bydcollector.collector.data.trips.TripTime
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
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
            val yearId = year.toString().padStart(4, '0')
            val months = yearDays.groupBy { it.month }.toSortedMap(compareByDescending { it }).map { (month, monthDays) ->
                val monthId = "$yearId-${month.toString().padStart(2, '0')}"
                val days = monthDays.sortedByDescending { it.day }.map { day ->
                    val dayId = "$monthId-${day.day.toString().padStart(2, '0')}"
                    val trips = day.trips.map { summary -> summary.toUi(routes[summary.tripId].orEmpty()) }
                    val energy = aggregateEnergy(trips)
                    TripDayUi(
                        id = dayId,
                        title = dayTitle(year, month, day.day, locale),
                        distanceKm = trips.sumOrNull { it.distanceKm },
                        energyKwh = trips.sumOrNull { it.energyKwh },
                        dischargedKwh = energy.dischargedKwh,
                        regeneratedKwh = energy.regeneratedKwh,
                        netKwh = energy.netKwh,
                        averageConsumptionKwhPer100Km = energy.averageConsumptionKwhPer100Km,
                        energyCompleteness = energy.completeness,
                        netCompleteness = energy.netCompleteness,
                        trips = trips
                    )
                }
                val monthTrips = days.flatMap { it.trips }
                val monthEnergy = aggregateEnergy(monthTrips)
                TripMonthUi(
                    id = monthId,
                    title = monthTitle(year, month, locale),
                    distanceKm = monthTrips.sumOrNull { it.distanceKm },
                    energyKwh = monthTrips.sumOrNull { it.energyKwh },
                    dischargedKwh = monthEnergy.dischargedKwh,
                    regeneratedKwh = monthEnergy.regeneratedKwh,
                    netKwh = monthEnergy.netKwh,
                    averageConsumptionKwhPer100Km = monthEnergy.averageConsumptionKwhPer100Km,
                    energyCompleteness = monthEnergy.completeness,
                    netCompleteness = monthEnergy.netCompleteness,
                    days = days
                )
            }
            val yearTrips = months.flatMap { month -> month.days.flatMap { it.trips } }
            val yearEnergy = aggregateEnergy(yearTrips)
            TripYearUi(
                id = yearId,
                title = year.toString(),
                distanceKm = yearTrips.sumOrNull { it.distanceKm },
                energyKwh = yearTrips.sumOrNull { it.energyKwh },
                dischargedKwh = yearEnergy.dischargedKwh,
                regeneratedKwh = yearEnergy.regeneratedKwh,
                netKwh = yearEnergy.netKwh,
                averageConsumptionKwhPer100Km = yearEnergy.averageConsumptionKwhPer100Km,
                energyCompleteness = yearEnergy.completeness,
                netCompleteness = yearEnergy.netCompleteness,
                months = months
            )
        }
    }

    fun current(session: TripSession, language: UiLanguage, route: List<RoutePoint> = emptyList()): CurrentTripUi {
        val start = TripTime.localTime(session.startedAt)
        val end = session.endedAt?.let(TripTime::localTime)
        val selectedNet = session.netKwh.validNumber() ?: session.energyKwh.validNumber()
        val splitCompleteness = energyCompleteness(session.dischargedKwh, session.regeneratedKwh, session.netKwh, session.energyPartial)
        val trip = TripSummaryUi(
            id = session.tripId,
            startAt = start?.withNano(0)?.toString() ?: session.startedAt,
            endAt = end?.withNano(0)?.toString() ?: "—",
            duration = formatDuration(session.durationMs ?: session.endedAt?.let { TripTime.durationMs(session.startedAt, it) }),
            distanceKm = session.distanceKm,
            socStart = session.startSoc,
            socEnd = session.endSoc,
            energyKwh = session.energyKwh,
            dischargedKwh = session.dischargedKwh.validNumber(),
            regeneratedKwh = session.regeneratedKwh.validNumber(),
            netKwh = selectedNet,
            averageConsumptionKwhPer100Km = signedAverage(selectedNet, session.distanceKm),
            energyCompleteness = splitCompleteness,
            netCompleteness = netCompleteness(session.netKwh, session.energyKwh, splitCompleteness),
            energyCoveredMs = session.energyCoveredMs,
            energyUncoveredMs = session.energyUncoveredMs,
            energyObservedAt = session.energyObservedAt?.let { observed ->
                TripTime.localTime(observed)?.withNano(0)?.toString() ?: observed
            },
            route = routePoints(route),
            open = session.state == TripSession.STATE_OPEN
        )
        return CurrentTripUi(
            trip = trip,
            nextRouteSequence = route.maxOfOrNull(RoutePoint::sequence)?.takeIf { it != Long.MAX_VALUE }?.plus(1L) ?: 0L
        )
    }

    fun routePoints(route: List<RoutePoint>): List<TripRoutePointUi> = route.map { point ->
        TripRoutePointUi(
            sequence = point.sequence,
            latitude = point.latitude ?: 0.0,
            longitude = point.longitude ?: 0.0,
            speedKmh = point.speedKmh,
            consumptionKwhPer100Km = point.instantaneousConsumptionKwhPer100Km,
            gap = point.kind != RoutePoint.KIND_VALID,
            final = point.isFinal
        )
    }

    private fun TripSummary.toUi(route: List<RoutePoint>): TripSummaryUi {
        val start = TripTime.localTime(startedAt)
        val end = endedAt?.let(TripTime::localTime)
        val selectedNet = netKwh.validNumber() ?: energyKwh.validNumber()
        val splitCompleteness = energyCompleteness(dischargedKwh, regeneratedKwh, netKwh, energyPartial)
        return TripSummaryUi(
            id = tripId,
            startAt = start?.withNano(0)?.toString() ?: startedAt,
            endAt = end?.withNano(0)?.toString() ?: "—",
            duration = formatDuration(durationMs ?: endedAt?.let { TripTime.durationMs(startedAt, it) }),
            distanceKm = distanceKm,
            socStart = startSoc,
            socEnd = endSoc,
            energyKwh = energyKwh,
            dischargedKwh = dischargedKwh.validNumber(),
            regeneratedKwh = regeneratedKwh.validNumber(),
            netKwh = selectedNet,
            averageConsumptionKwhPer100Km = signedAverage(selectedNet, distanceKm),
            energyCompleteness = splitCompleteness,
            netCompleteness = netCompleteness(netKwh, energyKwh, splitCompleteness),
            energyCoveredMs = energyCoveredMs,
            energyUncoveredMs = energyUncoveredMs,
            energyObservedAt = energyObservedAt,
            route = routePoints(route)
        )
    }

    private fun monthTitle(year: Int, month: Int, locale: Locale): String = runCatching {
        Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale)
            .replaceFirstChar { it.titlecase(locale) } + " $year"
    }.getOrDefault("${month.toString().padStart(2, '0')} $year")

    private fun dayTitle(year: Int, month: Int, day: Int, locale: Locale): String = runCatching {
        val date = LocalDate.of(year, month, day)
        if (locale.language == "uk") {
            DateTimeFormatter.ofPattern("d MMMM, EEEE", locale).format(date)
        } else {
            DateTimeFormatter.ofPattern("MMMM d, EEEE", locale).format(date)
        }
    }.getOrDefault(day.toString().padStart(2, '0'))

    private fun formatDuration(durationMs: Long?): String {
        if (durationMs == null) return "—"
        val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
        return "%d:%02d".format(Locale.US, totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun aggregateEnergy(trips: List<TripSummaryUi>): EnergyAggregate {
        val averageRows = trips.filter { it.netKwh.validNumber() != null && it.distanceKm.validDistance() != null }
        val completeness = when {
            trips.none { it.dischargedKwh.validNumber() != null || it.regeneratedKwh.validNumber() != null } ->
                TripEnergyCompleteness.UNAVAILABLE
            trips.all { it.energyCompleteness == TripEnergyCompleteness.COMPLETE } && averageRows.size == trips.size ->
                TripEnergyCompleteness.COMPLETE
            else -> TripEnergyCompleteness.PARTIAL
        }
        return EnergyAggregate(
            dischargedKwh = trips.sumOrNull { it.dischargedKwh.validNumber() },
            regeneratedKwh = trips.sumOrNull { it.regeneratedKwh.validNumber() },
            netKwh = trips.sumOrNull { it.netKwh.validNumber() },
            averageConsumptionKwhPer100Km = TripMetrics.averageConsumptionKwhPer100Km(
                averageRows.sumOrNull { it.netKwh.validNumber() },
                averageRows.sumOrNull { it.distanceKm.validDistance() }
            ),
            completeness = completeness,
            netCompleteness = when {
                trips.none { it.netKwh.validNumber() != null } -> TripEnergyCompleteness.UNAVAILABLE
                trips.all { it.netCompleteness == TripEnergyCompleteness.COMPLETE } && averageRows.size == trips.size ->
                    TripEnergyCompleteness.COMPLETE
                else -> TripEnergyCompleteness.PARTIAL
            }
        )
    }

    private fun energyCompleteness(
        dischargedKwh: Double?,
        regeneratedKwh: Double?,
        netKwh: Double?,
        partial: Boolean?
    ): TripEnergyCompleteness {
        val values = listOf(dischargedKwh.validNumber(), regeneratedKwh.validNumber(), netKwh.validNumber())
        return when {
            values.all { it == null } -> TripEnergyCompleteness.UNAVAILABLE
            values.all { it != null } && partial == false -> TripEnergyCompleteness.COMPLETE
            else -> TripEnergyCompleteness.PARTIAL
        }
    }

    private fun signedAverage(netKwh: Double?, distanceKm: Double?): Double? =
        TripMetrics.averageConsumptionKwhPer100Km(netKwh.validNumber(), distanceKm.validDistance())

    // The legacy total is an approved display fallback, never fabricated gross/regeneration.
    private fun netCompleteness(netKwh: Double?, legacyKwh: Double?, split: TripEnergyCompleteness): TripEnergyCompleteness =
        if (netKwh.validNumber() == null && legacyKwh.validNumber() != null) TripEnergyCompleteness.COMPLETE else split

    private fun Double?.validNumber(): Double? = this?.takeIf(Double::isFinite)

    private fun Double?.validDistance(): Double? = validNumber()?.takeIf { it > 0.0 }

    private inline fun List<TripSummaryUi>.sumOrNull(value: (TripSummaryUi) -> Double?): Double? {
        val values = mapNotNull(value)
        return values.takeIf { it.isNotEmpty() }?.sum()
    }

    private data class EnergyAggregate(
        val dischargedKwh: Double?,
        val regeneratedKwh: Double?,
        val netKwh: Double?,
        val averageConsumptionKwhPer100Km: Double?,
        val completeness: TripEnergyCompleteness,
        val netCompleteness: TripEnergyCompleteness
    )
}
