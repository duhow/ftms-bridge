package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessTreadmill(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {

    // BH Fitness treadmills encode inclination with a factor of 6× relative to the
    // FTMS spec (raw uint16 × 0.1 gives a value 6× larger than the actual grade).
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        return sample.copy(
            inclinationPercent = sample.inclinationPercent / 6.0,
            rampAngleDeg = sample.rampAngleDeg / 6.0
        )
    }
}
