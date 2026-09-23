package com.bydcollector.collector

import android.app.Instrumentation
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.update.*
import java.lang.reflect.Field

/** Opt-in emulator-only real window fault injection. No network or vehicle access. */
internal object HintWindowBehaviorGate {
    fun run(instrumentation: Instrumentation): String {
        val app = instrumentation.targetContext.applicationContext as BydCollectorApplication
        check(Settings.canDrawOverlays(app)) { "Grant emulator overlay app-op before this gate" }
        val prefs = app.getSharedPreferences(CollectorSettings.PREFS_NAME, 0)
        val keys = listOf(CollectorSettings.KEY_UPDATE_AUTO_CHECK,
            CollectorSettings.KEY_UPDATE_HINT_ENABLED, CollectorSettings.KEY_USER_SHUTDOWN)
        val previous = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val delegates = listOf("updateChecks", "updateHints", "updateRuntime").associate { name ->
            val field = BydCollectorApplication::class.java.getDeclaredField("${name}\$delegate")
                .apply { isAccessible = true }
            field to field.get(app)
        }
        check(delegates.values.all { !(it as Lazy<*>).isInitialized() }) {
            "Run this opt-in gate in a fresh instrumentation process"
        }
        var checks = 0
        val attemptTimes = mutableListOf<Long>() // main-thread owned
        val session = UpdateCheckSession(dispatch = { it() }, checker = {
            checks++
            UpdateCheckResult.Available(UpdateInfo("99.0.0", "https://example.invalid/fixture.apk", "fixture"))
        })
        val overlay = UpdateHintOverlay(app, attachWindow = { windows, host, layout ->
            attemptTimes += SystemClock.elapsedRealtime()
            if (attemptTimes.size <= 2) throw WindowManager.BadTokenException("injected window creation failure")
            windows.addView(host, layout)
        })
        fun delegate(name: String): Field = delegates.keys.single { it.name == "${name}\$delegate" }
        try {
            check(prefs.edit().putBoolean(keys[0], false).putBoolean(keys[1], true)
                .putBoolean(keys[2], false).commit())
            onMain(instrumentation) {
                delegate("updateChecks").set(app, lazyOf(session))
                delegate("updateHints").set(app, lazyOf(overlay))
                delegate("updateRuntime").set(app, lazy { UpdateRuntime(app) })
                check(mainDisplayForUpdateHintIfReady(app) != null) { "Emulator display must be ON" }
                app.updateRuntime.start("hint_window_fixture")
                check(session.request(manual = true))
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (SystemClock.elapsedRealtime() < deadline &&
                !onMain(instrumentation) { UpdateHintCoordinator.ownSnapshot().phase == UpdateHintPhase.VISIBLE }) {
                SystemClock.sleep(25L)
            }
            return onMain(instrumentation) {
                val visible = UpdateHintCoordinator.ownSnapshot()
                check(visible.phase == UpdateHintPhase.VISIBLE) { "No real first draw after retries; attempts=${attemptTimes.size}" }
                check(attemptTimes.size == 3) { "Unexpected attempts: $attemptTimes" }
                val gaps = attemptTimes.zipWithNext { a, b -> b - a }
                check(gaps[0] >= 1_000L && gaps[1] >= 3_000L) { "Retry fired before deadline: $gaps" }
                check(visible.expiresAtElapsedMs - SystemClock.elapsedRealtime() in 7_500L..10_000L) {
                    "Visible lifetime did not start from first draw"
                }
                check(checks == 1) { "Retry repeated the fake HTTP check" }
                overlay.dismiss("fixture_finished")
                app.updateRuntime.onPresentationAccessChanged()
                check(UpdateHintCoordinator.ownSnapshot().phase == UpdateHintPhase.NONE)
                check(attemptTimes.size == 3) { "Already drawn result was replayed" }
                "HINT_WINDOW_GATE_PASS attempts=3 retry_gaps_ms=$gaps checks=$checks first_draw=true"
            }
        } finally {
            onMain(instrumentation) {
                runCatching { app.updateRuntime.shutdown() }
                delegates.forEach { (field, value) -> field.set(app, value) }
            }
            val restore = prefs.edit()
            previous.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putBoolean(key, value) }
            check(restore.commit())
        }
    }

    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        var outcome: Result<T>? = null
        instrumentation.runOnMainSync { outcome = runCatching(action) }
        return checkNotNull(outcome).getOrThrow()
    }
}
