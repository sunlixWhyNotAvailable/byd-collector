package com.bydcollector.collector.telegram

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargingEtaCalculatorTest {
    @Test
    fun acUsesSourceTimeMedianAndAnchorsRoundedFinishToMeasurementEvent() {
        val session = ChargingEtaSessionId("boot-a", "ac-1")
        val calculator = ChargingEtaCalculator()
        calculator.beginSession(session)
        addConstantWindow(calculator, session, 10.0)

        val result = calculator.estimate(input(
            session = session,
            nowElapsedMs = 60_000,
            measuredAtWallMs = WALL_BASE,
            soc = 50.0,
            energy = 10.0,
            type = ChargingType.AC
        ))

        assertEquals(ChargingEtaSource.CALCULATED_AC, result.source)
        assertEquals(3_600.0, result.seconds!!, 1e-9)
        assertEquals(60L, result.roundedUpMinutes)
        assertEquals(WALL_BASE + 3_600_000L, result.finishAtWallMs)
        assertEquals(WALL_BASE, result.measuredAtWallMs)
    }

    @Test
    fun powerWindowRequiresNearlySixtyCoveredSourceSecondsAndDoesNotRepeatStaleSamples() {
        val session = ChargingEtaSessionId("boot-a", "dc-window")
        val exact = ChargingPowerWindow().apply { reset(session) }
        for (t in 0L..59_000L step 1_000L) assertTrue(exact.add(session, t, 10.0))
        assertNull(exact.weightedMedianKw(session, 60_000L, 60_000L))
        assertTrue(exact.add(session, 60_000L, 10.0))
        assertEquals(10.0, exact.weightedMedianKw(session, 60_000L, 60_000L)!!.powerKw, 1e-9)
        assertEquals(10.0, exact.weightedMedianKw(session, 60_000L, 60_200L)!!.powerKw, 1e-9)
        assertNull(exact.weightedMedianKw(session, 60_000L, 65_001L))
        assertNull(exact.weightedMedianKw(session, 60_000L, 120_001L))

        val jittered = ChargingPowerWindow().apply { reset(session) }
        var t = 0L
        while (t < 60_000L) {
            assertTrue(jittered.add(session, t, 12.0))
            t += if (t % 3_000L == 0L) 1_003L else 997L
        }
        assertTrue(jittered.add(session, 60_000L, 12.0))
        assertEquals(12.0, jittered.weightedMedianKw(session, 60_000L, 60_000L)!!.powerKw, 1e-9)
    }

    @Test
    fun earlierSocEventUsesOnlyPowerAtOrBeforeItsSourceTime() {
        val session = ChargingEtaSessionId("boot-as-of", "earlier-soc")
        val calculator = ChargingEtaCalculator()
        calculator.beginSession(session)
        for (at in 0L..29_000L step 1_000L) {
            assertTrue(calculator.addPowerSample(session, at, 10.0))
        }
        for (at in 30_000L..60_000L step 1_000L) {
            assertTrue(calculator.addPowerSample(session, at, 100.0))
        }
        // Later power remains fresh at processing time but is outside the SOC event's history.
        for (at in 61_000L..120_000L step 1_000L) {
            assertTrue(calculator.addPowerSample(session, at, 200.0))
        }

        val earlierEvent = input(
            session = session,
            nowElapsedMs = 120_000L,
            measuredElapsed = 60_000L,
            measuredAtWallMs = WALL_BASE,
            soc = 50.0,
            energy = 10.0,
            type = ChargingType.AC
        )
        val result = calculator.estimate(earlierEvent)
        assertEquals(ChargingEtaSource.CALCULATED_AC, result.source)
        assertEquals(3_600.0 * 10.0 / 55.0, result.seconds!!, 1e-9)
        assertEquals(55.0, result.calculationPowerKw!!, 1e-9)

        val staleAtProcessing = calculator.estimate(earlierEvent.copy(nowElapsedMs = 130_001L))
        assertEquals(ChargingEtaUnavailableReason.INSUFFICIENT_POWER_WINDOW, staleAtProcessing.unavailableReason)
    }

    @Test
    fun invalidGapsOutOfOrderSamplesAndSessionChangesCannotReusePowerHistory() {
        val first = ChargingEtaSessionId("boot-a", "session-a")
        val restarted = ChargingEtaSessionId("boot-b", "session-a")
        val window = ChargingPowerWindow().apply { reset(first) }
        for (t in 0L..60_000L step 1_000L) {
            assertTrue(window.add(first, t, if (t == 30_000L) null else 24.0))
        }
        assertNull(window.weightedMedianKw(first, 60_000L, 60_000L))
        assertFalse(window.add(first, 59_000L, 24.0))
        assertFalse(window.add(restarted, 61_000L, 24.0))
        window.reset(restarted)
        assertNull(window.weightedMedianKw(restarted, 60_000L, 60_000L))

        val calculator = ChargingEtaCalculator()
        calculator.beginSession(first)
        addConstantWindow(calculator, first, 30.0)
        val valid = calculator.estimate(input(first, 60_000, WALL_BASE, 70.0, 5.0, ChargingType.AC))
        assertTrue(valid.available)
        val late = calculator.estimate(input(first, 60_000, WALL_BASE + 1, 71.0, 5.0, ChargingType.AC, measuredElapsed = 59_000))
        assertEquals(ChargingEtaUnavailableReason.OUT_OF_ORDER_EVALUATION, late.unavailableReason)

        calculator.beginSession(restarted)
        val noCarry = calculator.estimate(input(restarted, 60_000, WALL_BASE + 2, 70.0, 5.0, ChargingType.AC))
        assertEquals(ChargingEtaUnavailableReason.INSUFFICIENT_POWER_WINDOW, noCarry.unavailableReason)
    }

    @Test
    fun typePauseMissingInputsAndStaleStateNeverBecomeZeroEta() {
        val session = ChargingEtaSessionId("boot-a", "state-guards")
        val calculator = ChargingEtaCalculator()
        calculator.beginSession(session)
        addConstantWindow(calculator, session, 30.0)

        val unknown = calculator.estimate(input(session, 60_000, WALL_BASE, 50.0, 10.0, ChargingType.UNKNOWN))
        assertEquals(ChargingEtaUnavailableReason.UNKNOWN_CHARGE_TYPE, unknown.unavailableReason)
        assertNull(unknown.seconds)

        val conflict = calculator.estimate(input(session, 60_001, WALL_BASE + 1, 50.0, 10.0, ChargingType.CONFLICT))
        assertEquals(ChargingEtaUnavailableReason.UNKNOWN_CHARGE_TYPE, conflict.unavailableReason)
        val paused = calculator.estimate(input(session, 60_002, WALL_BASE + 2, 50.0, 10.0, ChargingType.AC, paused = true))
        assertEquals(ChargingEtaUnavailableReason.CHARGING_PAUSED, paused.unavailableReason)
        val stopped = calculator.estimate(input(session, 60_003, WALL_BASE + 3, 50.0, 10.0, ChargingType.AC, active = false))
        assertEquals(ChargingEtaUnavailableReason.CHARGING_INACTIVE, stopped.unavailableReason)
        val noEnergy = calculator.estimate(input(session, 60_004, WALL_BASE + 4, 50.0, null, ChargingType.AC))
        assertEquals(ChargingEtaUnavailableReason.INVALID_REMAINING_ENERGY, noEnergy.unavailableReason)
        val zeroSoc = calculator.estimate(input(session, 60_005, WALL_BASE + 5, 0.0, 10.0, ChargingType.AC))
        assertEquals(ChargingEtaUnavailableReason.INVALID_SOC, zeroSoc.unavailableReason)
        val zeroSocDc = calculator.estimate(input(session, 60_006, WALL_BASE + 6, 0.0, null, ChargingType.DC))
        assertEquals(ChargingEtaSource.CALCULATED_DC, zeroSocDc.source)
        val futureEnergy = calculator.estimate(
            input(
                session,
                nowElapsedMs = 60_007,
                measuredAtWallMs = WALL_BASE + 7,
                soc = 50.0,
                energy = 10.0,
                type = ChargingType.AC,
                measuredElapsed = 60_006,
                energySourceElapsed = 60_007
            )
        )
        assertEquals(ChargingEtaUnavailableReason.INVALID_REMAINING_ENERGY, futureEnergy.unavailableReason)
        assertNull(futureEnergy.seconds)
        val staleEvent = calculator.estimate(input(session, 200_006, WALL_BASE + 6, 50.0, 10.0, ChargingType.AC, measuredElapsed = 60_000))
        assertEquals(ChargingEtaUnavailableReason.INVALID_MEASUREMENT_TIME, staleEvent.unavailableReason)
    }

    @Test
    fun frozenDcProfileHasPinnedFingerprintAndIntegratesSimpleBoundaries() {
        val profile = DcChargingProfile.SL07_FROZEN
        assertEquals("sl07-all6-cutoff80-v1", profile.version)
        assertEquals(54, profile.observedBins.size)
        assertEquals(2, profile.observedBins.first().donorCount)
        assertEquals(5, profile.observedBins.last().donorCount)
        assertEquals("dae8239fc5a63f05a29fb0c92276dc8f8d5e6c20e9e093ff7f23fa68ba38462c", DcChargingProfile.SOURCE_SAMPLES_SHA256)
        assertEquals(EXPECTED_ALL_SIX_PROFILE_SHA256, profile.observedBinsSha256)

        val p26 = profile.observedBins.first().referencePowerKw
        val e26 = profile.observedBins.first().energyKwh
        assertEquals(6.0 * 3_600.0 * e26 / p26, profile.estimateSeconds(20.0, 26.0, 200.0)!!, 1e-8)
        assertEquals(3_600.0 * e26 / p26, profile.estimateSeconds(25.5, 26.5, 200.0)!!, 1e-8)
        assertTrue(profile.estimateSeconds(0.0, 100.0, 30.0)!!.isFinite())
        assertNull(profile.estimateSeconds(100.0, 100.0, 200.0))
        assertNull(profile.estimateSeconds(80.0, 100.0, 1.0))
    }

    @Test
    fun oemStabilityIsCausalFreshAndKeepsOriginalSourceTimestamp() {
        val session = ChargingEtaSessionId("boot-oem", "oem-1")
        val calculator = ChargingEtaCalculator()
        calculator.beginSession(session)
        for (at in 1_000L..61_000L step 5_000L) {
            assertTrue(calculator.observeOem(session, at, 1_000L, at, WALL_BASE - 5_000L, 0, 10))
        }
        val fallback = calculator.estimate(input(
            session = session,
            nowElapsedMs = 61_000,
            measuredAtWallMs = WALL_BASE,
            soc = 60.0,
            energy = null,
            type = ChargingType.AC
        ))
        assertEquals(ChargingEtaSource.OEM, fallback.source)
        assertEquals(600.0, fallback.seconds!!, 1e-9)
        assertEquals(ChargingEtaUnavailableReason.INSUFFICIENT_POWER_WINDOW, fallback.fallbackReason)
        assertEquals(WALL_BASE - 5_000L, fallback.sourceObservedAtWallMs)
        assertEquals(WALL_BASE + 600_000L, fallback.finishAtWallMs)

        assertTrue(calculator.observeOem(session, 62_000L, 62_000L, 62_000L, WALL_BASE + 1_000L, 0, 30))
        val earlierEvent = calculator.estimate(input(
            session = session,
            nowElapsedMs = 62_000L,
            measuredElapsed = 61_000L,
            measuredAtWallMs = WALL_BASE,
            soc = 60.0,
            energy = null,
            type = ChargingType.AC
        ))
        assertEquals(ChargingEtaSource.OEM, earlierEvent.source)
        assertEquals(600.0, earlierEvent.seconds!!, 1e-9)
        assertEquals(WALL_BASE - 5_000L, earlierEvent.sourceObservedAtWallMs)

        val nativeOnly = calculator.estimate(input(
            session = session,
            nowElapsedMs = 61_001,
            measuredAtWallMs = WALL_BASE + 1,
            soc = 60.0,
            energy = null,
            type = ChargingType.AC,
            selection = ChargingEtaSelection.OEM_ONLY,
            active = true
        ))
        assertEquals(ChargingEtaSource.OEM, nativeOnly.source)
        assertNull(nativeOnly.fallbackReason)
        val stopped = calculator.estimate(input(
            session = session,
            nowElapsedMs = 61_002,
            measuredAtWallMs = WALL_BASE + 2,
            soc = 60.0,
            energy = null,
            type = ChargingType.AC,
            selection = ChargingEtaSelection.OEM_ONLY,
            active = false
        ))
        assertEquals(ChargingEtaUnavailableReason.CHARGING_INACTIVE, stopped.unavailableReason)
    }

    @Test
    fun oemJumpSentinelStaleSourceAndBootBoundaryAreUnavailable() {
        val session = ChargingEtaSessionId("boot-a", "oem-guard")
        val calculator = ChargingEtaCalculator()
        calculator.beginSession(session)
        for (at in 1_000L..61_000L step 5_000L) {
            val isJump = at < 11_000L
            assertTrue(calculator.observeOem(session, at, if (isJump) 1_000L else at, at, WALL_BASE,
                if (isJump) 10 else 0, if (isJump) 0 else 10))
        }
        assertEquals(ChargingEtaUnavailableReason.OEM_NOT_STABLE,
            calculator.estimate(input(
                session, 61_000, WALL_BASE, 60.0, null, ChargingType.AC,
                selection = ChargingEtaSelection.OEM_ONLY
            )).unavailableReason)

        val sentinel = OemEtaStabilizer().apply { reset(session) }
        for (at in 1_000L..61_000L step 5_000L) {
            val valid = at < 61_000L
            assertTrue(sentinel.observe(session, at, 1_000L, at, WALL_BASE, 0, if (valid) 10 else 255))
        }
        assertNull(sentinel.stable(session, 61_000, 61_000))

        val aged = OemEtaStabilizer().apply { reset(session) }
        for (at in 1_000L..121_000L step 5_000L) {
            assertTrue(aged.observe(session, at, 1_000L, at, WALL_BASE, 0, 10))
        }
        assertTrue(aged.observe(session, 121_001L, 1_000L, 121_001L, WALL_BASE, 0, 10))
        assertNull(aged.stable(session, 61_000L, 121_001L))

        val bootChanged = ChargingEtaSessionId("boot-b", "oem-guard")
        val sentinelCalculator = ChargingEtaCalculator()
        sentinelCalculator.beginSession(session)
        for (at in 1_000L..61_000L step 5_000L) {
            assertTrue(sentinelCalculator.observeOem(session, at, 1_000L, at, WALL_BASE, 0, 10))
        }
        assertEquals(ChargingEtaSource.OEM, sentinelCalculator.estimate(input(
            session, 61_000, WALL_BASE, 60.0, null, ChargingType.AC,
            selection = ChargingEtaSelection.OEM_ONLY
        )).source)
        sentinelCalculator.beginSession(bootChanged)
        assertEquals(
            ChargingEtaUnavailableReason.OEM_NOT_STABLE,
            sentinelCalculator.estimate(input(bootChanged, 61_000, WALL_BASE, 60.0, null, ChargingType.AC, selection = ChargingEtaSelection.OEM_ONLY)).unavailableReason
        )
        assertFalse(sentinelCalculator.observeOem(session, 62_000, 1_000, 62_000, WALL_BASE, 0, 10))
    }

    @Test
    fun heldoutCutoff80ReplayUsesRealPowerWindowsAndLooCurves() {
        val windows = loadCsv("dc_eta_power_windows.csv").associateByRows { it[0] }
        val profileRows = loadCsv("dc_eta_loo_profiles.csv").associateByRows { it[0] }
        val targetRows = loadCsv("dc_eta_replay_targets.csv")
        val errorsTo85 = ArrayList<Double>()

        for (target in targetRows) {
            val sessionName = target[0]
            val targetSoc = target[1].toInt()
            val heldoutBins = profileRows[sessionName].orEmpty().map { row ->
                val donors = row[4].split(';')
                assertFalse(sessionName in donors, "LOO donors for $sessionName include the heldout session")
                DcEtaProfileBin(
                    soc = row[1].toInt(),
                    energyKwh = row[2].toDouble(),
                    referencePowerKw = row[3].toDouble(),
                    donorCount = donors.size
                )
            }
            val replayProfile = DcChargingProfile.fromRecordedLoo(sessionName, heldoutBins)
            val session = ChargingEtaSessionId("recorded-corpus", sessionName)
            val calculator = ChargingEtaCalculator(replayProfile)
            calculator.beginSession(session)
            val window = ChargingPowerWindow().apply { reset(session) }
            val rawWindow = windows[sessionName] ?: error("Missing recorded power window for $sessionName")
            rawWindow.forEach { row ->
                assertTrue(calculator.addPowerSample(session, row[1].toLong(), row[2].toDouble()))
                assertTrue(window.add(session, row[1].toLong(), row[2].toDouble()))
            }
            val measuredPower = window.weightedMedianKw(session, 120_000L, 120_200L)
                ?: error("Recorded source window did not meet the production 60-second coverage rule for $sessionName")
            val result = calculator.estimate(input(
                session = session,
                nowElapsedMs = 120_200,
                measuredElapsed = 120_000,
                measuredAtWallMs = WALL_BASE,
                soc = 80.0,
                energy = null,
                type = ChargingType.DC
            ))
            assertEquals(ChargingEtaSource.CALCULATED_DC, result.source)
            assertTrue(result.available)
            val targetPrediction = replayProfile.estimateSeconds(80.0, targetSoc.toDouble(), measuredPower.powerKw)
                ?: error("Recorded LOO profile cannot forecast target $targetSoc for $sessionName")
            assertEquals(target[4].toDouble(), targetPrediction, 0.000_01)

            val actualSeconds = target[2].toDouble()
            val pauseSeconds = target[3].toDouble()
            if (targetSoc == 85) errorsTo85 += targetPrediction - actualSeconds
            if (sessionName == "dc01") {
                assertEquals(375.502, actualSeconds, 1e-9) // deliberately slow heldout session
                assertEquals(0.0, pauseSeconds, 1e-9)
            }
            if (sessionName == "dc03" && targetSoc == 90) {
                assertEquals(542.06, actualSeconds, 1e-9)
                assertEquals(115.388, pauseSeconds, 1e-9) // pause at 88% remains in observed target time
            }
        }
        assertEquals(20.1709275666, errorsTo85.map { kotlin.math.abs(it) }.average(), 0.000_01)
    }

    private fun addConstantWindow(calculator: ChargingEtaCalculator, session: ChargingEtaSessionId, powerKw: Double) {
        for (t in 0L..60_000L step 1_000L) assertTrue(calculator.addPowerSample(session, t, powerKw))
    }

    private fun input(
        session: ChargingEtaSessionId,
        nowElapsedMs: Long,
        measuredAtWallMs: Long,
        soc: Double?,
        energy: Double?,
        type: ChargingType,
        selection: ChargingEtaSelection = ChargingEtaSelection.CALCULATED,
        active: Boolean = true,
        paused: Boolean = false,
        measuredElapsed: Long = nowElapsedMs,
        energySourceElapsed: Long? = measuredElapsed
    ) = ChargingEtaInput(
        session = session,
        nowElapsedMs = nowElapsedMs,
        measuredAtElapsedMs = measuredElapsed,
        measuredAtWallMs = measuredAtWallMs,
        socPct = soc,
        remainingEnergyKwh = energy,
        remainingEnergySourceElapsedMs = energySourceElapsed,
        chargeType = type,
        selection = selection,
        chargingActive = active,
        paused = paused
    )

    private fun loadCsv(name: String): List<List<String>> {
        val path = "/com/bydcollector/collector/telegram/$name"
        val stream = javaClass.getResourceAsStream(path) ?: error("Missing recorded replay resource $path")
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readLines()
                .drop(1)
                .filter(String::isNotBlank)
                .map { it.split(',') }
        }
    }

    private fun List<List<String>>.associateByRows(key: (List<String>) -> String): Map<String, List<List<String>>> =
        groupBy(key)

    private fun assertEquals(expected: Double, actual: Double, tolerance: Double) =
        kotlin.test.assertEquals(expected, actual, tolerance)

    companion object {
        private const val WALL_BASE = 1_800_000_000_000L
        private const val EXPECTED_ALL_SIX_PROFILE_SHA256 = "515813ab80b7933161f3fa92a64bc12cf830d1254bcc3183ffb1caffd9d3bd7c"
    }
}
