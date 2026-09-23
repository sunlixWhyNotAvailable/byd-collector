package com.bydcollector.collector.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.service.CollectorSettings

/** Owns the existing check schedule without retaining an Activity or starting vehicle work. */
internal class UpdateRuntime(private val app: BydCollectorApplication) {
    private val handler = Handler(Looper.getMainLooper())
    private val settings = CollectorSettings(app)
    private var started = false
    private var installing = false
    private var awaitingInstallerReturn = false
    private var lastProcessedCompletionToken = 0L
    private val presentation = UpdateResultPresentation()
    private val wakePolicy = UpdateWakePolicy()
    private val hintCreationRetry: UpdateHintCreationRetry by lazy {
        UpdateHintCreationRetry(
            nowElapsedMs = { SystemClock.elapsedRealtime() },
            postDelayed = { task, delayMs -> handler.postDelayed(task, delayMs) },
            removeCallbacks = { task -> handler.removeCallbacks(task) },
            isEligible = ::canCreateHint,
            onEvent = { name, detail -> app.recordUpdateEvent(name, detail) },
            startAttempt = { resultId, attemptToken ->
                val snapshot = app.updateChecks.snapshot()
                val info = (snapshot.uiState as? UpdateUiState.Available)?.info
                if (snapshot.availableResultId == resultId && info != null) {
                    app.updateHints.show(resultId, attemptToken, info)
                } else {
                    hintCreationRetry.onSkippedBeforeCreation(resultId, attemptToken, "result_unavailable")
                }
            }
        )
    }
    private var wakeReceiver: BroadcastReceiver? = null
    var ownUiVisible = false
        private set
    private val timer = Runnable { applyAction(UpdateAutoCheckRuntime.onTimerElapsed(enabled(), ownUiVisible)) }
    private val checkListener: () -> Unit = {
        handler.post {
            val snapshot = app.updateChecks.snapshot()
            if (app.updateHints.activeResultId != null && app.updateHints.activeResultId != snapshot.availableResultId) {
                cancelHintPresentation("stale_result")
            }
            presentPendingHint()
            processCompletions()
        }
        Unit
    }

    init {
        app.updateChecks.addListener(checkListener)
    }

    // Explicit normal entry points call this; a coordination-only service bind does not.
    fun start(source: String) {
        if (settings.isUserShutdownRequested()) return
        val firstEntry = !started
        val wasSleeping = wakePolicy.sleeping
        val newWake = wakePolicy.onEntry(SystemClock.elapsedRealtime(), isInteractive())
        if (firstEntry) {
            started = true
            observeWake()
            app.recordUpdateEvent("runtime_started", "source=$source auto_enabled=${enabled()}")
        }
        when {
            newWake -> restartAfterWake(source)
            wakePolicy.sleeping -> if (firstEntry || !wasSleeping) pauseForSleep()
            firstEntry -> applyAction(UpdateAutoCheckRuntime.onRuntimeStarted(enabled()))
        }
        processCompletions()
    }

