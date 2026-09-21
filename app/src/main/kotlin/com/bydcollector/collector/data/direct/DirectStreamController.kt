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
    private var appliedMask = -1
    private var token = 0L
    private val epochs = longArrayOf(0, 0)
    private var scheduler: ScheduledExecutorService? = null
    private var appReleased = false

    fun configureDesired(main: Boolean, secondary: Boolean) {
        synchronized(stateLock) {
            val nextMask = (if (main) P.STREAM_MAIN else 0) or (if (secondary) P.STREAM_SECONDARY else 0)
            consumerMask = if (appReleased) nextMask else (consumerMask or (nextMask and wantedMask.inv())) and nextMask
            wantedMask = nextMask
            appReleased = false
            startHeartbeatLocked()
        }
    }

    fun ensureReady(stream: Int = 0): Boolean {
        if (stream != 0) index(stream)
        synchronized(stateLock) {
            if (!appReleased && stream != 0 && wantedMask and stream != 0) consumerMask = consumerMask or stream
        }
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
                } else {
                    // A concurrent fence reply may already have advanced one stream.
                    epochs[0] = maxOf(epochs[0], result.mainEpoch)
                    epochs[1] = maxOf(epochs[1], result.secondaryEpoch)
                }
                startHeartbeatLocked()
            }
            true
        }
        val reconciled = claimed && when (stream) {
            P.STREAM_MAIN -> reconcile(P.STREAM_MAIN)
            P.STREAM_SECONDARY -> reconcile(P.STREAM_SECONDARY)
            else -> reconcile(P.STREAM_MAIN) && reconcile(P.STREAM_SECONDARY)
        }
        if (!reconciled || stream == 0) return reconciled
        // Re-entry after a failed consumer must not race the next scheduled heartbeat.
        // Only the selected stream is renewed; Main readiness never renews secondary.
        val owner = credentials(stream) ?: return synchronized(stateLock) { wantedMask and stream == 0 }
        return exchange(P.CONTROL_RENEW, nonce, owner.controllerToken, stream, owner.epoch, 0).ok
    }

    fun setDesired(stream: Int, enabled: Boolean): Boolean {
        index(stream)
        synchronized(stateLock) {
            wantedMask = if (enabled) wantedMask or stream else wantedMask and stream.inv()
            consumerMask = if (enabled) consumerMask or stream else consumerMask and stream.inv()
            appReleased = false
            startHeartbeatLocked()
        }
        return ensureReady(stream)
    }

    fun pauseAndFence(stream: Int): Boolean {
        if (!ensureReady(stream)) return false
        return mutate(stream, P.CONTROL_PAUSE_FENCE)
    }

    fun resume(stream: Int): Boolean = mutate(stream, P.CONTROL_RESUME)

    fun credentials(stream: Int): DirectStreamCredentials? = synchronized(stateLock) {
        val position = index(stream)
        if (appReleased || token <= 0 || wantedMask and stream == 0 || appliedMask and stream == 0 || consumerMask and stream == 0) null
        else DirectStreamCredentials(token, epochs[position])
    }

    /** Ordinary service death relinquishes the APP lease, not the user's desired collection. */
    fun releaseApp() {
        synchronized(stateLock) {
            appReleased = true
            scheduler?.shutdownNow()
            scheduler = null
        }
    }

    fun releaseLease(stream: Int) {
        index(stream)
        synchronized(stateLock) { consumerMask = consumerMask and stream.inv() }
    }

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
        if (appReleased || wantedMask == 0 || scheduler != null) return
        scheduler = Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "byd-stream-lease").apply { isDaemon = true }
        }.also { executor ->
            listOf(P.STREAM_MAIN, P.STREAM_SECONDARY).forEach { stream ->
                // A blocked secondary Binder operation must not starve Main's heartbeat.
                executor.scheduleWithFixedDelay({
                    try {
                        val request = credentials(stream) ?: return@scheduleWithFixedDelay
                        exchange(P.CONTROL_RENEW, nonce, request.controllerToken, stream, request.epoch, 0)
                    } catch (_: Exception) {
                        // Renewal never claims/re-enables: helper fallback is the safe failure mode.
                    }
                }, 0, 500, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun index(stream: Int): Int = when (stream) {
        P.STREAM_MAIN -> 0
        P.STREAM_SECONDARY -> 1
        else -> throw IllegalArgumentException("invalid collection stream: $stream")
    }
}

object DirectStreamController {
    private val controller = AppStreamController()
    fun configureDesired(main: Boolean, secondary: Boolean) = controller.configureDesired(main, secondary)
    fun ensureReady(stream: Int = 0): Boolean = controller.ensureReady(stream)
    fun setDesired(stream: Int, enabled: Boolean): Boolean = controller.setDesired(stream, enabled)
    fun pauseAndFence(stream: Int): Boolean = controller.pauseAndFence(stream)
    fun resume(stream: Int): Boolean = controller.resume(stream)
    fun credentials(stream: Int): DirectStreamCredentials? = controller.credentials(stream)
    fun releaseApp() = controller.releaseApp()
    fun releaseLease(stream: Int) = controller.releaseLease(stream)
}
