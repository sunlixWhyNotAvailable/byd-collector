package com.bydcollector.collector.service

import com.bydcollector.collector.data.direct.DirectKpiInvalidation
import com.bydcollector.collector.data.direct.DirectKpiMailboxSnapshot
import com.bydcollector.collector.data.direct.DirectKpiSubscriptionResult
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.direct.CollectorHelperProtocol

/** Owns only KPI subscribe/drain/unsubscribe Binder calls; vendor reads stay on the poll worker. */
internal class KpiCallbackDrainWorker(
    private val helper: DirectVehicleHelper,
    private val onSubscription: (DirectKpiSubscriptionResult) -> Unit,
    private val onSnapshot: (DirectKpiMailboxSnapshot) -> Unit,
    private val onListenerStatus: (Int) -> Unit,
    private val onTransportLoss: (String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val lock = Object()
    private var active = false
    private var closed = false
    private var revision = 0L
    private var invalidationRevision = 0L
    private var handledInvalidationRevision = 0L
    private var latestInvalidation: DirectKpiInvalidation? = null
    private var reconnectRequested = false
    private val worker = Thread(::runLoop, "byd-kpi-mailbox").apply { isDaemon = true }

    fun start() = worker.start()

    fun setActive(value: Boolean) {
        synchronized(lock) {
            if (closed || active == value) return
            active = value
            revision++
            lock.notifyAll()
        }
    }

    fun requestReconnect() {
        synchronized(lock) {
            if (closed) return
            reconnectRequested = true
            revision++
            lock.notifyAll()
        }
    }

    /** A zero timeout only signals closure; positive timeouts may wait for an in-flight Binder call. */
    fun closeAndJoin(timeoutMs: Long): Boolean {
        synchronized(lock) {
            closed = true
            revision++
            lock.notifyAll()
        }
        if (Thread.currentThread() !== worker && timeoutMs > 0L) {
            try {
                worker.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !worker.isAlive
    }

    private fun onInvalidated(value: DirectKpiInvalidation) {
        synchronized(lock) {
            if (closed) return
            latestInvalidation = value
            invalidationRevision++
            revision++
            lock.notifyAll()
        }
    }

    private fun runLoop() {
        var subscription: DirectKpiSubscriptionResult? = null
        var forceDrain = false
        var retryAtMs: Long? = null
        var failures = 0
        var lastDrainedSequence = -1L
        try {
            while (true) {
                val step = synchronized(lock) {
                    while (true) {
                        if (closed) return@synchronized DrainStep.Close
                        val current = subscription
                        if (!active) {
                            if (current != null) return@synchronized DrainStep.Deactivate(current)
                            val observed = revision
                            while (!closed && !active && revision == observed) lock.wait()
                            continue
                        }
                        val retryDeadline = retryAtMs
                        if (reconnectRequested || (retryDeadline != null && nowMs() >= retryDeadline) ||
                            (current == null && retryDeadline == null)
                        ) return@synchronized DrainStep.Subscribe(current)
                        if (forceDrain && current != null) {
                            forceDrain = false
                            return@synchronized DrainStep.Drain(current, null)
                        }
                        val pendingRevision = invalidationRevision
                        if (pendingRevision > handledInvalidationRevision) {
                            handledInvalidationRevision = pendingRevision
                            val invalidation = latestInvalidation
                            if (current != null && matches(current, invalidation) &&
                                invalidation!!.sequence > lastDrainedSequence
                            ) return@synchronized DrainStep.Drain(current, invalidation)
                            continue
                        }
                        val observed = revision
                        val waitMs = retryDeadline?.let { (it - nowMs()).coerceAtLeast(1L) } ?: 0L
                        if (waitMs == 0L) {
                            while (!closed && active && revision == observed) lock.wait()
                        } else {
                            val deadline = nowMs() + waitMs
                            while (!closed && active && revision == observed) {
                                val remaining = deadline - nowMs()
                                if (remaining <= 0L) break
                                lock.wait(remaining)
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") DrainStep.Close
                }

                when (step) {
                    DrainStep.Close -> {
                        subscription?.let { runCatching { helper.unsubscribeKpi(it.subscriptionId) } }
                        subscription = null
                        onListenerStatus(CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE)
                        return
                    }
                    is DrainStep.Deactivate -> {
                        runCatching { helper.unsubscribeKpi(step.subscription.subscriptionId) }
                        subscription = null
                        forceDrain = false
                        retryAtMs = null
                        failures = 0
                        lastDrainedSequence = -1L
                        onListenerStatus(CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE)
                    }
                    is DrainStep.Subscribe -> {
                        step.previous?.let { runCatching { helper.unsubscribeKpi(it.subscriptionId) } }
                        synchronized(lock) { reconnectRequested = false }
                        val result = runCatching { helper.subscribeKpi(::onInvalidated) }
                            .getOrElse { error ->
                                onTransportLoss(error.message ?: error.javaClass.simpleName)
                                null
                            }
                        if (result?.ok == true) {
                            subscription = result
                            lastDrainedSequence = -1L
                            forceDrain = true // seed the mailbox even if its initial notify raced subscription.
                            onSubscription(result)
                            onListenerStatus(result.listenerStatus)
                            retryAtMs = if (result.listenerStatus == CollectorHelperProtocol.KPI_LISTENER_READY) null
                                else nowMs() + retryDelay(++failures)
                        } else {
                            subscription = null
                            onTransportLoss(result?.error ?: "KPI subscription failed")
                            onListenerStatus(CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE)
                            retryAtMs = nowMs() + retryDelay(++failures)
                        }
                    }
                    is DrainStep.Drain -> {
                        val event = step.invalidation
                        val snapshot = runCatching { helper.drainKpi(step.subscription.subscriptionId) }
                            .getOrNull()
                        if (snapshot == null || !snapshot.ok ||
                            snapshot.subscriptionId != step.subscription.subscriptionId ||
                            snapshot.helperBootId != step.subscription.helperBootId ||
                            snapshot.helperGeneration != step.subscription.helperGeneration
                        ) {
                            val reason = snapshot?.error ?: "KPI mailbox drain failed or helper generation changed"
                            subscription = null // A drain failure invalidates the client's local subscription.
                            forceDrain = false
                            lastDrainedSequence = -1L
                            onTransportLoss(reason)
                            onListenerStatus(CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE)
                            retryAtMs = nowMs() + retryDelay(++failures)
                        } else {
                            if (event != null) lastDrainedSequence = maxOf(lastDrainedSequence, event.sequence)
                            lastDrainedSequence = maxOf(lastDrainedSequence, snapshot.sequence)
                            failures = if (snapshot.listenerStatus == CollectorHelperProtocol.KPI_LISTENER_READY) 0 else failures
                            retryAtMs = if (snapshot.listenerStatus == CollectorHelperProtocol.KPI_LISTENER_READY) null
                                else nowMs() + retryDelay(++failures)
                            val deliver = synchronized(lock) { active && !closed }
                            if (deliver) {
                                onListenerStatus(snapshot.listenerStatus)
                                onSnapshot(snapshot)
                            }
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            subscription?.let { runCatching { helper.unsubscribeKpi(it.subscriptionId) } }
            onListenerStatus(CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE)
        }
    }

    private fun matches(subscription: DirectKpiSubscriptionResult, value: DirectKpiInvalidation?): Boolean =
        value != null && value.subscriptionId == subscription.subscriptionId &&
            value.helperBootId == subscription.helperBootId &&
            value.helperGeneration == subscription.helperGeneration

    private fun retryDelay(failures: Int): Long = when (failures) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 5_000L
        4 -> 15_000L
        else -> 30_000L
    }

    private sealed class DrainStep {
        data object Close : DrainStep()
        data class Deactivate(val subscription: DirectKpiSubscriptionResult) : DrainStep()
        data class Subscribe(val previous: DirectKpiSubscriptionResult?) : DrainStep()
        data class Drain(val subscription: DirectKpiSubscriptionResult, val invalidation: DirectKpiInvalidation?) : DrainStep()
    }
}
