package com.bydcollector.collector

import android.app.Application
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.update.UpdateAutoCheckRuntime

//starts process-scoped app bookkeeping before either CollectorService or MainActivity is created
class BydCollectorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = CollectorSettings(this)
        UpdateAutoCheckRuntime.onRuntimeStarted(settings.isUpdateAutoCheckEnabled())
    }
}
