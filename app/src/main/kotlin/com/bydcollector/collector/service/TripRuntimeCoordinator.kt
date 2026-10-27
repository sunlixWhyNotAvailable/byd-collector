package com.bydcollector.collector.service

import android.content.Context
import android.os.SystemClock
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
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
import com.bydcollector.collector.data.trips.TripBusinessInputMutation
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
import com.bydcollector.collector.util.diagnosticDetail

data class TripBusinessResult(
    val consumed: Boolean,
    val priorCompletionWatermark: Long,
    val completionWatermark: Long,
    val powerSessionId: String?,
    val confirmedPowerOn: Boolean,
    val closedPowerSession: Boolean = false
)

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
    private var powerTracker = VehiclePowerBoundaryTracker()
    private val bootId = bootIdProvider()
    private val segmentId = "$bootId:${System.currentTimeMillis()}"
    @Volatile private var ownerThread: Thread? = null
    private var initialized = false
    private var session: TripSession? = null
    private var nextRouteSequence = 0L
    private var gpsStarted = false
    private val gpsStartRetry = GpsStartRetryGate()
    private var lastLocation: GpsLocationSample? = null
    private var trustAnchor: GpsLocationSample? = null
    private var lastLocationTripId: String? = null
    private var latestBatteryPowerKw: Double? = null
    private var latestBatteryPowerAtMs = Long.MIN_VALUE
    private var latestBatteryPowerBootId: String? = null
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
                    gpsStartRetry.reset(preserveOutage = true)
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
        val telemetry = computeTelemetrySnapshot(observations, receivedElapsedMs)
        val snapshot = telemetry.copy(
            energy = energySnapshot,
            eventElapsedMs = energySnapshot?.sourceElapsedMs ?: telemetry.eventElapsedMs,
            eventBootId = energySnapshot?.sourceBootId ?: telemetry.eventBootId
        )
        if (liveTelemetry) vehicleSpeedReference.observe(snapshot.speedKmh, receivedElapsedMs)
        dispatch(onDropped = { diagnosticPowerSession?.complete(null) }) {
            try {
                ensureInitialized()
                // Capture the frontier here, not when Telegram eventually runs: a later
                // OFF must never be applied before this earlier queued ON/poll input.
                beforeBoundary(tripStore.completionWatermark())
                latestBatteryPowerKw = snapshot.batteryPowerKw
                latestBatteryPowerAtMs = snapshot.receivedElapsedMs
                latestBatteryPowerBootId = bootId

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

    /** Applies one durable business-input journal entry and completes only after its Trips transaction commits. */
    fun onBusinessInput(
        journalGeneration: String,
        inputId: Long,
        sourceIdentity: String,
        timestamp: String,
        readings: List<PollReading>,
        observations: List<NormalizedObservation>,
        liveTelemetry: Boolean,
        energySnapshot: EnergySnapshot? = null,
        eventSourceStamp: NormalizedSourceStamp? = null,
        allowWhilePaused: Boolean = false
    ): CompletableFuture<TripBusinessResult> {
        val future = CompletableFuture<TripBusinessResult>()
        if (!running.get()) {
            future.completeExceptionally(IllegalStateException("Trips runtime is closed"))
            return future
        }
        if (paused.get() && !allowWhilePaused) {
            future.completeExceptionally(IllegalStateException("Trips runtime is paused"))
            return future
        }
        val work: () -> Unit = task@ {
            val priorBatteryPowerKw = latestBatteryPowerKw
            val priorBatteryPowerAtMs = latestBatteryPowerAtMs
            val priorBatteryPowerBootId = latestBatteryPowerBootId
            try {
                check(running.get()) { "Trips runtime is closed" }
                check(allowWhilePaused || !paused.get()) { "Trips runtime is paused" }
                val replay = tripStore.businessInputReplay(journalGeneration, inputId, sourceIdentity)
                if (replay != null) {
                    future.complete(
                        TripBusinessResult(
                            consumed = false,
                            priorCompletionWatermark = replay.priorCompletionWatermark,
                            completionWatermark = replay.completionWatermark,
                            powerSessionId = replay.powerSessionId,
                            confirmedPowerOn = false
                        )
                    )
                    return@task
                }

                ensureInitialized()
                val currentStamp = eventSourceStamp ?: observations.firstNotNullOfOrNull { observation ->
                    observation.sourceStamp?.takeIf { it.identity == sourceIdentity }
                }
                val eventElapsedMs = currentStamp?.elapsedMs ?: Long.MIN_VALUE
                val snapshot = computeTelemetrySnapshot(
                    observations = observations,
                    receivedElapsedMs = eventElapsedMs,
                    sourceStamped = true,
                    eventSourceStamp = currentStamp
                ).copy(
                    energy = energySnapshot?.takeIf {
                        currentStamp != null && it.sourceBootId == currentStamp.bootId &&
                            it.sourceElapsedMs == currentStamp.elapsedMs
                    }
                )
                val speedElapsedMs = snapshot.speedElapsedMs
                if (liveTelemetry && speedElapsedMs != null && snapshot.speedBootId == bootId) {
                    vehicleSpeedReference.observe(snapshot.speedKmh, speedElapsedMs)
                }
                latestBatteryPowerKw = snapshot.batteryPowerKw
                latestBatteryPowerAtMs = snapshot.batteryPowerElapsedMs
                    ?.takeIf { snapshot.batteryPowerBootId == bootId }
                    ?: Long.MIN_VALUE
                latestBatteryPowerBootId = snapshot.batteryPowerBootId

                val decodedPower = readings.firstOrNull { it.rawKey == POWER_FIELD_KEY }
                    ?.descValue?.trim()?.toIntOrNull()
                val willTransition = when {
                    decodedPower == null || decodedPower < 0 -> false
                    decodedPower > 0 -> powerTracker.current() != VehiclePowerState.ON
                    else -> powerTracker.current() != VehiclePowerState.OFF
                }
                if (willTransition) {
                    require(currentStamp != null && currentStamp.elapsedMs >= 0L && currentStamp.bootId.isNotBlank()) {
                        "Business power boundary has no source acquisition stamp"
                    }
                }
                val finalGpsStopped = willTransition && decodedPower == 0
                if (finalGpsStopped) stopGps(markFinal = true)

                val committed = tripStore.consumeBusinessInput(journalGeneration, inputId, sourceIdentity) { _ ->
                    val mutation = applyBusinessInput(
                        timestamp = timestamp,
                        readings = readings,
                        snapshot = snapshot,
                        finalGpsStopped = finalGpsStopped
                    )
                    TripBusinessInputMutation(mutation, mutation.powerSessionId)
                }
                if (committed.applied) {
                    val mutation = checkNotNull(committed.value)
                    if (!allowWhilePaused && powerTracker.current() == VehiclePowerState.ON) {
                        if (!locationCaptureEnabled()) stopGps()
                        else if (liveTelemetry) ensureGpsRunning()
                    }
                    future.complete(
                        TripBusinessResult(
                            consumed = true,
                            priorCompletionWatermark = committed.priorCompletionWatermark,
                            completionWatermark = committed.completionWatermark,
                            powerSessionId = committed.powerSessionId,
                            confirmedPowerOn = mutation.confirmedPowerOn,
                            closedPowerSession = mutation.closedPowerSession
                        )
                    )
                } else {
                    future.complete(
                        TripBusinessResult(
                            consumed = false,
                            priorCompletionWatermark = committed.priorCompletionWatermark,
                            completionWatermark = committed.completionWatermark,
                            powerSessionId = committed.powerSessionId,
                            confirmedPowerOn = false
                        )
                    )
                }
            } catch (error: Throwable) {
                latestBatteryPowerKw = priorBatteryPowerKw
                latestBatteryPowerAtMs = priorBatteryPowerAtMs
                latestBatteryPowerBootId = priorBatteryPowerBootId
                try {
                    restoreRuntimeFromDurableState()
                } catch (restoreError: Throwable) {
                    error.addSuppressed(restoreError)
                }
                future.completeExceptionally(error)
            }
        }
        if (Thread.currentThread() === ownerThread) {
            work()
        } else {
            try {
                executor.execute {
                    ownerThread = Thread.currentThread()
                    work()
                }
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future
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

    private data class BusinessMutation(
        val powerSessionId: String?, val confirmedPowerOn: Boolean, val closedPowerSession: Boolean
    )

    private fun applyBusinessInput(
        timestamp: String,
        readings: List<PollReading>,
        snapshot: TelemetrySnapshot,
        finalGpsStopped: Boolean
    ): BusinessMutation {
        val decodedPower = readings.firstOrNull { it.rawKey == POWER_FIELD_KEY }
            ?.descValue?.trim()?.toIntOrNull()
        val transition = powerTracker.observe(decodedPower)
        var boundaryTripId: String? = null
        try {
            when (transition?.current) {
                VehiclePowerState.ON -> {
                    handlePowerOn(timestamp, snapshot)
                    boundaryTripId = session?.tripId
                }
                VehiclePowerState.OFF -> {
                    boundaryTripId = handlePowerOff(
                        timestamp = timestamp,
                        snapshot = snapshot,
                        diagnosticPowerSession = null,
                        finalGpsStopped = finalGpsStopped,
                        notifyCompletion = false
                    )
                }
                else -> Unit
            }
            if (powerTracker.current() == VehiclePowerState.ON) {
                updateOpenSession(timestamp, snapshot)
            }
            return BusinessMutation(
                powerSessionId = boundaryTripId ?: session?.tripId ?: snapshot.energy?.powerSessionId,
                confirmedPowerOn = transition?.current == VehiclePowerState.ON,
                closedPowerSession = transition?.current == VehiclePowerState.OFF
            )
        } catch (error: Throwable) {
            transition?.let(powerTracker::rollback)
            throw error
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
                    tripId = snapshot.energy?.powerSessionId ?: TripId.forPowerSession(snapshot.eventBootId, snapshot.eventElapsedMs),
                    startedAt = snapshot.energy?.startedAt ?: timestamp,
                    startElapsedMs = snapshot.eventElapsedMs,
                    startBootId = snapshot.eventBootId,
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
        diagnosticPowerSession: CompletableFuture<String?>?,
        finalGpsStopped: Boolean = false,
        notifyCompletion: Boolean = true
    ): String? {
        if (!finalGpsStopped) stopGps(markFinal = true)
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
                elapsedMs = snapshot.eventElapsedMs,
                bootId = snapshot.eventBootId,
                segmentId = segmentId
            )
        }
        val completion = if (completionEnabled()) TripCompletionIntent(
            identity = "power_off:${closed?.tripId ?: snapshot.energy?.powerSessionId ?: "${snapshot.eventBootId}:${snapshot.eventElapsedMs}"}",
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
        if (notifyCompletion) durableCompletion?.let { runCatching { onCompletionReady(it.sequence) } }
        return closed?.tripId ?: current?.tripId
    }

    private fun ensureGpsRunning() {
        if (gpsStarted || !locationCaptureEnabled()) return
        val nowMs = elapsedRealtimeMs()
        if (!gpsStartRetry.canAttempt(nowMs)) return
        val result = locationSource.start(trustAnchor?.takeIf { it.canAnchorRecovery(bootId, nowMs) })
        gpsStarted = result.started
        if (gpsStarted) {
            val recovered = gpsStartRetry.hasFailures
            gpsStartRetry.reset()
            if (recovered) recordEvent("gps_capture_recovered", "GPS route capture restarted", null)
            return
        }
        val firstFailure = gpsStartRetry.onFailure(elapsedRealtimeMs())
        val reason = result.failureReason ?: "gps_start_failed"
        if (firstFailure && result.recordGap) handleGap(reason, Instant.now().toString())
        recordEvent(if (firstFailure) "gps_capture_unavailable" else "gps_capture_retry",
            "GPS route capture did not start", "reason=$reason" + result.errorDetail?.let { "\n$it" }.orEmpty())
    }

    private fun stopGps(markFinal: Boolean = false) {
        locationSource.stop(markFinal)
        gpsStarted = false
        gpsStartRetry.reset()
    }

    private fun handleLocation(sample: GpsLocationSample, isFinal: Boolean) {
        if (!running.get()) return
        lastLocation = sample
        trustAnchor = sample
        persistLocation(LocationNormalizer.observations(sample, sample.receiveWallTimeMs))
        val current = session ?: return
        lastLocationTripId = current.tripId
        val sampleElapsedMs = sample.receiveElapsedRealtimeNanos / 1_000_000L
        val powerAgeMs = if (sample.bootId == latestBatteryPowerBootId && sample.bootId == bootId) {
            sampleElapsedMs - latestBatteryPowerAtMs
        } else Long.MIN_VALUE
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
        restoreRuntimeFromDurableState()
    }

    private fun restoreRuntimeFromDurableState() {
        session = tripStore.loadOpenSession()
        nextRouteSequence = session?.let { tripStore.nextRouteSequence(it.tripId) } ?: 0L
        lastLocation = session?.let { current ->
            tripStore.queryRoutePoints(current.tripId).lastOrNull { it.kind == RoutePoint.KIND_VALID }?.toGpsSample()
        }
        lastLocationTripId = session?.tripId?.takeIf { lastLocation != null }
        trustAnchor = lastLocation
        // An open trip is durable evidence that ON still needs a matching OFF. Without
        // one (including when history is disabled), OFF is the restart baseline rather
        // than a new boundary. Establish it only after retryable store reads succeed.
        powerTracker = VehiclePowerBoundaryTracker()
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
                    recordEvent("trip_runtime_error", "Trip runtime operation failed", error.diagnosticDetail())
                }
            }
        }.onFailure { error ->
            recordEvent("trip_dispatch_error", "Trip runtime dispatch failed", error.diagnosticDetail())
            onDropped()
        }
    }

    private fun Map<String, NormalizedObservation>.number(key: String): Double? {
        val observation = this[key] ?: return null
        if (observation.quality != NormalizedQuality.OK) return null
        return observation.value.number?.takeIf(Double::isFinite)
    }

    private fun computeTelemetrySnapshot(
        observations: List<NormalizedObservation>,
        receivedElapsedMs: Long,
        sourceStamped: Boolean = false,
        eventSourceStamp: NormalizedSourceStamp? = null
    ): TelemetrySnapshot {
        val values = observations.associateBy { it.field.fieldKey }
        fun sourceStamp(key: String): NormalizedSourceStamp? = values[key]?.sourceStamp
        return TelemetrySnapshot(
            soc = values.number("soc"),
            odometerKm = values.number("odometer_km"),
            tripEnergyKwh = values.number("trip_energy_kwh"),
            speedKmh = values.number("speed_kmh"),
            batteryPowerKw = values.number("battery_power_kw"),
            receivedElapsedMs = receivedElapsedMs,
            eventElapsedMs = eventSourceStamp?.elapsedMs ?: receivedElapsedMs,
            eventBootId = eventSourceStamp?.bootId ?: bootId,
            speedElapsedMs = if (sourceStamped) sourceStamp("speed_kmh")?.elapsedMs else receivedElapsedMs,
            speedBootId = if (sourceStamped) sourceStamp("speed_kmh")?.bootId else bootId,
            batteryPowerElapsedMs = if (sourceStamped) sourceStamp("battery_power_kw")?.elapsedMs else receivedElapsedMs,
            batteryPowerBootId = if (sourceStamped) sourceStamp("battery_power_kw")?.bootId else bootId
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
        val eventElapsedMs: Long,
        val eventBootId: String,
        val speedElapsedMs: Long?,
        val speedBootId: String?,
        val batteryPowerElapsedMs: Long?,
        val batteryPowerBootId: String?,
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
