package com.bydcollector.collector.update

import android.os.Handler
import android.os.Looper
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.service.CollectorSettings

/** Owns the existing check schedule without retaining an Activity or starting vehicle work. */
internal class UpdateRuntime(private val app: BydCollectorApplication) {
    private val handler = Handler(Looper.getMainLooper())
    private val settings = CollectorSettings(app)
    private var started = false
    private var installing = false
    private var awaitingInstallerReturn = false
    private val presentation = UpdateResultPresentation()
    var ownUiVisible = false
        private set
    private val timer = Runnable { applyAction(UpdateAutoCheckRuntime.onTimerElapsed(enabled(), ownUiVisible)) }
    private val checkListener: () -> Unit = {
        handler.post {
            val snapshot = app.updateChecks.snapshot()
            if (app.updateHints.activeResultId != null && app.updateHints.activeResultId != snapshot.availableResultId) {
                app.updateHints.dismiss("stale_result")
            }
            presentPendingHint()
            if (started && !app.updateChecks.snapshot().inFlight) {
                applyAction(UpdateAutoCheckRuntime.onTimerElapsed(enabled(), ownUiVisible))
            }
        }
        Unit
    }

    init {
        app.updateChecks.addListener(checkListener)
    }

    // Explicit normal entry points call this; a coordination-only service bind does not.
    fun start(source: String) {
        if (started || settings.isUserShutdownRequested()) return
        started = true
        app.recordUpdateEvent("runtime_started", "source=$source auto_enabled=${enabled()}")
        applyAction(UpdateAutoCheckRuntime.onRuntimeStarted(enabled()))
    }

    fun onUiVisible() {
        ownUiVisible = true
        app.updateHints.dismiss("own_ui_visible")
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
        if (!ownUiVisible || !started || installing || settings.isUserShutdownRequested()) return
        val snapshot = app.updateChecks.snapshot()
        if (presentation.markPresented(snapshot, resultId)) {
            app.recordUpdateEvent("offer_shown", "result_id=$resultId version=${(snapshot.uiState as UpdateUiState.Available).info.version}")
        }
    }

    fun onHintPresented(resultId: Long) {
        if (ownUiVisible || !started || installing || settings.isUserShutdownRequested()) return
        presentation.markPresented(app.updateChecks.snapshot(), resultId)
    }

    private fun presentPendingHint() {
        val snapshot = app.updateChecks.snapshot()
        if (presentation.canPresentHint(snapshot, ownUiVisible,
                started && !installing && !settings.isUserShutdownRequested() && settings.isUpdateHintEnabled())) {
            app.updateHints.show(checkNotNull(snapshot.availableResultId), (snapshot.uiState as UpdateUiState.Available).info)
        }
    }

    fun onAutoCheckEnabledChanged() {
        handler.removeCallbacks(timer)
        if (started) applyAction(UpdateAutoCheckRuntime.onAutoCheckEnabledChanged(enabled()))
    }

    fun request(manual: Boolean): Boolean {
        if (!started || installing || settings.isUserShutdownRequested() || (!manual && !enabled())) return false
        val accepted = app.updateChecks.request(manual)
        if (accepted) {
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
        app.updateChecks.reset()
        presentation.reset()
        app.updateHints.shutdown()
    }

    fun onInstallStarted() {
        installing = true
        awaitingInstallerReturn = false
        handler.removeCallbacks(timer)
        app.updateHints.dismiss("install_started")
    }

    fun onInstallerLaunched() {
        if (installing) awaitingInstallerReturn = true
    }

    fun onInstallFinished() {
        installing = false
        awaitingInstallerReturn = false
        if (started) applyAction(UpdateAutoCheckRuntime.onTimerElapsed(enabled(), ownUiVisible))
    }

    private fun enabled() = started && settings.isUpdateAutoCheckEnabled() && !settings.isUserShutdownRequested()

    private fun applyAction(action: UpdateAutoCheckAction) {
        if (!started || installing) return
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
