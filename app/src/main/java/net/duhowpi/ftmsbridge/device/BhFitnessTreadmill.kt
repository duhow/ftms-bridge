package net.duhowpi.ftmsbridge.device

import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample
import kotlin.math.roundToInt

class BhFitnessTreadmill(deviceName: String) :
    BhFitnessFtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {

    private val tag = "BhFitnessTreadmill"
    override fun getSupportedDeviceName(): Regex = Regex("^T01_\\d{5}$")
    override val shouldCalculateDistanceInApp: Boolean = true
    override val shouldCalculateEnergyInApp: Boolean = true

    // Treadmill-specific session accumulators.
    // Distance and energy are tracked via the shared BhFitnessFtmsDevice accumulators.
    // Elapsed time requires its own anchor because C112 fires only once at session start.
    @Volatile private var hasWorkoutStartAnchor: Boolean = false
    @Volatile private var derivedElapsedSec: Double = 0.0

    // Elapsed time, distance, and calories are always 0 in the FTMS packet; the real
    // values come from the iConcept 0xC112 notification and are merged here.
    @Synchronized
    override fun onDataReceived(data: ByteArray): FitnessSample? {
        val sample = super.onDataReceived(data) ?: return null
        val dtSec = sampleDtSec(sample.timestampMs)

        if (dtSec > 0.0 && hasWorkoutStartAnchor) {
            derivedElapsedSec += dtSec
        }
        val elapsedSec = if (sample.elapsedTimeSec > 0) {
            derivedElapsedSec = kotlin.math.max(derivedElapsedSec, sample.elapsedTimeSec.toDouble())
            hasWorkoutStartAnchor = true
            sample.elapsedTimeSec
        } else {
            kotlin.math.round(derivedElapsedSec).toInt()
        }

        val correctedIncline = getIncline(sample)

        if (dtSec > 0.0) {
            accumulateDistance(sample.speedKmh, dtSec)
            accumulateEnergy(sample.speedKmh, correctedIncline, dtSec)
        }

        val distanceM = getAccumulatedDistanceM()
        val energyKcal = getAccumulatedEnergyKcal()

        Log.d(tag, "treadmill: speed=%.2f km/h incl=%.1f%% elapsed=${elapsedSec}s dist=${distanceM}m kcal=${energyKcal}"
            .format(sample.speedKmh, correctedIncline))
        return sample.copy(
            inclinationPercent = correctedIncline,
            rampAngleDeg = sample.rampAngleDeg / (INCLINE_SCALE / 10.0),
            elapsedTimeSec = elapsedSec,
            totalDistanceM = distanceM,
            totalEnergyKcal = energyKcal
        )
    }

    /**
     * Encodes a physical inclination percentage to the raw SINT16 value expected by
     * BH Fitness treadmills in the FTMS Control Point Set Target Inclination command.
     *
     * Delegates to [toRawIncline] which handles both positive (standard x10) and
     * decline (hardcoded DECLINE_RAW map) cases.
     */
    override fun encodeTargetInclineRaw(physicalPercent: Double): Int =
        toRawIncline(physicalPercent)

    override fun getDisplayIncline(sample: FitnessSample): Double = sample.inclinationPercent

    fun getIncline(sample: FitnessSample): Double {
        // Recover the raw INT16 device value (FTMS parses inclinationPercent = rawDevice * 0.1).
        val rawInclination = (sample.inclinationPercent * 10).toInt()

        // If the raw value matches one of the known decline codes, return the exact physical
        // percent from the map key (avoids floating-point imprecision of the inverse formula).
        val declineEntry = DECLINE_RAW.entries.find { it.value == rawInclination }
        if (declineEntry != null) {
            return declineEntry.key.toDouble()
        }
        return rawInclination / INCLINE_SCALE
    }

    /**
     * Encodes a physical inclination percentage to the raw SINT16 value expected by
     * BH Fitness treadmills in the FTMS Control Point Set Target Inclination command.
     *
     * Positive incline uses standard FTMS encoding (0.1 % units):
     *   raw = physicalPercent * 10   → +1 % = 10, +5 % = 50
     *
     * Decline uses hardcoded values that exactly match what the device reports in
     * its own Treadmill Data notifications (confirmed from captured device logs):
     *   -1 % → 450  (device reads back (450-500)/62.5 = -0.8 % → displays -1 %)
     *   -2 % → 380  (device reads back (380-500)/62.5 = -1.9 % → displays -2 %)
     *   -3 % → 320  (device reads back (320-500)/62.5 = -2.9 % → displays -3 %)
     *
     * The inverse formula (pct × 62.5 + 500) gives 438/375/313, which are NOT the
     * values the device firmware expects — static values are required.
     */
    fun toRawIncline(incline: Double): Int {
        if (incline < 0) {
            // Use explicit mapping for the discrete decline steps the device expects.
            // Map keys: -3 -> 320, -2 -> 380, -1 -> 450.
            val key = incline.roundToInt().coerceIn(-3, -1)
            return DECLINE_RAW[key] ?: 450
        }
        return (incline * INCLINE_POSITIVE_SCALE).roundToInt()
    }

    companion object {
        /** BH Fitness treadmill inclination scale factor: raw / 62.5 = physical %. */
        const val INCLINE_SCALE = 62.5

        /** Standard FTMS positive-inclination encoding: raw = physicalPercent * 10. */
        const val INCLINE_POSITIVE_SCALE = 10.0

        /** Mapping from negative physical percent to device raw value required by firmware. */
        val DECLINE_RAW: Map<Int, Int> = mapOf(
            -3 to 320,
            -2 to 380,
            -1 to 450
        )
    }

    /**
     * Resets all session accumulators so the timer and counters start from zero on the
     * next recording.  Called at recording start to align the in-app timer with the
     * user's activity start rather than the BLE connect time.
     */
    @Synchronized
    override fun reset() {
        derivedElapsedSec = 0.0
        // Keep hasWorkoutStartAnchor = true so the timer resumes counting immediately
        // from the next FTMS packet without waiting for a new C112 signal (which will
        // not arrive again because this device only sends C112 once per session).
        hasWorkoutStartAnchor = true
        resetAccumulators()
    }

    override fun onIConceptData(data: ByteArray) {
        val parsed = parseIConceptWorkoutData(data) ?: return
        if (parsed.elapsedTimeSec > 0) {
            derivedElapsedSec = kotlin.math.max(derivedElapsedSec, parsed.elapsedTimeSec.toDouble())
            hasWorkoutStartAnchor = true
        }
        // Use iConcept snapshot as lower-bound baseline if present, then continue
        // with derived progression because this device does not keep streaming counters.
        accumulatedDistanceM = kotlin.math.max(accumulatedDistanceM, parsed.totalDistanceM.toDouble())
        accumulatedEnergyKcal = kotlin.math.max(accumulatedEnergyKcal, parsed.totalEnergyKcal.toDouble())
    }
}
