package com.bydcollector.collector.data.callback

import java.util.concurrent.atomic.AtomicBoolean

/** Serializes receipt-driven callback normalization; wakeups coalesce, committed raw rows do not. */
class CallbackNormalizationWorker(
    private val threadName: String,
    private val drainPage: () -> Boolean,
    private val onFault: (Throwable) -> Unit = {},
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val monotonicNanos: () -> Long = System::nanoTime
) {
    private val lock = Object()
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private var signaled = false
    private var lastFaultReportNanos: Long? = null
    private var lastFaultSignature: String? = null

    fun isRunning(): Boolean = running.get()

    fun isStopping(): Boolean = !running.get() && worker?.isAlive == true

    fun signal() {
        synchronized(lock) {
            signaled = true
            lock.notifyAll()
        }
    }

    fun start(): Boolean = synchronized(lock) {
        if (worker?.isAlive == true || !running.compareAndSet(false, true)) return@synchronized false
        // A fresh worker always scans durable receipts, including after process or service restart.
        signaled = true
        val next = Thread(::runLoop, threadName).apply { isDaemon = true }
        worker = next
        next.start()
        true
    }

    fun stopAndJoin(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val current = synchronized(lock) {
            running.set(false)
            lock.notifyAll()
            worker
        }
        current?.interrupt()
        if (timeoutMs > 0 && current != null && current !== Thread.currentThread()) current.join(timeoutMs)
        val stopped = current?.isAlive != true
        if (stopped) synchronized(lock) { if (worker === current) worker = null }
        return stopped
    }

    /** Stops after the current normalized page completes without interrupting its DB transaction. */
    fun requestStopAfterCurrentPage() {
        synchronized(lock) {
            running.set(false)
            lock.notifyAll()
        }
    }

    fun awaitStopped(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val current = synchronized(lock) { worker }
        if (timeoutMs > 0 && current != null && current !== Thread.currentThread()) current.join(timeoutMs)
        val stopped = current?.isAlive != true
        if (stopped) synchronized(lock) { if (worker === current) worker = null }
        return stopped
    }

    private fun runLoop() {
        var backoffIndex = 0
        try {
            while (running.get()) {
                if (!awaitSignal()) break
                while (running.get()) {
                    try {
                        val hasMore = drainPage()
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        lastFaultReportNanos = null
                        lastFaultSignature = null
                        backoffIndex = 0
                        if (hasMore) continue
                        if (consumeSignal()) continue
                        break
                    } catch (error: InterruptedException) {
                        throw error
                    } catch (error: Exception) {
                        if (!running.get() || Thread.currentThread().isInterrupted) {
                            throw InterruptedException("callback normalization stopped")
                        }
                        reportFault(error)
                        sleepMs(RETRY_DELAY_MS[backoffIndex])
                        backoffIndex = (backoffIndex + 1).coerceAtMost(RETRY_DELAY_MS.lastIndex)
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            running.set(false)
        }
    }

    private fun awaitSignal(): Boolean = synchronized(lock) {
        while (running.get() && !signaled) lock.wait()
        if (!running.get()) false else {
            signaled = false
            true
        }
    }

    private fun consumeSignal(): Boolean = synchronized(lock) {
        if (!signaled) false else {
            signaled = false
            true
        }
    }

    private fun reportFault(error: Throwable) {
        val now = monotonicNanos()
        val last = lastFaultReportNanos
        val signature = generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .joinToString("|") { "${it::class.java.name}:${it.message.orEmpty()}" }
        if (signature != lastFaultSignature || last == null || now - last >= SUMMARY_INTERVAL_NANOS) {
            lastFaultReportNanos = now
            lastFaultSignature = signature
            runCatching { onFault(error) }
        }
    }

    companion object {
        private val RETRY_DELAY_MS = longArrayOf(100L, 250L, 500L, 1_000L)
        private const val SUMMARY_INTERVAL_NANOS = 30_000_000_000L
        private const val MAX_CAUSE_DEPTH = 8
    }
}
