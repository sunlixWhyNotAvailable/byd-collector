package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol as P
import com.bydcollector.collector.util.diagnosticDetail
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
    val error: String? = null,
    val cause: Throwable? = null
) {
    val ok: Boolean get() = status == P.STATUS_OK
}

data class DirectStreamCredentials(val controllerToken: Long, val epoch: Long, val generation: Long = 0)

class DirectStreamControlException(
    val stage: String,
    val stream: Int,
    val reason: String,
    val cancelled: Boolean,
    val reply: DirectStreamControlResult?,
    detail: String
) : RuntimeException(detail, reply?.cause)

/** One APP-process controller; heartbeat IO never shares a lock/client with replay or a fence. */
internal class AppStreamController(
    private val elapsedMs: () -> Long = android.os.SystemClock::elapsedRealtime,
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
    private val consumerOwners = arrayOfNulls<Any>(2)
    private val defaultOwners = arrayOf(Any(), Any())
    private val leaseExpiry = longArrayOf(0L, 0L)
    private var validLeaseMask = 0
    private var claimRequired = true
    private var controlGeneration = 0L
    private var scheduler: ScheduledExecutorService? = null
    private var appReleased = false
    private val renewalFailed = booleanArrayOf(false, false)
    @Volatile private var diagnostic: ((String, String) -> Unit)? = null

    fun setDiagnostic(callback: ((String, String) -> Unit)?) { diagnostic = callback }

    fun observeReadiness(operation: () -> Boolean): Boolean = try { operation() } catch (error: DirectStreamControlException) {
        if (!error.cancelled) throw error
        runCatching { diagnostic?.invoke("stream_control_cancelled", error.diagnosticDetail()) }
        throw InterruptedException(error.message).apply { initCause(error) }
    }

    fun observeControl(operation: () -> Boolean): Boolean = try { operation() } catch (error: DirectStreamControlException) {
        runCatching { diagnostic?.invoke(if (error.cancelled) "stream_control_cancelled" else "stream_control_failed", error.diagnosticDetail()) }
        false
    }

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
            validLeaseMask = validLeaseMask and nextMask
            for (stream in listOf(P.STREAM_MAIN, P.STREAM_SECONDARY)) {
                if (nextMask and stream == 0) {
                    consumerOwners[index(stream)] = null
                    readinessGeneration[index(stream)]++
                }
            }
            if (consumerMask == 0) stopHeartbeatLocked()
            appReleased = false
        }
    }

    fun ensureReady(stream: Int = 0): Boolean {
        if (stream != 0) index(stream)
        if (Thread.currentThread().isInterrupted) throw controlFailure("claim", stream, "thread_interrupted", cancelled = true)
        claimLock.withLock {
            val (wanted, generation) = synchronized(stateLock) {
                if (appReleased) throw controlFailure("claim", stream, "app_released", cancelled = true)
                if (!claimRequired && token > 0) return@withLock true
                validLeaseMask = 0
                wantedMask to ++controlGeneration
            }
            val result = exchange(P.CONTROL_CLAIM, nonce, 0, 0, 0, wanted)
            if (!result.ok || result.controllerToken <= 0)
                throw controlFailure("claim", stream, "claim_rejected", DirectStreamCredentials(0, 0, generation), result)
            synchronized(stateLock) {
                if (appReleased || controlGeneration != generation)
                    throw controlFailure("claim", stream, "claim_superseded", DirectStreamCredentials(0, 0, generation), result,
                        cancelled = appReleased)
                token = result.controllerToken
                // A recovered helper may restart epochs, even if its numeric token repeats.
                epochs[0] = result.mainEpoch
                epochs[1] = result.secondaryEpoch
                claimRequired = false
                // An idempotent CLAIM need not apply new intent; explicitly reconcile both policies.
                appliedMask = wantedMask.inv()
                appliedAutonomyMask = autonomyMask.inv()
            }
            true
        }
        return when (stream) {
            P.STREAM_MAIN -> reconcilePolicy(P.STREAM_MAIN)
            P.STREAM_SECONDARY -> reconcilePolicy(P.STREAM_SECONDARY)
            else -> reconcilePolicy(P.STREAM_MAIN) && reconcilePolicy(P.STREAM_SECONDARY)
        }
    }

    fun setDesired(stream: Int, enabled: Boolean): Boolean {
        setDesiredLocally(stream, enabled)
        // Stop/Shutdown must still finish local cleanup when the helper has already exited.
        return observeControl { ensureReady(stream) }
    }

    fun setDesiredLocally(stream: Int, enabled: Boolean) {
        index(stream)
        synchronized(stateLock) {
            wantedMask = if (enabled) wantedMask or stream else wantedMask and stream.inv()
            if (!enabled) {
                consumerMask = consumerMask and stream.inv()
                validLeaseMask = validLeaseMask and stream.inv()
                consumerOwners[index(stream)] = null
                autonomyMask = autonomyMask and stream.inv()
                readinessGeneration[index(stream)]++
                if (consumerMask == 0) stopHeartbeatLocked()
            }
            appReleased = false
        }
    }

    /** Marks an actual poller/callback consumer ready and only then starts or renews its APP lease. */
    fun setConsumerReady(stream: Int, ready: Boolean, isCurrent: () -> Boolean = { true }): Boolean {
        return setConsumerReady(stream, ready, defaultOwners[index(stream)], isCurrent)
    }

    fun setConsumerReady(stream: Int, ready: Boolean, owner: Any, isCurrent: () -> Boolean = { true }): Boolean {
        val position = index(stream)
        if (!ready) {
            releaseLease(stream, owner)
            return true
        }

        val generation = synchronized(stateLock) {
            if (appReleased || wantedMask and stream == 0 || !isCurrent())
                throw controlFailure("consumer_ready", stream, "consumer_no_longer_wanted_or_current", cancelled = true)
            if (consumerOwners[position] === owner && !claimRequired &&
                credentialsLocked(stream) != null &&
                (wantedMask and stream) == (appliedMask and stream) &&
                (autonomyMask and stream) == (appliedAutonomyMask and stream)) return true
            consumerMask = consumerMask or stream
            validLeaseMask = validLeaseMask and stream.inv()
            consumerOwners[position] = owner
            ++readinessGeneration[position]
        }
        try {
            ensureReady(stream)
        } catch (error: DirectStreamControlException) {
            releaseReadiness(stream, generation)
            throw error
        }
        val request = synchronized(stateLock) {
            if (appReleased || readinessGeneration[position] != generation || consumerMask and stream == 0 ||
                wantedMask and stream == 0 || !isCurrent()
            ) {
                releaseReadinessLocked(stream, generation)
                throw controlFailure("consumer_ready", stream, "consumer_superseded",
                    cancelled = appReleased || wantedMask and stream == 0 || !isCurrent() ||
                        readinessGeneration[position] != generation)
            }
            controlCredentialsLocked(stream)
        } ?: run {
            releaseReadiness(stream, generation)
            throw controlFailure("consumer_ready", stream, "control_credentials_unavailable")
        }
        val result = exchange(P.CONTROL_RENEW, nonce, request.controllerToken, stream, request.epoch, 0)
        return synchronized(stateLock) {
            val current = isCurrent()
            if (!result.ok || appReleased || readinessGeneration[position] != generation ||
                consumerMask and stream == 0 || token != request.controllerToken ||
                controlGeneration != request.generation ||
                result.controllerToken != token || !current
            ) {
                if (controlGeneration == request.generation && readinessGeneration[position] == generation &&
                    (!result.ok || result.controllerToken != token)) {
                    claimRequired = true
                }
                if (!result.ok || !current) releaseReadinessLocked(stream, generation)
                throw controlFailure("consumer_renew", stream, "renew_rejected_or_superseded", request, result,
                    cancelled = appReleased || wantedMask and stream == 0 || !current)
            }
            acceptEpochs(result)
            validLeaseMask = validLeaseMask or stream
            leaseExpiry[position] = result.leaseExpiresElapsedMs
            renewalFailed[position] = false
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
            validLeaseMask = 0
            consumerOwners.fill(null)
            controlGeneration++
            claimRequired = true
            for (position in readinessGeneration.indices) readinessGeneration[position]++
            stopHeartbeatLocked()
        }
    }

    fun releaseLease(stream: Int) {
        synchronized(stateLock) { releaseReadinessLocked(stream, readinessGeneration[index(stream)]) }
    }

    fun releaseLease(stream: Int, owner: Any) {
        synchronized(stateLock) {
            if (consumerOwners[index(stream)] === owner) {
                releaseReadinessLocked(stream, readinessGeneration[index(stream)])
            }
        }
    }

    fun invalidateHelper() = synchronized(stateLock) {
        claimRequired = true
        controlGeneration++
        validLeaseMask = 0
        token = 0L
    }

    private fun reconcilePolicy(stream: Int): Boolean = reconcile(stream) && reconcileAutonomy(stream)

    private fun reconcile(stream: Int): Boolean {
        // Main readiness must not wait on a secondary archive fence when no intent changed.
        synchronized(stateLock) {
            if (!appReleased && !claimRequired && token > 0 && (wantedMask and stream) == (appliedMask and stream)) return true
        }
        return streamLocks[index(stream)].withLock {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw controlFailure("set_desired", stream, "thread_interrupted", cancelled = true)
            val request = synchronized(stateLock) {
                if (appReleased || claimRequired || token <= 0)
                    throw controlFailure("set_desired", stream, "controller_unavailable", cancelled = appReleased)
                val enabled = wantedMask and stream != 0
                if (enabled == (appliedMask and stream != 0)) return true
                DirectStreamCredentials(token, epochs[index(stream)], controlGeneration) to enabled
            }
            val result = exchange(
                P.CONTROL_SET_DESIRED, nonce, request.first.controllerToken, stream, request.first.epoch, if (request.second) 1 else 0
            )
            synchronized(stateLock) {
                if (!acceptControlReply(request.first, result))
                    throw controlFailure("set_desired", stream, "reply_rejected_or_superseded", request.first, result, appReleased)
                acceptEpochs(result)
                appliedMask = if (request.second) appliedMask or stream else appliedMask and stream.inv()
            }
        }
        @Suppress("UNREACHABLE_CODE") false
        }
    }

    private fun reconcileAutonomy(stream: Int): Boolean {
        synchronized(stateLock) {
            if (!appReleased && !claimRequired && token > 0 &&
                (autonomyMask and stream) == (appliedAutonomyMask and stream)
            ) return true
        }
        return streamLocks[index(stream)].withLock {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw controlFailure("set_autonomy", stream, "thread_interrupted", cancelled = true)
            val request = synchronized(stateLock) {
                if (appReleased || claimRequired || token <= 0)
                    throw controlFailure("set_autonomy", stream, "controller_unavailable", cancelled = appReleased)
                val allowed = autonomyMask and stream != 0
                if (allowed == (appliedAutonomyMask and stream != 0)) return true
                DirectStreamCredentials(token, epochs[index(stream)], controlGeneration) to allowed
            }
            val result = exchange(
                P.CONTROL_SET_AUTONOMY, nonce, request.first.controllerToken, stream, request.first.epoch, if (request.second) 1 else 0
            )
            synchronized(stateLock) {
                if (!acceptControlReply(request.first, result))
                    throw controlFailure("set_autonomy", stream, "reply_rejected_or_superseded", request.first, result, appReleased)
                acceptEpochs(result)
                appliedAutonomyMask = if (request.second) appliedAutonomyMask or stream
                    else appliedAutonomyMask and stream.inv()
            }
        }
        @Suppress("UNREACHABLE_CODE") false
        }
    }

    private fun mutate(stream: Int, action: Int): Boolean = streamLocks[index(stream)].withLock {
        val request = credentials(stream) ?: throw controlFailure("mutate_$action", stream, "lease_unavailable")
        val result = exchange(action, nonce, request.controllerToken, stream, request.epoch, 0)
        synchronized(stateLock) {
            if (!acceptControlReply(request, result) || wantedMask and stream == 0)
                throw controlFailure("mutate_$action", stream, "reply_rejected_or_superseded", request, result,
                    cancelled = appReleased || wantedMask and stream == 0)
            acceptEpochs(result)
            true
        }
    }

    private fun acceptEpochs(result: DirectStreamControlResult) {
        epochs[0] = maxOf(epochs[0], result.mainEpoch)
        epochs[1] = maxOf(epochs[1], result.secondaryEpoch)
    }

    private fun acceptControlReply(request: DirectStreamCredentials, result: DirectStreamControlResult): Boolean {
        if (appReleased || controlGeneration != request.generation || token != request.controllerToken) return false
        if (!result.ok || result.controllerToken != token) {
            claimRequired = true
            validLeaseMask = 0
            return false
        }
        return true
    }

    private fun controlFailure(
        stage: String, stream: Int, reason: String, request: DirectStreamCredentials? = null,
        reply: DirectStreamControlResult? = null, cancelled: Boolean = false
    ): DirectStreamControlException = synchronized(stateLock) {
        DirectStreamControlException(stage, stream, reason, cancelled, reply,
            "stage=$stage stream=$stream cancelled=$cancelled reason=$reason " +
                "request=$request current_token=$token current_generation=$controlGeneration " +
                "wanted_mask=$wantedMask consumer_mask=$consumerMask app_released=$appReleased " +
                "readiness_generation=${readinessGeneration.joinToString()} " +
                "status=${reply?.status} reply_token=${reply?.controllerToken} " +
                "main_epoch=${reply?.mainEpoch} secondary_epoch=${reply?.secondaryEpoch} " +
                "error=${reply?.error.orEmpty()}")
    }

    internal fun renewOnce(stream: Int) {
        val position = index(stream)
        val (request, generation) = synchronized(stateLock) {
            (controlCredentialsLocked(stream) ?: return) to readinessGeneration[position]
        }
        val result = try {
            exchange(P.CONTROL_RENEW, nonce, request.controllerToken, stream, request.epoch, 0)
        } catch (error: Exception) {
            DirectStreamControlResult(-1, error = error.stackTraceToString())
        }
        val transition = synchronized(stateLock) {
            if (readinessGeneration[position] != generation || controlGeneration != request.generation ||
                appReleased || consumerMask and stream == 0 || token != request.controllerToken ||
                epochs[position] != request.epoch) return
            val wasFailed = renewalFailed[position]
            val accepted = acceptControlReply(request, result)
            renewalFailed[position] = !accepted
            if (accepted) {
                acceptEpochs(result)
                validLeaseMask = validLeaseMask or stream
                leaseExpiry[position] = result.leaseExpiresElapsedMs
            }
            when {
                !accepted && !wasFailed -> "stream_lease_renewal_error"
                accepted && wasFailed -> "stream_lease_renewal_recovered"
                else -> null
            }
        }
        if (transition != null) runCatching {
            diagnostic?.invoke(transition, "stream=$stream status=${result.status} error=${result.error.orEmpty()}")
        }
    }

    private fun startHeartbeatLocked() {
        if (appReleased || consumerMask == 0 || scheduler != null) return
        scheduler = Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "byd-stream-lease").apply { isDaemon = true }
        }.also { executor ->
            listOf(P.STREAM_MAIN, P.STREAM_SECONDARY).forEach { stream ->
                // A blocked secondary Binder operation must not starve Main's heartbeat.
                executor.scheduleWithFixedDelay({ renewOnce(stream) }, 500, 500, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun stopHeartbeatLocked() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    private fun credentialsLocked(stream: Int): DirectStreamCredentials? {
        if (validLeaseMask and stream == 0 || elapsedMs() >= leaseExpiry[index(stream)]) return null
        return controlCredentialsLocked(stream)
    }

    private fun controlCredentialsLocked(stream: Int): DirectStreamCredentials? {
        val position = index(stream)
        return if (appReleased || claimRequired || token <= 0 || wantedMask and stream == 0 || appliedMask and stream == 0 ||
            consumerMask and stream == 0
        ) null else DirectStreamCredentials(token, epochs[position], controlGeneration)
    }

    private fun releaseReadiness(stream: Int, generation: Long) {
        synchronized(stateLock) { releaseReadinessLocked(stream, generation) }
    }

    private fun releaseReadinessLocked(stream: Int, generation: Long) {
        val position = index(stream)
        if (readinessGeneration[position] != generation) return
        consumerMask = consumerMask and stream.inv()
        validLeaseMask = validLeaseMask and stream.inv()
        consumerOwners[position] = null
        leaseExpiry[position] = 0
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
    fun ensureReady(stream: Int = 0): Boolean = controller.observeReadiness { controller.ensureReady(stream) }
    fun setDesired(stream: Int, enabled: Boolean): Boolean = controller.setDesired(stream, enabled)
    fun setDesiredLocally(stream: Int, enabled: Boolean) = controller.setDesiredLocally(stream, enabled)
    fun pauseAndFence(stream: Int): Boolean = controller.observeControl { controller.pauseAndFence(stream) }
    fun resume(stream: Int): Boolean = controller.observeControl { controller.resume(stream) }
    fun credentials(stream: Int): DirectStreamCredentials? = controller.credentials(stream)
    fun setConsumerReady(stream: Int, ready: Boolean, isCurrent: () -> Boolean = { true }): Boolean =
        controller.observeControl { controller.setConsumerReady(stream, ready, isCurrent) }
    fun releaseApp() = controller.releaseApp()
    fun releaseLease(stream: Int) = controller.releaseLease(stream)
    fun setConsumerReady(stream: Int, ready: Boolean, owner: Any, isCurrent: () -> Boolean = { true }): Boolean =
        controller.observeControl { controller.setConsumerReady(stream, ready, owner, isCurrent) }
    fun ensureConsumerReady(stream: Int, owner: Any, isCurrent: () -> Boolean): Boolean =
        controller.observeReadiness { controller.setConsumerReady(stream, true, owner, isCurrent) }
    fun releaseLease(stream: Int, owner: Any) = controller.releaseLease(stream, owner)
    fun invalidateHelper() = controller.invalidateHelper()
}
