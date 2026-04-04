package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessTreadmill(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {

    // Last values received from the iConcept 0xC112 workout-counter notification.
    // These replace the zeros the device sends in the standard 0x2ACD FTMS packet.
    @Volatile private var iConceptElapsedSec: Int = 0
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
        return sample.copy(
            inclinationPercent = sample.inclinationPercent / 6.25,
            rampAngleDeg = sample.rampAngleDeg / 6.25,
            elapsedTimeSec = iConceptElapsedSec,
            totalDistanceM = iConceptDistanceM,
            totalEnergyKcal = iConceptCalories
        )
    }

    override fun onIConceptData(data: ByteArray) {
        val parsed = FtmsDataParser.parseIConceptWorkoutData(data) ?: return
        iConceptElapsedSec = parsed.elapsedTimeSec
        iConceptDistanceM = parsed.totalDistanceM
        iConceptCalories = parsed.totalEnergyKcal
    }
}
