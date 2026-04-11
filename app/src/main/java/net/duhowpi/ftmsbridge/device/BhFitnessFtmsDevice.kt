package net.duhowpi.ftmsbridge.device

import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Abstract base class for all BH Fitness FTMS devices.
 *
 * Centralises BH Fitness / iConcept-specific detection logic and the iConcept
 * proprietary packet parser so that concrete device classes ([BhFitnessTreadmill],
 * [BhFitnessIndoorBike], [BhFitnessVerticalBike]) do not need to redeclare them.
 */
abstract class BhFitnessFtmsDevice(
    deviceName: String,
    machineType: FtmsConstants.MachineType
) : FtmsDevice(deviceName, machineType) {

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
