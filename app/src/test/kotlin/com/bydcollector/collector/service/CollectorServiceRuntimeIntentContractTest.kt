package com.bydcollector.collector.service

import com.bydcollector.collector.ui.DebugRuntimeStatus
import com.bydcollector.collector.ui.RuntimeActionStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectorServiceRuntimeIntentContractTest {
    @Test
    fun runtimeStatusEnumsExposeEveryTransition() {
        assertEquals(
            listOf("STOPPED", "STARTING", "RUNNING", "STOPPING", "ERROR"),
            RuntimeActionStatus.entries.map { it.name }
        )
        assertTrue(DebugRuntimeStatus.entries.map { it.name }.contains("STOPPING"))
    }

    @Test
    fun servicePublishesLastIntentRuntimeGuards() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(service.contains("setMainRuntime(RuntimeActionStatus.STARTING)"))
        assertTrue(service.contains("setMainRuntime(RuntimeActionStatus.STOPPING)"))
        assertTrue(service.contains("setMainRuntime(RuntimeActionStatus.RUNNING)"))
        assertTrue(service.contains("setMainRuntime(RuntimeActionStatus.ERROR)"))
        assertTrue(service.contains("debugWorkGeneration.incrementAndGet()"))
        assertTrue(service.contains("debugStartQueued.set(true)"))
        assertTrue(service.contains("setDebugRuntime(DebugRuntimeStatus.STARTING, generation = startGeneration)"))
        assertTrue(service.contains("if (!settings.isDebugPollingEnabled() || settings.isDebugManuallyStopped())"))
        assertTrue(service.contains("if (!debugStartStillCurrent(startGeneration)) return@execute"))
        assertTrue(service.contains("setDebugRuntime(DebugRuntimeStatus.STOPPING"))
        assertTrue(service.contains("influxWorkGeneration.incrementAndGet()"))
        assertTrue(service.contains("if (submittedGeneration != generation.get() || !canExecute()) return@onSuccess"))
        assertTrue(service.contains("if (submittedGeneration == generation.get() && canExecute())"))
        val rejectedAction = service
            .substringAfter("private fun <T> executeChannel(")
            .substringAfter("catch (error: RejectedExecutionException) {")
            .substringBefore("private data class ChannelActionStatus")
        assertTrue(rejectedAction.contains("onFailedAction?.invoke()"))
        assertTrue(service.contains("mqttRuntimeStatus == RuntimeActionStatus.STOPPING"))
        assertTrue(service.contains("if (!mqttOfflineQueued.compareAndSet(false, true)) return"))
        assertTrue(!service.contains("mqttOfflineCompletionGeneration"))
        assertTrue(service.contains("if (completedOk) RuntimeActionStatus.STOPPED else RuntimeActionStatus.ERROR"))
        assertTrue(service.contains("settings.setTelegramConnectionStatus(\"failed\", \"telegram_test_error\")"))
    }

    @Test
    fun dashboardRuntimeContractsCarryPerChannelStatus() {
        val state = sourceFile("com/bydcollector/collector/ui/DashboardState.kt").readText()
        val flags = sourceFile("com/bydcollector/collector/ui/DashboardUiStateStore.kt").readText()
        val provider = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        assertTrue(state.contains("val mainRuntimeStatus: RuntimeActionStatus"))
        assertTrue(state.contains("val mqttRuntimeStatus: RuntimeActionStatus"))
        assertTrue(state.contains("val influxRuntimeStatus: RuntimeActionStatus"))
        assertTrue(flags.contains("mainRuntimeStatus = flags.mainRuntimeStatus"))
        assertTrue(flags.contains("mqttRuntimeStatus = flags.mqttRuntimeStatus"))
        assertTrue(flags.contains("influxRuntimeStatus = flags.influxRuntimeStatus"))
        assertTrue(provider.contains("CollectorService.mainRuntimeStatus()"))
        assertTrue(provider.contains("CollectorService.mqttRuntimeStatus()"))
        assertTrue(provider.contains("CollectorService.influxRuntimeStatus()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
