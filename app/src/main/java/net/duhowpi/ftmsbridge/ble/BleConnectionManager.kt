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
    private val pendingCharReads = LinkedList<BluetoothGattCharacteristic>()
    private var readingChar = false
    private var requestControlPending = false
    private var controlPointChar: BluetoothGattCharacteristic? = null

    var isConnected = false
        private set

    var connectedDeviceName: String? = null
        private set

    var connectedDeviceAddress: String? = null
        private set

    var connectedDeviceSerial: String? = null
        private set

    var connectedDeviceHwRevision: String? = null
        private set

    var connectedDeviceFwRevision: String? = null
        private set

    val recentEvents = ArrayDeque<String>()

    interface ConnectionListener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onServicesReady(ftmsCharacteristics: List<UUID>, hasHeartRate: Boolean)
        fun onFtmsData(uuid: UUID, data: ByteArray)
        fun onHeartRateData(data: ByteArray)
        fun onFeaturesRead(data: ByteArray)
        fun onDeviceInfoRead()
        fun onMachineStatusChanged(opCode: Int, params: ByteArray)
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
        connectedDeviceSerial = null
        connectedDeviceHwRevision = null
        connectedDeviceFwRevision = null
        recentEvents.clear()
        pendingCharReads.clear()
        readingChar = false
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
        connectedDeviceSerial = null
        connectedDeviceHwRevision = null
        connectedDeviceFwRevision = null
        recentEvents.clear()
        pendingCharReads.clear()
        pendingDescriptorWrites.clear()
        readingChar = false
        writingDescriptor = false
        requestControlPending = false
        controlPointChar = null
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

    private fun scheduleCharRead(characteristic: BluetoothGattCharacteristic) {
        pendingCharReads.add(characteristic)
        readNextChar()
    }

    private fun writeControlPoint(data: ByteArray) {
        val g = gatt ?: return
        val char = controlPointChar ?: return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        @Suppress("DEPRECATION")
        char.value = data
        @Suppress("DEPRECATION")
        val ok = g.writeCharacteristic(char)
        debugLogger.logMessage("Control point write ${data.joinToString(" ") { String.format("%02X", it) }}: ok=$ok")
    }

    private fun readNextChar() {
        if (readingChar) return
        val char = pendingCharReads.poll() ?: return
        readingChar = true
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            readingChar = false
            readNextChar()
            return
        }
        @Suppress("DEPRECATION")
        if (gatt?.readCharacteristic(char) != true) {
            readingChar = false
            readNextChar()
        }
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
                    connectedDeviceSerial = null
                    connectedDeviceHwRevision = null
                    connectedDeviceFwRevision = null
                    pendingCharReads.clear()
                    pendingDescriptorWrites.clear()
                    readingChar = false
                    writingDescriptor = false
                    requestControlPending = false
                    controlPointChar = null
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
                        scheduleCharRead(featureChar)
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

                // FTMS Control Point: subscribe to notifications/indications.
                // BH Fitness iConcept 3.0 uses NOTIFY (props=24) instead of the standard
                // INDICATE (props=40). Subscribing here enables the machine to send start/stop
                // events back, and also allows us to send "Request Control" to trigger data streaming.
                val cpChar = ftmsService.getCharacteristic(FtmsConstants.FITNESS_MACHINE_CONTROL_POINT_UUID)
                if (cpChar != null) {
                    controlPointChar = cpChar
                    enableNotification(cpChar)
                    requestControlPending = true
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

            // iConcept 3.0 / BH Fitness proprietary service — subscribe to notify chars
            val iConceptService = gatt.getService(FtmsConstants.ICONCEPT_SERVICE_UUID)
            if (iConceptService != null) {
                debugLogger.logMessage("iConcept proprietary service found")
                listOf(FtmsConstants.ICONCEPT_NOTIFY_1_UUID, FtmsConstants.ICONCEPT_NOTIFY_2_UUID).forEach { uuid ->
                    iConceptService.getCharacteristic(uuid)?.let { enableNotification(it) }
                }
            }

            listener?.onServicesReady(ftmsChars, hasHeartRate)

            // Schedule reads for Device Information Service characteristics
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val devInfoService = gatt.getService(FtmsConstants.DEVICE_INFO_SERVICE_UUID)
                if (devInfoService != null) {
                    debugLogger.logMessage("Device Information Service found")
                    listOf(
                        FtmsConstants.SERIAL_NUMBER_UUID,
                        FtmsConstants.HARDWARE_REVISION_UUID,
                        FtmsConstants.FIRMWARE_REVISION_UUID
                    ).forEach { uuid ->
                        devInfoService.getCharacteristic(uuid)?.let { scheduleCharRead(it) }
                    }
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            readingChar = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readNextChar()
                return
            }
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: run { readNextChar(); return }
            debugLogger.logEvent("READ", characteristic.uuid.toString(), data)
            addRecentEvent("READ", characteristic.uuid.toString(), data)

            when (characteristic.uuid) {
                FtmsConstants.FITNESS_MACHINE_FEATURE_UUID -> listener?.onFeaturesRead(data)
                FtmsConstants.SERIAL_NUMBER_UUID -> {
                    connectedDeviceSerial = String(data, Charsets.UTF_8).trim()
                    listener?.onDeviceInfoRead()
                }
                FtmsConstants.HARDWARE_REVISION_UUID -> {
                    connectedDeviceHwRevision = String(data, Charsets.UTF_8).trim()
                    listener?.onDeviceInfoRead()
                }
                FtmsConstants.FIRMWARE_REVISION_UUID -> {
                    connectedDeviceFwRevision = String(data, Charsets.UTF_8).trim()
                    listener?.onDeviceInfoRead()
                }
            }
            readNextChar()
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
                    val opCode = data[0].toInt() and 0xFF
                    val params = if (data.size > 1) data.copyOfRange(1, data.size) else byteArrayOf()
                    debugLogger.logMessage("Machine status update: ${data.joinToString(" ") { String.format("%02X", it) }}")
                    listener?.onMachineStatusChanged(opCode, params)
                }
                FtmsConstants.FITNESS_MACHINE_CONTROL_POINT_UUID -> {
                    // Asynchronous control-point notification (e.g. response to Request Control,
                    // or unsolicited machine-state event on BH Fitness iConcept 3.0 devices).
                    debugLogger.logMessage("Control point notification: ${data.joinToString(" ") { String.format("%02X", it) }}")
                    if (data.isNotEmpty() && (data[0].toInt() and 0xFF) != FtmsConstants.CONTROL_RESPONSE_CODE) {
                        // Treat non-response notifications as machine status events
                        val opCode = data[0].toInt() and 0xFF
                        val params = if (data.size > 1) data.copyOfRange(1, data.size) else byteArrayOf()
                        listener?.onMachineStatusChanged(opCode, params)
                    }
                }
                FtmsConstants.TRAINING_STATUS_UUID -> {
                    debugLogger.logMessage("Training status update: ${data.joinToString(" ") { String.format("%02X", it) }}")
                }
                FtmsConstants.ICONCEPT_NOTIFY_1_UUID,
                FtmsConstants.ICONCEPT_NOTIFY_2_UUID -> {
                    debugLogger.logMessage("iConcept notify ${characteristic.uuid.toString().takeLast(4)}: ${data.joinToString(" ") { String.format("%02X", it) }}")
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
            // Once all CCCD subscriptions are complete, send "Request Control" to the
            // FTMS control point. BH Fitness iConcept 3.0 devices require this before
            // they start streaming treadmill data and sending start/stop events.
            if (pendingDescriptorWrites.isEmpty() && !writingDescriptor && requestControlPending) {
                requestControlPending = false
                writeControlPoint(byteArrayOf(FtmsConstants.CONTROL_REQUEST_CONTROL))
            }
        }
    }
}
