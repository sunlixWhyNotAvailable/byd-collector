package com.bydcollector.collector.location

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.service.TripRuntimeCoordinator
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GpsTrustGateTest {
    @Test fun rejectedGpsCannotReplacePersistedTripAnchorOrLeakIntoNextTrip() {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "gps-trip-recovery.db"
        var store = TripStore(TripDatabaseHelper(context, dbName))
        var elapsed = 1_000L
        val published = mutableListOf<List<NormalizedObservation>>()
        val errors = mutableListOf<String>()
        fun coordinator() = TripRuntimeCoordinator(context, store, { true }, { true },
            { published += it }, { true }, {},
            { kind, _, detail -> if (kind.endsWith("_error")) errors += "$kind:$detail" },
            elapsedRealtimeMs = { elapsed }, bootIdProvider = { "gps-test-boot" })
        var runtime = coordinator()
        fun drain() {
            val done = CompletableFuture<Unit>()
            runtime.afterPendingTrips { done.complete(Unit) }
            done.get(5, TimeUnit.SECONDS)
            assertTrue(errors.isEmpty(), errors.joinToString())
        }
        fun power(on: Boolean, time: Long) {
            elapsed = time
            runtime.onSuccessfulPoll(at(time), listOf(PollReading("bodywork_power_level",
                if (on) "2" else "0", if (on) "2" else "0")), emptyList(), true)
            drain()
        }
        fun gps(time: Long, latitude: Double, mock: Boolean = false) {
            elapsed = time
            val source = ReflectionHelpers.getField<AndroidGpsLocationSource>(runtime, "locationSource")
            // Input after Android's 1 Hz sample gate; the production trust/sink/store path remains intact.
            ReflectionHelpers.callInstanceMethod<Unit>(source, "emit",
                ReflectionHelpers.ClassParameter.from(GpsLocationSample::class.java, sample(time, latitude, mock)),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
            drain()
        }
        try {
            power(true, 1_000)
            val firstTrip = assertNotNull(store.loadOpenSession()).tripId
            gps(1_000, 50.00001)
            gps(2_000, 50.00002)
            gps(3_000, 50.00003)
            assertEquals(50.00003, store.queryRoutePoints(firstTrip).last { it.kind == RoutePoint.KIND_VALID }.latitude)
            val beforeReject = published.size
            gps(4_000, 61.0)
            assertEquals(beforeReject, published.size, "rejected GPS must not enter normalized live data")
            assertEquals("untrusted:continuity_speed", store.queryRoutePoints(firstTrip).last().quality)

            runtime.close()
            store.close()
            store = TripStore(TripDatabaseHelper(context, dbName))
            runtime = coordinator()
            power(true, 5_000)
            assertEquals(firstTrip, store.loadOpenSession()?.tripId)
            val afterRestore = published.size
            gps(6_000, 61.00001)
            gps(7_000, 61.00002)
            gps(8_000, 61.00003)
            assertEquals(afterRestore, published.size)
            val route = store.queryRoutePoints(firstTrip)
            assertEquals("untrusted:recovery_anchor_speed", route.last().quality)
            assertEquals(1, route.count { it.kind == RoutePoint.KIND_VALID })
            assertTrue(route.any { it.kind == RoutePoint.KIND_GAP })

            gps(9_000, 50.00004)
            gps(10_000, 50.00005)
            gps(11_000, 50.00006)
            gps(12_000, 50.00007)
            assertTrue(published.size > afterRestore, "consistent fixes near the persisted anchor must resume publication")
            assertEquals(50.00007, store.queryRoutePoints(firstTrip).last { it.kind == RoutePoint.KIND_VALID }.latitude)
            gps(13_000, 61.0)
            power(false, 14_000)
            val firstCompletion = store.pendingCompletions(store.completionWatermark()).single()
            assertEquals(50.00007, firstCompletion.lastLocation?.latitude)
            assertEquals(at(12_000), firstCompletion.lastLocation?.capturedAt)
            assertTrue(store.queryRoutePoints(firstTrip).last { it.kind == RoutePoint.KIND_VALID }.isFinal)

            power(true, 15_000)
            val nextTrip = assertNotNull(store.loadOpenSession()).tripId
            assertNotEquals(firstTrip, nextTrip)
            gps(16_000, 61.0, mock = true)
            gps(17_000, 61.00001)
            gps(18_000, 61.00002)
            gps(19_000, 61.00003)
            assertEquals("untrusted:recovery_anchor_speed", store.queryRoutePoints(nextTrip).last().quality)
            power(false, 20_000)
            val completions = store.pendingCompletions(store.completionWatermark())
            assertEquals(2, completions.size)
            assertNull(completions.last().lastLocation, "a new trip cannot inherit a previous trip's coordinate")
            assertTrue(store.queryRoutePoints(nextTrip).none { it.kind == RoutePoint.KIND_VALID })
            assertTrue(store.queryRoutePoints(nextTrip).any { it.quality == "untrusted:mock_source" })
            assertTrue(store.verify())

            val anchor = sample(12_000, 50.00007, false)
            assertTrue(anchor.canAnchorRecovery("gps-test-boot", 20_000))
            assertFalse(anchor.canAnchorRecovery("another-boot", 20_000))
            assertFalse(anchor.copy(bootId = "unknown").canAnchorRecovery("unknown", 20_000))
            assertFalse(anchor.copy(bootId = "").canAnchorRecovery("", 20_000))
            assertFalse(anchor.copy(elapsedRealtimeNanos = 0).canAnchorRecovery("gps-test-boot", 20_000))
            assertFalse(anchor.canAnchorRecovery("gps-test-boot", 11_000))
            assertFalse(anchor.copy(receiveElapsedRealtimeNanos = 1).canAnchorRecovery("gps-test-boot", 20_000))
        } finally {
            runtime.close()
            store.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun at(elapsed: Long) = Instant.ofEpochMilli(1_780_000_000_000L + elapsed).toString()
    private fun sample(elapsed: Long, latitude: Double, mock: Boolean) = GpsLocationSample(
        observedAt = at(elapsed), wallTimeMs = 1_780_000_000_000L + elapsed,
        elapsedRealtimeNanos = elapsed * 1_000_000L, bootId = "gps-test-boot", segmentId = "gps-fixture",
        latitude = latitude, longitude = 30.0, accuracyM = 5.0, speedMps = 2.0,
        altitudeM = null, bearingDeg = null, isMock = mock)
}
