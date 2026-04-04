package net.duhowpi.ftmsbridge.ftms

import java.util.UUID

object FtmsConstants {
    // Fitness Machine Service
    val FTMS_SERVICE_UUID: UUID = uuidFrom16Bit(0x1826)

    // Characteristics
    val FITNESS_MACHINE_FEATURE_UUID: UUID = uuidFrom16Bit(0x2ACC)
    val TREADMILL_DATA_UUID: UUID = uuidFrom16Bit(0x2ACD)
    val CROSS_TRAINER_DATA_UUID: UUID = uuidFrom16Bit(0x2ACE)
    val INDOOR_BIKE_DATA_UUID: UUID = uuidFrom16Bit(0x2AD2)
    val TRAINING_STATUS_UUID: UUID = uuidFrom16Bit(0x2AD3)
    val SUPPORTED_SPEED_RANGE_UUID: UUID = uuidFrom16Bit(0x2AD4)
    val SUPPORTED_INCLINATION_RANGE_UUID: UUID = uuidFrom16Bit(0x2AD5)
    val SUPPORTED_RESISTANCE_RANGE_UUID: UUID = uuidFrom16Bit(0x2AD6)
    val FITNESS_MACHINE_STATUS_UUID: UUID = uuidFrom16Bit(0x2AD7)
    val FITNESS_MACHINE_CONTROL_POINT_UUID: UUID = uuidFrom16Bit(0x2AD9)

    // Heart Rate Service
    val HR_SERVICE_UUID: UUID = uuidFrom16Bit(0x180D)
    val HR_MEASUREMENT_UUID: UUID = uuidFrom16Bit(0x2A37)

    // Device Information Service
    val DEVICE_INFO_SERVICE_UUID: UUID = uuidFrom16Bit(0x180A)
    val SERIAL_NUMBER_UUID: UUID = uuidFrom16Bit(0x2A25)
    val FIRMWARE_REVISION_UUID: UUID = uuidFrom16Bit(0x2A26)
    val HARDWARE_REVISION_UUID: UUID = uuidFrom16Bit(0x2A27)

    // CCCD for enabling notifications
    val CCCD_UUID: UUID = uuidFrom16Bit(0x2902)

    // Machine types detected from available characteristics
    enum class MachineType {
        TREADMILL,
        INDOOR_BIKE,
        CROSS_TRAINER,
        UNKNOWN
    }

    // Missing/invalid value sentinels per FTMS spec
    const val INVALID_UINT16 = 0xFFFF
    const val INVALID_SINT16 = 0x7FFF

    private fun uuidFrom16Bit(shortUuid: Int): UUID {
        return UUID.fromString(
            String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid)
        )
    }
}
