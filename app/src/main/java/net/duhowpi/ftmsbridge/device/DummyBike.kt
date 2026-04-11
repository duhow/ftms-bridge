package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

/**
 * Virtual FTMS indoor bike used in debug builds for UI testing without a real device.
 * Values (cadence, resistance and power) can be manipulated at runtime.
 */
class DummyBike : FitnessDevice {

    override val deviceName: String = "Virtual Bike"
    override val machineType: FtmsConstants.MachineType = FtmsConstants.MachineType.INDOOR_BIKE
    override val capabilities: FtmsCapabilities? = null

    /** Current cadence in RPM. Writable for UI testing. */
    var cadenceRpm: Double = 75.0

    /** Current resistance level (1–22). Writable for UI testing. */
    var resistanceLevel: Int = 5

    private var elapsedSec: Int = 0
    private var distanceM: Double = 0.0
    private var energyKcal: Double = 0.0
    private val intervalSec: Int = 2

    // Approximate watts from cadence + resistance: torque × angular velocity.
    private fun estimatePower(): Int {
        val torqueNm = 2.0 + (resistanceLevel - 1) * 2.0
        val angularVelocity = cadenceRpm * 2.0 * kotlin.math.PI / 60.0
        return (torqueNm * angularVelocity).toInt().coerceAtLeast(0)
    }

    /**
     * Advances the internal state by [intervalSec] seconds and returns a [FitnessSample]
     * reflecting current cadence, resistance and derived power/speed.
     */
    fun generateSample(): FitnessSample {
        elapsedSec += intervalSec
        // Approximate speed from cadence (typical gear ratio + wheel size → ~0.25 km/h per RPM)
        val speedKmh = cadenceRpm * 0.25
        distanceM += speedKmh / 3.6 * intervalSec
        val powerW = estimatePower()
        val energyDeltaKcal = powerW.toDouble() * intervalSec / FitnessDevice.JOULES_PER_KCAL
        energyKcal += energyDeltaKcal
        return FitnessSample(
            timestampMs = System.currentTimeMillis(),
            elapsedTimeSec = elapsedSec,
            speedKmh = speedKmh,
            cadenceRpm = cadenceRpm,
            totalDistanceM = distanceM.toInt(),
            resistanceLevel = resistanceLevel,
            instantaneousPowerW = powerW,
            totalEnergyKcal = energyKcal.toInt()
        )
    }

    /**
     * Resets accumulated state (elapsed time, distance, energy) to zero.
     * Called by [MainActivity.startRecording] via [FitnessDevice.reset].
     */
    override fun reset() {
        elapsedSec = 0
        distanceM = 0.0
        energyKcal = 0.0
    }

    override fun isMoving(sample: FitnessSample): Boolean =
        sample.cadenceRpm > BhFitnessIndoorBike.MIN_MOVING_CADENCE_RPM ||
            sample.instantaneousPowerW > BhFitnessIndoorBike.MIN_MOVING_POWER_W

    override fun onFeaturesReceived(data: ByteArray) {}
    override fun onDataReceived(data: ByteArray): FitnessSample? = generateSample()
}
