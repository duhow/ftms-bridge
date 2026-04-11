package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

/**
 * Virtual FTMS treadmill used in debug builds for UI testing without a real device.
 * Values (speed and incline) can be manipulated at runtime.
 */
class DummyTreadmill : FitnessDevice {

    override val deviceName: String = "Virtual Treadmill"
    override val machineType: FtmsConstants.MachineType = FtmsConstants.MachineType.TREADMILL
    override val capabilities: FtmsCapabilities? = null

    /** Current speed in km/h. Writable for UI testing. */
    var speedKmh: Double = 8.0

    /** Current inclination in percent. Writable for UI testing. */
    var inclinationPercent: Double = 0.0

    private var elapsedSec: Int = 0
    private var distanceM: Double = 0.0
    private var energyKcal: Double = 0.0
    private val intervalSec: Int = 2

    /**
     * Advances the internal state by [intervalSec] seconds and returns a [FitnessSample]
     * reflecting current speed and inclination.
     */
    fun generateSample(): FitnessSample {
        elapsedSec += intervalSec
        distanceM += speedKmh / 3.6 * intervalSec
        energyKcal += speedKmh / 3.6 * intervalSec * assumedBodyWeightKg * energyConversionFactor
        return FitnessSample(
            timestampMs = System.currentTimeMillis(),
            elapsedTimeSec = elapsedSec,
            speedKmh = speedKmh,
            totalDistanceM = distanceM.toInt(),
            totalEnergyKcal = energyKcal.toInt(),
            inclinationPercent = inclinationPercent
        )
    }

    /** Assumed user body weight (kg) for energy estimation. */
    private val assumedBodyWeightKg: Double = 70.0

    /** MET-based energy conversion factor (kcal per kg per meter). */
    private val energyConversionFactor: Double = 0.001

    /**
     * Resets accumulated state (elapsed time, distance, energy) to zero.
     * Called by [MainActivity.startRecording] via [FitnessDevice.reset].
     */
    override fun reset() {
        elapsedSec = 0
        distanceM = 0.0
        energyKcal = 0.0
    }

    override fun onFeaturesReceived(data: ByteArray) {}
    override fun onDataReceived(data: ByteArray): FitnessSample? = generateSample()
}
