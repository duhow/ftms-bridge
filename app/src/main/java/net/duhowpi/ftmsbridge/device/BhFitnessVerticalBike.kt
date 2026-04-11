package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessVerticalBike(deviceName: String) :
    BhFitnessFtmsDevice(deviceName, FtmsConstants.MachineType.CROSS_TRAINER) {

    // BH Fitness vertical bikes (e.g. C01_XXXXX) advertise the FTMS Cross Trainer
    // characteristic (0x2ACE) instead of Indoor Bike (0x2AD2).  The iConcept
    // proprietary service (0xC100) is present and may carry workout counters on
    // 0xC112, similar to the BH Fitness treadmill.
    //
    // If the device sends zeros for elapsed time, distance, or calories in the FTMS
    // packet (as the treadmill does), the values cached from 0xC112 are used instead.
    //
    // Note: actual exercise data packets have not yet been captured for this device
    // model.  Quirk corrections will be refined once packet samples are available.
    @Volatile private var iConceptElapsedSec: Int = 0
    @Volatile private var iConceptDistanceM: Int = 0
    @Volatile private var iConceptCalories: Int = 0

    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        return sample.copy(
            elapsedTimeSec = if (sample.elapsedTimeSec == 0) iConceptElapsedSec else sample.elapsedTimeSec,
            totalDistanceM = if (sample.totalDistanceM == 0) iConceptDistanceM else sample.totalDistanceM,
            totalEnergyKcal = if (sample.totalEnergyKcal == 0) iConceptCalories else sample.totalEnergyKcal
        )
    }

    override fun onIConceptData(data: ByteArray) {
        val parsed = FtmsDataParser.parseIConceptWorkoutData(data) ?: return
        iConceptElapsedSec = parsed.elapsedTimeSec
        iConceptDistanceM = parsed.totalDistanceM
        iConceptCalories = parsed.totalEnergyKcal
    }
}
