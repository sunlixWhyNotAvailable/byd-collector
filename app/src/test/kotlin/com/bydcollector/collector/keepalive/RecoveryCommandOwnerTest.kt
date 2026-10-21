package com.bydcollector.collector.keepalive

import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryCommandOwnerTest {
    @Test
    fun liveProcessBlocksAnotherLaunchAndForcedExitAllowsRecovery() {
        val owner = KeepAliveDaemon.RecoveryCommandOwner()
        val first = assertNotNull(owner.start(waitingCommand()))
        try {
            assertTrue(first.isAlive)
            assertNull(owner.start(versionCommand()))
            assertTrue(owner.stop(first))
            assertFalse(first.isAlive)
            val next = assertNotNull(owner.start(versionCommand()))
            try {
                assertTrue(next.waitFor(10, TimeUnit.SECONDS))
            } finally {
                owner.stop(next)
            }
        } finally {
            owner.stop(first)
        }
    }

    @Test
    fun naturalExitAndFailedLaunchDoNotLatchOwnership() {
        val owner = KeepAliveDaemon.RecoveryCommandOwner()
        assertFailsWith<IOException> {
            owner.start(ProcessBuilder(File(System.getProperty("java.home"), "missing-recovery-command").path))
        }
        val exited = assertNotNull(owner.start(versionCommand()))
        try {
            assertTrue(exited.waitFor(10, TimeUnit.SECONDS))
            val live = assertNotNull(owner.start(waitingCommand()))
            try {
                assertTrue(owner.stop(exited))
                assertTrue(live.isAlive)
                assertNull(owner.start(versionCommand()))
            } finally {
                assertTrue(owner.stop(live))
            }
        } finally {
            owner.stop(exited)
        }
    }

    private fun versionCommand(): ProcessBuilder = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java" + if (isWindows()) ".exe" else "").path,
        "-version"
    ).redirectErrorStream(true)

    // Real OS processes exercise ownership/termination, not fabricated Binder or receiver outcomes.
    private fun waitingCommand(): ProcessBuilder = if (isWindows()) {
        ProcessBuilder(File(System.getenv("SystemRoot"), "System32/ping.exe").path, "-n", "120", "127.0.0.1")
    } else {
        ProcessBuilder("/bin/sleep", "120")
    }

    private fun isWindows() = System.getProperty("os.name", "").orEmpty().startsWith("Windows", ignoreCase = true)
}
