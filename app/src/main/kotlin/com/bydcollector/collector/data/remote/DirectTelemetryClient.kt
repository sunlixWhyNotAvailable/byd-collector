package com.bydcollector.collector.data.remote

import android.content.Context
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.direct.DirectAutoserviceReader
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import java.io.File

class DirectTelemetryClient(
    context: Context,
    private val clock: Clock = SystemClockAdapter(),
    private val adbClient: AdbLocalClient = AdbLocalClient(File(context.filesDir, "adb_keys")),
    private val helper: DirectVehicleHelper = DirectVehicleHelperClient(),
    private val reader: DirectAutoserviceReader = DirectAutoserviceReader(helper),
    private val expectedOwnerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP
) : TelemetryClient {
    private val appContext = context.applicationContext
    @Volatile private var nextLaunchAttemptAtMs: Long = 0

    override fun read(): TelemetryReadResult {
        val startedAt = clock.elapsedRealtimeMs()
        ensureHelperReady(startedAt, expectedOwnerMode)?.let { return it }

        val snapshot = reader.readSnapshot()
        return if (snapshot.readings.isEmpty()) {
            //records an explicit failed poll instead of pretending an empty autoservice snapshot is success
            failure(
                category = "autoservice_snapshot_empty",
                message = snapshot.errorSummary().ifBlank { "Direct autoservice helper returned no usable readings" },
                startedAt = startedAt,
                rawBody = snapshot.toJson(ok = false, includeFields = false)
            )
        } else {
            val warningMessage = snapshot.errorSummary().takeIf { it.isNotBlank() }
            //keeps partial successes usable while preserving warning metadata for diagnostics
            TelemetryReadResult.Success(
                rawBody = snapshot.toJson(ok = true, includeFields = false),
                elapsedMs = clock.elapsedRealtimeMs() - startedAt,
                readings = snapshot.readings,
                warningCategory = warningMessage?.let { "autoservice_partial_failure" },
                warningMessage = warningMessage,
                diagnosticKey = snapshot.batchDiagnostics.stateKey,
                diagnosticMessage = snapshot.batchDiagnostics.summary()
            )
        }
    }

    internal fun ensureHelperReady(
        ownerMode: DirectHelperOwnerMode = expectedOwnerMode
    ): TelemetryReadResult.Failure? {
        return ensureHelperReady(clock.elapsedRealtimeMs(), ownerMode)
    }

    private fun ensureHelperReady(
        startedAt: Long,
        ownerMode: DirectHelperOwnerMode
    ): TelemetryReadResult.Failure? {
        if (helper.ownerMode() != ownerMode) {
            val now = clock.elapsedRealtimeMs()
            //backs off helper launch failures because adb/app_process startup can block for seconds
            if (now < nextLaunchAttemptAtMs) {
                return failure("helper_launch_backoff", "Direct helper launch is cooling down after a previous failure", startedAt)
            }
            val launch = DirectBridgeManager.ensureRunning(
                context = appContext,
                adbClient = adbClient,
                helper = helper,
                ownerMode = ownerMode
            )
            if (!launch.ok) {
                nextLaunchAttemptAtMs = clock.elapsedRealtimeMs() + LAUNCH_FAILURE_BACKOFF_MS
                return launchFailure(launch, startedAt)
            }
            if (!waitForHelper(ownerMode)) {
                nextLaunchAttemptAtMs = clock.elapsedRealtimeMs() + LAUNCH_FAILURE_BACKOFF_MS
                return failure("helper_unavailable", "Direct helper did not answer Binder ping", startedAt)
            }
        }
        return null
    }

    private fun waitForHelper(ownerMode: DirectHelperOwnerMode): Boolean {
        repeat(10) {
            if (helper.ownerMode() == ownerMode) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun failure(category: String, message: String, startedAt: Long, rawBody: String? = null): TelemetryReadResult.Failure {
        return TelemetryReadResult.Failure(
            category = category,
            message = message,
            rawBody = rawBody,
            elapsedMs = clock.elapsedRealtimeMs() - startedAt
        )
    }

    private fun launchFailure(
        result: DirectBridgeResult,
        startedAt: Long
    ): TelemetryReadResult.Failure {
        val error = result.message
        return when {
            error.contains("adb_authorization_required") -> {
                failure("adb_authorization_required", "ADB key is not authorized", startedAt)
            }
            error.contains("adb_authorization_unavailable") -> {
                failure("adb_authorization_unavailable", "Local ADB daemon is not reachable", startedAt)
            }
            error.contains("adb_authorization_timeout") -> {
                failure("adb_authorization_timeout", "ADB authorization timed out", startedAt)
            }
            else -> failure("helper_launch_failed", error, startedAt)
        }
    }

    companion object {
        private const val LAUNCH_FAILURE_BACKOFF_MS = 30_000L
    }
}
