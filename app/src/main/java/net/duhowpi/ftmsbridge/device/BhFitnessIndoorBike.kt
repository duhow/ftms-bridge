package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessIndoorBike(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.INDOOR_BIKE) {

    // BH Fitness indoor bikes repurpose the "Total Energy" field (normally kcal) to
    // encode strides/min × 100. Convert to proper strides/min and clear the energy
    // field so the UI does not display a misleading kcal value.
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        val strides = if (sample.totalEnergyKcal > 0) sample.totalEnergyKcal / 100.0 else 0.0
        return sample.copy(
            stridesPerMin = strides,
            totalEnergyKcal = 0
        )
    }
}
