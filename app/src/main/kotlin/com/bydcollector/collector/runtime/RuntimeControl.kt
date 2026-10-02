package com.bydcollector.collector.runtime

import android.content.Context
import android.os.*
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.service.*
import com.bydcollector.collector.system.CollectorAutoStart
import com.bydcollector.collector.update.UpdateInfo
import com.bydcollector.collector.ha.HaRunSession
import com.bydcollector.collector.ha.HaExportChannel
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** UI lifetime changes presentation demand only, never execution ownership. */
internal object RuntimeControl {
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableMapOf<IBinder, IBinder.DeathRecipient>()
    private val visible = mutableSetOf<IBinder>()

    fun call(context: Context, operation: String, args: Bundle): Bundle {
        val task = FutureTask { onMain(context.applicationContext as BydCollectorApplication, operation, args) }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
        return try { task.get(3, TimeUnit.SECONDS) } catch (error: Exception) {
            task.cancel(false)
            throw IllegalStateException("Runtime control did not complete: $operation", error)
        }
    }

    private fun onMain(app: BydCollectorApplication, op: String, args: Bundle): Bundle = Bundle().apply {
        val update = app.updateRuntime
        when (op) {
            "visibility" -> {
                val token = requireNotNull(args.getBinder("token"))
                if (!observers.containsKey(token)) {
                    val death = IBinder.DeathRecipient { main.post { detach(app, token) } }
                    token.linkToDeath(death, 0)
                    observers[token] = death
                }
                val wasVisible = visible.isNotEmpty()
                if (args.getBoolean("ui")) visible += token else visible -= token
                KpiUiVisibility.update(token, args.getBoolean("kpi"))
                if (wasVisible != visible.isNotEmpty()) {
                    if (visible.isEmpty()) update.onUiHidden() else update.onUiVisible()
                }
            }
            "attach" -> if (!CollectorSettings(app).isUserShutdownRequested() && !CollectorService.isRunning()) {
                CollectorServiceController.start(app)
            }
            "watchdog" -> CollectorAutoStart.scheduleWatchdog(app, CollectorSettings(app), BydCollectorApplication.store(app))
            "cancelWatchdog" -> CollectorAutoStart.cancelScheduled(app)
            "grantMqtt", "grantInflux" -> {
                HaRunSession.process.start(if (op == "grantMqtt") HaExportChannel.MQTT else HaExportChannel.INFLUX)
                check(CollectorSettings(app).rememberTaskRemoval()) { "Could not persist export intent" }
            }
            "compress" -> putBoolean("accepted", TripCompressionService.start(app))
            "compressDismiss" -> putBoolean("accepted", TripCompressionService.dismissResult())
            "updateStart" -> update.start("ui_client")
            "updateResume" -> update.onUiResumed()
            "updateAccess" -> update.onPresentationAccessChanged()
            "hintStyle" -> app.updateHints.refresh(args.getBoolean("dark"))
            "updateAuto" -> update.onAutoCheckEnabledChanged()
            "updateRequest" -> putBoolean("accepted", update.request(args.getBoolean("manual")))
            "updateDismiss" -> update.dismissOffer()
            "updateClear" -> app.updateChecks.clearPresentation()
            "updateShutdown" -> update.shutdown()
            "installStart" -> update.onInstallStarted()
            "installLaunched" -> update.onInstallerLaunched()
            "installFinish" -> update.onInstallFinished()
            "offer" -> update.onOfferPresented(args.getLong("result"))
            "hintOpen" -> putBoolean("opened", app.updateHints.consumeOpenRequest())
            "history" -> {
                val version = requireNotNull(args.getString("version"))
                val url = requireNotNull(args.getString("url"))
                val notes = args.getString("notes").orEmpty()
                require(version.length < 128 && url.length < 4096 && notes.length < 256 * 1024)
                app.releaseNotesHistory.request(UpdateInfo(version, url, notes, args.getString("type")))
            }
            else -> error("Unknown runtime control")
        }
    }

    private fun detach(app: BydCollectorApplication, token: IBinder) {
        observers.remove(token)?.let { token.unlinkToDeath(it, 0) }
        visible.remove(token)
        KpiUiVisibility.update(token, false)
        if (visible.isEmpty()) app.updateRuntime.onUiHidden()
    }
}
