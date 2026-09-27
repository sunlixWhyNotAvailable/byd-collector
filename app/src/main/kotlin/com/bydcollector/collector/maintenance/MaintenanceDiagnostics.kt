package com.bydcollector.collector.maintenance

import android.os.SystemClock
import com.bydcollector.collector.util.diagnosticDetail
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MaintenanceDiagnostics(
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val appendEvent: (event: String, detail: String) -> Unit
) : AutoCloseable {
    private data class State(
        val active: Boolean = false,
        val operation: String? = null,
        val databasePath: String? = null,
        val phase: String? = null,
        val phaseActive: Boolean = false,
        val runStartedAtMs: Long? = null,
        val runFinishedAtMs: Long? = null,
        val phaseStartedAtMs: Long? = null,
        val phaseFinishedAtMs: Long? = null,
        val lastProgressAtMs: Long? = null,
        val remainingCount: Long? = null,
        val remainingUnit: String? = null,
        val status: String? = null,
        val error: String? = null
    )

    private val stateLock = Any()

    @Volatile
    private var state = State()

    @Volatile
    private var heartbeatExecutor: ScheduledExecutorService? = null

    init {
        require(heartbeatIntervalMs > 0L)
    }

    fun begin(operation: String, databasePath: String?) {
        val now = now()
        synchronized(stateLock) {
            stopHeartbeatLocked()
            state = State(
                active = true,
                operation = operation.take(MAX_FIELD_CHARS),
                databasePath = databasePath?.take(MAX_PATH_CHARS),
                phase = "starting",
                phaseActive = true,
                runStartedAtMs = now,
                phaseStartedAtMs = now,
                lastProgressAtMs = now
            )
            startHeartbeatLocked()
        }
        emit("database_maintenance_begin", cachedSnapshot().detail)
    }

    fun beginPhase(name: String) {
        val now = now()
        val previous = synchronized(stateLock) {
            val old = state
            val closed = closePhase(old, now, successful = true)
            state = closed.copy(
                phase = name.take(MAX_FIELD_CHARS),
                phaseActive = true,
                phaseStartedAtMs = now,
                phaseFinishedAtMs = null,
                lastProgressAtMs = now
            )
            old.takeIf { it.phaseActive }
        }
        previous?.let { emit("database_maintenance_phase_end", snapshot(it, now, "success").detail) }
        emit("database_maintenance_phase_begin", cachedSnapshot().detail)
    }

    fun endPhase(status: String = "success") {
        val now = now()
        val ended = synchronized(stateLock) {
            val old = state
            if (!old.phaseActive) return
            state = closePhase(old, now, successful = status == "success")
            old
        }
        emit("database_maintenance_phase_end", snapshot(ended, now, status).detail)
    }

    fun failPhase(error: Throwable) {
        val now = now()
        val message = errorMessage(error)
        val ended = synchronized(stateLock) {
            val old = state
            if (!old.phaseActive) {
                state = old.copy(error = message)
                null
            } else {
                state = closePhase(old.copy(error = message), now, successful = false)
                old.copy(error = message)
            }
        }
        ended?.let { emit("database_maintenance_phase_end", snapshot(it, now, "error", message).detail) }
        this.error(error, "phase=${ended?.phase ?: "unknown"}")
    }

    fun reportDrainProgress(
        remainingCount: Long?,
        unit: String?,
        madeProgress: Boolean = false
    ) {
        val now = now()
        synchronized(stateLock) {
            val old = state
            val sameUnit = old.remainingUnit == unit
            val decreased = sameUnit && remainingCount != null && old.remainingCount != null &&
                remainingCount < old.remainingCount
            state = old.copy(
                remainingCount = remainingCount,
                remainingUnit = unit?.take(MAX_FIELD_CHARS),
                lastProgressAtMs = if (madeProgress || decreased) now else old.lastProgressAtMs
            )
        }
    }

    fun error(error: Throwable, context: String? = null) {
        val message = errorMessage(error)
        synchronized(stateLock) {
            state = state.copy(error = message)
        }
        val trace = runCatching { error.diagnosticDetail(context ?: "database maintenance") }
            .getOrElse { message }
            .take(MAX_ERROR_DETAIL_CHARS)
        emit("database_maintenance_error", cachedSnapshot().copy(error = message).detail + "\n" + trace)
    }

    fun finish(status: String) {
        val now = now()
        val terminal = status.take(MAX_FIELD_CHARS)
        val old = synchronized(stateLock) {
            val current = state
            state = closePhase(current.copy(status = terminal), now, successful = terminal == "success")
                .copy(active = false, status = terminal, runFinishedAtMs = now)
            stopHeartbeatLocked()
            current
        }
        if (old.phaseActive) {
            emit("database_maintenance_phase_end", snapshot(old, now, terminal).detail)
        }
        emit("database_maintenance_end", cachedSnapshot().detail)
    }

    fun cachedSnapshot(): MaintenanceDiagnosticSnapshot {
        val cached = state
        return snapshot(cached, now(), cached.status)
    }

    fun <T> runPhase(name: String, action: () -> T): T {
        beginPhase(name)
        return try {
            val result = action()
            endPhase(if (result is Boolean && !result) "negative_result" else "success")
            result
        } catch (error: Throwable) {
            failPhase(error)
            throw error
        }
    }

    override fun close() {
        val current = state
        if (current.active) finish("closed") else synchronized(stateLock) { stopHeartbeatLocked() }
    }

    private fun heartbeat() {
        val cached = state
        if (cached.active) emit("database_maintenance_heartbeat", snapshot(cached, now(), cached.status).detail)
    }

    private fun startHeartbeatLocked() {
        runCatching {
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "byd-db-maintenance-heartbeat").apply { isDaemon = true }
            }
            heartbeatExecutor = executor
            executor.scheduleAtFixedRate(
                ::heartbeat,
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }.onFailure {
            stopHeartbeatLocked()
        }
    }

    private fun stopHeartbeatLocked() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null
    }

    private fun closePhase(current: State, atMs: Long, successful: Boolean): State {
        if (!current.phaseActive) return current
        return current.copy(
            phaseActive = false,
            phaseFinishedAtMs = atMs,
            lastProgressAtMs = if (successful) atMs else current.lastProgressAtMs
        )
    }

    private fun snapshot(
        source: State,
        nowMs: Long,
        terminalStatus: String?,
        error: String? = source.error
    ): MaintenanceDiagnosticSnapshot {
        val runEnd = source.runFinishedAtMs ?: nowMs
        val phaseEnd = if (source.phaseActive) nowMs else source.phaseFinishedAtMs ?: nowMs
        return MaintenanceDiagnosticSnapshot(
            operation = source.operation,
            databasePath = source.databasePath,
            phase = source.phase,
            active = source.active,
            status = terminalStatus ?: source.status,
            runDurationMs = source.runStartedAtMs?.let { (runEnd - it).coerceAtLeast(0L) },
            phaseDurationMs = source.phaseStartedAtMs?.let { (phaseEnd - it).coerceAtLeast(0L) },
            remainingCount = source.remainingCount,
            remainingUnit = source.remainingUnit,
            lastProgressAgeMs = source.lastProgressAtMs?.let { (nowMs - it).coerceAtLeast(0L) },
            error = error
        )
    }

    private fun emit(event: String, detail: String) {
        runCatching { appendEvent(event, detail.take(MAX_DETAIL_CHARS)) }
    }

    private fun errorMessage(error: Throwable): String =
        "${error::class.java.simpleName}: ${error.message ?: "no message"}".take(MAX_ERROR_CHARS)

    private fun now(): Long = runCatching { elapsedRealtimeMs() }.getOrDefault(0L)

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val MAX_FIELD_CHARS = 128
        private const val MAX_PATH_CHARS = 1_024
        private const val MAX_ERROR_CHARS = 1_024
        private const val MAX_ERROR_DETAIL_CHARS = 8_192
        private const val MAX_DETAIL_CHARS = 8_192
    }
}

data class MaintenanceDiagnosticSnapshot(
    val operation: String?,
    val databasePath: String?,
    val phase: String?,
    val active: Boolean,
    val status: String?,
    val runDurationMs: Long?,
    val phaseDurationMs: Long?,
    val remainingCount: Long?,
    val remainingUnit: String?,
    val lastProgressAgeMs: Long?,
    val error: String?
) {
    val detail: String
        get() = listOf(
            "operation=${quoted(operation)}",
            "database_path=${quoted(databasePath)}",
            "phase=${quoted(phase)}",
            "active=$active",
            "status=${quoted(status)}",
            "run_duration_ms=${runDurationMs ?: "unknown"}",
            "phase_duration_ms=${phaseDurationMs ?: "unknown"}",
            "remaining_count=${remainingCount ?: "unknown"}",
            "remaining_unit=${quoted(remainingUnit)}",
            "last_progress_age_ms=${lastProgressAgeMs ?: "unknown"}",
            "error=${quoted(error)}"
        ).joinToString(" ")

    override fun toString(): String = detail

    private fun quoted(value: String?): String =
        value?.let { "\"${it.take(1_024).replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n")}\"" }
            ?: "unknown"
}
