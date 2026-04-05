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
        val correctedIncline = sample.inclinationPercent / 6.25
        Log.d(tag, "treadmill: speed=%.2f km/h incl=%.1f%% elapsed=${elapsedSec}s dist=${iConceptDistanceM}m kcal=${iConceptCalories}"
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
