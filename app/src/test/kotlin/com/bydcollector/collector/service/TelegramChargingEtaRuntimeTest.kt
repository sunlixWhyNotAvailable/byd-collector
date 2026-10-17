package com.bydcollector.collector.service

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import com.bydcollector.collector.telegram.ChargingEtaSelection
import java.time.Instant
import java.util.Date
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramChargingEtaRuntimeTest {
    @Test
    fun progressUsesFreshAcWindowAndTypeChangeResetsOnlyEtaHistory() {
        val engine = TelegramEventEngine()
        val config = config()

        var result = poll(engine, config, 0, 50.0)
        assertTrue(result.events.isEmpty())
        result = poll(engine, config, 1, 50.0)
        assertTrue(result.state.chargingSessionId == null)
        result = poll(engine, config, 2, 50.0)
        val sessionId = assertNotNull(result.state.chargingSessionId)
        assertTrue(result.chargingEtaStateDiagnosticDetail.orEmpty().contains("eta_reason=session_started"))

        for (sequence in 3L..14L) {
            result = poll(
                engine,
                config,
                sequence,
                if (sequence == 14L) 55.0 else 50.0
            )
        }

        val progress = result.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("AC", progress.variables["charge_type"])
        assertEquals("50m", progress.variables["charge_time_to_full"])
        val finishAt = Instant.ofEpochMilli(WALL_BASE + 70_000L + 50L * 60_000L)
        assertEquals(
            java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date.from(finishAt)),
            progress.variables["charge_full_time"]
        )
        assertEquals("$sessionId:progress:55", progress.dedupeKey)
        assertTrue(progress.chargingEtaDiagnosticDetail.orEmpty().contains("window_power_kw=20"))

        val changedType = poll(engine, config, 15, 60.0, chargeType = 4, gunType = 3)
        val dcProgress = changedType.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("DC", dcProgress.variables["charge_type"])
        assertEquals("—", dcProgress.variables["charge_time_to_full"])
        assertEquals("—", dcProgress.variables["charge_full_time"])
        assertEquals("$sessionId:progress:60", dcProgress.dedupeKey)
        assertTrue(changedType.chargingEtaStateDiagnosticDetail.orEmpty().contains("eta_reason=type_changed"))
        assertEquals(sessionId, changedType.state.chargingSessionId)
    }

    @Test
    fun replaySampleDoesNotFillTheLivePowerCoverageGap() {
        val engine = TelegramEventEngine()
        val config = config()
        poll(engine, config, 0, 50.0)
        poll(engine, config, 1, 50.0)
        poll(engine, config, 2, 50.0)
        val replay = poll(engine, config, 3, 50.0, origin = PollOrigin.REPLAY)
        assertTrue(replay.events.isEmpty())

        var result = replay
        for (sequence in 4L..14L) {
            result = poll(
                engine,
                config,
                sequence,
                if (sequence == 14L) 55.0 else 50.0
            )
        }
        val progress = result.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("—", progress.variables["charge_time_to_full"])
        assertEquals("—", progress.variables["charge_full_time"])
        assertTrue(progress.chargingEtaDiagnosticDetail.orEmpty().contains("eta_reason=insufficient_power_window"))
    }

    @Test
    fun delayedProgressKeepsItsSourceTimeButDoesNotUseStalePower() {
        val engine = TelegramEventEngine()
        val config = config()
        poll(engine, config, 0, 50.0)
        poll(engine, config, 1, 50.0)
        poll(engine, config, 2, 50.0)
        var result = poll(engine, config, 3, 50.0)
        for (sequence in 4L..14L) {
            result = poll(
                engine,
                config,
                sequence,
                if (sequence == 14L) 55.0 else 50.0,
                nowOffsetMs = if (sequence == 14L) 30_000L else 0L
            )
        }
        val progress = result.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals(WALL_BASE + 70_000L, progress.occurredAtMs)
        assertEquals("—", progress.variables["charge_time_to_full"])
        assertEquals("—", progress.variables["charge_full_time"])
        assertTrue(progress.chargingEtaDiagnosticDetail.orEmpty().contains("eta_reason=insufficient_power_window"))
    }

    @Test
    fun selectorChangesUseWarmHistoryAndRestartPauseResumeDoNotChangeChargeIdentity() {
        val engine = TelegramEventEngine()
        val oemOnly = config(acSelection = ChargingEtaSelection.OEM_ONLY)
        poll(engine, oemOnly, 0, 50.0)
        poll(engine, oemOnly, 1, 50.0)
        poll(engine, oemOnly, 2, 50.0)
        var firstProgress = poll(engine, oemOnly, 3, 50.0)
        for (sequence in 4L..14L) {
            firstProgress = poll(
                engine,
                oemOnly,
                sequence,
                if (sequence == 14L) 55.0 else 50.0
            )
        }
        val sessionId = assertNotNull(firstProgress.state.chargingSessionId)
        val oemOnlyEvent = firstProgress.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("—", oemOnlyEvent.variables["charge_time_to_full"])

        val calculated = oemOnly.copy(
            acEtaSelection = ChargingEtaSelection.CALCULATED,
            dcEtaSelection = ChargingEtaSelection.OEM_ONLY
        )
        val calculatedResult = poll(engine, calculated, 15, 60.0)
        val calculatedProgress = calculatedResult
            .events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("$sessionId:progress:60", calculatedProgress.dedupeKey)
        assertEquals("40m", calculatedProgress.variables["charge_time_to_full"])

        val restarted = TelegramEventEngine(TelegramEventState.fromJson(calculatedResult.state.toJson()))
        var result = poll(restarted, calculated, 16, 65.0)
        val afterRestart = result.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals(sessionId, result.state.chargingSessionId)
        assertEquals("$sessionId:progress:65", afterRestart.dedupeKey)
        assertEquals("—", afterRestart.variables["charge_time_to_full"])

        result = poll(restarted, calculated, 17, 65.0, bmsState = 13)
        assertNotNull(
            result.chargingEtaStateDiagnosticDetail,
            "active=${result.state.chargingActive} evidence=${result.state.chargingEvidenceSource}"
        )
        assertTrue(
            result.chargingEtaStateDiagnosticDetail.orEmpty().contains("eta_reason=paused"),
            result.chargingEtaStateDiagnosticDetail.orEmpty()
        )
        result = poll(restarted, calculated, 18, 65.0, bmsState = 13)
        assertNull(result.chargingEtaStateDiagnosticDetail)
        result = poll(restarted, calculated, 19, 65.0, bmsState = 1)
        assertTrue(result.chargingEtaStateDiagnosticDetail.orEmpty().contains("eta_reason=resumed"))
        result = poll(restarted, calculated, 20, 70.0)
        val afterResume = result.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals(sessionId, result.state.chargingSessionId)
        assertEquals("$sessionId:progress:70", afterResume.dedupeKey)
        assertEquals("—", afterResume.variables["charge_time_to_full"])

        val inactiveAfterRestart = TelegramEventEngine(
            TelegramEventState.fromJson(calculatedResult.state.toJson())
        )
        poll(inactiveAfterRestart, calculated, 16, 65.0)
        result = poll(inactiveAfterRestart, calculated, 17, 65.0, bmsState = 2, currentAmps = 0.0)
        assertEquals(sessionId, result.state.chargingSessionId)
        assertTrue(result.chargingEtaStateDiagnosticDetail.orEmpty().contains("eta_reason=inactive"))
        result = poll(inactiveAfterRestart, calculated, 18, 65.0, bmsState = 2, currentAmps = 0.0)
        assertNull(result.chargingEtaStateDiagnosticDetail)
    }

    private fun poll(
        engine: TelegramEventEngine,
        config: TelegramEventConfig,
        sequence: Long,
        soc: Double,
        origin: PollOrigin = PollOrigin.LIVE,
        chargeType: Int = 2,
        gunType: Int = 2,
        bmsState: Int = 1,
        currentAmps: Double = -50.0,
        nowOffsetMs: Long = 0L
    ): TelegramEventResult {
        val elapsedMs = sequence * SAMPLE_INTERVAL_MS
        val source = PollSampleSource(
            identity = "eta-runtime:$sequence",
            bootId = BOOT_ID,
            capturedElapsedMs = elapsedMs,
            generatorId = "eta-runtime",
            sequence = sequence
        )
        val readings = listOf(
            floatReading("statistic_1014_1145045040_5", soc),
            floatReading("power_battery_remain_electricity", 20.0),
            floatReading("charging_charge_battery_volt", 400.0),
            floatReading("charging_charge_current", currentAmps),
            PollReading("charging_1009_876609586_5", gunType.toString(), gunType.toString()),
            PollReading("charging_1009_876609592_5", chargeType.toString(), chargeType.toString()),
            PollReading("charging_1009_876609560_5", bmsState.toString(), bmsState.toString()),
            PollReading("charging_1009_1146095640_5", "0", "0"),
            PollReading("charging_1009_1146095648_5", "0", "0")
        )
        val wallMs = WALL_BASE + elapsedMs
        return engine.onSourcePoll(
            pollId = sequence,
            timestamp = Instant.ofEpochMilli(wallMs).toString(),
            source = source,
            readings = readings,
            origin = origin,
            currentBootId = BOOT_ID,
            config = config,
            nowMs = wallMs,
            normalizer = VehicleStateNormalizer(),
            nowElapsedMs = elapsedMs + nowOffsetMs
        )
    }

    private fun floatReading(key: String, value: Double) = PollReading(
        rawKey = key,
        rawValue = value.toString(),
        descValue = value.toString()
    )

    private fun config(
        acSelection: ChargingEtaSelection = ChargingEtaSelection.CALCULATED,
        dcSelection: ChargingEtaSelection = ChargingEtaSelection.CALCULATED
    ) = TelegramEventConfig(
        enabledEvents = setOf(TelegramEventType.CHARGING_PROGRESS),
        chargeStepPercent = 5,
        lowVoltageThreshold = 12.0,
        unavailableDelayMs = 60_000L,
        tripEndDelayMs = 30_000L,
        language = TelegramTemplateLanguage.EN,
        acEtaSelection = acSelection,
        dcEtaSelection = dcSelection
    )

    companion object {
        private const val BOOT_ID = "eta-runtime-boot"
        private const val WALL_BASE = 1_797_000_000_000L
        private const val SAMPLE_INTERVAL_MS = 5_000L
    }
}
