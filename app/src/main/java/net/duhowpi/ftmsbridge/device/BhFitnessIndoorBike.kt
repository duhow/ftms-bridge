package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessIndoorBike(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.INDOOR_BIKE) {

    // BH Fitness indoor bikes repurpose several standard FTMS fields:
    //
    //  Speed field      — contains the same raw stride counter as Total Energy.
    //                     NOT a real km/h speed; must be zeroed.
    //  Total Energy     — stride counter encoded as strides/min × 100 (same raw
    //                     value as speed field).  Convert and clear.
    //  Energy/hr        — always 0x5400 (21504 kcal/hr); garbage constant. Zero.
    //  Energy/min       — always 0; not meaningful.
    //  Metabolic Equiv  — always 0x7B (12.3 MET); constant, not a real reading. Zero.
    //  Distance         — always 0; device does not report it. Left as-is.
    //
    //  Reliable fields: cadenceRpm, instantaneousPowerW, heartRateBpm.
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        val strides = if (sample.totalEnergyKcal > 0) sample.totalEnergyKcal / 100.0 else 0.0
        return sample.copy(
            speedKmh = 0.0,
            averageSpeedKmh = 0.0,
            stridesPerMin = strides,
            totalEnergyKcal = 0,
            energyPerHourKcal = 0,
            energyPerMinuteKcal = 0,
            metabolicEquivalent = 0.0
        )
    }
}
