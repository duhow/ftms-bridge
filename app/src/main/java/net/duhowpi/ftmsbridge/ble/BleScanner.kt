package net.duhowpi.ftmsbridge.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat

class BleScanner(private val context: Context) {
    private val tag = "BleScanner"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    var isScanning = false
        private set

    interface ScanListener {
        fun onDeviceFound(result: ScanResult)
        fun onScanFailed(errorCode: Int)
    }

    fun startScan(listener: ScanListener) {
        if (isScanning) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "Missing BLUETOOTH_SCAN permission")
            return
        }

        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(tag, "BLE scanner not available")
            return
        }

        // Scan without service UUID filters to also pick up devices like
        // Gadgetbridge HR proxy that may not include service UUIDs in advertisement
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (ActivityCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) return
                // Only report devices with a visible name to avoid noise
                val name = result.device.name
                if (!name.isNullOrBlank()) {
                    listener.onDeviceFound(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(tag, "Scan failed: $errorCode")
                isScanning = false
                listener.onScanFailed(errorCode)
            }
        }

        // Pass null for filters to scan all BLE devices
        scanner?.startScan(null, settings, scanCallback)
        isScanning = true
        Log.i(tag, "BLE scan started (unfiltered, named devices only)")
    }

    fun stopScan() {
        if (!isScanning) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
        isScanning = false
        Log.i(tag, "BLE scan stopped")
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Returns all bonded devices that could be BLE-capable.
     *
     * Android caches a bonded device's transport type only after an active connection has
     * been established.  Idle paired devices (e.g. a Mi Band managed by Gadgetbridge that
     * isn't currently advertising) often report [BluetoothDevice.DEVICE_TYPE_UNKNOWN], so
     * filtering strictly on [BluetoothDevice.DEVICE_TYPE_LE] / [BluetoothDevice.DEVICE_TYPE_DUAL]
     * would exclude them.  We therefore include UNKNOWN-type devices as well and rely on the
     * caller to apply class-based or name-based filtering to exclude non-fitness peripherals.
     */
    fun getBondedBleDevices(): List<BluetoothDevice> {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return bluetoothAdapter?.bondedDevices
            ?.filter {
                it.type == BluetoothDevice.DEVICE_TYPE_LE ||
                it.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                it.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
            }
            ?: emptyList()
    }
}
