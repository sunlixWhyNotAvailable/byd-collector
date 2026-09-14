package com.bydcollector.collector.direct

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperWakeLockControllerTest {
    @Test
    fun autonomousStartupAcquiresOnceAndAppHandoffDoesNotReleaseOrReacquire() {
        val fixture = Fixture()

        fixture.controller.enterAutonomousMode()
        fixture.clock.now = 90_000L
        repeat(5) { fixture.controller.maintain() }

        assertTrue(fixture.controller.isAutonomousMode)
        assertTrue(fixture.controller.isHeld)
        assertEquals(1, fixture.acquireCount)
        assertEquals(0, fixture.releaseCount)
        assertEquals(listOf("INFO: autonomous helper wake lock acquired"), fixture.logs)
    }

    @Test
    fun acquisitionFailureDoesNotStopModeAndRetriesNoMoreThanEveryThirtySeconds() {
        val fixture = Fixture(failAcquisitions = 2)

        fixture.controller.enterAutonomousMode()
        fixture.clock.now = 29_999L
        fixture.controller.maintain()
        fixture.clock.now = 30_000L
        fixture.controller.maintain()
        fixture.clock.now = 59_999L
        fixture.controller.maintain()
        fixture.clock.now = 60_000L
        fixture.controller.maintain()

        assertTrue(fixture.controller.isAutonomousMode)
        assertTrue(fixture.controller.isHeld)
        assertEquals(3, fixture.acquireCount)
        assertEquals(2, fixture.logs.count { it.contains("acquire failed") })
        assertEquals(1, fixture.logs.count { it.contains("acquired") })
    }

    @Test
    fun explicitStopOrNormalExitReleasesAndRepeatedTeardownIsSafe() {
        val fixture = Fixture()
        fixture.controller.enterAutonomousMode()

        fixture.controller.exitAutonomousMode()
        fixture.controller.exitAutonomousMode()
        fixture.clock.now = 60_000L
        fixture.controller.maintain()

        assertFalse(fixture.controller.isAutonomousMode)
        assertFalse(fixture.controller.isHeld)
        assertEquals(1, fixture.acquireCount)
        assertEquals(1, fixture.releaseCount)
        assertEquals(1, fixture.logs.count { it.contains("released") })
    }

    @Test
    fun releaseFailureIsLoggedAndRetainsHandleForFinalTeardownRetry() {
        val fixture = Fixture(failReleases = 1)
        fixture.controller.enterAutonomousMode()

        fixture.controller.exitAutonomousMode()

        assertFalse(fixture.controller.isAutonomousMode)
        assertTrue(fixture.controller.isHeld)
        assertEquals(1, fixture.releaseCount)
        assertTrue(fixture.logs.last().contains("release failed: IllegalStateException: release denied"))
    }

    @Test
    fun transientReleaseFailureSucceedsOnSecondTeardownAttempt() {
        val fixture = Fixture(failReleases = 1)
        fixture.controller.enterAutonomousMode()

        fixture.controller.exitAutonomousMode()
        fixture.controller.exitAutonomousMode()

        assertFalse(fixture.controller.isAutonomousMode)
        assertFalse(fixture.controller.isHeld)
        assertEquals(2, fixture.releaseCount)
        assertEquals(1, fixture.logs.count { it.contains("release failed") })
        assertEquals(1, fixture.logs.count { it.contains("released") })
    }

    @Test
    fun unexpectedLossRetriesOnScheduleAndDiagnosticFailureCannotBreakLifecycle() {
        val clock = FakeClock()
        var acquireCount = 0
        var releaseCount = 0
        var held = true
        val controller = HelperWakeLockController(
            clock,
            {
                acquireCount++
                held = true
                object : HelperWakeLockController.HeldLock {
                    override fun isHeld() = held
                    override fun release() {
                        releaseCount++
                        held = false
                    }
                }
            },
            { error("diagnostic sink unavailable") }
        )

        controller.enterAutonomousMode()
        held = false
        clock.now = 29_999L
        controller.maintain()
        assertFalse(controller.isHeld)
        clock.now = 30_000L
        controller.maintain()
        controller.exitAutonomousMode()

        assertEquals(2, acquireCount)
        assertEquals(1, releaseCount)
        assertFalse(controller.isAutonomousMode)
        assertFalse(controller.isHeld)
    }

    @Test
    fun daemonCreatesLockOnlyForSpoolModeBeforePollingAndReleasesOnAllOwnerExits() {
        val daemon = source("CollectorHelperDaemon.java")
        val platform = source("HelperWakeLockPlatform.java")
        val workerCreation = daemon.substringAfter("final WorkerPollLoop workerPollLoop = spoolMode")
            .substringBefore("Binder helperBinder")
        val runtime = daemon.substringAfter("helperBinder.attachInterface")
            .substringBefore("Looper.loop();")
        val stop = daemon.substringAfter("void stop()")
            .substringBefore("void markConsumerHeartbeat()")
        val stopEndpoint = daemon.substringAfter("if (code == CollectorHelperProtocol.TX_STOP_OWNER)")
            .substringBefore("return true;")
        val normalExit = daemon.substringAfter("Looper.loop();")

        assertTrue(workerCreation.contains("? new WorkerPollLoop("))
        assertTrue(workerCreation.contains("HelperWakeLockPlatform::acquireShellPartialWakeLock"))
        assertTrue(runtime.indexOf("workerPollLoop.startAutonomousMode()") < runtime.indexOf("addService.invoke"))
        assertTrue(runtime.indexOf("workerPollLoop.startAutonomousMode()") < runtime.indexOf("workerPollLoop.start()"))
        assertTrue(stop.contains("wakeLockController.exitAutonomousMode()"))
        assertTrue(stopEndpoint.indexOf("if (accepted)") < stopEndpoint.indexOf("workerPollLoop.stop()"))
        assertTrue(daemon.contains("if (workerPollLoop != null) workerPollLoop.stop();"))
        assertTrue(normalExit.indexOf("workerPollLoop.stop()") < normalExit.indexOf("workerSpool.close()"))
        assertTrue(normalExit.indexOf("workerSpool.close()") < normalExit.indexOf("ownerLock.close()"))
        assertTrue(normalExit.indexOf("ownerLock.close()") < normalExit.indexOf("System.exit(0)"))
        assertTrue(daemon.contains("wakeLockController.maintain();"))
        assertTrue(platform.contains("Process.myUid() != SHELL_UID"))
        assertTrue(platform.indexOf("currentActivityThread") < platform.indexOf("systemMain"))
        assertTrue(platform.contains("SHELL_UID = 2000"))
        assertTrue(platform.contains("SHELL_PACKAGE = \"com.android.shell\""))
        assertTrue(platform.contains("Context.class.getMethod(\"getOpPackageName\")"))
        assertFalse(platform.contains("shellContext.getOpPackageName()"))
        assertTrue(platform.indexOf("getOpPackageName.invoke(shellContext)") < platform.indexOf("newWakeLock("))
        assertTrue(platform.contains("shell op-package attribution unavailable"))
        assertTrue(platform.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(platform.contains("wakeLock.setReferenceCounted(false)"))
        assertTrue(platform.contains("wakeLock.acquire()"))
    }

    private class Fixture(
        private var failAcquisitions: Int = 0,
        private var failReleases: Int = 0
    ) {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        var acquireCount = 0
        var releaseCount = 0
        var lockHeld = false
        val controller = HelperWakeLockController(
            clock,
            {
                acquireCount++
                if (failAcquisitions > 0) {
                    failAcquisitions--
                    error("acquire denied")
                }
                lockHeld = true
                object : HelperWakeLockController.HeldLock {
                    override fun isHeld() = lockHeld
                    override fun release() {
                        releaseCount++
                        if (failReleases > 0) {
                            failReleases--
                            error("release denied")
                        }
                        lockHeld = false
                    }
                }
            },
            logs::add
        )
    }

    private class FakeClock(var now: Long = 0L) : HelperWakeLockController.Clock {
        override fun elapsedRealtime(): Long = now
    }

    private fun source(name: String): String = listOf(
        File("app/src/main/java/com/bydcollector/collector/direct/$name"),
        File("src/main/java/com/bydcollector/collector/direct/$name")
    ).first(File::isFile).readText()
}
