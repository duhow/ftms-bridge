package net.duhowpi.ftmsbridge.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import java.util.LinkedList
import java.util.UUID

class BleConnectionManager(
    private val context: Context,
    private val debugLogger: BtDebugLogger
) {
    private val tag = "BleConnectionManager"
    private var gatt: BluetoothGatt? = null
    private var listener: ConnectionListener? = null
    private val pendingDescriptorWrites = LinkedList<BluetoothGattDescriptor>()
    private var writingDescriptor = false

    var isConnected = false
        private set

    var connectedDeviceName: String? = null
        private set

    var connectedDeviceAddress: String? = null
        private set

    val recentEvents = ArrayDeque<String>()

    interface ConnectionListener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onServicesReady(ftmsCharacteristics: List<UUID>, hasHeartRate: Boolean)
        fun onFtmsData(uuid: UUID, data: ByteArray)
        fun onHeartRateData(data: ByteArray)
        fun onFeaturesRead(data: ByteArray)
    }

    fun connect(device: BluetoothDevice, connectionListener: ConnectionListener) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        this.listener = connectionListener
        val name = device.name ?: "Unknown"
        connectedDeviceName = name
        connectedDeviceAddress = device.address
        recentEvents.clear()
        debugLogger.startSession(name, device.address)
        debugLogger.logMessage("Connecting to $name (${device.address})")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
        connectedDeviceName = null
        connectedDeviceAddress = null
        recentEvents.clear()
        debugLogger.stopSession()
        listener?.onDisconnected()
    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val g = gatt ?: return
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(FtmsConstants.CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            pendingDescriptorWrites.add(descriptor)
            writeNextDescriptor()
        }
    }

    private fun addRecentEvent(direction: String, uuid: String, data: ByteArray) {
        val hex = data.take(20).joinToString(" ") { String.format("%02X", it) }
        val suffix = if (data.size > 20) "... (${data.size}B)" else " (${data.size}B)"
        val entry = "$direction ${uuid.takeLast(4)}: $hex$suffix"
        synchronized(recentEvents) {
            if (recentEvents.size >= 30) recentEvents.removeFirst()
            recentEvents.addLast(entry)
        }
    }

    private fun writeNextDescriptor() {
        if (writingDescriptor) return
        val descriptor = pendingDescriptorWrites.poll() ?: return
        writingDescriptor = true
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        @Suppress("DEPRECATION")
        gatt?.writeDescriptor(descriptor)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(tag, "Connected to ${gatt.device.name}")
                    debugLogger.logMessage("GATT connected, discovering services...")
                    isConnected = true
                    listener?.onConnected(gatt.device.name ?: "Unknown")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(tag, "Disconnected")
                    debugLogger.logMessage("GATT disconnected (status=$status)")
                    isConnected = false
                    connectedDeviceName = null
                    connectedDeviceAddress = null
                    debugLogger.stopSession()
                    listener?.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                debugLogger.logMessage("Service discovery failed: $status")
                return
            }

            val ftmsService = gatt.getService(FtmsConstants.FTMS_SERVICE_UUID)
            val hrService = gatt.getService(FtmsConstants.HR_SERVICE_UUID)

            debugLogger.logMessage("Services discovered. FTMS=${ftmsService != null}, HR=${hrService != null}")

            // Log all discovered services and characteristics
            for (service in gatt.services) {
                debugLogger.logMessage("Service: ${service.uuid}")
                for (char in service.characteristics) {
                    debugLogger.logMessage("  Characteristic: ${char.uuid} (props=${char.properties})")
                }
            }

            val ftmsChars = mutableListOf<UUID>()

            if (ftmsService != null) {
                // Read machine features
                val featureChar = ftmsService.getCharacteristic(FtmsConstants.FITNESS_MACHINE_FEATURE_UUID)
                if (featureChar != null) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        @Suppress("DEPRECATION")
                        gatt.readCharacteristic(featureChar)
                    }
                }

                // Subscribe to data characteristics
                val treadmillChar = ftmsService.getCharacteristic(FtmsConstants.TREADMILL_DATA_UUID)
                if (treadmillChar != null) {
                    ftmsChars.add(FtmsConstants.TREADMILL_DATA_UUID)
                    enableNotification(treadmillChar)
                }

                val bikeChar = ftmsService.getCharacteristic(FtmsConstants.INDOOR_BIKE_DATA_UUID)
                if (bikeChar != null) {
                    ftmsChars.add(FtmsConstants.INDOOR_BIKE_DATA_UUID)
                    enableNotification(bikeChar)
                }

                // Machine status notifications
                val statusChar = ftmsService.getCharacteristic(FtmsConstants.FITNESS_MACHINE_STATUS_UUID)
                if (statusChar != null) {
                    enableNotification(statusChar)
                }

                // Training status notifications
                val trainingChar = ftmsService.getCharacteristic(FtmsConstants.TRAINING_STATUS_UUID)
                if (trainingChar != null) {
                    enableNotification(trainingChar)
                }
            }

            var hasHeartRate = false
            if (hrService != null) {
                val hrChar = hrService.getCharacteristic(FtmsConstants.HR_MEASUREMENT_UUID)
                if (hrChar != null) {
                    hasHeartRate = true
                    enableNotification(hrChar)
                }
            }

            listener?.onServicesReady(ftmsChars, hasHeartRate)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            debugLogger.logEvent("READ", characteristic.uuid.toString(), data)
            addRecentEvent("READ", characteristic.uuid.toString(), data)

            if (characteristic.uuid == FtmsConstants.FITNESS_MACHINE_FEATURE_UUID) {
                listener?.onFeaturesRead(data)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            debugLogger.logEvent("NOTIFY", characteristic.uuid.toString(), data)
            addRecentEvent("NOTIFY", characteristic.uuid.toString(), data)

            when (characteristic.uuid) {
                FtmsConstants.TREADMILL_DATA_UUID,
                FtmsConstants.INDOOR_BIKE_DATA_UUID -> {
                    listener?.onFtmsData(characteristic.uuid, data)
                }
                FtmsConstants.HR_MEASUREMENT_UUID -> {
                    listener?.onHeartRateData(data)
                }
                FtmsConstants.FITNESS_MACHINE_STATUS_UUID -> {
                    debugLogger.logMessage("Machine status update: ${data.joinToString(" ") { String.format("%02X", it) }}")
                }
                FtmsConstants.TRAINING_STATUS_UUID -> {
                    debugLogger.logMessage("Training status update: ${data.joinToString(" ") { String.format("%02X", it) }}")
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            debugLogger.logMessage("Descriptor write for ${descriptor.characteristic.uuid}: status=$status")
            writingDescriptor = false
            writeNextDescriptor()
        }
    }
}
