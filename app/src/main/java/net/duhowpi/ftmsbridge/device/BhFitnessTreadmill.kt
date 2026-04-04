package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessTreadmill(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {

    // BH Fitness treadmills encode inclination with a non-standard scale relative to the
    // FTMS spec. The device sends 0–1000 for a physical range of 0%–16%, so the raw
    // value (in FTMS 0.1% units) is 6.25× larger than the actual gradient.
    // Factor: 100 (FTMS value at max) / 16 (physical % at max) = 6.25.
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        return sample.copy(
            inclinationPercent = sample.inclinationPercent / 6.25,
            rampAngleDeg = sample.rampAngleDeg / 6.25
        )
    }
}
