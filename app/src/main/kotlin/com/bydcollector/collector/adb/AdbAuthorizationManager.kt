package com.bydcollector.collector.adb

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.system.RequiredAccessChecker
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import com.bydcollector.collector.util.diagnosticDetail
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

enum class AccessCheckMode {
    NORMAL,
    COLD_START,
    FORCE,
    OBSERVE
}

data class AccessRuntimeSnapshot(
    val permissionsGranted: Boolean = false,
    val adbAuthorized: Boolean = false
)

//coordinates local access checks and guarantees that a force request replaces all older work
object AdbAuthorizationManager {
    private val coordinator = AdbPipelineCoordinator()
    private val repairThrottle = AccessRepairThrottle(REPAIR_COOLDOWN_MS)
    private val automaticPromptAttemptedInProcess = AtomicBoolean(false)

    @Volatile
    private var runtimeSnapshot = AccessRuntimeSnapshot()

    fun currentSnapshot(): AccessRuntimeSnapshot = runtimeSnapshot

    fun request(
        context: Context,
        store: TelemetryStore,
        source: String,
        mode: AccessCheckMode,
        helperOwnerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP,
        onComplete: ((AccessRuntimeSnapshot) -> Unit)? = null,
        onTerminal: (() -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        val terminalDelivered = AtomicBoolean(false)
        val deliverTerminal = {
            if (terminalDelivered.compareAndSet(false, true)) onTerminal?.invoke()
        }
        val submitted = coordinator.submit(mode, onTerminal = deliverTerminal) { lease ->
            runCheck(appContext, store, source, mode, helperOwnerMode, lease, onComplete)
        }
        if (!submitted) {
            deliverTerminal()
            store.recordEvent(
                "adb_self_check_in_progress",
                "ADB access self-check is already running",
                "source=$source mode=${mode.name.lowercase()}"
            )
        }
        return submitted
    }

    /** Refreshes only current OS permissions and the existing local ADB identity. */
    fun requestObservation(context: Context): Boolean {
        val appContext = context.applicationContext
        if (isUserShutdownRequested(appContext)) return false
        return coordinator.submit(AccessCheckMode.OBSERVE) { lease ->
            runObservation(appContext, lease)
        }
    }

    fun cancelObservation() {
        coordinator.cancel(AccessCheckMode.OBSERVE)
    }

    private fun runCheck(
        appContext: Context,
        store: TelemetryStore,
        source: String,
        mode: AccessCheckMode,
        helperOwnerMode: DirectHelperOwnerMode,
        lease: AdbPipelineLease,
        onComplete: ((AccessRuntimeSnapshot) -> Unit)?
    ) {
        store.recordEvent(
            "adb_self_check_started",
            "ADB access self-check started",
            "source=$source mode=${mode.name.lowercase()}"
        )

        try {
            val cancellation = lease.cancellation
            var permissionsGranted = !RequiredAccessChecker.hasMissingRequiredAccess(appContext)
            var helperReady = helperReadyAfterRebind(cancellation, helperOwnerMode)
            var adbAuthorized = runtimeSnapshot.adbAuthorized
            val repairNeeded = !permissionsGranted || !helperReady
            val repairAllowed = repairNeeded && (
                mode == AccessCheckMode.FORCE || repairThrottle.tryAcquire(SystemClock.elapsedRealtime())
            )

            if (mode == AccessCheckMode.FORCE || !adbAuthorized || repairAllowed) {
                val client = adbClient(appContext, store, cancellation)
                val authorization = authorize(store, client, source, mode)
                cancellation.throwIfCancelled()
                adbAuthorized = authorization.category == "adb_authorization_connected"
                store.recordEvent(
                    "adb_self_check_result",
                    authorization.message,
                    "source=$source mode=${mode.name.lowercase()} category=${authorization.category} ${authorization.detail.orEmpty()}".trim()
                )

                if (adbAuthorized && repairAllowed) {
                    runRepair(
                        appContext = appContext,
                        store = store,
                        client = client,
                        source = source,
                        helperReady = helperReady,
                        helperOwnerMode = helperOwnerMode,
                        cancellation = cancellation
                    )
                    permissionsGranted = !RequiredAccessChecker.hasMissingRequiredAccess(appContext)
                    helperReady = DirectVehicleHelperClient().ownerMode() == helperOwnerMode
                } else if (repairNeeded && !repairAllowed) {
                    store.recordEvent(
                        "adb_repair_rate_limited",
                        "ADB access repair skipped by cooldown",
                        "source=$source cooldown_ms=$REPAIR_COOLDOWN_MS"
                    )
                }
            }

            cancellation.throwIfCancelled()
            val completed = AccessRuntimeSnapshot(
                permissionsGranted = permissionsGranted,
                adbAuthorized = adbAuthorized
            )
            if (!publishSnapshot(appContext, lease, completed) {
                    onComplete?.invoke(completed)
                }
            ) return

            store.recordEvent(
                "required_access_self_check_result",
                "Required access self-check completed",
                "source=$source permissions=${completed.permissionsGranted} adb=${completed.adbAuthorized} helper=$helperReady"
            )
        } catch (_: AdbOperationCancelledException) {
            store.recordEvent(
                "adb_self_check_cancelled",
                "ADB access self-check cancelled",
                "source=$source mode=${mode.name.lowercase()}"
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            store.recordEvent(
                "adb_self_check_cancelled",
                "ADB access self-check interrupted",
                "source=$source mode=${mode.name.lowercase()}"
            )
        } catch (error: Exception) {
            val failed = AccessRuntimeSnapshot(
                permissionsGranted = runCatching {
                    !RequiredAccessChecker.hasMissingRequiredAccess(appContext)
                }.getOrDefault(false),
                adbAuthorized = false
            )
            if (publishSnapshot(appContext, lease, failed) {
                    onComplete?.invoke(failed)
                }
            ) {
                store.recordEvent(
                    "adb_self_check_error",
                    "ADB access self-check failed",
                    "source=$source ${error::class.java.simpleName}: ${error.message ?: "no message"}"
                )
            }
        }
    }

    private fun runObservation(appContext: Context, lease: AdbPipelineLease) {
        try {
            if (isUserShutdownRequested(appContext)) return
            lease.cancellation.throwIfCancelled()
            val permissionsGranted = !RequiredAccessChecker.hasMissingRequiredAccess(appContext)
            val adbAuthorized = adbClient(appContext, null, lease.cancellation)
                .checkAuthorizationReadOnly()
            lease.cancellation.throwIfCancelled()
            val completed = AccessRuntimeSnapshot(
                permissionsGranted,
                adbAuthorized.category == "adb_authorization_connected"
            )
            if (publishSnapshot(
                appContext,
                lease,
                completed
            )) {
                recordObservation(
                    appContext,
                    "completed",
                    "permissions=${completed.permissionsGranted} adb=${completed.adbAuthorized} category=${adbAuthorized.category}"
                )
            }
        } catch (_: AdbOperationCancelledException) {
            // Cancellation is expected when an interactive check takes ownership.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            val permissionsGranted = runCatching {
                !RequiredAccessChecker.hasMissingRequiredAccess(appContext)
            }.getOrDefault(false)
            val failed = AccessRuntimeSnapshot(permissionsGranted, false)
            if (publishSnapshot(appContext, lease, failed)) {
                recordObservation(
                    appContext,
                    "error",
                    error.diagnosticDetail("permissions=${failed.permissionsGranted}")
                )
            }
        }
    }

    private fun publishSnapshot(
        appContext: Context,
        lease: AdbPipelineLease,
        snapshot: AccessRuntimeSnapshot,
        onPublished: (() -> Unit)? = null
    ): Boolean = coordinator.publishIfCurrent(lease) {
        runtimeSnapshot = snapshot
        BydCollectorApplication.dashboardUiStateStore(appContext).publishAccessStatus(
            permissionsGranted = snapshot.permissionsGranted,
            adbAuthorized = snapshot.adbAuthorized
        )
        try {
            onPublished?.invoke()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Access status callback failed", error)
        }
    }

    private fun recordObservation(context: Context, result: String, detail: String) {
        val app = context.applicationContext as BydCollectorApplication
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            app.operationalEventJournal.append(
                Instant.now().toString(),
                SystemClock.elapsedRealtime(),
                "adb_access_observation",
                result,
            detail.take(32_768)
            )
        }
    }

