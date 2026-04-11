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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import java.util.LinkedList
import java.util.UUID
import kotlin.math.roundToInt

class BleConnectionManager(
    private val context: Context,
    private val debugLogger: BtDebugLogger
) {
    private val tag = "BleConnectionManager"
    private var gatt: BluetoothGatt? = null
    private var listener: ConnectionListener? = null

    // Single serialised GATT operation queue.  Android's GATT layer only handles
    // one operation at a time; mixing reads and descriptor writes without serialisation
    // causes writeDescriptor() to return false silently, leaving writingDescriptor
    // stuck at true and no CCCD ever written.
    private sealed class GattOp {
        class ReadChar(val char: BluetoothGattCharacteristic) : GattOp()
        class WriteDescriptor(val descriptor: BluetoothGattDescriptor) : GattOp()
        class WriteChar(val char: BluetoothGattCharacteristic, val data: ByteArray) : GattOp()
    }
    private val gattQueue = LinkedList<GattOp>()
    private var gattBusy = false
    private val retryHandler = Handler(Looper.getMainLooper())

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
        fun onIConceptData(data: ByteArray)
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
        gattQueue.clear()
        gattBusy = false
        controlPointChar = null
        debugLogger.startSession(name, device.address)
        debugLogger.logMessage("Connecting to $name (${device.address})")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        retryHandler.removeCallbacksAndMessages(null)
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
        gattQueue.clear()
        gattBusy = false
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
            enqueueOp(GattOp.WriteDescriptor(descriptor))
        }
    }

    private fun scheduleCharRead(characteristic: BluetoothGattCharacteristic) {
        enqueueOp(GattOp.ReadChar(characteristic))
    }

    private fun writeControlPoint(data: ByteArray) {
        val char = controlPointChar ?: return
        enqueueOp(GattOp.WriteChar(char, data))
    }

    fun sendControlPoint(data: ByteArray): Boolean {
        if (!isConnected) return false
        val char = controlPointChar ?: return false
        enqueueOp(GattOp.WriteChar(char, data))
        return true
    }

    private fun enqueueOp(op: GattOp) {
        gattQueue.add(op)
        advanceQueue()
    }

    /** Execute the next queued GATT operation, if the bus is free. */
    private fun advanceQueue() {
        if (gattBusy) return
        val g = gatt ?: return
        val op = gattQueue.poll() ?: return
        gattBusy = true

        val started = when (op) {
            is GattOp.WriteDescriptor -> {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) false
                else {
                    debugLogger.logMessage("CCCD write ${op.descriptor.characteristic.uuid.toString().takeLast(4)}")
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(op.descriptor) == true
                }
            }
            is GattOp.ReadChar -> {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) false
                else {
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(op.char) == true
                }
            }
            is GattOp.WriteChar -> {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) false
                else {
                    val dataHex = op.data.joinToString(" ") { String.format("%02X", it) }
                    val extractedLevel = extractResistanceLevelFromControlWrite(op.char.uuid, op.data)
                    if (extractedLevel != null) {
                        debugLogger.logMessage(
                            "Char write ${op.char.uuid.toString().takeLast(4)}: $dataHex (resistanceLevel=$extractedLevel)"
                        )
                    } else {
                        debugLogger.logMessage("Char write ${op.char.uuid.toString().takeLast(4)}: $dataHex")
                    }
                    @Suppress("DEPRECATION")
                    op.char.value = op.data
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(op.char) == true
                }
            }
        }

        if (!started) {
            // GATT busy or transient error — put the operation back and retry shortly.
            gattBusy = false
            gattQueue.addFirst(op)
            debugLogger.logMessage("GATT op failed, retrying in 200 ms")
            retryHandler.postDelayed({ advanceQueue() }, 200)
        }
    }

    /** Called by every GATT completion callback to free the bus and run the next op. */
    private fun onOperationComplete() {
        gattBusy = false
        advanceQueue()
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

    private fun extractResistanceLevelFromControlWrite(charUuid: UUID, payload: ByteArray): Int? {
        if (charUuid != FtmsConstants.FITNESS_MACHINE_CONTROL_POINT_UUID) return null
        if (payload.size < 3) return null
        val opCode = payload[0].toInt() and 0xFF
        if (opCode != (FtmsConstants.CONTROL_SET_TARGET_RESISTANCE_LEVEL.toInt() and 0xFF)) return null
        val rawValue = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        val decodedLevel = (rawValue / RESISTANCE_LEVEL_MULTIPLIER).roundToInt()
        return decodedLevel.coerceIn(RESISTANCE_LOG_MIN_LEVEL, RESISTANCE_LOG_MAX_LEVEL)
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
                    retryHandler.removeCallbacksAndMessages(null)
                    gattQueue.clear()
                    gattBusy = false
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
                // --- Step 1: subscribe to all data / status notification characteristics ---
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

                val crossTrainerChar = ftmsService.getCharacteristic(FtmsConstants.CROSS_TRAINER_DATA_UUID)
                if (crossTrainerChar != null) {
                    ftmsChars.add(FtmsConstants.CROSS_TRAINER_DATA_UUID)
                    enableNotification(crossTrainerChar)
                }

                val stairClimberChar = ftmsService.getCharacteristic(FtmsConstants.STAIR_CLIMBER_DATA_UUID)
                if (stairClimberChar != null) {
                    ftmsChars.add(FtmsConstants.STAIR_CLIMBER_DATA_UUID)
                    enableNotification(stairClimberChar)
                }

                val stepClimberChar = ftmsService.getCharacteristic(FtmsConstants.STEP_CLIMBER_DATA_UUID)
                if (stepClimberChar != null) {
                    ftmsChars.add(FtmsConstants.STEP_CLIMBER_DATA_UUID)
                    enableNotification(stepClimberChar)
                }

                val statusChar = ftmsService.getCharacteristic(FtmsConstants.FITNESS_MACHINE_STATUS_UUID)
                if (statusChar != null) enableNotification(statusChar)

                val trainingChar = ftmsService.getCharacteristic(FtmsConstants.TRAINING_STATUS_UUID)
                if (trainingChar != null) enableNotification(trainingChar)

                // --- Step 2: subscribe to control point, then queue "Request Control" ---
                // BH Fitness iConcept 3.0 uses NOTIFY (props=24) for the control point.
                // Sending Request Control (0x00) after subscribing triggers data streaming.
                val cpChar = ftmsService.getCharacteristic(FtmsConstants.FITNESS_MACHINE_CONTROL_POINT_UUID)
                if (cpChar != null) {
                    controlPointChar = cpChar
                    enableNotification(cpChar)
                    writeControlPoint(byteArrayOf(FtmsConstants.CONTROL_REQUEST_CONTROL))
                }
            }

            var hasHeartRate = false
            if (hrService != null) {
                val hrChar = hrService.getCharacteristic(FtmsConstants.HR_MEASUREMENT_UUID)
                if (hrChar != null) {
                    hasHeartRate = true
                    enableNotification(hrChar)
                }
                // Heart Rate Control Point (0x2A39): some devices (e.g. Mi Band variants)
                // require writing 0x01 to this characteristic to start continuous HR streaming.
                val hrCpChar = hrService.getCharacteristic(FtmsConstants.HR_CONTROL_POINT_UUID)
                if (hrCpChar != null &&
                    (hrCpChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    debugLogger.logMessage("HR Control Point found – writing 0x01 to start continuous HR")
                    enqueueOp(GattOp.WriteChar(hrCpChar, byteArrayOf(0x01)))
                }
            }

            // Mi Band and similar devices expose 0x2A37 under the proprietary fee0 service
            // instead of (or in addition to) the standard 0x180D HR service. The !hasHeartRate
            // guard avoids double-subscription; 0x180D is checked first so standard-compliant
            // devices always take the preferred path.
            val miBandHrService = gatt.getService(FtmsConstants.MIBAND_HR_SERVICE_UUID)
            if (miBandHrService != null) {
                val hrChar = miBandHrService.getCharacteristic(FtmsConstants.HR_MEASUREMENT_UUID)
                if (hrChar != null && !hasHeartRate) {
                    hasHeartRate = true
                    debugLogger.logMessage("Mi Band HR service (fee0) found – subscribing to 0x2A37")
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

            // --- Step 3: read informational characteristics AFTER all CCCD writes ---
            // The unified GattOp queue guarantees all descriptor writes (and Request Control)
            // complete before any read begins, eliminating the bus-busy race condition.
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                ftmsService?.getCharacteristic(FtmsConstants.FITNESS_MACHINE_FEATURE_UUID)
                    ?.let { scheduleCharRead(it) }

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
            onOperationComplete()
            if (status != BluetoothGatt.GATT_SUCCESS) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
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
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Spontaneous notification — does NOT advance the operation queue.
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            debugLogger.logEvent("NOTIFY", characteristic.uuid.toString(), data)
            addRecentEvent("NOTIFY", characteristic.uuid.toString(), data)

            when (characteristic.uuid) {
                FtmsConstants.TREADMILL_DATA_UUID,
                FtmsConstants.INDOOR_BIKE_DATA_UUID,
                FtmsConstants.CROSS_TRAINER_DATA_UUID,
                FtmsConstants.STAIR_CLIMBER_DATA_UUID,
                FtmsConstants.STEP_CLIMBER_DATA_UUID -> {
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
                    debugLogger.logMessage("Control point notification: ${data.joinToString(" ") { String.format("%02X", it) }}")
                    if (data.isNotEmpty() && (data[0].toInt() and 0xFF) != FtmsConstants.CONTROL_RESPONSE_CODE) {
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
                    listener?.onIConceptData(data)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            debugLogger.logMessage("Descriptor write for ${descriptor.characteristic.uuid}: status=$status")
            onOperationComplete()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            debugLogger.logMessage("Characteristic write for ${characteristic.uuid}: status=$status")
            onOperationComplete()
        }
    }

    companion object {
        private const val RESISTANCE_LEVEL_MULTIPLIER = 6.25
        private const val RESISTANCE_LOG_MIN_LEVEL = 1
        private const val RESISTANCE_LOG_MAX_LEVEL = 8
    }
}
