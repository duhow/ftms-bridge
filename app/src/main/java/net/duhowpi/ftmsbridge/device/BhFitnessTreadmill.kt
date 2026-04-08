package net.duhowpi.ftmsbridge.device

import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessTreadmill(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {

    companion object {
        private const val METERS_PER_KMH_PER_SEC = 1.0 / 3.6
        private const val DEFAULT_BODY_WEIGHT_KG = 75.0
        private const val WALK_RUN_THRESHOLD_KMH = 8.0
    }

    private val tag = "BhFitnessTreadmill"

    // Last values received from the iConcept 0xC112 workout-counter notification.
    // C112 fires only once at session start on this device model, so elapsed time,
    // distance, and energy continue from derived packet-delta estimates.
    @Volatile private var iConceptDistanceM: Int = 0
    @Volatile private var iConceptCalories: Int = 0
    @Volatile private var lastSampleTimestampMs: Long = 0L
    @Volatile private var hasWorkoutStartAnchor: Boolean = false
    @Volatile private var derivedElapsedSec: Double = 0.0
    @Volatile private var derivedDistanceM: Double = 0.0
    @Volatile private var derivedEnergyKcal: Double = 0.0

    // BH Fitness treadmills encode inclination with a non-standard scale relative to the
    // FTMS spec. The device sends 0–1000 for a physical range of 0%–16%, so the raw
    // value (in FTMS 0.1% units) is 6.25× larger than the actual gradient.
    // Factor: 100 (FTMS value at max) / 16 (physical % at max) = 6.25.
    //
    // The T01 model also supports a small decline range (-1% to -3%), but instead of
    // using negative INT16 values it sends POSITIVE raw values in [300, 550] that map to
    // negative grades via a different formula: actual = (raw - 500) / 62.5.
    //
    // Positive: raw 60/120/180/240 → +1/+2/+3/+4 %  (raw / 62.5)
    // Decline:  raw 450/380/320   → -1/-2/-3 %  ((raw - 500) / 62.5)
    //
    // Because the two encodings overlap in the same raw range (e.g. raw=450 could be
    // either +7.2% incline or -1% decline), we use the TRANSITION behaviour to resolve
    // the ambiguity: the machine always jumps in a single step from flat (raw=0) when
    // entering decline, whereas positive incline increases gradually (~60 per step).
    @Volatile private var rawInclinePrev: Int = 0
    @Volatile private var hasSeenFlat: Boolean = false
    @Volatile private var declineMode: Boolean = false

    private fun estimateEnergyDeltaKcal(speedKmh: Double, inclinePercent: Double, dtSec: Double): Double {
        if (dtSec <= 0.0 || speedKmh <= 0.0) return 0.0
        val speedMPerMin = speedKmh * 1000.0 / 60.0
        val grade = inclinePercent / 100.0
        val vo2MlKgMin = if (speedKmh < WALK_RUN_THRESHOLD_KMH) {
            // ACSM walking equation
            (0.1 * speedMPerMin) + (1.8 * speedMPerMin * grade) + 3.5
        } else {
            // ACSM running equation
            (0.2 * speedMPerMin) + (0.9 * speedMPerMin * grade) + 3.5
        }
        val kcalPerMin = (vo2MlKgMin * DEFAULT_BODY_WEIGHT_KG) / 200.0
        return kcalPerMin * (dtSec / 60.0)
    }

    // Elapsed time, distance, and calories are always 0 in the FTMS packet; the real
    // values come from the iConcept 0xC112 notification and are merged here.
    @Synchronized
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        val nowMs = sample.timestampMs
        val dtSec = if (lastSampleTimestampMs > 0L) {
            ((nowMs - lastSampleTimestampMs).coerceAtLeast(0L)) / 1000.0
        } else {
            0.0
        }
        lastSampleTimestampMs = nowMs

        if (dtSec > 0.0 && hasWorkoutStartAnchor) {
            derivedElapsedSec += dtSec
        }
        val elapsedSec = if (sample.elapsedTimeSec > 0) {
            derivedElapsedSec = kotlin.math.max(derivedElapsedSec, sample.elapsedTimeSec.toDouble())
            hasWorkoutStartAnchor = true
            sample.elapsedTimeSec
        } else {
            kotlin.math.round(derivedElapsedSec).toInt()
        }

        // sample.inclinationPercent = rawDevice * 0.1 (FTMS parse, before BH correction)
        // Recover the raw INT16 device value for transition detection.
        val rawInclination = (sample.inclinationPercent * 10).toInt()

        // Track whether we have seen the machine at flat (raw < 50) at least once.
        // This prevents misclassifying a high positive incline value seen at session
        // start (before the machine has returned to flat) as decline.
        if (rawInclination < 50) hasSeenFlat = true

        // Enter decline mode: raw is in the known decline range AND arrived as a large
        // single-step jump from flat (not a gradual positive-incline ramp-up).
        if (hasSeenFlat && rawInclination > 270 && rawInclinePrev < 50) {
            declineMode = true
        }
        // Exit decline mode when the machine returns to flat.
        if (rawInclination < 50) declineMode = false

        rawInclinePrev = rawInclination

        val correctedIncline = if (declineMode) {
            (rawInclination - 500) / 62.5
        } else {
            rawInclination / 62.5
        }

        if (dtSec > 0.0) {
            derivedDistanceM += sample.speedKmh * dtSec * METERS_PER_KMH_PER_SEC
            derivedEnergyKcal += estimateEnergyDeltaKcal(sample.speedKmh, correctedIncline, dtSec)
        }

        val distanceM = kotlin.math.round(derivedDistanceM).toInt()
        val energyKcal = kotlin.math.round(derivedEnergyKcal).toInt()

        Log.d(tag, "treadmill: speed=%.2f km/h incl=%.1f%% (raw=$rawInclination decline=$declineMode) elapsed=${elapsedSec}s dist=${distanceM}m kcal=${energyKcal}"
            .format(sample.speedKmh, correctedIncline))
        return sample.copy(
            inclinationPercent = correctedIncline,
            rampAngleDeg = sample.rampAngleDeg / 6.25,
            elapsedTimeSec = elapsedSec,
            totalDistanceM = distanceM,
            totalEnergyKcal = energyKcal
        )
    }

    /**
     * Resets the derived elapsed-time accumulator so that the timer reported in
     * subsequent samples starts from zero.  Called at recording start to align the
     * in-app timer with the user's activity start rather than the BLE connect time.
     */
    @Synchronized
    override fun resetElapsedTime() {
        derivedElapsedSec = 0.0
        lastSampleTimestampMs = 0L
        // Keep hasWorkoutStartAnchor = true so the timer resumes counting immediately
        // from the next FTMS packet without waiting for a new C112 signal (which will
        // not arrive again because this device only sends C112 once per session).
        hasWorkoutStartAnchor = true
    }

    override fun onIConceptData(data: ByteArray) {
        val parsed = FtmsDataParser.parseIConceptWorkoutData(data) ?: return
        iConceptDistanceM = parsed.totalDistanceM
        iConceptCalories = parsed.totalEnergyKcal
        if (parsed.elapsedTimeSec > 0) {
            derivedElapsedSec = kotlin.math.max(derivedElapsedSec, parsed.elapsedTimeSec.toDouble())
            hasWorkoutStartAnchor = true
        }
        // Use iConcept snapshot as lower-bound baseline if present, then continue
        // with derived progression because this device does not keep streaming counters.
        derivedDistanceM = kotlin.math.max(derivedDistanceM, parsed.totalDistanceM.toDouble())
        derivedEnergyKcal = kotlin.math.max(derivedEnergyKcal, parsed.totalEnergyKcal.toDouble())
        Log.d(tag, "iconcept C112: elapsed=${parsed.elapsedTimeSec}s dist=${parsed.totalDistanceM}m kcal=${parsed.totalEnergyKcal}")
    }
}
