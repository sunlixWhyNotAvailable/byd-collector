package com.bydcollector.collector.service

import android.content.Context
import android.os.SystemClock
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripId
import com.bydcollector.collector.data.trips.TripMetrics
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.location.AndroidGpsLocationSource
import com.bydcollector.collector.location.GpsLocationSample
import com.bydcollector.collector.location.GpsLocationSink
import com.bydcollector.collector.location.LocationNormalizer
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ConfirmedPowerOff(
    val observedAt: String,
    val session: TripSession?,
    val lastLocation: GpsLocationSample?
)

/** Serializes power-session, route, and normalized-location writes behind one owner. */
class TripRuntimeCoordinator(
    context: Context,
    private val tripStore: TripStore,
    private val historyEnabled: () -> Boolean,
    private val locationCaptureEnabled: () -> Boolean,
    private val persistLocation: (List<NormalizedObservation>) -> Unit,
    private val onConfirmedPowerOff: (ConfirmedPowerOff) -> Unit,
    private val recordEvent: (String, String, String?) -> Unit,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val bootIdProvider: () -> String = ::readBootId
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val paused = AtomicBoolean(false)
    private val executor: ExecutorService = namedSingleThreadExecutor(THREAD_NAME)
    private val powerTracker = VehiclePowerBoundaryTracker()
    private val bootId = bootIdProvider()
    private val segmentId = "$bootId:${System.currentTimeMillis()}"
    @Volatile private var ownerThread: Thread? = null
    private var initialized = false
    private var session: TripSession? = null
    private var nextRouteSequence = 0L
    private var gpsStarted = false
    private var lastLocation: GpsLocationSample? = null
    private var latestBatteryPowerKw: Double? = null
    private var latestBatteryPowerAtMs = Long.MIN_VALUE
    private val locationSource = AndroidGpsLocationSource(
        context = context,
        sink = object : GpsLocationSink {
            override fun onLocation(sample: GpsLocationSample, isFinal: Boolean) {
                dispatch { handleLocation(sample, isFinal) }
            }

            override fun onGap(reason: String, observedAt: String) {
                dispatch { handleGap(reason, observedAt) }
            }
        },
        bootIdProvider = { bootId },
        segmentIdProvider = { segmentId }
    )

    fun onSuccessfulPoll(
        timestamp: String,
        readings: List<PollReading>,
        observations: List<NormalizedObservation>
    ) {
        dispatch {
            ensureInitialized()
            val liveTelemetry = isLiveTripTelemetryTimestamp(timestamp)
            val values = observations.associateBy { it.field.fieldKey }
            val snapshot = TelemetrySnapshot(
                soc = values.number("soc"),
                odometerKm = values.number("odometer_km"),
                tripEnergyKwh = values.number("trip_energy_kwh"),
                speedKmh = values.number("speed_kmh"),
                batteryPowerKw = values.number("battery_power_kw")
            )
            latestBatteryPowerKw = snapshot.batteryPowerKw
            latestBatteryPowerAtMs = elapsedRealtimeMs()

            val decodedPower = readings.firstOrNull { it.rawKey == POWER_FIELD_KEY }
                ?.descValue
                ?.trim()
                ?.toIntOrNull()
            val transition = powerTracker.observe(decodedPower)
            when (transition?.current) {
                VehiclePowerState.ON -> handlePowerOn(timestamp, snapshot)
                VehiclePowerState.OFF -> handlePowerOff(timestamp, snapshot)
                else -> Unit
            }
            if (powerTracker.current() == VehiclePowerState.ON) {
                updateOpenSession(timestamp, snapshot)
                if (!locationCaptureEnabled()) stopGps()
                else if (liveTelemetry) ensureGpsRunning()
            }
        }
    }

    fun resume() {
        if (running.get()) paused.set(false)
    }

    /** Stops callbacks and records a route gap without treating process/maintenance death as vehicle-off. */
    fun pause(reason: String) {
        if (!running.get()) return
        paused.set(true)
        runCatching {
            executor.execute {
                ownerThread = Thread.currentThread()
                stopGps()
                if (session != null) handleGap(reason, Instant.now().toString())
                tripStore.checkpoint()
            }
        }
    }

    fun pauseAndAwait(reason: String, timeoutMs: Long = 2_000L): Boolean {
        if (!running.get()) return true
        paused.set(true)
        val future = executor.submit {
            ownerThread = Thread.currentThread()
            stopGps()
            if (session != null) handleGap(reason, Instant.now().toString())
            tripStore.checkpoint()
        }
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS); true }.getOrDefault(false)
    }

    override fun close() {
        if (!running.get()) return
        val future = executor.submit {
            ownerThread = Thread.currentThread()
            stopGps()
            if (session != null && !paused.get()) handleGap("service_destroyed", Instant.now().toString())
            tripStore.checkpoint()
        }
        runCatching { future.get(2_000L, TimeUnit.MILLISECONDS) }
        running.set(false)
        executor.shutdown()
        if (!runCatching { executor.awaitTermination(2_000L, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            executor.shutdownNow()
        }
    }

    private fun handlePowerOn(timestamp: String, snapshot: TelemetrySnapshot) {
        if (session == null) lastLocation = null
        if (historyEnabled()) {
            if (session == null) {
                val elapsed = elapsedRealtimeMs()
                session = TripSession(
                    tripId = TripId.forPowerSession(bootId, elapsed),
                    startedAt = timestamp,
                    startElapsedMs = elapsed,
                    startBootId = bootId,
                    startSegmentId = segmentId,
                    startSoc = snapshot.soc,
                    endSoc = snapshot.soc,
                    startOdometerKm = snapshot.odometerKm,
                    lastOdometerKm = snapshot.odometerKm,
                    startTripEnergyKwh = snapshot.tripEnergyKwh,
                    lastTripEnergyKwh = snapshot.tripEnergyKwh
                ).also(tripStore::upsertSession)
                nextRouteSequence = 0L
                recordEvent("power_trip_started", "Vehicle power session started", "trip_id=${session?.tripId}")
            } else if (session?.startSegmentId != segmentId) {
                handleGap("runtime_segment_changed", timestamp)
            }
        }
    }

    private fun updateOpenSession(timestamp: String, snapshot: TelemetrySnapshot) {
        val current = session ?: return
        val startSoc = current.startSoc ?: snapshot.soc
        val startOdometer = current.startOdometerKm ?: snapshot.odometerKm
        val startEnergy = current.startTripEnergyKwh ?: snapshot.tripEnergyKwh
        val odometerReset = decreased(snapshot.odometerKm, current.lastOdometerKm)
        val energyReset = decreased(snapshot.tripEnergyKwh, current.lastTripEnergyKwh)
        val lastOdometer = if (odometerReset) current.lastOdometerKm else snapshot.odometerKm ?: current.lastOdometerKm
        val lastEnergy = if (energyReset) current.lastTripEnergyKwh else snapshot.tripEnergyKwh ?: current.lastTripEnergyKwh
        val quality = listOfNotNull(
            current.quality.takeUnless { it == TripSession.QUALITY_OK },
            "odometer_reset".takeIf { odometerReset },
            "trip_energy_reset".takeIf { energyReset }
        ).flatMap { it.split(',') }.distinct().joinToString(",").ifBlank { TripSession.QUALITY_OK }
        val distance = TripMetrics.delta(startOdometer, lastOdometer)
            .takeUnless { "odometer_reset" in quality }
        val energy = TripMetrics.delta(startEnergy, lastEnergy)
            .takeUnless { "trip_energy_reset" in quality }
        val duration = durationMs(current.startedAt, timestamp)
        session = current.copy(
            movementObserved = current.movementObserved || (snapshot.speedKmh ?: 0.0) > MOVEMENT_THRESHOLD_KMH,
            startSoc = startSoc,
            endSoc = snapshot.soc ?: current.endSoc,
            startOdometerKm = startOdometer,
            lastOdometerKm = lastOdometer,
            startTripEnergyKwh = startEnergy,
            lastTripEnergyKwh = lastEnergy,
            durationMs = duration,
            distanceKm = distance,
            energyKwh = energy,
            averageConsumptionKwhPer100Km = TripMetrics.averageConsumptionKwhPer100Km(energy, distance),
            quality = quality
        ).also(tripStore::upsertSession)
    }

    private fun handlePowerOff(timestamp: String, snapshot: TelemetrySnapshot) {
        stopGps(markFinal = true)
        updateOpenSession(timestamp, snapshot)
        val closed = session?.let { current ->
            markLastRoutePointFinal(current.tripId)
            current.copy(
                state = TripSession.STATE_CLOSED,
                endedAt = timestamp,
                endElapsedMs = elapsedRealtimeMs(),
                endBootId = bootId,
                endSegmentId = segmentId,
                termination = "power_off"
            ).also(tripStore::upsertSession)
        }
        if (closed != null) {
            recordEvent("power_trip_finished", "Vehicle power session finished", "trip_id=${closed.tripId}")
        }
        session = null
        nextRouteSequence = 0L
        onConfirmedPowerOff(ConfirmedPowerOff(timestamp, closed, lastLocation))
    }

    private fun ensureGpsRunning() {
        if (gpsStarted || !locationCaptureEnabled()) return
        gpsStarted = locationSource.start()
        if (!gpsStarted) {
            recordEvent("gps_capture_unavailable", "GPS route capture did not start", null)
        }
    }

    private fun stopGps(markFinal: Boolean = false) {
        if (!gpsStarted) return
        locationSource.stop(markFinal)
        gpsStarted = false
    }

    private fun handleLocation(sample: GpsLocationSample, isFinal: Boolean) {
        if (!running.get()) return
        lastLocation = sample
        persistLocation(LocationNormalizer.observations(sample, System.currentTimeMillis()))
        val current = session ?: return
        val power = latestBatteryPowerKw.takeIf {
            elapsedRealtimeMs() - latestBatteryPowerAtMs <= TELEMETRY_FRESH_MS
        }
        val point = TripMetrics.routePoint(
            tripId = current.tripId,
            sequence = nextRouteSequence++,
            sample = sample,
            batteryPowerKw = power,
            isFirst = nextRouteSequence == 1L,
            isFinal = isFinal
        )
        tripStore.upsertRoutePoint(point)
        if ((point.speedKmh ?: 0.0) > MOVEMENT_THRESHOLD_KMH && !current.movementObserved) {
            session = current.copy(movementObserved = true).also(tripStore::upsertSession)
        }
    }

    private fun handleGap(reason: String, observedAt: String) {
        persistLocation(LocationNormalizer.gap(observedAt, reason))
        val current = session ?: return
        tripStore.upsertRoutePoint(
            TripMetrics.gapPoint(current.tripId, nextRouteSequence++, observedAt, bootId, segmentId, reason)
        )
    }

    private fun markLastRoutePointFinal(tripId: String) {
        tripStore.queryRoutePoints(tripId).lastOrNull()?.let { last ->
            if (!last.isFinal) tripStore.upsertRoutePoint(last.copy(isFinal = true))
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        session = tripStore.loadOpenSession()
        nextRouteSequence = session?.let { tripStore.nextRouteSequence(it.tripId) } ?: 0L
        lastLocation = session?.let { current ->
            tripStore.queryRoutePoints(current.tripId).lastOrNull { it.kind == RoutePoint.KIND_VALID }?.toGpsSample()
        }
        initialized = true
    }

    private fun dispatch(block: () -> Unit) {
        if (!running.get()) return
        if (Thread.currentThread() === ownerThread) {
            block()
            return
        }
        if (paused.get()) return
        runCatching {
            executor.execute {
                ownerThread = Thread.currentThread()
                runCatching(block).onFailure { error ->
                    recordEvent("trip_runtime_error", "Trip runtime operation failed", "${error::class.java.simpleName}: ${error.message.orEmpty()}")
                }
            }
        }
    }

    private fun Map<String, NormalizedObservation>.number(key: String): Double? {
        val observation = this[key] ?: return null
        if (observation.quality != NormalizedQuality.OK) return null
        return observation.value.number?.takeIf(Double::isFinite)
    }

    private fun decreased(value: Double?, previous: Double?): Boolean =
        value != null && previous != null && value.isFinite() && previous.isFinite() && value + COUNTER_EPSILON < previous

    private fun RoutePoint.toGpsSample(): GpsLocationSample? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        val wall = runCatching { Instant.parse(observedAt).toEpochMilli() }.getOrDefault(0L)
        return GpsLocationSample(
            observedAt = observedAt,
            wallTimeMs = wall,
            elapsedRealtimeNanos = (elapsedMs ?: 0L) * 1_000_000L,
            bootId = bootId.orEmpty(),
            segmentId = segmentId.orEmpty(),
            latitude = lat,
            longitude = lon,
            accuracyM = accuracyM,
            speedMps = speedKmh?.div(3.6),
            altitudeM = altitudeM,
            bearingDeg = bearingDeg
        )
    }

    private data class TelemetrySnapshot(
        val soc: Double?,
        val odometerKm: Double?,
        val tripEnergyKwh: Double?,
        val speedKmh: Double?,
        val batteryPowerKw: Double?
    )

    companion object {
        private const val THREAD_NAME = "byd-trips"
        private const val POWER_FIELD_KEY = "bodywork_power_level"
        private const val MOVEMENT_THRESHOLD_KMH = 0.5
        private const val TELEMETRY_FRESH_MS = 2_000L
        private const val COUNTER_EPSILON = 1e-6

        private fun durationMs(startedAt: String, endedAt: String): Long? = runCatching {
            Duration.between(Instant.parse(startedAt), Instant.parse(endedAt)).toMillis().coerceAtLeast(0L)
        }.getOrNull()

        private fun readBootId(): String = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim().ifBlank { "unknown" }
        }.getOrDefault("unknown")
    }
}

internal fun isLiveTripTelemetryTimestamp(
    timestamp: String,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = 10_000L
): Boolean = runCatching {
    val capturedAtMs = OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
    capturedAtMs <= nowMs && nowMs - capturedAtMs <= maxAgeMs
}.getOrDefault(false)
