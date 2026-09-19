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
import com.bydcollector.collector.data.trips.TripTime
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.trips.withEnergySnapshot
import com.bydcollector.collector.data.trips.closedAtPowerOff
import com.bydcollector.collector.data.trips.TripCompletionIntent
import com.bydcollector.collector.data.trips.TripCompletionLocation
import com.bydcollector.collector.location.AndroidGpsLocationSource
import com.bydcollector.collector.location.GpsLocationSample
import com.bydcollector.collector.location.GpsLocationSink
import com.bydcollector.collector.location.GpsStartRetryGate
import com.bydcollector.collector.location.LocationNormalizer
import com.bydcollector.collector.location.VehicleSpeedReference
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes power-session, route, and normalized-location writes behind one owner. */
class TripRuntimeCoordinator(
    context: Context,
    private val tripStore: TripStore,
    private val historyEnabled: () -> Boolean,
    private val locationCaptureEnabled: () -> Boolean,
    private val persistLocation: (List<NormalizedObservation>) -> Unit,
    private val completionEnabled: () -> Boolean,
    private val onCompletionReady: (Long) -> Unit,
    private val recordEvent: (String, String, String?) -> Unit,
    private val onConfirmedPowerOn: () -> Unit = {},
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
    private val gpsStartRetry = GpsStartRetryGate()
    private var lastLocation: GpsLocationSample? = null
    private var lastLocationTripId: String? = null
    private var latestBatteryPowerKw: Double? = null
    private var latestBatteryPowerAtMs = Long.MIN_VALUE
    private val vehicleSpeedReference = VehicleSpeedReference(nowElapsedMs = elapsedRealtimeMs)
    private val locationSource = AndroidGpsLocationSource(
        context = context,
        sink = object : GpsLocationSink {
            override fun onLocation(sample: GpsLocationSample, isFinal: Boolean) {
                dispatch { handleLocation(sample, isFinal) }
            }

            override fun onUntrusted(sample: GpsLocationSample, reason: String) {
                dispatch { handleUntrusted(sample, reason) }
            }

            override fun onGap(reason: String, observedAt: String) {
                dispatch { handleGap(reason, observedAt) }
            }

            override fun onProviderChanged(enabled: Boolean) {
                dispatch {
                    gpsStartRetry.reset()
                    gpsStarted = false
                    if (enabled && powerTracker.current() == VehiclePowerState.ON && locationCaptureEnabled()) ensureGpsRunning()
                }
            }
        },
        bootIdProvider = { bootId },
        segmentIdProvider = { segmentId },
        vehicleSpeedReferenceKmh = vehicleSpeedReference::current
    )

    fun onSuccessfulPoll(
        timestamp: String,
        readings: List<PollReading>,
        observations: List<NormalizedObservation>,
        liveTelemetry: Boolean,
        diagnosticPowerSession: CompletableFuture<String?>? = null,
        energySnapshot: EnergySnapshot? = null,
        beforeBoundary: (Long) -> Unit = {}
    ) {
        val receivedElapsedMs = elapsedRealtimeMs()
        val snapshot = computeTelemetrySnapshot(observations, receivedElapsedMs).copy(energy = energySnapshot)
        if (liveTelemetry) vehicleSpeedReference.observe(snapshot.speedKmh, receivedElapsedMs)
        dispatch(onDropped = { diagnosticPowerSession?.complete(null) }) {
            try {
                ensureInitialized()
                // Capture the frontier here, not when Telegram eventually runs: a later
                // OFF must never be applied before this earlier queued ON/poll input.
                beforeBoundary(tripStore.completionWatermark())
                latestBatteryPowerKw = snapshot.batteryPowerKw
                latestBatteryPowerAtMs = snapshot.receivedElapsedMs

                val decodedPower = readings.firstOrNull { it.rawKey == POWER_FIELD_KEY }
                    ?.descValue
                    ?.trim()
                    ?.toIntOrNull()
                val transition = powerTracker.observe(decodedPower)
                try {
                    when (transition?.current) {
                        VehiclePowerState.ON -> {
                            handlePowerOn(timestamp, snapshot)
                            // Recovery is only a scheduling hint. It must not roll back a
                            // confirmed boundary if the service is already tearing down.
                            runCatching { onConfirmedPowerOn() }
                        }
                        VehiclePowerState.OFF -> handlePowerOff(timestamp, snapshot, diagnosticPowerSession)
                        else -> Unit
                    }
                } catch (error: Throwable) {
                    transition?.let(powerTracker::rollback)
                    throw error
                }
                if (powerTracker.current() == VehiclePowerState.ON) {
                    updateOpenSession(timestamp, snapshot)
                    if (!locationCaptureEnabled()) stopGps()
                    else if (liveTelemetry) ensureGpsRunning()
                    diagnosticPowerSession?.complete(session?.tripId)
                }
            } finally {
                diagnosticPowerSession?.complete(null)
            }
        }
    }

    fun resume() {
        if (running.get()) paused.set(false)
    }

    /** Orders ticks/recovery behind already submitted Trips inputs, even with polling paused. */
    fun afterPendingTrips(onDropped: () -> Unit = {}, action: (Long) -> Unit) {
        dispatch(onDropped = onDropped, allowWhilePaused = true) {
            action(tripStore.completionWatermark())
        }
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
        if (session == null) {
            lastLocation = null
            lastLocationTripId = null
        }
        if (historyEnabled()) {
            if (session == null) {
                val startEnergy = snapshot.tripEnergyKwh?.takeIf { it.isFinite() && it >= 0.0 }
                session = TripSession(
                    tripId = snapshot.energy?.powerSessionId ?: TripId.forPowerSession(bootId, snapshot.receivedElapsedMs),
                    startedAt = snapshot.energy?.startedAt ?: timestamp,
                    startElapsedMs = snapshot.energy?.sourceElapsedMs ?: snapshot.receivedElapsedMs,
                    startBootId = snapshot.energy?.sourceBootId ?: bootId,
                    startSegmentId = segmentId,
                    startSoc = snapshot.soc,
                    endSoc = snapshot.soc,
                    startOdometerKm = snapshot.odometerKm,
                    lastOdometerKm = snapshot.odometerKm,
                    startTripEnergyKwh = startEnergy,
                    lastTripEnergyKwh = startEnergy,
                    energyKwh = startEnergy?.let { 0.0 }
                ).let { trip -> snapshot.energy?.let(trip::withEnergySnapshot) ?: trip }.also(tripStore::upsertSession)
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
        val currentEnergy = snapshot.tripEnergyKwh?.takeIf { it.isFinite() && it >= 0.0 }
        val startEnergy = current.startTripEnergyKwh ?: currentEnergy
        val odometerReset = decreased(snapshot.odometerKm, current.lastOdometerKm)
        val lastOdometer = if (odometerReset) current.lastOdometerKm else snapshot.odometerKm ?: current.lastOdometerKm
        val legacyResetWithoutTotal = current.energyKwh == null && "trip_energy_reset" in current.quality
        val accumulatedEnergy = if (legacyResetWithoutTotal) {
            0.0
        } else {
            current.energyKwh ?: TripMetrics.delta(startEnergy, current.lastTripEnergyKwh ?: currentEnergy)
        }
        val energyUpdate = TripMetrics.advanceEnergyCounter(
            accumulatedKwh = accumulatedEnergy,
            lastCounterKwh = currentEnergy.takeIf { legacyResetWithoutTotal }
                ?: current.lastTripEnergyKwh
                ?: currentEnergy,
            currentCounterKwh = currentEnergy
        )
        val quality = listOfNotNull(
            current.quality.takeUnless { it == TripSession.QUALITY_OK },
            "odometer_reset".takeIf { odometerReset },
            "trip_energy_reset".takeIf { energyUpdate.resetObserved }
        ).flatMap { it.split(',') }.distinct().joinToString(",").ifBlank { TripSession.QUALITY_OK }
        val distance = TripMetrics.delta(startOdometer, lastOdometer)
            .takeUnless { "odometer_reset" in quality }
        val energy = energyUpdate.accumulatedKwh
        val duration = TripTime.durationMs(current.startedAt, timestamp)
        session = current.copy(
            movementObserved = current.movementObserved || (snapshot.speedKmh ?: 0.0) > MOVEMENT_THRESHOLD_KMH,
            startSoc = startSoc,
            endSoc = snapshot.soc ?: current.endSoc,
            startOdometerKm = startOdometer,
            lastOdometerKm = lastOdometer,
            startTripEnergyKwh = startEnergy,
            lastTripEnergyKwh = energyUpdate.lastCounterKwh,
            durationMs = duration,
            distanceKm = distance,
            energyKwh = energy,
            averageConsumptionKwhPer100Km = TripMetrics.averageConsumptionKwhPer100Km(energy, distance),
            quality = quality
        ).let { trip -> snapshot.energy?.let(trip::withEnergySnapshot) ?: trip }.also(tripStore::upsertSession)
    }

    private fun handlePowerOff(
        timestamp: String,
        snapshot: TelemetrySnapshot,
        diagnosticPowerSession: CompletableFuture<String?>?
    ) {
        stopGps(markFinal = true)
        updateOpenSession(timestamp, snapshot)
        val current = session
        val trustedLocation = when {
            current == null -> lastLocation
            lastLocationTripId == current.tripId -> lastLocation
            else -> tripStore.queryRoutePoints(current.tripId)
                .lastOrNull { it.kind == RoutePoint.KIND_VALID }
                ?.toGpsSample()
        }
        diagnosticPowerSession?.complete(current?.tripId)
        val closed = current?.let { current ->
            markLastRoutePointFinal(current.tripId)
            current.closedAtPowerOff(
                timestamp = timestamp,
                elapsedMs = snapshot.energy?.sourceElapsedMs ?: snapshot.receivedElapsedMs,
                bootId = snapshot.energy?.sourceBootId ?: bootId,
                segmentId = segmentId
            )
        }
        val completion = if (completionEnabled()) TripCompletionIntent(
            identity = "power_off:${closed?.tripId ?: snapshot.energy?.powerSessionId ?: "$bootId:${snapshot.receivedElapsedMs}"}",
            observedAt = timestamp,
            session = closed,
            lastLocation = trustedLocation?.let { TripCompletionLocation(it.latitude, it.longitude, it.observedAt) },
            energySnapshot = snapshot.energy,
            odometerKm = snapshot.odometerKm ?: closed?.lastOdometerKm,
            soc = snapshot.soc ?: closed?.endSoc,
            tripEnergyKwh = snapshot.tripEnergyKwh ?: closed?.lastTripEnergyKwh
        ) else null
        val durableCompletion = tripStore.closeSessionWithCompletion(closed, completion)
        // Only the atomic local commit above controls whether this boundary succeeded.
        session = null
        nextRouteSequence = 0L
        if (closed != null) runCatching {
            recordEvent("power_trip_finished", "Vehicle power session finished", "trip_id=${closed.tripId}")
        }
        durableCompletion?.let { runCatching { onCompletionReady(it.sequence) } }
    }

    private fun ensureGpsRunning() {
        if (gpsStarted || !locationCaptureEnabled()) return
        val nowMs = elapsedRealtimeMs()
        if (!gpsStartRetry.canAttempt(nowMs)) return
        val result = locationSource.start(lastLocation)
        gpsStarted = result.started
        if (gpsStarted) return gpsStartRetry.reset()
        if (!gpsStartRetry.onFailure(nowMs)) return
        val reason = result.failureReason ?: "gps_start_failed"
        if (result.recordGap) handleGap(reason, Instant.now().toString())
        recordEvent("gps_capture_unavailable", "GPS route capture did not start", "reason=$reason")
    }

    private fun stopGps(markFinal: Boolean = false) {
        locationSource.stop(markFinal)
        gpsStarted = false
        gpsStartRetry.reset()
    }

    private fun handleLocation(sample: GpsLocationSample, isFinal: Boolean) {
        if (!running.get()) return
        lastLocation = sample
        persistLocation(LocationNormalizer.observations(sample, sample.receiveWallTimeMs))
        val current = session ?: return
        lastLocationTripId = current.tripId
        val powerAgeMs = sample.receiveElapsedRealtimeNanos / 1_000_000L - latestBatteryPowerAtMs
        val power = latestBatteryPowerKw.takeIf {
            powerAgeMs in 0L..TELEMETRY_FRESH_MS
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

    private fun handleUntrusted(sample: GpsLocationSample, reason: String) {
        if (!running.get()) return
        val receivedAt = runCatching { Instant.ofEpochMilli(sample.receiveWallTimeMs).toString() }.getOrElse { Instant.now().toString() }
        recordEvent(
            "gps_fix_untrusted",
            "Rejected GPS fix retained outside normalized location state",
            "reason=$reason source_at=${sample.observedAt} received_at=$receivedAt"
        )
        val current = session ?: return
        tripStore.upsertRoutePoint(
            TripMetrics.untrustedPoint(current.tripId, nextRouteSequence++, sample, reason)
        )
    }

    private fun handleGap(reason: String, observedAt: String) {
        persistLocation(LocationNormalizer.gap(observedAt, reason))
        val current = session ?: return
        tripStore.upsertRoutePoint(
            TripMetrics.gapPoint(current.tripId, nextRouteSequence++, observedAt, bootId, segmentId, reason)
        )
    }

    private fun markLastRoutePointFinal(tripId: String) {
        tripStore.queryRoutePoints(tripId).lastOrNull { it.kind == RoutePoint.KIND_VALID }?.let { last ->
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
        lastLocationTripId = session?.tripId?.takeIf { lastLocation != null }
        // An open trip is durable evidence that ON still needs a matching OFF. Without
        // one (including when history is disabled), OFF is the restart baseline rather
        // than a new boundary. Establish it only after retryable store reads succeed.
        powerTracker.restoreBaseline(hasOpenSession = session != null)
        initialized = true
    }

    private fun dispatch(onDropped: () -> Unit = {}, allowWhilePaused: Boolean = false, block: () -> Unit) {
        if (!running.get()) {
            onDropped()
            return
        }
        if (Thread.currentThread() === ownerThread) {
            block()
            return
        }
        if (paused.get() && !allowWhilePaused) {
            onDropped()
            return
        }
        runCatching {
            executor.execute {
                ownerThread = Thread.currentThread()
                runCatching(block).onFailure { error ->
                    recordEvent("trip_runtime_error", "Trip runtime operation failed", "${error::class.java.simpleName}: ${error.message.orEmpty()}")
                }
            }
        }.onFailure { onDropped() }
    }

    private fun Map<String, NormalizedObservation>.number(key: String): Double? {
        val observation = this[key] ?: return null
        if (observation.quality != NormalizedQuality.OK) return null
        return observation.value.number?.takeIf(Double::isFinite)
    }

    private fun computeTelemetrySnapshot(
        observations: List<NormalizedObservation>,
        receivedElapsedMs: Long
    ): TelemetrySnapshot {
        val values = observations.associateBy { it.field.fieldKey }
        return TelemetrySnapshot(
            soc = values.number("soc"),
            odometerKm = values.number("odometer_km"),
            tripEnergyKwh = values.number("trip_energy_kwh"),
            speedKmh = values.number("speed_kmh"),
            batteryPowerKw = values.number("battery_power_kw"),
            receivedElapsedMs = receivedElapsedMs
        )
    }

    private fun decreased(value: Double?, previous: Double?): Boolean =
        value != null && previous != null && value.isFinite() && previous.isFinite() && value + COUNTER_EPSILON < previous

    private fun RoutePoint.toGpsSample(): GpsLocationSample? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        val wall = TripTime.instant(observedAt)?.toEpochMilli() ?: 0L
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
            bearingDeg = bearingDeg,
            receiveWallTimeMs = receiveWallTimeMs ?: wall
        )
    }

    private data class TelemetrySnapshot(
        val soc: Double?,
        val odometerKm: Double?,
        val tripEnergyKwh: Double?,
        val speedKmh: Double?,
        val batteryPowerKw: Double?,
        val receivedElapsedMs: Long,
        val energy: EnergySnapshot? = null
    )

    companion object {
        private const val THREAD_NAME = "byd-trips"
        private const val POWER_FIELD_KEY = "bodywork_power_level"
        private const val MOVEMENT_THRESHOLD_KMH = 0.5
        private const val TELEMETRY_FRESH_MS = 2_000L
        private const val COUNTER_EPSILON = 1e-6

        private fun readBootId(): String = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim().ifBlank { "unknown" }
        }.getOrDefault("unknown")
    }
}