    /** BootReceiver forwards real boot/wake signals even when recovery reuses a live service. */
    fun onSystemWake(action: String) {
        if (settings.isUserShutdownRequested()) return
        if (!started) {
            start("wake:$action")
            return
        }
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT && !isInteractive()) {
            if (!wakePolicy.sleeping) pauseForSleep()
            return
        }
        if (wakePolicy.onWake(action, SystemClock.elapsedRealtime())) restartAfterWake(action)
    }

    private fun observeWake() {
        if (wakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) pauseForSleep()
                else intent.action?.let(::onSystemWake)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            wakeReceiver = receiver
        } catch (error: RuntimeException) {
            app.recordUpdateEvent("wake_observer_failed", error::class.java.simpleName)
        }
    }

    private fun isInteractive(): Boolean = app.getSystemService(PowerManager::class.java)?.isInteractive != false

    private fun pauseForSleep() {
        if (!started) return
        wakePolicy.onSleep()
        handler.removeCallbacks(timer)
        UpdateAutoCheckRuntime.onAutoCheckEnabledChanged(enabled = false)
        app.updateChecks.invalidateAutomatic()
        cancelHintPresentation("screen_off")
        app.recordUpdateEvent("auto_check_sleep")
    }

    private fun restartAfterWake(source: String) {
        handler.removeCallbacks(timer)
        app.updateChecks.invalidateAutomatic()
        cancelHintPresentation("wake")
        UpdateAutoCheckRuntime.reset()
        val action = UpdateAutoCheckRuntime.onRuntimeStarted(enabled())
        val manualInFlight = app.updateChecks.hasCurrentManualRequest()
        if (manualInFlight) UpdateAutoCheckRuntime.onCheckStarted()
        else applyAction(action)
        app.recordUpdateEvent("auto_check_wake", "source=$source manual_in_flight=$manualInFlight suppression_cleared=true")
        processCompletions()
    }

    fun onUiVisible() {
        ownUiVisible = true
        cancelHintPresentation("own_ui_visible")
        start("activity")
        applyAction(UpdateAutoCheckRuntime.onForeground(enabled()))
    }

    fun onUiHidden() {
        ownUiVisible = false
        presentPendingHint()
        if (started) applyAction(UpdateAutoCheckRuntime.onBackground(enabled()))
    }

    fun onUiResumed() {
        if (awaitingInstallerReturn) onInstallFinished()
        presentPendingHint()
    }

    /** Reuses the cached result after permission/lifecycle changes; no HTTP check. */
    fun onPresentationAccessChanged() {
        presentPendingHint()
    }

    fun onOfferPresented(resultId: Long) {
        if (!ownUiVisible || !started || installing || wakePolicy.sleeping || settings.isUserShutdownRequested()) return
        val snapshot = app.updateChecks.snapshot()
        if (presentation.markPresented(snapshot, resultId)) {
            app.recordUpdateEvent("offer_shown", "result_id=$resultId version=${(snapshot.uiState as UpdateUiState.Available).info.version}")
        }
    }

    fun onHintPresented(resultId: Long, attemptToken: Long): Boolean {
        if (!hintCreationRetry.isCurrentAttachedAttempt(resultId, attemptToken) ||
            !canCreateHint(resultId)) {
            hintCreationRetry.cancelAttempt(resultId, attemptToken, "presentation_rejected")
            return false
        }
        return presentation.markPresented(app.updateChecks.snapshot(), resultId)
    }

    fun canAttachHint(resultId: Long, attemptToken: Long): Boolean =
        hintCreationRetry.isCurrentCreationAttempt(resultId, attemptToken) && canCreateHint(resultId)

    fun canMaintainHint(resultId: Long, attemptToken: Long): Boolean =
        hintCreationRetry.isCurrentAttachedAttempt(resultId, attemptToken) && hintEnvironmentReady(resultId)

    fun onHintCreationFailed(resultId: Long, attemptToken: Long, phase: String, errorClass: String): Boolean =
        hintCreationRetry.onTechnicalFailure(resultId, attemptToken, phase, errorClass)

    fun onHintCreationSkipped(resultId: Long, attemptToken: Long, reason: String): Boolean =
        hintCreationRetry.onSkippedBeforeCreation(resultId, attemptToken, reason)

    fun onHintCreationAttached(resultId: Long, attemptToken: Long): Boolean =
        hintCreationRetry.onAttached(resultId, attemptToken)

    fun onHintCreationUnavailable(resultId: Long, attemptToken: Long, reason: String) {
        hintCreationRetry.cancelAttempt(resultId, attemptToken, reason)
    }

    private fun presentPendingHint() {
        val snapshot = app.updateChecks.snapshot()
        val resultId = snapshot.availableResultId.takeIf { snapshot.uiState is UpdateUiState.Available }
        hintCreationRetry.offer(resultId)
        if (resultId == null) {
            if (app.updateHints.activeResultId != null) cancelHintPresentation("stale_result")
            return
        }
        if (mainDisplayForUpdateHintIfReady(app) == null) {
            cancelHintPresentation("display_not_ready")
            if (snapshot.uiState is UpdateUiState.Available) {
                app.recordUpdateEvent("hint_unavailable", "reason=display_not_ready phase=show")
            }
            return
        }
        if (!settings.isUpdateHintEnabled() || !canDrawUpdateHints()) {
            cancelHintPresentation(if (settings.isUpdateHintEnabled()) "permission_lost" else "disabled")
        }
    }

    fun onAutoCheckEnabledChanged() {
        handler.removeCallbacks(timer)
        if (!settings.isUpdateAutoCheckEnabled()) app.updateChecks.invalidateAutomatic()
        if (started) applyAction(UpdateAutoCheckRuntime.onAutoCheckEnabledChanged(enabled()))
    }

    fun request(manual: Boolean): Boolean {
        if (!started || installing || settings.isUserShutdownRequested() || (!manual && !enabled())) return false
        // A completed worker may be waiting for its posted listener. Consume it
        // before a user action/new timer can start another check in the same generation.
        processCompletions(applyScheduling = false)
        if (!manual) {
            val action = UpdateAutoCheckRuntime.onTimerElapsed(enabled(), ownUiVisible)
            if (action != UpdateAutoCheckAction.Run) {
                applyAction(action)
                return false
            }
        }
        val accepted = app.updateChecks.request(manual)
        if (accepted || (manual && app.updateChecks.hasCurrentManualRequest())) {
            UpdateAutoCheckRuntime.onCheckStarted()
            handler.removeCallbacks(timer)
        }
        app.recordUpdateEvent("check_requested", "manual=$manual accepted=$accepted")
        return accepted
    }

    fun dismissOffer() {
        val wasAvailable = app.updateChecks.dismiss()
        if (wasAvailable) {
            UpdateAutoCheckRuntime.onDismissed()
            app.recordUpdateEvent("offer_dismissed", "auto_suppression_ms=3600000")
            applyAction(UpdateAutoCheckRuntime.onForeground(enabled()))
        }
    }

    fun shutdown() {
        started = false
        installing = false
        awaitingInstallerReturn = false
        ownUiVisible = false
        handler.removeCallbacks(timer)
        UpdateAutoCheckRuntime.reset()
        hintCreationRetry.offer(null)
        app.updateChecks.reset()
        presentation.reset()
        app.updateHints.shutdown()
        wakeReceiver?.let { receiver ->
            runCatching { app.unregisterReceiver(receiver) }
                .onFailure { app.recordUpdateEvent("wake_observer_remove_failed", it::class.java.simpleName) }
        }
        wakeReceiver = null
        wakePolicy.reset()
    }

    fun onInstallStarted() {
        installing = true
        awaitingInstallerReturn = false
        handler.removeCallbacks(timer)
        app.updateChecks.invalidateAutomatic()
        cancelHintPresentation("install_started")
    }

    fun onInstallerLaunched() {
        if (installing) awaitingInstallerReturn = true
    }

    fun onInstallFinished() {
        installing = false
        awaitingInstallerReturn = false
        if (started) applyAction(UpdateAutoCheckRuntime.onTimerElapsed(enabled(), ownUiVisible))
    }

    private fun processCompletions(applyScheduling: Boolean = true) {
        if (!started) return
        val generation = app.updateChecks.snapshot().generation
        val pending = app.updateChecks.completionsAfter(lastProcessedCompletionToken)
        var staleFlightReleased = false
        var nextAction: UpdateAutoCheckAction? = null
        pending.forEach { completion ->
            lastProcessedCompletionToken = completion.token
            if (completion.generation != generation) {
                staleFlightReleased = true
                app.recordUpdateEvent("check_completion_stale", "token=${completion.token} generation=${completion.generation} current_generation=$generation")
                return@forEach
            }
            handler.removeCallbacks(timer)
            nextAction = UpdateAutoCheckRuntime.onCheckCompleted(
                result = completion.result,
                completedAtElapsedMs = completion.completedAtElapsedMs,
                enabled = enabled()
            )
            app.recordUpdateEvent("check_completion_consumed", "token=${completion.token} generation=$generation completed_elapsed_ms=${completion.completedAtElapsedMs} result=${completion.result::class.java.simpleName}")
        }
        if (pending.isNotEmpty()) app.updateChecks.acknowledgeCompletionsThrough(lastProcessedCompletionToken)
        if (!applyScheduling) return
        if (nextAction != null) applyAction(checkNotNull(nextAction))
        else if (staleFlightReleased) {
            // A current deadline may already have fired while the old physical
            // request still owned the single-flight gate. Re-evaluate it now.
            applyAction(UpdateAutoCheckRuntime.onTimerElapsed(enabled(), ownUiVisible))
        }
    }

    private fun enabled() = started && !wakePolicy.sleeping && settings.isUpdateAutoCheckEnabled() && !settings.isUserShutdownRequested()

    private fun canCreateHint(resultId: Long): Boolean {
        val snapshot = app.updateChecks.snapshot()
        return hintEnvironmentReady(resultId) && presentation.canPresentHint(
            snapshot,
            ownUiVisible = ownUiVisible,
            hintEnabled = true
        )
    }

    private fun hintEnvironmentReady(resultId: Long): Boolean {
        val snapshot = app.updateChecks.snapshot()
        return snapshot.availableResultId == resultId && snapshot.uiState is UpdateUiState.Available &&
            !ownUiVisible && started && !installing && !wakePolicy.sleeping &&
            !settings.isUserShutdownRequested() && settings.isUpdateHintEnabled() &&
            canDrawUpdateHints() && mainDisplayForUpdateHintIfReady(app) != null
    }

    private fun canDrawUpdateHints(): Boolean = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)

    private fun cancelHintPresentation(reason: String) {
        hintCreationRetry.cancelPending(reason)
        app.updateHints.dismiss(reason)
    }

    private fun applyAction(action: UpdateAutoCheckAction) {
        if (!started || installing || wakePolicy.sleeping) return
        app.recordUpdateEvent("auto_check_gate", "action=$action visible=$ownUiVisible ${UpdateAutoCheckRuntime.diagnosticState()}")
        when (action) {
            UpdateAutoCheckAction.None -> Unit
            is UpdateAutoCheckAction.Schedule -> {
                handler.removeCallbacks(timer)
                handler.postDelayed(timer, action.delayMs)
            }
            UpdateAutoCheckAction.Run -> {
                // If a physical request is busy, its completion listener retries
                // the still-unconsumed deadline without a second polling timer.
                request(manual = false)
            }
        }
    }
}

internal fun mainDisplayForUpdateHintIfReady(context: Context): Display? {
    return try {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return null
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        display.takeIf { isUpdateHintDisplayReady(powerManager.isInteractive, it.state) }
    } catch (_: RuntimeException) {
        null
    }
}

internal fun isUpdateHintDisplayReady(isInteractive: Boolean, mainDisplayState: Int?): Boolean =
    isInteractive && mainDisplayState == Display.STATE_ON
