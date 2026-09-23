package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol as P
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class DirectStreamControlResult(
    val status: Int,
    val controllerToken: Long = 0,
    val mainEpoch: Long = 0,
    val secondaryEpoch: Long = 0,
    val leaseExpiresElapsedMs: Long = 0,
    val error: String? = null
) {
    val ok: Boolean get() = status == P.STATUS_OK
}

data class DirectStreamCredentials(val controllerToken: Long, val epoch: Long)

/** One APP-process controller; heartbeat IO never shares a lock/client with replay or a fence. */
internal class AppStreamController(
    private val exchange: (Int, String, Long, Int, Long, Int) -> DirectStreamControlResult = { action, nonce, token, stream, epoch, value ->
        DirectVehicleHelperClient().streamControl(action, nonce, token, stream, epoch, value)
    }
) {
    private val nonce = UUID.randomUUID().toString()
    private val stateLock = Any()
    private val claimLock = ReentrantLock()
    private val streamLocks = arrayOf(ReentrantLock(), ReentrantLock())
    private var wantedMask = 0
    private var consumerMask = 0
    private var autonomyMask = 0
    private var appliedMask = -1
    private var appliedAutonomyMask = -1
    private var token = 0L
    private val epochs = longArrayOf(0, 0)
    private val readinessGeneration = longArrayOf(0L, 0L)
    private var scheduler: ScheduledExecutorService? = null
    private var appReleased = false
    private val renewalFailed = booleanArrayOf(false, false)
    @Volatile private var diagnostic: ((String, String) -> Unit)? = null

    fun setDiagnostic(callback: ((String, String) -> Unit)?) { diagnostic = callback }

    fun configureDesired(
        main: Boolean,
        secondary: Boolean,
        mainAutonomous: Boolean = false,
        secondaryAutonomous: Boolean = false
    ) {
        synchronized(stateLock) {
            val nextMask = (if (main) P.STREAM_MAIN else 0) or (if (secondary) P.STREAM_SECONDARY else 0)
            val nextAutonomyMask =
                (if (main && mainAutonomous) P.STREAM_MAIN else 0) or
                (if (secondary && secondaryAutonomous) P.STREAM_SECONDARY else 0)
            wantedMask = nextMask
            autonomyMask = nextAutonomyMask
            consumerMask = consumerMask and nextMask
            if (consumerMask == 0) stopHeartbeatLocked()
            appReleased = false
        }
    }

    fun ensureReady(stream: Int = 0): Boolean {
        if (stream != 0) index(stream)
        if (Thread.currentThread().isInterrupted) return false
        val claimed = claimLock.withLock {
            val wanted = synchronized(stateLock) { if (appReleased) return false else wantedMask }
            val result = exchange(P.CONTROL_CLAIM, nonce, 0, 0, 0, wanted)
            if (!result.ok || result.controllerToken <= 0) return@withLock false
            synchronized(stateLock) {
                if (token != result.controllerToken || appliedMask == -1) {
                    token = result.controllerToken
                    epochs[0] = result.mainEpoch
                    epochs[1] = result.secondaryEpoch
                    appliedMask = wanted
                    appliedAutonomyMask = 0
                } else {
                    // A concurrent fence reply may already have advanced one stream.
                    epochs[0] = maxOf(epochs[0], result.mainEpoch)
                    epochs[1] = maxOf(epochs[1], result.secondaryEpoch)
                }
            }
            true
        }
        if (!claimed) return false
        return when (stream) {
            P.STREAM_MAIN -> reconcilePolicy(P.STREAM_MAIN)
            P.STREAM_SECONDARY -> reconcilePolicy(P.STREAM_SECONDARY)
            else -> reconcilePolicy(P.STREAM_MAIN) && reconcilePolicy(P.STREAM_SECONDARY)
        }
    }

    fun setDesired(stream: Int, enabled: Boolean): Boolean {
        index(stream)
        synchronized(stateLock) {
            wantedMask = if (enabled) wantedMask or stream else wantedMask and stream.inv()
            if (!enabled) {
                consumerMask = consumerMask and stream.inv()
                autonomyMask = autonomyMask and stream.inv()
                readinessGeneration[index(stream)]++
                if (consumerMask == 0) stopHeartbeatLocked()
            }
            appReleased = false
        }
        return ensureReady(stream)
    }

    /** Marks an actual poller/callback consumer ready and only then starts or renews its APP lease. */
    fun setConsumerReady(stream: Int, ready: Boolean, isCurrent: () -> Boolean = { true }): Boolean {
        val position = index(stream)
        if (!ready) {
            synchronized(stateLock) {
                consumerMask = consumerMask and stream.inv()
                readinessGeneration[position]++
                if (consumerMask == 0) stopHeartbeatLocked()
            }
            return true
        }

        val generation = synchronized(stateLock) {
            if (appReleased || wantedMask and stream == 0 || !isCurrent()) return false
            consumerMask = consumerMask or stream
            ++readinessGeneration[position]
        }
        if (!ensureReady(stream)) {
            releaseReadiness(stream, generation)
            return false
        }
        val owner = synchronized(stateLock) {
            if (appReleased || readinessGeneration[position] != generation || consumerMask and stream == 0 ||
                wantedMask and stream == 0 || !isCurrent()
            ) {
                releaseReadinessLocked(stream, generation)
                return false
            }
            credentialsLocked(stream)
        } ?: run {
            releaseReadiness(stream, generation)
            return false
        }
        val result = exchange(P.CONTROL_RENEW, nonce, owner.controllerToken, stream, owner.epoch, 0)
        return synchronized(stateLock) {
            val current = isCurrent()
            if (!result.ok || appReleased || readinessGeneration[position] != generation ||
                consumerMask and stream == 0 || token != owner.controllerToken ||
                result.controllerToken != token || !current
            ) {
                if (!result.ok || !current) releaseReadinessLocked(stream, generation)
                return false
            }
            acceptEpochs(result)
            startHeartbeatLocked()
            true
        }
    }

    fun pauseAndFence(stream: Int): Boolean {
        if (!ensureReady(stream)) return false
        return mutate(stream, P.CONTROL_PAUSE_FENCE)
    }

    fun resume(stream: Int): Boolean = mutate(stream, P.CONTROL_RESUME)

    fun credentials(stream: Int): DirectStreamCredentials? = synchronized(stateLock) {
        credentialsLocked(stream)
    }

    /** Ordinary service death relinquishes the APP lease, not the user's desired collection. */
    fun releaseApp() {
        synchronized(stateLock) {
            appReleased = true
            consumerMask = 0
            for (position in readinessGeneration.indices) readinessGeneration[position]++
            stopHeartbeatLocked()
        }
    }

    fun releaseLease(stream: Int) {
        setConsumerReady(stream, false)
    }

    private fun reconcilePolicy(stream: Int): Boolean = reconcile(stream) && reconcileAutonomy(stream)

    private fun reconcile(stream: Int): Boolean {
        // Main readiness must not wait on a secondary archive fence when no intent changed.
        synchronized(stateLock) {
            if (!appReleased && token > 0 && (wantedMask and stream) == (appliedMask and stream)) return true
        }
        return streamLocks[index(stream)].withLock {
        while (true) {
            if (Thread.currentThread().isInterrupted) return false
            val request = synchronized(stateLock) {
                if (appReleased || token <= 0) return false
                val enabled = wantedMask and stream != 0
                if (enabled == (appliedMask and stream != 0)) return true
                Triple(token, epochs[index(stream)], enabled)
            }
            val result = exchange(
                P.CONTROL_SET_DESIRED, nonce, request.first, stream, request.second, if (request.third) 1 else 0
            )
            synchronized(stateLock) {
                if (!result.ok || token != request.first || result.controllerToken != token) return false
                acceptEpochs(result)
                appliedMask = if (request.third) appliedMask or stream else appliedMask and stream.inv()
            }
        }
        @Suppress("UNREACHABLE_CODE") false
        }
    }

    private fun reconcileAutonomy(stream: Int): Boolean {
        synchronized(stateLock) {
            if (!appReleased && token > 0 &&
                (autonomyMask and stream) == (appliedAutonomyMask and stream)
            ) return true
        }
        return streamLocks[index(stream)].withLock {
        while (true) {
            if (Thread.currentThread().isInterrupted) return false
            val request = synchronized(stateLock) {
                if (appReleased || token <= 0) return false
                val allowed = autonomyMask and stream != 0
                if (allowed == (appliedAutonomyMask and stream != 0)) return true
                Triple(token, epochs[index(stream)], allowed)
            }
            val result = exchange(
                P.CONTROL_SET_AUTONOMY, nonce, request.first, stream, request.second, if (request.third) 1 else 0
            )
            synchronized(stateLock) {
                if (!result.ok || token != request.first || result.controllerToken != token) return false
                acceptEpochs(result)
                appliedAutonomyMask = if (request.third) appliedAutonomyMask or stream
                    else appliedAutonomyMask and stream.inv()
            }
        }
        @Suppress("UNREACHABLE_CODE") false
        }
    }

    private fun mutate(stream: Int, action: Int): Boolean = streamLocks[index(stream)].withLock {
        val request = credentials(stream) ?: return false
        val result = exchange(action, nonce, request.controllerToken, stream, request.epoch, 0)
        synchronized(stateLock) {
            if (!result.ok || token != request.controllerToken || result.controllerToken != token ||
                appReleased || wantedMask and stream == 0
            ) return false
            acceptEpochs(result)
            true
        }
    }

    private fun acceptEpochs(result: DirectStreamControlResult) {
        epochs[0] = maxOf(epochs[0], result.mainEpoch)
        epochs[1] = maxOf(epochs[1], result.secondaryEpoch)
    }

    private fun startHeartbeatLocked() {
        if (appReleased || consumerMask == 0 || scheduler != null) return
        scheduler = Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "byd-stream-lease").apply { isDaemon = true }
        }.also { executor ->
            listOf(P.STREAM_MAIN, P.STREAM_SECONDARY).forEach { stream ->
                // A blocked secondary Binder operation must not starve Main's heartbeat.
                executor.scheduleWithFixedDelay({
                    try {
                        val request = credentials(stream) ?: return@scheduleWithFixedDelay
                        val result = exchange(P.CONTROL_RENEW, nonce, request.controllerToken, stream, request.epoch, 0)
                        val wasFailed = synchronized(stateLock) {
                            val index = index(stream)
                            val previous = renewalFailed[index]
                            renewalFailed[index] = !result.ok
                            previous
                        }
                        if (!result.ok && !wasFailed) {
                            runCatching {
                                diagnostic?.invoke(
                                    "stream_lease_renewal_error",
                                    "stream=$stream status=${result.status} error=${result.error.orEmpty()}"
                                )
                            }
                        } else if (result.ok && wasFailed) {
                            runCatching { diagnostic?.invoke("stream_lease_renewal_recovered", "stream=$stream") }
                        }
                    } catch (error: Exception) {
                        val firstFailure = synchronized(stateLock) {
                            val index = index(stream)
                            val first = !renewalFailed[index]
                            renewalFailed[index] = true
                            first
                        }
                        if (firstFailure) runCatching {
                            diagnostic?.invoke("stream_lease_renewal_error", "stream=$stream\n${error.stackTraceToString()}")
                        }
                    }
                }, 0, 500, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun stopHeartbeatLocked() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    private fun credentialsLocked(stream: Int): DirectStreamCredentials? {
        val position = index(stream)
        return if (appReleased || token <= 0 || wantedMask and stream == 0 || appliedMask and stream == 0 ||
            consumerMask and stream == 0
        ) null else DirectStreamCredentials(token, epochs[position])
    }

    private fun releaseReadiness(stream: Int, generation: Long) {
        synchronized(stateLock) { releaseReadinessLocked(stream, generation) }
    }

    private fun releaseReadinessLocked(stream: Int, generation: Long) {
        val position = index(stream)
        if (readinessGeneration[position] != generation) return
        consumerMask = consumerMask and stream.inv()
        readinessGeneration[position]++
        if (consumerMask == 0) stopHeartbeatLocked()
    }

    private fun index(stream: Int): Int = when (stream) {
        P.STREAM_MAIN -> 0
        P.STREAM_SECONDARY -> 1
        else -> throw IllegalArgumentException("invalid collection stream: $stream")
    }
}

object DirectStreamController {
    private val controller = AppStreamController()
    fun setDiagnostic(callback: ((String, String) -> Unit)?) = controller.setDiagnostic(callback)
    fun configureDesired(
        main: Boolean,
        secondary: Boolean,
        mainAutonomous: Boolean = false,
        secondaryAutonomous: Boolean = false
    ) = controller.configureDesired(main, secondary, mainAutonomous, secondaryAutonomous)
    fun ensureReady(stream: Int = 0): Boolean = controller.ensureReady(stream)
    fun setDesired(stream: Int, enabled: Boolean): Boolean = controller.setDesired(stream, enabled)
    fun pauseAndFence(stream: Int): Boolean = controller.pauseAndFence(stream)
    fun resume(stream: Int): Boolean = controller.resume(stream)
    fun credentials(stream: Int): DirectStreamCredentials? = controller.credentials(stream)
    fun setConsumerReady(stream: Int, ready: Boolean, isCurrent: () -> Boolean = { true }): Boolean =
        controller.setConsumerReady(stream, ready, isCurrent)
    fun releaseApp() = controller.releaseApp()
    fun releaseLease(stream: Int) = controller.releaseLease(stream)
}