    private fun isUserShutdownRequested(context: Context): Boolean = context
        .getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(CollectorSettings.KEY_USER_SHUTDOWN, false)

    private const val TAG = "AdbAuthorization"

    private fun authorize(
        store: TelemetryStore,
        client: AdbLocalClient,
        source: String,
        mode: AccessCheckMode
    ): AdbAuthorizationResult {
        if (mode == AccessCheckMode.FORCE) return client.requestAuthorization()

        val check = client.checkAuthorization()
        if (check.category != "adb_authorization_required" || mode != AccessCheckMode.COLD_START) {
            return check
        }

        if (!automaticPromptAttemptedInProcess.compareAndSet(false, true)) {
            store.recordEvent(
                "adb_auto_prompt_skipped",
                "ADB automatic RSA prompt already attempted in this app process",
                "source=$source"
            )
            return check
        }

        val fingerprint = client.keyFingerprint()
        store.recordEvent(
            "adb_auto_prompt_once_started",
            "Sending one automatic RSA public key prompt",
            "source=$source key=${fingerprint.take(16)}"
        )
        return client.requestAuthorization()
    }

    private fun runRepair(
        appContext: Context,
        store: TelemetryStore,
        client: AdbLocalClient,
        source: String,
        helperReady: Boolean,
        helperOwnerMode: DirectHelperOwnerMode,
        cancellation: AdbCancellation
    ) {
        RequiredAccessChecker.missingShellGrantCommands(appContext).forEachIndexed { index, command ->
            cancellation.throwIfCancelled()
            store.recordEvent(
                "adb_permission_grant_started",
                "Granting missing required access through local ADB",
                "source=$source index=$index command=$command"
            )
            val result = client.execShell(command, timeoutMs = 10_000)
            store.recordEvent(
                if (result.ok) "adb_permission_grant_success" else "adb_permission_grant_failed",
                if (result.ok) "Required access grant command completed" else "Required access grant command failed",
                "source=$source elapsed_ms=${result.elapsedMs} error=${result.error.orEmpty()} output=${result.output.take(500)}"
            )
        }

        if (!helperReady) {
            val bridge = DirectBridgeManager.ensureRunning(
                context = appContext,
                adbClient = client,
                ownerMode = helperOwnerMode,
                cancellation = cancellation
            )
            store.recordEvent(
                if (bridge.ok) "direct_helper_ready" else "direct_helper_unavailable",
                if (bridge.ok) "Direct autoservice helper is ready" else "Direct autoservice helper is unavailable",
                "source=$source ${bridge.message}"
            )
        }
    }

