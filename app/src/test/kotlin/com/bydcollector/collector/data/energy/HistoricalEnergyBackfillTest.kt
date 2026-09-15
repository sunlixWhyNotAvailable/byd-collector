package com.bydcollector.collector.data.energy

import com.bydcollector.collector.data.local.HistoricalEnergyPoll
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.trips.TripSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalEnergyBackfillTest {
    @Test
    fun `complete mixed-offset trip requires valid all-on bookends and preserves every interval`() {
        val trip = trip("2026-08-21T15:00:00Z", "2026-08-21T18:00:02+03:00")
        val accumulator = HistoricalEnergyAccumulator(trip, 10, 12, 7)
        accumulator.accept(poll(10, "2026-08-21T18:00:00+03:00", session = 7, current = "10"))
        accumulator.accept(poll(11, "2026-08-21T15:00:01Z", session = 7, current = "-10"))
        accumulator.accept(poll(12, "2026-08-21T18:00:02+03:00", session = 7, current = "10"))

        val outcome = accumulator.finish()
        assertTrue(outcome.toString(), outcome is HistoricalEnergyResult.Complete)
        val result = outcome as HistoricalEnergyResult.Complete
        assertEquals(2_000L, result.snapshot.energyCoveredMs)
        assertEquals(0L, result.snapshot.energyUncoveredMs)
        assertTrue(result.snapshot.dischargedKwh!! > 0.0)
        assertTrue(result.snapshot.regeneratedKwh!! > 0.0)
        assertEquals(0.0, result.snapshot.netKwh!!, 1e-9)
    }

    @Test
    fun `final off is excluded only after its interval clock is validated`() {
        val trip = trip("2026-08-21T15:00:00Z", "2026-08-21T15:00:03Z")
        val accumulator = HistoricalEnergyAccumulator(trip, 1, 2, 1)
        accumulator.accept(poll(1, trip.startedAt))
        accumulator.accept(poll(2, trip.endedAt!!, power = "0", voltage = null, current = null, gun = null))
        assertRejected(accumulator, "gap_exceeded")
    }

    @Test
    fun `final off cannot publish a trimmed fragment as complete`() {
        val trip = trip("2026-08-21T15:00:00Z", "2026-08-21T15:00:02Z")
        val accumulator = HistoricalEnergyAccumulator(trip, 1, 3, 1)
        accumulator.accept(poll(1, trip.startedAt))
        accumulator.accept(poll(2, "2026-08-21T15:00:01Z"))
        accumulator.accept(poll(3, trip.endedAt!!, power = "0", voltage = null, current = null, gun = null))
        assertRejected(accumulator, "final_off_uncovered_interval")
    }

    @Test
    fun `cancellation at finalization prevents terminal action`() {
        val gate = HistoricalBackfillFinalizationGate()
        val generation = gate.enable()
        gate.cancel()
        var committed = false
        val result = gate.finalizeIfCurrent(generation) { committed = true }
        assertEquals(null, result)
        assertTrue(!committed)
    }

    @Test
    fun `failed missing nonfinite charge and session changes reject whole trip`() {
        assertRejected(twoPolls { copy(ok = false) }, "failed_poll")
        assertRejected(twoPolls { copy(readings = readings.filterNot { it.rawKey == "charging_charge_current" }) }, "missing_or_invalid_required")
        assertRejected(twoPolls { copy(readings = readings.map { if (it.rawKey == "charging_charge_current") it.copy(descValue = "NaN") else it }) }, "missing_or_invalid_required")
        assertRejected(twoPolls { copy(readings = readings.map { if (it.rawKey == "charging_1009_876609586_5") it.copy(rawValue = "2") else it }) }, "charging_veto")
        assertRejected(twoPolls { copy(sessionId = 9) }, "collection_session_changed")
        assertRejected(twoPolls { copy(readings = readings + PollReading("charging_1009_89128973_5", "1")) }, "charging_veto")
    }

    @Test
    fun `duplicate and missing bookends and zero covered time reject`() {
        val duplicate = HistoricalEnergyAccumulator(trip("2026-08-21T15:00:00Z", "2026-08-21T15:00:01Z"), 1, 2, 1)
        duplicate.accept(poll(1, "2026-08-21T15:00:00Z"))
        duplicate.accept(poll(2, "2026-08-21T15:00:00Z"))
        assertRejected(duplicate, "non_monotonic_time")

        val missing = HistoricalEnergyAccumulator(trip("2026-08-21T15:00:00Z", "2026-08-21T15:00:01Z"), 1, 2, 1)
        missing.accept(poll(1, "2026-08-21T15:00:00Z"))
        assertRejected(missing, "missing_end_bookend")

        val zero = HistoricalEnergyAccumulator(trip("2026-08-21T15:00:00Z", "2026-08-21T15:00:01Z"), 1, 2, 1)
        zero.accept(poll(1, "2026-08-21T15:00:00Z", power = "0", voltage = null, current = null, gun = null))
        assertRejected(zero, "missing_or_invalid_required")

        val missingRow = HistoricalEnergyAccumulator(trip("2026-08-21T15:00:00Z", "2026-08-21T15:00:01Z"), 1, 3, 1)
        missingRow.accept(poll(1, "2026-08-21T15:00:00Z"))
        missingRow.accept(poll(3, "2026-08-21T15:00:01Z"))
        assertRejected(missingRow, "missing_poll_row")
    }

    @Test
    fun `overlap detection uses instants instead of text ordering`() {
        val candidate = trip("2026-08-21T15:00:00Z", "2026-08-21T15:10:00Z", "a")
        val overlap = trip("2026-08-21T18:05:00+03:00", "2026-08-21T18:15:00+03:00", "b")
        val adjacent = trip("2026-08-21T18:10:00+03:00", "2026-08-21T18:20:00+03:00", "c")
        assertTrue(HistoricalEnergyAccumulator.overlaps(candidate, overlap))
        assertTrue(!HistoricalEnergyAccumulator.overlaps(candidate, adjacent))
    }

    private fun twoPolls(changeSecond: HistoricalEnergyPoll.() -> HistoricalEnergyPoll): HistoricalEnergyAccumulator {
        val trip = trip("2026-08-21T15:00:00Z", "2026-08-21T15:00:01Z")
        return HistoricalEnergyAccumulator(trip, 1, 2, 1).also {
            it.accept(poll(1, trip.startedAt))
            it.accept(poll(2, trip.endedAt!!).changeSecond())
        }
    }

    private fun assertRejected(accumulator: HistoricalEnergyAccumulator, reason: String) {
        assertEquals(reason, (accumulator.finish() as HistoricalEnergyResult.Rejected).reason)
    }

    private fun trip(start: String, end: String, id: String = "trip") = TripSession(
        tripId = id,
        state = TripSession.STATE_CLOSED,
        startedAt = start,
        endedAt = end
    )

    private fun poll(
        id: Long,
        timestamp: String,
        session: Long = 1,
        power: String = "2",
        voltage: String? = "400",
        current: String? = "10",
        gun: String? = "1"
    ) = HistoricalEnergyPoll(
        pollId = id,
        sessionId = session,
        timestamp = timestamp,
        ok = true,
        readings = listOf(
            PollReading("charging_charge_battery_volt", voltage, voltage),
            PollReading("charging_charge_current", current, current),
            PollReading("bodywork_power_level", power, power),
            PollReading("charging_1009_876609586_5", gun)
        )
    )
}
