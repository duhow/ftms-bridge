package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import net.duhowpi.ftmsbridge.model.FitnessSample
import java.util.UUID

open class FtmsDevice(
    override val deviceName: String,
    override val machineType: FtmsConstants.MachineType
) : FitnessDevice {

    override var capabilities: FtmsCapabilities? = null
        protected set

    override fun onFeaturesReceived(data: ByteArray) {
        capabilities = FtmsCapabilities.fromBytes(data)
    }

    override fun onDataReceived(data: ByteArray): FitnessSample? {
        return when (machineType) {
            FtmsConstants.MachineType.TREADMILL -> FtmsDataParser.parseTreadmillData(data)
            FtmsConstants.MachineType.INDOOR_BIKE -> FtmsDataParser.parseIndoorBikeData(data)
            FtmsConstants.MachineType.CROSS_TRAINER -> FtmsDataParser.parseCrossTrainerData(data)
            FtmsConstants.MachineType.STAIR_CLIMBER -> FtmsDataParser.parseStairClimberData(data)
            else -> null
        }
    }

    /**
     * Returns a [Regex] that matches advertised device names supported by this device
     * class, or `null` if this class does not use name-based detection.
     *
     * Subclasses (e.g. [BhFitnessFtmsDevice]) override this to return their pattern.
     * The default implementation returns `null` (no name-based filter).
     */
    open fun getSupportedDeviceName(): Regex? = null

    /**
     * Returns `true` if the given advertised [name] (and optional [macAddress])
     * match this device class.
     *
     * Default: delegates to [getSupportedDeviceName]; returns `true` when the regex
     * finds a match anywhere in [name].
     *
     * TODO: MAC address prefix matching is not yet implemented; only [name] is used.
     */
    fun matchesDevice(name: String, macAddress: String? = null): Boolean =
        getSupportedDeviceName()?.containsMatchIn(name) == true

    companion object {
        fun createFromCharacteristics(
            deviceName: String,
            characteristics: List<UUID>
        ): FtmsDevice {
            val type = when {
                characteristics.contains(FtmsConstants.TREADMILL_DATA_UUID) ->
                    FtmsConstants.MachineType.TREADMILL
                characteristics.contains(FtmsConstants.INDOOR_BIKE_DATA_UUID) ->
                    FtmsConstants.MachineType.INDOOR_BIKE
                characteristics.contains(FtmsConstants.CROSS_TRAINER_DATA_UUID) ->
                    FtmsConstants.MachineType.CROSS_TRAINER
                characteristics.contains(FtmsConstants.STAIR_CLIMBER_DATA_UUID) ||
                characteristics.contains(FtmsConstants.STEP_CLIMBER_DATA_UUID) ->
                    FtmsConstants.MachineType.STAIR_CLIMBER
                else -> FtmsConstants.MachineType.UNKNOWN
            }

            return if (BhFitnessFtmsDevice.getSupportedDeviceName().containsMatchIn(deviceName)) {
                when (type) {
                    FtmsConstants.MachineType.TREADMILL -> BhFitnessTreadmill(deviceName)
                    FtmsConstants.MachineType.INDOOR_BIKE -> BhFitnessIndoorBike(deviceName)
                    FtmsConstants.MachineType.CROSS_TRAINER -> BhFitnessVerticalBike(deviceName)
                    else -> FtmsDevice(deviceName, type)
                }
            } else {
                FtmsDevice(deviceName, type)
            }
        }
    }
}
