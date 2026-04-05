package net.duhowpi.ftmsbridge.model

import android.bluetooth.BluetoothDevice

data class ScannedDeviceInfo(
    val name: String,
    val address: String,
    var rssi: Int,
    val isFtms: Boolean,
    val isHr: Boolean,
    val device: BluetoothDevice,
    /** True when the device was found via the bonded-devices list rather than an active BLE scan. */
    val isBonded: Boolean = false,
    val machineType: String? = null
) {
    val typeLabel: String get() = when {
        isFtms && isHr -> when (machineType) {
            "TREADMILL" -> "🏃 FTMS+HR"
            "INDOOR_BIKE" -> "🚴 FTMS+HR"
            "CROSS_TRAINER" -> "🏋️ FTMS+HR"
            "STAIR_CLIMBER" -> "🪜 FTMS+HR"
            else -> "🏋️ FTMS+HR"
        }
        isFtms -> when (machineType) {
            "TREADMILL" -> "🏃 FTMS"
            "INDOOR_BIKE" -> "🚴 FTMS"
            "CROSS_TRAINER" -> "🏋️ FTMS"
            "STAIR_CLIMBER" -> "🪜 FTMS"
            else -> "🏋️ FTMS"
        }
        isHr -> "HR"
        isBonded -> "Paired"
        else -> "BLE"
    }

    val rssiLabel: String get() = when {
        rssi != Int.MIN_VALUE -> "$rssi dBm"
        isBonded -> "Paired"
        else -> ""
    }

    val signalBars: String get() = when {
        rssi >= -60 -> "●●●●"
        rssi >= -70 -> "●●●○"
        rssi >= -80 -> "●●○○"
        rssi > Int.MIN_VALUE -> "●○○○"
        isBonded -> ""
        else -> "○○○○"
    }
}
