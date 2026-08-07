package com.bydcollector.collector.telegram

import org.json.JSONObject
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramReachabilityProbeTest {
    @Test
    fun staleAndIntervalGatesUseMonotonicTimeAndResetCancelsTheGate() {
        val directory = Files.createTempDirectory("telegram-probe").toFile()
        val log = directory.resolve("telegram_reachability.jsonl")
        var elapsedMs = 1_000L
        var calls = 0
        lateinit var probe: TelegramReachabilityProbe
        probe = TelegramReachabilityProbe(
            logFile = log,
            processGeneration = 7L,
            epochMs = { 100L },
            elapsedRealtimeMs = { elapsedMs }
        )
        val getMe = {
            calls += 1
            probe.onRequest(evidence("getMe", elapsedMs))
            TelegramSendResult.Success
        }

        probe.maybeProbe(true, true, 9_999L, getMe)
        probe.maybeProbe(true, true, 10_000L, getMe)
        elapsedMs += 29_999L
        probe.maybeProbe(true, true, 40_000L, getMe)
        elapsedMs += 1L
        probe.maybeProbe(true, true, 40_001L, getMe)

        assertEquals(2, calls)
        assertEquals(2, log.readLines().size)

        probe.reset()
        probe.maybeProbe(true, true, 10_000L, getMe)
        assertEquals(3, calls)
    }

    @Test
    fun recentRealRequestReplacesGetMeWithoutGeneratingAMessage() {
        val log = Files.createTempDirectory("telegram-request-evidence").resolve("probe.jsonl").toFile()
        var elapsedMs = 20_000L
        val probe = TelegramReachabilityProbe(
            logFile = log,
            processGeneration = 11L,
            epochMs = { 10L },
            elapsedRealtimeMs = { elapsedMs }
        )
        probe.onRequest(evidence("sendMessage", elapsedMs - 1_000L))
        var getMeCalls = 0

        probe.maybeProbe(true, true, 10_000L) {
            getMeCalls += 1
            TelegramSendResult.Success
        }

        val json = JSONObject(log.readText())
        assertEquals(0, getMeCalls)
        assertEquals("sendMessage", json.getString("operation"))
        assertEquals(11L, json.getLong("process_generation"))
        assertEquals(true, json.getBoolean("network_reached"))
        assertEquals(true, json.getBoolean("authenticated"))
        assertEquals(
            setOf(
                "operation",
                "epoch_ms",
                "elapsed_realtime_ms",
                "process_generation",
                "duration_ms",
                "http_status",
                "network_reached",
                "authenticated",
                "result",
                "failure_kind",
                "exception_class"
            ),
            json.keys().asSequence().toSet()
        )
        assertFalse(log.readText().contains("token"))
        assertFalse(log.readText().contains("chat"))
        assertFalse(log.readText().contains("message"))
        assertFalse(log.readText().contains("description"))
    }

    @Test
    fun localCredentialRejectionIsLoggedWithoutNetworkEvidence() {
        val log = Files.createTempDirectory("telegram-local-rejection").resolve("probe.jsonl").toFile()
        val probe = TelegramReachabilityProbe(log, 3L, epochMs = { 50L }, elapsedRealtimeMs = { 60L })

        probe.maybeProbe(true, true, 10_000L) {
            TelegramSendResult.Failure(TelegramSendFailureKind.CONFIGURATION)
        }

        val json = JSONObject(log.readText())
        assertEquals("getMe", json.getString("operation"))
        assertEquals(false, json.getBoolean("network_reached"))
        assertEquals(false, json.getBoolean("authenticated"))
        assertEquals("configuration", json.getString("failure_kind"))
    }

    @Test
    fun capStopsAppendWithoutRotationOrOverwrite() {
        val log = Files.createTempDirectory("telegram-cap").resolve("probe.jsonl").toFile()
        var elapsedMs = 30_000L
        val probe = TelegramReachabilityProbe(
            logFile = log,
            processGeneration = 1L,
            elapsedRealtimeMs = { elapsedMs },
            maxBytes = 700L
        )

        repeat(20) {
            probe.onRequest(evidence("sendMessage", elapsedMs))
            probe.maybeProbe(true, true, 10_000L) { error("recent request must suppress getMe") }
            elapsedMs += TelegramReachabilityProbe.PROBE_INTERVAL_MS
        }
        val cappedBytes = log.readBytes()
        probe.onRequest(evidence("sendMessage", elapsedMs))
        probe.maybeProbe(true, true, 10_000L) { error("recent request must suppress getMe") }

        assertTrue(probe.isLoggingCapped())
        assertTrue(log.length() <= 700L)
        assertTrue(cappedBytes.contentEquals(log.readBytes()))
        log.readLines().forEach { JSONObject(it) }
    }

    @Test
    fun appendFailureIsExposedWithoutPathOrCredentialTextAndKeepsTheCadenceGate() {
        val unwritableLog = Files.createTempDirectory("telegram-secret-path").toFile()
        var elapsedMs = 30_000L
        val reportedFailures = mutableListOf<String>()
        val probe = TelegramReachabilityProbe(
            logFile = unwritableLog,
            processGeneration = 1L,
            elapsedRealtimeMs = { elapsedMs },
            onAppendFailure = reportedFailures::add
        )
        probe.onRequest(evidence("sendMessage", elapsedMs))

        probe.maybeProbe(true, true, 10_000L) { error("recent request must suppress getMe") }

        assertEquals(FileNotFoundException::class.java.name, probe.lastAppendFailureClass())
        assertFalse(probe.lastAppendFailureClass().orEmpty().contains("secret"))
        assertEquals(listOf(FileNotFoundException::class.java.name), reportedFailures)

        elapsedMs += TelegramReachabilityProbe.PROBE_INTERVAL_MS
        probe.onRequest(evidence("sendMessage", elapsedMs))
        probe.maybeProbe(true, true, 10_000L) { error("recent request must suppress getMe") }
        assertEquals(FileNotFoundException::class.java.name, probe.lastAppendFailureClass())
        assertEquals(1, reportedFailures.size)
    }

    @Test
    fun resetReturnsWhileGetMeIsBlockedAndInvalidatesItsResult() {
        val log = Files.createTempDirectory("telegram-reset-race").resolve("probe.jsonl").toFile()
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val probe = TelegramReachabilityProbe(log, 5L, elapsedRealtimeMs = { 30_000L })
        val worker = thread(start = true, name = "blocked-get-me") {
            probe.maybeProbe(true, true, 10_000L) {
                requestStarted.countDown()
                releaseRequest.await()
                TelegramSendResult.Success
            }
        }

        assertTrue(requestStarted.await(1, TimeUnit.SECONDS))
        val resetReturned = CountDownLatch(1)
        thread(start = true, name = "probe-reset") {
            probe.reset()
            resetReturned.countDown()
        }
        try {
            assertTrue(resetReturned.await(1, TimeUnit.SECONDS))
        } finally {
            releaseRequest.countDown()
            worker.join(1_000L)
        }

        assertFalse(worker.isAlive)
        assertFalse(log.exists())
    }

    @Test
    fun intentionalStopDuringGetMeInvalidatesInFlightAndBlocksANewReservationUntilDeactivated() {
        val log = Files.createTempDirectory("telegram-intentional-stop-race").resolve("probe.jsonl").toFile()
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val resetReachedDeactivation = CountDownLatch(1)
        val allowDeactivation = CountDownLatch(1)
        val lateProbeStarted = CountDownLatch(1)
        val stateLock = Any()
        var mainPollState: Pair<Boolean, Long?> = true to 10_000L
        val getMeCalls = AtomicInteger()
        val probe = TelegramReachabilityProbe(log, 8L, elapsedRealtimeMs = { 30_000L })
        val inFlightProbe = thread(start = true, name = "intentional-stop-in-flight-get-me") {
            probe.maybeProbe(
                telegramEnabled = { true },
                mainPollState = { synchronized(stateLock) { mainPollState } }
            ) {
                getMeCalls.incrementAndGet()
                probe.onRequest(evidence("getMe", 30_000L))
                requestStarted.countDown()
                releaseRequest.await()
                TelegramSendResult.Success
            }
        }
        var stopThread: Thread? = null
        var lateProbe: Thread? = null

        try {
            assertTrue(requestStarted.await(1, TimeUnit.SECONDS))
            stopThread = thread(start = true, name = "intentional-main-stop") {
                probe.resetAndRunAtomically {
                    resetReachedDeactivation.countDown()
                    allowDeactivation.await()
                    synchronized(stateLock) {
                        mainPollState = false to null
                    }
                }
            }
            assertTrue(resetReachedDeactivation.await(1, TimeUnit.SECONDS))
            lateProbe = thread(start = true, name = "probe-during-main-deactivation") {
                lateProbeStarted.countDown()
                probe.maybeProbe(
                    telegramEnabled = { true },
                    mainPollState = { synchronized(stateLock) { mainPollState } }
                ) {
                    getMeCalls.incrementAndGet()
                    TelegramSendResult.Success
                }
            }
            assertTrue(lateProbeStarted.await(1, TimeUnit.SECONDS))
        } finally {
            allowDeactivation.countDown()
            stopThread?.join(1_000L)
            releaseRequest.countDown()
            inFlightProbe.join(1_000L)
            lateProbe?.join(1_000L)
        }

        assertFalse(inFlightProbe.isAlive)
        assertFalse(stopThread?.isAlive == true)
        assertFalse(lateProbe?.isAlive == true)
        assertEquals(1, getMeCalls.get())
        assertFalse(log.exists())
    }

    @Test
    fun liveEligibilityAfterBlockedSendPreventsProbeAfterMainReset() {
        val log = Files.createTempDirectory("telegram-blocked-send-race").resolve("probe.jsonl").toFile()
        val blockedSendStarted = CountDownLatch(1)
        val releaseBlockedSend = CountDownLatch(1)
        val stateLock = Any()
        var mainPollState: Pair<Boolean, Long?> = true to 10_000L
        var getMeCalls = 0
        val probe = TelegramReachabilityProbe(log, 6L, elapsedRealtimeMs = { 30_000L })
        val worker = thread(start = true, name = "blocked-send-then-probe") {
            blockedSendStarted.countDown()
            releaseBlockedSend.await()
            probe.maybeProbe(
                telegramEnabled = { true },
                mainPollState = { synchronized(stateLock) { mainPollState } }
            ) {
                getMeCalls += 1
                TelegramSendResult.Success
            }
        }

        try {
            assertTrue(blockedSendStarted.await(1, TimeUnit.SECONDS))
            synchronized(stateLock) {
                mainPollState = false to null
            }
            probe.reset()
        } finally {
            releaseBlockedSend.countDown()
            worker.join(1_000L)
        }

        assertFalse(worker.isAlive)
        assertEquals(0, getMeCalls)
        assertFalse(log.exists())
    }

    private fun evidence(operation: String, elapsedMs: Long) = TelegramRequestEvidence(
        operation = operation,
        epochMs = 123L,
        elapsedRealtimeMs = elapsedMs,
        durationMs = 5L,
        httpStatus = 200,
        networkReached = true,
        authenticated = true,
        result = "success",
        failureKind = null,
        exceptionClass = null
    )
}
