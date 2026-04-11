package net.duhowpi.ftmsbridge.device

import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

class BhFitnessIndoorBike(deviceName: String) :
    BhFitnessFtmsDevice(deviceName, FtmsConstants.MachineType.INDOOR_BIKE) {

    companion object {
        private const val TAG = "BhFitnessIndoorBike"
        private const val MAX_VALID_SPEED_KMH = 80.0
        private const val MAX_VALID_CADENCE_RPM = 220.0
        private const val MAX_SPEED_STEP_PER_SEC = 20.0
        private const val MAX_CADENCE_STEP_PER_SEC = 80.0
        private const val STRIDES_ENCODING_FACTOR = 100.0
        // Empirical movement thresholds from B01_17384 packet captures.
        const val MIN_MOVING_POWER_W = 5
        const val MIN_MOVING_CADENCE_RPM = 10.0
        private const val MIN_LEVEL_TORQUE_NM = 2.0
        private const val LEVEL_TORQUE_STEP_NM = 2.0
        // Raw internal ceiling for torque-derived level values before UI display offset is applied.
        private const val MAX_LEVEL = 23
        // Typical cycling gross efficiency (~24%): metabolic work ≈ mechanical work / 0.24.
        private const val CYCLING_GROSS_EFFICIENCY = 0.24
        // Empirical BH-specific mapping between decoded strides/min and crank cadence RPM.
        private const val STRIDES_TO_CADENCE_FACTOR = 2.6
        private const val TWO_PI_RADIANS = kotlin.math.PI * 2.0
    }

    private fun stridesFromEnergy(energyField: Int): Double {
        return if (energyField > 0) energyField / STRIDES_ENCODING_FACTOR else 0.0
    }

    override fun isMoving(sample: FitnessSample): Boolean =
        sample.cadenceRpm > MIN_MOVING_CADENCE_RPM || sample.instantaneousPowerW > MIN_MOVING_POWER_W

    override fun getSupportedDeviceName(): Regex = Regex("^B01_[0-9A-Fa-f]{5}$")

    private var hasAcceptedBaseline: Boolean = false
    private var lastAcceptedSpeedKmh: Double = 0.0
    private var lastAcceptedCadenceRpm: Double = 0.0
    private var lastDerivedLevel: Int = 0

    private fun deriveResistanceLevel(cadenceRpm: Double, powerW: Int): Int {
        if (powerW < MIN_MOVING_POWER_W || cadenceRpm < MIN_MOVING_CADENCE_RPM) {
            lastDerivedLevel = 0
            return 0
        }
        val angularVelocityRadPerSec = cadenceRpm * TWO_PI_RADIANS / 60.0
        if (angularVelocityRadPerSec <= 0.0) {
            lastDerivedLevel = 0
            return 0
        }
        val torqueNm = powerW.toDouble() / angularVelocityRadPerSec
        // Empirical mapping for BH indoor-bike console levels:
        // level ~= round((torqueNm - 2.0) / 2.0) + 1, clamped to 1..23 while moving.
        val level = kotlin.math.round((torqueNm - MIN_LEVEL_TORQUE_NM) / LEVEL_TORQUE_STEP_NM).toInt() + 1
        val clampedLevel = level.coerceIn(1, MAX_LEVEL)
        lastDerivedLevel = clampedLevel
        return clampedLevel
    }

    private fun estimatePowerForEnergy(
        cadenceRpm: Double,
        resistanceLevel: Int,
        stridesPerMin: Double,
        reportedPowerW: Int
    ): Double {
        val nonNegativePowerW = reportedPowerW.coerceAtLeast(0).toDouble()
        if (nonNegativePowerW > 0.0) return nonNegativePowerW

        val fallbackLevel = when {
            resistanceLevel > 0 -> resistanceLevel
            lastDerivedLevel > 0 -> lastDerivedLevel
            else -> 0
        }
        if (fallbackLevel <= 0) return 0.0

        val cadenceForEstimate = when {
            cadenceRpm >= MIN_MOVING_CADENCE_RPM -> cadenceRpm
            stridesPerMin > 0.0 -> (stridesPerMin * STRIDES_TO_CADENCE_FACTOR)
                .coerceIn(0.0, MAX_VALID_CADENCE_RPM)
            else -> 0.0
        }
        if (cadenceForEstimate < MIN_MOVING_CADENCE_RPM) return 0.0

        val angularVelocityRadPerSec = cadenceForEstimate * TWO_PI_RADIANS / 60.0
        val fallbackTorqueNm = MIN_LEVEL_TORQUE_NM + (fallbackLevel - 1) * LEVEL_TORQUE_STEP_NM
        return (fallbackTorqueNm * angularVelocityRadPerSec).coerceAtLeast(0.0)
    }

    private fun estimateEnergyDeltaKcal(
        cadenceRpm: Double,
        resistanceLevel: Int,
        stridesPerMin: Double,
        reportedPowerW: Int,
        dtSec: Double
    ): Double {
        if (dtSec <= 0.0) return 0.0
        val powerForEnergyW = estimatePowerForEnergy(
            cadenceRpm = cadenceRpm,
            resistanceLevel = resistanceLevel,
            stridesPerMin = stridesPerMin,
            reportedPowerW = reportedPowerW
        )
        if (powerForEnergyW <= 0.0) return 0.0
        val mechanicalJoules = powerForEnergyW * dtSec
        val metabolicJoules = mechanicalJoules / CYCLING_GROSS_EFFICIENCY
        return metabolicJoules / FitnessDevice.JOULES_PER_KCAL
    }

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
    //  Resistance Level — FTMS resistance flag is absent in observed packets; derive
    //                     bike level (1..23 raw) from torque estimated via power+cadence.
    //                     UI applies an offset and treats 0 as unavailable, so users see 1..22.
    //
    //  Reliable fields: cadenceRpm, instantaneousPowerW, heartRateBpm.
    //  Some sessions show occasional one-packet speed/cadence spikes. To avoid
    //  false dashboard/export jumps we clamp physically impossible values and
    //  reject abrupt single-step deltas relative to packet interval.
    @Synchronized
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        val nowMs = sample.timestampMs
        // Log a warning for negative time deltas before advancing the shared timestamp.
        if (lastAccumulatorTimestampMs > 0L) {
            val rawDeltaMs = nowMs - lastAccumulatorTimestampMs
            if (rawDeltaMs < 0L) {
                val absDeltaSec = kotlin.math.abs(rawDeltaMs) / 1000.0
                Log.w(
                    TAG,
                    "Negative sample delta ${rawDeltaMs}ms (~${"%.3f".format(absDeltaSec)}s); treating as 0 (clock adjustment?)"
                )
            }
        }
        val dtSec = sampleDtSec(nowMs)

        val rawSpeedKmh = sample.speedKmh
        val rawCadenceRpm = sample.cadenceRpm
        if (!hasAcceptedBaseline) {
            val baselineSpeed = rawSpeedKmh.coerceIn(0.0, MAX_VALID_SPEED_KMH)
            val baselineCadence = rawCadenceRpm.coerceIn(0.0, MAX_VALID_CADENCE_RPM)
            hasAcceptedBaseline = true
            lastAcceptedSpeedKmh = baselineSpeed
            lastAcceptedCadenceRpm = baselineCadence
            val baselineStrides = stridesFromEnergy(sample.totalEnergyKcal)
            val baselineLevel = deriveResistanceLevel(baselineCadence, sample.instantaneousPowerW)
            return sample.copy(
                speedKmh = 0.0,
                averageSpeedKmh = 0.0,
                cadenceRpm = baselineCadence,
                totalDistanceM = 0,
                stridesPerMin = baselineStrides,
                totalEnergyKcal = 0,
                resistanceLevel = baselineLevel,
                energyPerHourKcal = 0,
                energyPerMinuteKcal = 0,
                metabolicEquivalent = 0.0
            )
        }

        val speedStepLimit = MAX_SPEED_STEP_PER_SEC * dtSec
        val speedDelta = kotlin.math.abs(rawSpeedKmh - lastAcceptedSpeedKmh)
        val syntheticSpeedKmh = when {
            rawSpeedKmh < 0.0 || rawSpeedKmh > MAX_VALID_SPEED_KMH -> lastAcceptedSpeedKmh
            dtSec > 0.0 && speedDelta > speedStepLimit -> lastAcceptedSpeedKmh
            else -> rawSpeedKmh
        }
        lastAcceptedSpeedKmh = syntheticSpeedKmh

        val cadenceStepLimit = MAX_CADENCE_STEP_PER_SEC * dtSec
        val cadenceDelta = kotlin.math.abs(rawCadenceRpm - lastAcceptedCadenceRpm)
        val filteredCadenceRpm = when {
            rawCadenceRpm < 0.0 || rawCadenceRpm > MAX_VALID_CADENCE_RPM -> lastAcceptedCadenceRpm
            dtSec > 0.0 && cadenceDelta > cadenceStepLimit -> lastAcceptedCadenceRpm
            else -> rawCadenceRpm
        }
        lastAcceptedCadenceRpm = filteredCadenceRpm

        val strides = stridesFromEnergy(sample.totalEnergyKcal)
        val derivedLevel = deriveResistanceLevel(filteredCadenceRpm, sample.instantaneousPowerW)

        if (dtSec > 0.0) {
            accumulateDistance(syntheticSpeedKmh, dtSec)
            // Prefer measured watts for kcal integration; fallback estimation is used only
            // when the bike omits instantaneous power in a packet.
            val energyDeltaKcal = estimateEnergyDeltaKcal(
                cadenceRpm = filteredCadenceRpm,
                resistanceLevel = derivedLevel,
                stridesPerMin = strides,
                reportedPowerW = sample.instantaneousPowerW,
                dtSec = dtSec
            )
            accumulateEnergyDelta(energyDeltaKcal)
        }

        return sample.copy(
            speedKmh = 0.0,
            averageSpeedKmh = 0.0,
            cadenceRpm = filteredCadenceRpm,
            totalDistanceM = getAccumulatedDistanceM(),
            stridesPerMin = strides,
            totalEnergyKcal = getAccumulatedEnergyKcal(),
            resistanceLevel = derivedLevel,
            energyPerHourKcal = 0,
            energyPerMinuteKcal = 0,
            metabolicEquivalent = 0.0
        )
    }
}
