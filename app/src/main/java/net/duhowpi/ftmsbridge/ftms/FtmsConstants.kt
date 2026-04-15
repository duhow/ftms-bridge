package net.duhowpi.ftmsbridge.ftms

import java.util.UUID

object FtmsConstants {
    // Fitness Machine Service
    val FTMS_SERVICE_UUID: UUID = uuidFrom16Bit(0x1826)

    // Characteristics
    val FITNESS_MACHINE_FEATURE_UUID: UUID = uuidFrom16Bit(0x2ACC)
    val TREADMILL_DATA_UUID: UUID = uuidFrom16Bit(0x2ACD)
    val CROSS_TRAINER_DATA_UUID: UUID = uuidFrom16Bit(0x2ACE)
    val STEP_CLIMBER_DATA_UUID: UUID = uuidFrom16Bit(0x2AD0)
    val STAIR_CLIMBER_DATA_UUID: UUID = uuidFrom16Bit(0x2AD1)
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
    val HR_CONTROL_POINT_UUID: UUID = uuidFrom16Bit(0x2A39)

    // Mi Band proprietary service (fee0): used by Mi Band 3 and similar devices that
    // advertise this UUID instead of 0x180D.  The HR measurement characteristic
    // (0x2A37) lives under this service on those devices.
    val MIBAND_HR_SERVICE_UUID: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")

    // Device Information Service
    val DEVICE_INFO_SERVICE_UUID: UUID = uuidFrom16Bit(0x180A)
    val SERIAL_NUMBER_UUID: UUID = uuidFrom16Bit(0x2A25)
    val FIRMWARE_REVISION_UUID: UUID = uuidFrom16Bit(0x2A26)
    val HARDWARE_REVISION_UUID: UUID = uuidFrom16Bit(0x2A27)

    // CCCD for enabling notifications
    val CCCD_UUID: UUID = uuidFrom16Bit(0x2902)

    // iConcept 3.0 / BH Fitness proprietary service (vendor extension to FTMS)
    val ICONCEPT_SERVICE_UUID: UUID = UUID.fromString("0000c100-0000-1000-8000-00805f9b34fb")
    // Write-only (WRITE_NO_RESPONSE, props=8) channels — candidates for proprietary speed/incline commands
    val ICONCEPT_WRITE_1_UUID: UUID = UUID.fromString("0000c101-0000-1000-8000-00805f9b34fb")
    val ICONCEPT_WRITE_2_UUID: UUID = UUID.fromString("0000c102-0000-1000-8000-00805f9b34fb")
    val ICONCEPT_NOTIFY_1_UUID: UUID = UUID.fromString("0000c111-0000-1000-8000-00805f9b34fb")
    val ICONCEPT_NOTIFY_2_UUID: UUID = UUID.fromString("0000c112-0000-1000-8000-00805f9b34fb")

    // Fitness Machine Status op-codes (0x2AD7 characteristic)
    const val MACHINE_STATUS_RESET = 0x00
    const val MACHINE_STATUS_STOPPED_OR_PAUSED = 0x01   // param: 0x01=stop, 0x02=pause
    const val MACHINE_STATUS_STOPPED_BY_SAFETY_KEY = 0x02
    const val MACHINE_STATUS_STARTED_OR_RESUMED = 0x03
    const val MACHINE_STATUS_CONTROL_PERMISSION_LOST = 0xFF

    // Fitness Machine Control Point op-codes (0x2AD9 characteristic)
    const val CONTROL_REQUEST_CONTROL: Byte = 0x00
    const val CONTROL_RESET: Byte = 0x01
    const val CONTROL_SET_TARGET_SPEED: Byte = 0x02
    const val CONTROL_SET_TARGET_INCLINATION: Byte = 0x03
    const val CONTROL_SET_TARGET_RESISTANCE_LEVEL: Byte = 0x04
    const val RESISTANCE_LEVEL_MULTIPLIER = 10.0
    const val INDOOR_BIKE_RESISTANCE_COMMAND_OFFSET = 0
    const val CONTROL_START_OR_RESUME: Byte = 0x07
    const val CONTROL_STOP_OR_PAUSE: Byte = 0x08
    const val CONTROL_RESPONSE_CODE = 0x80

    // Machine types detected from available characteristics
    enum class MachineType {
        TREADMILL,
        INDOOR_BIKE,
        CROSS_TRAINER,
        STAIR_CLIMBER,
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
