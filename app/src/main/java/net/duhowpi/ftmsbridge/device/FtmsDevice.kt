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

            return if (FitnessDevice.isBhFitness(deviceName)) {
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
