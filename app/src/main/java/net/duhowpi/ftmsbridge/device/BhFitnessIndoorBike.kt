package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessIndoorBike(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.INDOOR_BIKE) {

    companion object {
        private const val MAX_VALID_SPEED_KMH = 80.0
        private const val MAX_VALID_CADENCE_RPM = 220.0
        private const val MAX_SPEED_STEP_PER_SEC = 20.0
        private const val MAX_CADENCE_STEP_PER_SEC = 80.0
    }

    @Volatile private var lastSampleTimestampMs: Long = 0L
    @Volatile private var cumulativeDistanceM: Double = 0.0
    @Volatile private var cumulativeEnergyKcal: Double = 0.0
    @Volatile private var lastAcceptedSpeedKmh: Double = 0.0
    @Volatile private var lastAcceptedCadenceRpm: Double = 0.0

    // BH Fitness indoor bikes repurpose several standard FTMS fields:
    //
    //  Speed field      — mirrors the same raw value as Total Energy.
    //                     Used as a synthetic speed signal: raw / 100 = km/h.
    //  Total Energy     — stride counter encoded as strides/min × 100 (same raw
    //                     value as speed field).  Convert to strides/min.
    //  Energy/hr        — always 0x5400 (21504 kcal/hr); garbage constant. Zero.
    //  Energy/min       — always 0; not meaningful.
    //  Metabolic Equiv  — always 0x7B (12.3 MET); constant, not a real reading. Zero.
    //  Distance         — always 0; derive cumulatively from synthetic speed + time.
    //  Total Energy     — derive cumulatively from power + time.
    //
    //  Reliable fields: cadenceRpm, instantaneousPowerW, heartRateBpm.
    //  Some sessions show occasional one-packet speed/cadence spikes. To avoid
    //  false dashboard/export jumps we clamp physically impossible values and
    //  reject abrupt single-step deltas relative to packet interval.
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        val nowMs = sample.timestampMs
        val dtSec = if (lastSampleTimestampMs > 0L) {
            ((nowMs - lastSampleTimestampMs).coerceAtLeast(0L)) / 1000.0
        } else {
            0.0
        }
        lastSampleTimestampMs = nowMs

        val rawSpeedKmh = sample.speedKmh
        val speedStepLimit = if (dtSec > 0.0) MAX_SPEED_STEP_PER_SEC * dtSec else Double.MAX_VALUE
        val speedDelta = kotlin.math.abs(rawSpeedKmh - lastAcceptedSpeedKmh)
        val syntheticSpeedKmh = when {
            rawSpeedKmh < 0.0 || rawSpeedKmh > MAX_VALID_SPEED_KMH -> lastAcceptedSpeedKmh
            dtSec > 0.0 && speedDelta > speedStepLimit -> lastAcceptedSpeedKmh
            else -> rawSpeedKmh
        }
        lastAcceptedSpeedKmh = syntheticSpeedKmh

        val rawCadenceRpm = sample.cadenceRpm
        val cadenceStepLimit = if (dtSec > 0.0) MAX_CADENCE_STEP_PER_SEC * dtSec else Double.MAX_VALUE
        val cadenceDelta = kotlin.math.abs(rawCadenceRpm - lastAcceptedCadenceRpm)
        val filteredCadenceRpm = when {
            rawCadenceRpm < 0.0 || rawCadenceRpm > MAX_VALID_CADENCE_RPM -> lastAcceptedCadenceRpm
            dtSec > 0.0 && cadenceDelta > cadenceStepLimit -> lastAcceptedCadenceRpm
            else -> rawCadenceRpm
        }
        lastAcceptedCadenceRpm = filteredCadenceRpm

        val strides = if (sample.totalEnergyKcal > 0) sample.totalEnergyKcal / 100.0 else 0.0

        if (dtSec > 0.0) {
            cumulativeDistanceM += syntheticSpeedKmh * (dtSec / 3600.0) * 1000.0
            val energyDeltaKcal = (sample.instantaneousPowerW.coerceAtLeast(0) * dtSec) / 4184.0
            cumulativeEnergyKcal += energyDeltaKcal
        }

        return sample.copy(
            speedKmh = syntheticSpeedKmh,
            averageSpeedKmh = 0.0,
            cadenceRpm = filteredCadenceRpm,
            totalDistanceM = cumulativeDistanceM.toInt(),
            stridesPerMin = strides,
            totalEnergyKcal = cumulativeEnergyKcal.toInt(),
            energyPerHourKcal = 0,
            energyPerMinuteKcal = 0,
            metabolicEquivalent = 0.0
        )
    }
}
