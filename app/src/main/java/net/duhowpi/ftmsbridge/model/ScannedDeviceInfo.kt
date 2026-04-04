package net.duhowpi.ftmsbridge.model

import android.bluetooth.BluetoothDevice

data class ScannedDeviceInfo(
    val name: String,
    val address: String,
    var rssi: Int,
    val isFtms: Boolean,
    val isHr: Boolean,
    val device: BluetoothDevice
) {
    val typeLabel: String get() = when {
        isFtms && isHr -> "FTMS+HR"
        isFtms -> "FTMS"
        isHr -> "HR"
        else -> "BLE"
    }

    val rssiLabel: String get() = "$rssi dBm"

    val signalBars: String get() = when {
        rssi >= -60 -> "●●●●"
        rssi >= -70 -> "●●●○"
        rssi >= -80 -> "●●○○"
        else -> "●○○○"
    }
}
