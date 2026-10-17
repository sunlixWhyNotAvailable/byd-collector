package com.bydcollector.collector.telegram

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** One complete 1%-SOC interval from the frozen DC reference. */
internal data class DcEtaProfileBin(
    val soc: Int,
    val energyKwh: Double,
    val referencePowerKw: Double,
    val donorCount: Int
)

/**
 * A pure, bounded DC profile. The production profile is frozen from all six
 * recorded SL07 sessions, including dc02's available 26–58% bins. Those bins
 * therefore intentionally differ from the dc02-heldout LOO curve. Custom
 * instances are only used to replay the
 * already-published leave-one-session-out (LOO) benchmark curves in tests.
 */
internal class DcChargingProfile private constructor(
    val version: String,
    bins: List<DcEtaProfileBin>,
    private val tailEnergyKwh: Double,
    private val tailLogIntercept: Double,
    private val tailLogSlope: Double,
    private val requireObservedBins: Boolean
) {
    internal val observedBins: List<DcEtaProfileBin> = bins.sortedBy { it.soc }
    private val observed = observedBins.associateBy { it.soc }
    val observedBinsSha256: String = sha256(observedBins.joinToString(separator = "", postfix = "") {
        "${it.soc},${java.lang.Double.toString(it.energyKwh)},${java.lang.Double.toString(it.referencePowerKw)},${it.donorCount}\n"
    })

    init {
        require(version.isNotBlank())
        require(observed.size == bins.size) { "Duplicate DC profile SOC bin" }
        require(bins.all { it.energyKwh.isFinite() && it.energyKwh > 0.0 })
        require(bins.all { it.referencePowerKw.isFinite() && it.referencePowerKw > 1.0 })
        require(bins.all { it.soc in 0..99 && it.donorCount >= 0 })
        require(tailEnergyKwh.isFinite() && tailEnergyKwh > 0.0)
        require(tailLogIntercept.isFinite() && tailLogSlope.isFinite())
        if (requireObservedBins) {
            require((26..79).all(observed::containsKey)) { "Frozen profile must cover SOC 26–79" }
        }
    }

    /**
     * Integrates whole and fractional SOC intervals. Energies are per 1%-bin;
     * a fractional first/last bin receives the corresponding linear share.
     */
    fun estimateSeconds(socPct: Double, targetSocPct: Double, currentPowerKw: Double): Double? {
        if (!socPct.isFinite() || !targetSocPct.isFinite() || !currentPowerKw.isFinite()) return null
        if (socPct < 0.0 || targetSocPct > 100.0 || socPct >= targetSocPct || currentPowerKw <= 1.0) return null

        val startsBelowTwenty = socPct < LOW_SOC_END
        val lowSocScale = if (startsBelowTwenty) {
            val expectedAtSoc = lowSocPowerAtSoc(socPct)
            if (expectedAtSoc <= 0.0 || !expectedAtSoc.isFinite()) return null
            currentPowerKw / expectedAtSoc
        } else {
            1.0
        }

        var cursor = socPct
        var seconds = 0.0
        while (cursor < targetSocPct) {
            val binSoc = floor(cursor).toInt().coerceIn(0, 99)
            val next = min(targetSocPct, binSoc + 1.0)
            if (next <= cursor) return null
            val energy = energyForBin(binSoc) ?: return null
            val referencePower = powerForBin(binSoc) ?: return null
            val effectivePower = if (startsBelowTwenty) {
                // Below20%, scale the shape rather than pinning every later
                // interval to the initially low session power.
                referencePower * lowSocScale
            } else {
                min(referencePower, currentPowerKw)
            }
            if (!effectivePower.isFinite() || effectivePower <= 1.0) return null
            seconds += 3_600.0 * energy * (next - cursor) / effectivePower
            cursor = next
        }
        return seconds.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun energyForBin(soc: Int): Double? = when {
        soc in 0 until LOW_SOC_END -> observed[26]?.energyKwh
        soc in 20..25 -> observed[26]?.energyKwh
        soc in 26..79 -> observed[soc]?.energyKwh
        soc in 80..99 -> observed[soc]?.energyKwh ?: tailEnergyKwh
        else -> null
    }

    private fun powerForBin(soc: Int): Double? = when {
        soc in 0 until LOW_SOC_END -> lowSocPowerForBin(soc)
        soc in 20..25 -> observed[26]?.referencePowerKw
        soc in 26..79 -> observed[soc]?.referencePowerKw
        soc in 80..99 -> observed[soc]?.referencePowerKw ?: exp(tailLogIntercept + tailLogSlope * (soc + 0.5))
        else -> null
    }

    private fun lowSocPowerAtSoc(socPct: Double): Double {
        val atTwenty = observed[26]?.referencePowerKw ?: return Double.NaN
        return atTwenty * exp(tailLogSlope * (LOW_SOC_END - socPct))
    }

    private fun lowSocPowerForBin(soc: Int): Double = lowSocPowerAtSoc(soc + 0.5)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val FROZEN_VERSION = "sl07-all6-cutoff80-v1"
        const val SOURCE_SAMPLES_SHA256 = "dae8239fc5a63f05a29fb0c92276dc8f8d5e6c20e9e093ff7f23fa68ba38462c"
        const val OBSERVED_BINS_SHA256 = "515813ab80b7933161f3fa92a64bc12cf830d1254bcc3183ffb1caffd9d3bd7c"
        const val UPPER_TAIL_LOG_INTERCEPT = 8.584445270014688
        const val UPPER_TAIL_LOG_SLOPE = -0.051846885610211314
        const val UPPER_TAIL_ENERGY_KWH = 0.8450476226957406
        private const val LOW_SOC_END = 20

        /** Production profile: per-bin medians and upper power envelope from all six donors. */
        val SL07_FROZEN: DcChargingProfile by lazy {
            DcChargingProfile(
                version = FROZEN_VERSION,
                bins = ALL_SIX_OBSERVED_BINS,
                tailEnergyKwh = UPPER_TAIL_ENERGY_KWH,
                tailLogIntercept = UPPER_TAIL_LOG_INTERCEPT,
                tailLogSlope = UPPER_TAIL_LOG_SLOPE,
                requireObservedBins = true
            )
        }

        /** Test-only profile factory for replaying the existing per-heldout CSV rows. */
        fun fromRecordedLoo(
            heldoutSession: String,
            bins: List<DcEtaProfileBin>
        ): DcChargingProfile = DcChargingProfile(
            version = "loo-$heldoutSession",
            bins = bins,
            tailEnergyKwh = UPPER_TAIL_ENERGY_KWH,
            tailLogIntercept = UPPER_TAIL_LOG_INTERCEPT,
            tailLogSlope = UPPER_TAIL_LOG_SLOPE,
            requireObservedBins = false
        )

        private val ALL_SIX_OBSERVED_BINS = listOf(
            DcEtaProfileBin(26, 0.8124795710943266, 154.58818110211104, 2),
            DcEtaProfileBin(27, 0.8394020197613101, 153.98766896242273, 2),
            DcEtaProfileBin(28, 0.8577309641875627, 154.0910143215663, 2),
            DcEtaProfileBin(29, 0.853578322970581, 154.6543927612305, 3),
            DcEtaProfileBin(30, 0.8647317784041851, 155.05248118805557, 3),
            DcEtaProfileBin(31, 0.8519452769421386, 155.33504052123718, 3),
            DcEtaProfileBin(32, 0.8403333050835207, 155.31762457141394, 4),
            DcEtaProfileBin(33, 0.8500871914636844, 155.19130317097245, 4),
            DcEtaProfileBin(34, 0.8678756928799778, 155.04145123370773, 4),
            DcEtaProfileBin(35, 0.8363665460242485, 154.6190808344792, 4),
            DcEtaProfileBin(36, 0.8655119827081341, 154.83651343056007, 4),
            DcEtaProfileBin(37, 0.8563045395391082, 155.45177944336857, 4),
            DcEtaProfileBin(38, 0.8501163539599206, 155.9379077730394, 4),
            DcEtaProfileBin(39, 0.8221408582175593, 156.16439879160126, 4),
            DcEtaProfileBin(40, 0.8576690837734986, 156.748, 4),
            DcEtaProfileBin(41, 0.8628483428472223, 157.2725043060032, 4),
            DcEtaProfileBin(42, 0.8582801172780439, 157.5359984759195, 4),
            DcEtaProfileBin(43, 0.8348842492822498, 157.01099999999997, 4),
            DcEtaProfileBin(44, 0.7650521313719852, 156.97799345521776, 5),
            DcEtaProfileBin(45, 0.8750606851861572, 156.67425202005617, 5),
            DcEtaProfileBin(46, 0.8506930690612792, 156.28189460404843, 5),
            DcEtaProfileBin(47, 0.8478781621327208, 156.23490728759765, 5),
            DcEtaProfileBin(48, 0.8694896914164142, 156.1178498303786, 5),
            DcEtaProfileBin(49, 0.9046516541389551, 151.30765447408652, 5),
            DcEtaProfileBin(50, 0.8632100957182564, 141.1898963928223, 5),
            DcEtaProfileBin(51, 0.8466725023447673, 126.87400135036474, 5),
            DcEtaProfileBin(52, 0.8383867970904242, 127.03189988848266, 5),
            DcEtaProfileBin(53, 0.8035276565379288, 127.6684767768587, 5),
            DcEtaProfileBin(54, 0.8216098159033374, 128.18411609691756, 5),
            DcEtaProfileBin(55, 0.8207322683836368, 128.76344691100772, 5),
            DcEtaProfileBin(56, 0.8336937660580696, 128.9682731547144, 5),
            DcEtaProfileBin(57, 0.7600297839726599, 129.01268462751125, 5),
            DcEtaProfileBin(58, 0.8443105002997333, 129.01726733218896, 5),
            DcEtaProfileBin(59, 0.846203820639297, 129.34501334500123, 5),
            DcEtaProfileBin(60, 0.8438995389583339, 129.922155218166, 5),
            DcEtaProfileBin(61, 0.7900421627765953, 129.74796720909717, 5),
            DcEtaProfileBin(62, 0.8287484684937544, 129.61591393059442, 5),
            DcEtaProfileBin(63, 0.8302410383397081, 130.42398910522462, 5),
            DcEtaProfileBin(64, 0.8379269470138888, 130.39954483651627, 5),
            DcEtaProfileBin(65, 0.839976449610901, 130.59886552781344, 5),
            DcEtaProfileBin(66, 0.8487717972222223, 130.30483788268435, 5),
            DcEtaProfileBin(67, 0.8529548125000003, 130.30500000000004, 5),
            DcEtaProfileBin(68, 0.8443800087201521, 130.30499999999998, 5),
            DcEtaProfileBin(69, 0.7627824750000003, 130.4273254488459, 5),
            DcEtaProfileBin(70, 0.8686606214884611, 131.59890889226597, 5),
            DcEtaProfileBin(71, 0.8501044092975278, 131.9696366309228, 5),
            DcEtaProfileBin(72, 0.8574629655858865, 132.09237349091495, 5),
            DcEtaProfileBin(73, 0.8318807157934569, 132.05499042226302, 5),
            DcEtaProfileBin(74, 1.2773394764434436, 117.96918459320118, 5),
            DcEtaProfileBin(75, 0.7189990612552639, 93.98683479347169, 5),
            DcEtaProfileBin(76, 0.826445117474709, 89.76594324489959, 5),
            DcEtaProfileBin(77, 0.8302240004788211, 93.45897793978574, 5),
            DcEtaProfileBin(78, 0.8399908360939529, 93.74174462350338, 5),
            DcEtaProfileBin(79, 0.866254375307681, 93.82093042765224, 5)
        )
    }
}
