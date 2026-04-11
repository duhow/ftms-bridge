package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample
import kotlin.math.roundToInt

interface FitnessDevice {
    val deviceName: String
    val machineType: FtmsConstants.MachineType
    val capabilities: FtmsCapabilities?

    fun onFeaturesReceived(data: ByteArray)
    fun onDataReceived(data: ByteArray): FitnessSample?

    /**
     * Encodes a physical inclination percentage into the raw SINT16 value to send in a
     * Fitness Machine Control Point Set Target Inclination command (opcode 0x03).
     *
     * Default: standard FTMS encoding — SINT16 in units of 0.1 %.
     * Devices with non-standard scaling (e.g. BH Fitness) override this.
     */
    fun encodeTargetInclineRaw(physicalPercent: Double): Int =
        (physicalPercent * 10.0).roundToInt()

    /**
     * Resets internal elapsed-time accumulators so that the derived elapsed time
     * reported in subsequent samples starts from zero.  Called when a new recording
     * session begins so the in-app timer is anchored to the activity start, not to
     * the BLE connection time.
     *
     * Default implementation is a no-op; devices that derive elapsed time internally
     * (e.g. BH Fitness treadmill) should override this.
     */
    fun resetElapsedTime() {}

    /**
     * Returns `true` when the machine is actively moving based on the given [sample].
     *
     * Default: speed > 0.1 km/h.
     * Devices without a meaningful speed field (e.g. BH Fitness indoor bikes) override
     * this to use cadence or power instead.
     */
    fun isMoving(sample: FitnessSample): Boolean = sample.speedKmh > 0.1

    /**
     * Returns `true` if the device does not produce a meaningful speed metric and the
     * speed card should be hidden in the UI.
     *
     * Default: `false` — speed is shown for all standard FTMS devices.
     * Override to `true` for devices where the speed field is repurposed or absent
     * (e.g. BH Fitness indoor bikes).
     */
    fun hidesSpeed(): Boolean = false

    /**
     * Estimates the caloric energy expenditure over a time interval using ACSM
     * metabolic equations.
     *
     * Walking equation (speed < [walkRunThresholdKmh]):
     *   VO₂ (mL/kg/min) = 0.1·v + 1.8·v·grade + 3.5
     * Running equation (speed ≥ [walkRunThresholdKmh]):
     *   VO₂ (mL/kg/min) = 0.2·v + 0.9·v·grade + 3.5
     * where v = speed in m/min and grade = incline / 100.
     *
     * Energy (kcal) = VO₂ × bodyWeight / 200 × dtMin
     *
     * Returns 0 when [dtSec] ≤ 0 or [speedKmh] ≤ 0.
     */
    fun estimateEnergyDeltaKcal(
        speedKmh: Double,
        inclinePercent: Double,
        dtSec: Double,
        bodyWeightKg: Double = DEFAULT_BODY_WEIGHT_KG,
        walkRunThresholdKmh: Double = WALK_RUN_THRESHOLD_KMH
    ): Double {
        if (dtSec <= 0.0 || speedKmh <= 0.0) return 0.0
        val speedMPerMin = speedKmh * 1000.0 / 60.0
        val grade = inclinePercent / 100.0
        val vo2MlKgMin = if (speedKmh < walkRunThresholdKmh) {
            // ACSM walking equation
            (0.1 * speedMPerMin) + (1.8 * speedMPerMin * grade) + 3.5
        } else {
            // ACSM running equation
            (0.2 * speedMPerMin) + (0.9 * speedMPerMin * grade) + 3.5
        }
        val kcalPerMin = (vo2MlKgMin * bodyWeightKg) / 200.0
        return kcalPerMin * (dtSec / 60.0)
    }

    companion object {
        /** km/h → m/s conversion factor (1 km/h = 1/3.6 m/s). */
        const val METERS_PER_KMH_PER_SEC = 1.0 / 3.6

        /** Energy conversion factor: 1 kcal = 4184 J. */
        const val JOULES_PER_KCAL = 4184.0

        /** Default assumed body weight (kg) used in ACSM energy estimation. */
        const val DEFAULT_BODY_WEIGHT_KG = 75.0

        /** Speed threshold (km/h) between walking and running ACSM equations. */
        const val WALK_RUN_THRESHOLD_KMH = 8.0
    }
}
