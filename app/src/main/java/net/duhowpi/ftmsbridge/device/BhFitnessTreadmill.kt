package net.duhowpi.ftmsbridge.device

import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample
import kotlin.math.abs
import kotlin.math.roundToInt

class BhFitnessTreadmill(deviceName: String) :
    BhFitnessFtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {

    private val tag = "BhFitnessTreadmill"
    // BH treadmills advertise names like T01_07D57 (5 hex chars after underscore).
    override fun getSupportedDeviceName(): Regex = Regex("^T01_[0-9A-Fa-f]{5}$")
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
     */
    override fun encodeTargetInclineRaw(incline: Double): Short {
        val rounded = incline.roundToInt()
        if (rounded in DECLINE_RAW.keys){ return DECLINE_RAW[rounded]!!.toShort() }
        return (incline * INCLINE_SCALE).roundToInt().toShort()
    }

    override fun getIncline(sample: FitnessSample): Double {
        val incline = sample.inclinationPercent
        val decline = DECLINE_RAW.entries.find { it.value == incline.roundToInt() }
        if (decline != null) {
            return decline.key.toDouble()
        }

        return (incline / INCLINE_SCALE).roundToInt().toDouble()
    }

    companion object {
        /** BH Fitness treadmill inclination scale factor: raw / 62.5 = physical %. */
        const val INCLINE_SCALE = 62.5

        /** Tolerance (in incline %) used to treat near-integer values as whole-percent steps. */
        private const val PERCENT_ROUNDING_TOLERANCE = 0.05

        /** Physical incline range for BH treadmill UI percentages. */
        const val INCLINE_STEP = 1.0
        const val INCLINE_PHYSICAL_MIN_PERCENT = -3.0
        const val INCLINE_PHYSICAL_MAX_PERCENT = 16.0

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
