package net.duhowpi.ftmsbridge.device

import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessTreadmill(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {

    private val tag = "BhFitnessTreadmill"

    // Last values received from the iConcept 0xC112 workout-counter notification.
    // These replace the zeros the device sends in the standard 0x2ACD FTMS packet.
    //
    // C112 fires only once at session start with the initial elapsed/distance/energy
    // snapshot (typically elapsed=2, distance=0, calories=0). It does NOT update during
    // the workout. Elapsed time is therefore advanced via wall-clock delta since the
    // last C112 receipt; distance and energy remain as reported by the device.
    @Volatile private var iConceptBaseElapsedSec: Int = 0
    @Volatile private var iConceptLastUpdateMs: Long = 0L
    @Volatile private var iConceptDistanceM: Int = 0
    @Volatile private var iConceptCalories: Int = 0

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

    // Elapsed time, distance, and calories are always 0 in the FTMS packet; the real
    // values come from the iConcept 0xC112 notification and are merged here.
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        val elapsedSec = if (iConceptLastUpdateMs > 0) {
            iConceptBaseElapsedSec +
                ((System.currentTimeMillis() - iConceptLastUpdateMs) / 1000).toInt()
        } else {
            iConceptBaseElapsedSec
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
        Log.d(tag, "treadmill: speed=%.2f km/h incl=%.1f%% (raw=$rawInclination decline=$declineMode) elapsed=${elapsedSec}s dist=${iConceptDistanceM}m kcal=${iConceptCalories}"
            .format(sample.speedKmh, correctedIncline))
        return sample.copy(
            inclinationPercent = correctedIncline,
            rampAngleDeg = sample.rampAngleDeg / 6.25,
            elapsedTimeSec = elapsedSec,
            totalDistanceM = iConceptDistanceM,
            totalEnergyKcal = iConceptCalories
        )
    }

    override fun onIConceptData(data: ByteArray) {
        val parsed = FtmsDataParser.parseIConceptWorkoutData(data) ?: return
        iConceptBaseElapsedSec = parsed.elapsedTimeSec
        iConceptLastUpdateMs = System.currentTimeMillis()
        iConceptDistanceM = parsed.totalDistanceM
        iConceptCalories = parsed.totalEnergyKcal
        Log.d(tag, "iconcept C112: elapsed=${parsed.elapsedTimeSec}s dist=${parsed.totalDistanceM}m kcal=${parsed.totalEnergyKcal}")
    }
}
