package net.duhowpi.ftmsbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.duhowpi.ftmsbridge.ble.BleConnectionManager
import net.duhowpi.ftmsbridge.ble.BleScanner
import net.duhowpi.ftmsbridge.ble.BtDebugLogger
import net.duhowpi.ftmsbridge.data.AppDatabase
import net.duhowpi.ftmsbridge.data.WorkoutSample
import net.duhowpi.ftmsbridge.data.WorkoutSession
import net.duhowpi.ftmsbridge.databinding.ActivityMainBinding
import net.duhowpi.ftmsbridge.device.FtmsDevice
import net.duhowpi.ftmsbridge.device.HeartRateSensor
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleScanner: BleScanner
    private lateinit var debugLogger: BtDebugLogger
    private lateinit var hrDebugLogger: BtDebugLogger
    private lateinit var db: AppDatabase

    private var ftmsConnectionManager: BleConnectionManager? = null
    private var hrConnectionManager: BleConnectionManager? = null

    private var fitnessDevice: FtmsDevice? = null
    private var heartRateSensor: HeartRateSensor? = null

    private var currentSessionId: Long? = null
    private var sessionStartTime: Long = 0
    private var isRecording = false

    // Track discovered devices for selection
    private val discoveredFtmsDevices = mutableMapOf<String, BluetoothDevice>()
    private val discoveredHrDevices = mutableMapOf<String, BluetoothDevice>()

    // Last known HR for merging into FTMS samples
    private var lastHeartRateBpm = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScanning()
        } else {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bleScanner.isBluetoothEnabled()) {
            startScanning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        debugLogger = BtDebugLogger(BuildConfig.BT_DEBUG_LOG)
        hrDebugLogger = BtDebugLogger(BuildConfig.BT_DEBUG_LOG)
        bleScanner = BleScanner(this)

        setupUI()
    }

    private fun setupUI() {
        binding.btnScan.setOnClickListener {
            if (bleScanner.isScanning) {
                bleScanner.stopScan()
                binding.btnScan.text = getString(R.string.scan)
            } else {
                checkPermissionsAndScan()
            }
        }

        binding.btnSession.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        updateConnectionStatus()
        resetMetrics()
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.remove(Manifest.permission.BLUETOOTH_SCAN)
            permissions.remove(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else if (!bleScanner.isBluetoothEnabled()) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startScanning()
        }
    }

    private fun startScanning() {
        discoveredFtmsDevices.clear()
        discoveredHrDevices.clear()

        bleScanner.startScan(object : BleScanner.ScanListener {
            override fun onDeviceFound(result: ScanResult) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                val device = result.device
                val name = device.name ?: return
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

                if (serviceUuids.contains(FtmsConstants.FTMS_SERVICE_UUID)) {
                    discoveredFtmsDevices[name] = device
                }
                if (serviceUuids.contains(FtmsConstants.HR_SERVICE_UUID)) {
                    discoveredHrDevices[name] = device
                }

                runOnUiThread { updateScanResults() }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    binding.btnScan.text = getString(R.string.scan)
                    Toast.makeText(this@MainActivity, getString(R.string.scan_failed, errorCode), Toast.LENGTH_SHORT).show()
                }
            }
        })

        binding.btnScan.text = getString(R.string.stop_scan)

        // Auto-stop after 15 seconds
        binding.root.postDelayed({
            if (bleScanner.isScanning) {
                bleScanner.stopScan()
                runOnUiThread {
                    binding.btnScan.text = getString(R.string.scan)
                    showDeviceSelection()
                }
            }
        }, 15000)
    }

    private fun updateScanResults() {
        val count = discoveredFtmsDevices.size + discoveredHrDevices.size
        binding.txtScanStatus.text = getString(R.string.devices_found, count)
        binding.txtScanStatus.visibility = View.VISIBLE
    }

    private fun showDeviceSelection() {
        if (discoveredFtmsDevices.isEmpty() && discoveredHrDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_devices_found), Toast.LENGTH_SHORT).show()
            return
        }

        // FTMS device selection
        if (discoveredFtmsDevices.isNotEmpty() && ftmsConnectionManager?.isConnected != true) {
            val names = discoveredFtmsDevices.keys.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_fitness_machine))
                .setItems(names) { _, which ->
                    val device = discoveredFtmsDevices[names[which]]
                    if (device != null) connectFtmsDevice(device)
                    // After FTMS selection, show HR selection
                    showHrDeviceSelection()
                }
                .setNegativeButton(android.R.string.cancel) {_, _ ->
                    showHrDeviceSelection()
                }
                .show()
        } else {
            showHrDeviceSelection()
        }
    }

    private fun showHrDeviceSelection() {
        if (discoveredHrDevices.isEmpty() || hrConnectionManager?.isConnected == true) return
        val names = discoveredHrDevices.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_hr_sensor))
            .setItems(names) { _, which ->
                val device = discoveredHrDevices[names[which]]
                if (device != null) connectHrDevice(device)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connectFtmsDevice(device: BluetoothDevice) {
        ftmsConnectionManager = BleConnectionManager(this, debugLogger)
        ftmsConnectionManager?.connect(device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected(deviceName: String) {
                runOnUiThread { updateConnectionStatus() }
            }

            override fun onDisconnected() {
                fitnessDevice = null
                runOnUiThread {
                    updateConnectionStatus()
                    if (isRecording) stopRecording()
                }
            }

            override fun onServicesReady(ftmsCharacteristics: List<UUID>, hasHeartRate: Boolean) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                val name = device.name ?: "Unknown"
                fitnessDevice = FtmsDevice.createFromCharacteristics(name, ftmsCharacteristics)
                runOnUiThread {
                    updateConnectionStatus()
                    binding.txtMachineType.text = fitnessDevice?.machineType?.name ?: "?"
                    binding.txtMachineType.visibility = View.VISIBLE
                }
            }

            override fun onFtmsData(uuid: UUID, data: ByteArray) {
                val sample = fitnessDevice?.onDataReceived(data) ?: return
                // Merge external HR if the machine doesn't provide one
                val mergedSample = if (sample.heartRateBpm == 0 && lastHeartRateBpm > 0) {
                    sample.copy(heartRateBpm = lastHeartRateBpm)
                } else {
                    sample
                }

                runOnUiThread { updateDashboard(mergedSample) }

                if (isRecording && currentSessionId != null) {
                    saveSample(mergedSample)
                }
            }

            override fun onHeartRateData(data: ByteArray) {
                lastHeartRateBpm = FtmsDataParser.parseHeartRate(data)
                runOnUiThread {
                    binding.valueHeartRate.text = if (lastHeartRateBpm > 0) "$lastHeartRateBpm" else "--"
                }
            }

            override fun onFeaturesRead(data: ByteArray) {
                fitnessDevice?.onFeaturesReceived(data)
                Log.i(tag, "FTMS Capabilities: ${fitnessDevice?.capabilities}")
            }
        })
    }

    private fun connectHrDevice(device: BluetoothDevice) {
        hrConnectionManager = BleConnectionManager(this, hrDebugLogger)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        heartRateSensor = HeartRateSensor(device.name ?: "HR Sensor")

        hrConnectionManager?.connect(device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected(deviceName: String) {
                runOnUiThread { updateConnectionStatus() }
            }

            override fun onDisconnected() {
                heartRateSensor = null
                lastHeartRateBpm = 0
                runOnUiThread { updateConnectionStatus() }
            }

            override fun onServicesReady(ftmsCharacteristics: List<UUID>, hasHeartRate: Boolean) {
                runOnUiThread { updateConnectionStatus() }
            }

            override fun onFtmsData(uuid: UUID, data: ByteArray) {}

            override fun onHeartRateData(data: ByteArray) {
                val hr = heartRateSensor?.onDataReceived(data) ?: FtmsDataParser.parseHeartRate(data)
                lastHeartRateBpm = hr
                runOnUiThread {
                    binding.valueHeartRate.text = if (hr > 0) "$hr" else "--"
                }
            }

            override fun onFeaturesRead(data: ByteArray) {}
        })
    }

    private fun updateConnectionStatus() {
        val ftmsConnected = ftmsConnectionManager?.isConnected == true
        val hrConnected = hrConnectionManager?.isConnected == true

        binding.indicatorFtms.setBackgroundResource(
            if (ftmsConnected) R.color.status_connected else R.color.status_disconnected
        )
        binding.indicatorHr.setBackgroundResource(
            if (hrConnected) R.color.status_connected else R.color.status_disconnected
        )

        binding.txtFtmsDevice.text = if (ftmsConnected)
            ftmsConnectionManager?.connectedDeviceName ?: getString(R.string.connected)
        else
            getString(R.string.not_connected)

        binding.txtHrDevice.text = if (hrConnected)
            hrConnectionManager?.connectedDeviceName ?: getString(R.string.connected)
        else
            getString(R.string.not_connected)

        binding.btnSession.isEnabled = ftmsConnected
    }

    private fun updateDashboard(sample: net.duhowpi.ftmsbridge.model.FitnessSample) {
        binding.valueSpeed.text = String.format("%.1f", sample.speedKmh)
        binding.valueCadence.text = if (sample.cadenceRpm > 0) String.format("%.0f", sample.cadenceRpm) else "--"
        binding.valuePower.text = if (sample.instantaneousPowerW > 0) "${sample.instantaneousPowerW}" else "--"
        binding.valueDistance.text = String.format("%.2f", sample.totalDistanceM / 1000.0)
        if (sample.heartRateBpm > 0) {
            binding.valueHeartRate.text = "${sample.heartRateBpm}"
        }
        binding.valueEnergy.text = if (sample.totalEnergyKcal > 0) "${sample.totalEnergyKcal}" else "--"
        binding.valueInclination.text = if (sample.inclinationPercent != 0.0) String.format("%.1f", sample.inclinationPercent) else "--"
        binding.valueResistance.text = if (sample.resistanceLevel > 0) "${sample.resistanceLevel}" else "--"

        // Elapsed time
        val minutes = sample.elapsedTimeSec / 60
        val seconds = sample.elapsedTimeSec % 60
        binding.valueElapsedTime.text = String.format("%d:%02d", minutes, seconds)
    }

    private fun resetMetrics() {
        binding.valueSpeed.text = "--"
        binding.valueCadence.text = "--"
        binding.valuePower.text = "--"
        binding.valueDistance.text = "--"
        binding.valueHeartRate.text = "--"
        binding.valueEnergy.text = "--"
        binding.valueElapsedTime.text = "0:00"
        binding.valueInclination.text = "--"
        binding.valueResistance.text = "--"
        binding.txtMachineType.visibility = View.GONE
    }

    private fun startRecording() {
        val device = fitnessDevice ?: return
        isRecording = true
        sessionStartTime = System.currentTimeMillis()
        binding.btnSession.text = getString(R.string.stop_session)
        binding.recordingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val session = WorkoutSession(
                startTimeMs = sessionStartTime,
                machineType = device.machineType.name,
                deviceName = device.deviceName
            )
            currentSessionId = db.sessionDao().insert(session)
            Log.i(tag, "Session started: $currentSessionId")
        }
    }

    private fun stopRecording() {
        isRecording = false
        binding.btnSession.text = getString(R.string.start_session)
        binding.recordingIndicator.visibility = View.GONE

        val sessionId = currentSessionId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.sessionDao().getById(sessionId)
            session?.let {
                it.endTimeMs = System.currentTimeMillis()
                db.sessionDao().update(it)
            }
            Log.i(tag, "Session stopped: $sessionId")
        }
        currentSessionId = null
    }

    private fun saveSample(sample: net.duhowpi.ftmsbridge.model.FitnessSample) {
        val sessionId = currentSessionId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dbSample = WorkoutSample(
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                elapsedTimeSec = sample.elapsedTimeSec,
                speedKmh = sample.speedKmh,
                cadenceRpm = sample.cadenceRpm,
                instantaneousPowerW = sample.instantaneousPowerW,
                totalDistanceM = sample.totalDistanceM,
                heartRateBpm = sample.heartRateBpm,
                inclinationPercent = sample.inclinationPercent,
                resistanceLevel = sample.resistanceLevel,
                totalEnergyKcal = sample.totalEnergyKcal
            )
            db.sampleDao().insert(dbSample)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stopScan()
        if (isRecording) stopRecording()
        ftmsConnectionManager?.disconnect()
        hrConnectionManager?.disconnect()
    }
}
