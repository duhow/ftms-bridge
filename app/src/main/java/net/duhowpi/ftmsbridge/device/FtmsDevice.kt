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
                else -> FtmsConstants.MachineType.UNKNOWN
            }

            return if (FitnessDevice.isBhFitness(deviceName)) {
                when (type) {
                    FtmsConstants.MachineType.TREADMILL -> BhFitnessTreadmill(deviceName)
                    FtmsConstants.MachineType.INDOOR_BIKE -> BhFitnessIndoorBike(deviceName)
                    else -> FtmsDevice(deviceName, type)
                }
            } else {
                FtmsDevice(deviceName, type)
            }
        }
    }
}