    private fun helperReadyAfterRebind(
        cancellation: AdbCancellation,
        helperOwnerMode: DirectHelperOwnerMode
    ): Boolean {
        if (DirectVehicleHelperClient().ownerMode() == helperOwnerMode) return true
        try {
            Thread.sleep(HELPER_REBIND_WAIT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AdbOperationCancelledException()
        }
        cancellation.throwIfCancelled()
        return DirectVehicleHelperClient().ownerMode() == helperOwnerMode
    }

    private fun adbClient(
        appContext: Context,
        store: TelemetryStore?,
        cancellation: AdbCancellation
    ): AdbLocalClient {
        return AdbLocalClient(
            keyDir = File(appContext.filesDir, "adb_keys"),
            eventSink = store?.let { sink -> { category, message, detail -> sink.recordEvent(category, message, detail) } },
            cancellation = cancellation
        )
    }

    private const val HELPER_REBIND_WAIT_MS = 1_000L
    private const val REPAIR_COOLDOWN_MS = 60_000L
}

internal class AccessRepairThrottle(private val intervalMs: Long) {
    private var lastAttemptAtMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastAttemptAtMs
        if (last != null && nowMs - last < intervalMs) return false
        lastAttemptAtMs = nowMs
        return true
    }
}

internal class AdbPipelineCoordinator {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bydcollector-adb-access").apply { isDaemon = true }
    }
    private val replacementExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bydcollector-adb-replace").apply { isDaemon = true }
    }
    private var generation = 0L
    private var active: AdbPipelineLease? = null

    fun submit(
        mode: AccessCheckMode,
        onTerminal: (() -> Unit)? = null,
        block: (AdbPipelineLease) -> Unit
    ): Boolean {
        synchronized(lock) {
            val previous = active
            val replace = mode == AccessCheckMode.FORCE ||
                (mode != AccessCheckMode.OBSERVE && previous?.mode == AccessCheckMode.OBSERVE) ||
                (mode == AccessCheckMode.COLD_START && previous?.mode == AccessCheckMode.NORMAL)
            if (previous != null && !replace) return false

            val lease = AdbPipelineLease(++generation, mode, onTerminal)
            val task = FutureTask<Unit> {
                try {
                    val shouldRun = synchronized(lock) {
                        active === lease && !lease.cancellation.isCancelled
                    }
                    if (shouldRun) block(lease)
                } finally {
                    synchronized(lock) {
                        if (active === lease) active = null
                    }
                    lease.terminal()
                }
            }
            lease.task = task
            active = lease
            if (previous == null) {
                runCatching { executor.execute(task) }.onFailure {
                    if (active === lease) active = null
                    lease.terminal()
                    return false
                }
            } else {
                runCatching {
                    replacementExecutor.execute {
                        previous.cancel()
                        synchronized(lock) {
                            if (active === lease) {
                                runCatching { executor.execute(task) }.onFailure {
                                    active = null
                                    lease.terminal()
                                }
                            } else {
                                lease.terminal()
                            }
                        }
                    }
                }.onFailure {
                    synchronized(lock) {
                        if (active === lease) active = null
                    }
                    lease.terminal()
                    return false
                }
            }
            return true
        }
    }

    fun publishIfCurrent(lease: AdbPipelineLease, publish: () -> Unit): Boolean {
        synchronized(lock) {
            if (active !== lease || lease.cancellation.isCancelled) return false
            publish()
            return true
        }
    }

    fun cancel(mode: AccessCheckMode) {
        val lease = synchronized(lock) {
            active?.takeIf { it.mode == mode }?.also { active = null }
        }
        lease?.cancel()
    }
}

internal class AdbPipelineLease(
    val generation: Long,
    val mode: AccessCheckMode,
    private val onTerminal: (() -> Unit)? = null
) {
    val cancellation = AdbCancellation()
    lateinit var task: FutureTask<Unit>
    private val terminalDelivered = AtomicBoolean(false)

    fun terminal() {
        if (terminalDelivered.compareAndSet(false, true)) onTerminal?.invoke()
    }

    fun cancel() {
        cancellation.cancel()
        task.cancel(true)
        terminal()
    }

}
