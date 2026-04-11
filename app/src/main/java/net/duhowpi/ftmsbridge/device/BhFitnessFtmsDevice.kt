package net.duhowpi.ftmsbridge.device

import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Abstract base class for all BH Fitness FTMS devices.
 *
 * Centralises BH Fitness / iConcept-specific detection logic, the iConcept
 * proprietary packet parser, and the common accumulated-metric helpers
 * (distance derived from speed×time, energy from power or ACSM equations) so
 * that concrete device classes ([BhFitnessTreadmill], [BhFitnessIndoorBike],
 * [BhFitnessVerticalBike]) do not need to redeclare them.
 */
abstract class BhFitnessFtmsDevice(
    deviceName: String,
    machineType: FtmsConstants.MachineType
) : FtmsDevice(deviceName, machineType) {

    // -------------------------------------------------------------------------
    // Accumulated-metric state
    //
    // BH Fitness devices typically send 0 for totalDistanceM and totalEnergyKcal
    // in their FTMS packets.  These fields are derived here from speed × time
    // (distance) and the ACSM / power equation (energy).
    // -------------------------------------------------------------------------

    @Volatile protected var accumulatedDistanceM: Double = 0.0
    @Volatile protected var accumulatedEnergyKcal: Double = 0.0
    @Volatile protected var lastAccumulatorTimestampMs: Long = 0L

    /**
     * Computes the elapsed time (in seconds) since [lastAccumulatorTimestampMs] and
     * advances the timestamp to [nowMs].  Returns 0 on the very first call or when
     * the clock goes backwards.
     */
    protected fun sampleDtSec(nowMs: Long): Double {
        val dt = if (lastAccumulatorTimestampMs > 0L)
            (nowMs - lastAccumulatorTimestampMs).coerceAtLeast(0L) / 1000.0
        else 0.0
        lastAccumulatorTimestampMs = nowMs
        return dt
    }

    /**
     * Adds the distance covered at [speedKmh] over [dtSec] seconds to the running total.
     * Uses [FitnessDevice.computeDistanceDeltaM] so the computation is consistent across
     * all devices.
     */
    protected fun accumulateDistance(speedKmh: Double, dtSec: Double) {
        accumulatedDistanceM += computeDistanceDeltaM(speedKmh, dtSec)
    }

    /**
     * Adds the energy (kcal) estimated by the ACSM treadmill equations for [speedKmh] /
     * [inclinePercent] over [dtSec] seconds.  Uses [FitnessDevice.estimateEnergyDeltaKcal].
     *
     * Devices whose energy comes from a different source (e.g. measured power on an indoor
     * bike) should call [accumulateEnergyDelta] with their own computed delta instead.
     */
    protected fun accumulateEnergy(speedKmh: Double, inclinePercent: Double, dtSec: Double) {
        accumulatedEnergyKcal += estimateEnergyDeltaKcal(speedKmh, inclinePercent, dtSec)
    }

    /**
     * Adds a pre-computed energy delta (kcal) directly to the running total.
     * Use this when the energy calculation differs from the ACSM treadmill equations
     * (e.g. power-based estimation on an indoor bike).
     */
    protected fun accumulateEnergyDelta(deltaKcal: Double) {
        accumulatedEnergyKcal += deltaKcal
    }

    /** Returns the rounded accumulated distance in metres. */
    fun getAccumulatedDistanceM(): Int = kotlin.math.round(accumulatedDistanceM).toInt()

    /** Returns the rounded accumulated energy in kcal. */
    fun getAccumulatedEnergyKcal(): Int = kotlin.math.round(accumulatedEnergyKcal).toInt()

    /**
     * Resets the accumulated distance, energy, and the inter-sample timestamp to zero.
     * Subclasses that track additional per-session state should call this method from
     * their own [reset] override rather than overriding it.
     */
    fun resetAccumulators() {
        accumulatedDistanceM = 0.0
        accumulatedEnergyKcal = 0.0
        lastAccumulatorTimestampMs = 0L
    }

    /** Called when a BH Fitness iConcept proprietary notification arrives. */
    open fun onIConceptData(data: ByteArray) {}

    /**
     * Parses the BH Fitness iConcept proprietary 0xC112 notification packet.
     *
     * The packet header is [F1 0D] for a workout-counter message.
     * Layout (all little-endian):
     *   [0]    F1  – header
     *   [1]    0D  – sub-type (workout data)
     *   [2-3]  UINT16  elapsed time (seconds)
     *   [4-6]  UINT24  total distance (metres)
     *   [7-8]  UINT16  total energy (kcal); 0xFFFF = not available
     *
     * Returns null if the data does not match the expected header or is too short.
     */
    protected fun parseIConceptWorkoutData(data: ByteArray): FitnessSample? {
        if (data.size < 9) return null
        if ((data[0].toInt() and 0xFF) != 0xF1 || (data[1].toInt() and 0xFF) != 0x0D) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(2)
        val elapsedTimeSec = buf.short.toInt() and 0xFFFF
        val b0 = buf.get().toInt() and 0xFF
        val b1 = buf.get().toInt() and 0xFF
        val b2 = buf.get().toInt() and 0xFF
        val distanceM = b0 or (b1 shl 8) or (b2 shl 16)
        val rawCalories = buf.short.toInt() and 0xFFFF
        val calories = if (rawCalories == FtmsConstants.INVALID_UINT16) 0 else rawCalories
        Log.d(TAG, "iConcept C112: elapsed=${elapsedTimeSec}s dist=${distanceM}m kcal=${calories}")
        return FitnessSample(elapsedTimeSec = elapsedTimeSec, totalDistanceM = distanceM, totalEnergyKcal = calories)
    }

    companion object {
        private const val TAG = "BhFitnessFtmsDevice"
    }
}
