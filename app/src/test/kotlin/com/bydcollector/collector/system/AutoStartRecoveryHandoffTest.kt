package com.bydcollector.collector.system

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoStartRecoveryHandoffTest {
    @Test
    fun slowReadinessDoesNotBlockSubmissionOrLoseTheRecoveryRequest() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var recovered: AutoStartRecoveryRequest? = null
        try {
            val runner = AutoStartRecoveryRunner(executor) { request ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                recovered = request
            }
            val request = AutoStartRecoveryRequest("boot", 3)

            assertTrue(runner.submit(request, finished::countDown))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(20, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(request, recovered)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectedWorkerLeavesCompletionAvailableForForegroundFallback() {
        var finished = false
        val runner = AutoStartRecoveryRunner(
            Executor { throw RejectedExecutionException("injected") }
        ) { error("must not run") }

        assertFalse(runner.submit(AutoStartRecoveryRequest("boot", 0)) { finished = true })
        assertFalse(finished)
    }

    @Test
    fun receiversOnlyPerformBoundedForegroundHandoff() {
        val handoff = sourceFile("com/bydcollector/collector/system/AutoStartRecoveryService.kt").readText()
        val receivers = listOf(
            "com/bydcollector/collector/system/BootReceiver.kt",
            "com/bydcollector/collector/system/InternalAutoStartReceiver.kt",
            "com/bydcollector/collector/system/KeepAliveRecoveryReceiver.kt"
        ).joinToString("\n") { sourceFile(it).readText() }
        val manifest = projectFile("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml").readText()

        assertTrue(handoff.contains("receiver.goAsync()"))
        assertInOrder(handoff, "AutoStartRecoveryService.enqueue", "pendingResult.finish()")
        assertTrue(handoff.contains("if (submitToRunningOwner())"))
        assertTrue(receivers.contains("handoffAutoStartRecovery"))
        assertFalse(receivers.contains("BydCollectorApplication.store"))
        assertFalse(receivers.contains("CollectorAutoStart.handleBroadcast"))
        assertTrue(manifest.contains("AutoStartRecoveryService"))
    }

    private fun sourceFile(path: String): File = projectFile(
        "app/src/main/kotlin/$path",
        "src/main/kotlin/$path"
    )

    private fun projectFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile)
            ?: error("Missing source file: ${paths.joinToString()}")

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0 && secondIndex > firstIndex, "Expected `$first` before `$second`")
    }
}
