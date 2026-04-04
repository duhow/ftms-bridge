package net.duhowpi.ftmsbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
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
import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import net.duhowpi.ftmsbridge.model.FitnessSample
import net.duhowpi.ftmsbridge.model.ScannedDeviceInfo
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val tag = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleScanner: BleScanner
    private lateinit var debugLogger: BtDebugLogger
    private lateinit var hrDebugLogger: BtDebugLogger
    private lateinit var db: AppDatabase
    private lateinit var scanAdapter: ScanResultAdapter

    private var ftmsConnectionManager: BleConnectionManager? = null
    private var hrConnectionManager: BleConnectionManager? = null

    private var fitnessDevice: FtmsDevice? = null
    private var heartRateSensor: HeartRateSensor? = null

    private var currentSessionId: Long? = null
    private var sessionStartTime: Long = 0
    private var isRecording = false

    // Raw scan results map, updated on every BLE event
    private val scanResultsMap = mutableMapOf<String, ScannedDeviceInfo>()

    // Last known HR for merging into FTMS samples
    private var lastHeartRateBpm = 0

    // Whether permissions were requested from scan button (so we auto-start scan)
    private var pendingScanAfterPermission = false

    // Throttled scan list updates (1 second)
    private val updateHandler = Handler(Looper.getMainLooper())
    private val scanListUpdateRunnable = object : Runnable {
        override fun run() {
            updateScanListUI()
            if (bleScanner.isScanning) {
                updateHandler.postDelayed(this, 1000)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (pendingScanAfterPermission) {
                pendingScanAfterPermission = false
                if (!bleScanner.isBluetoothEnabled()) {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else {
                    startScanning()
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bleScanner.isBluetoothEnabled()) startScanning()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        debugLogger = BtDebugLogger(BuildConfig.BT_DEBUG_LOG, this)
        hrDebugLogger = BtDebugLogger(BuildConfig.BT_DEBUG_LOG, this)
        bleScanner = BleScanner(this)

        setupScanList()
        setupUI()

        // Request permissions immediately on app start (without auto-scanning)
        requestPermissionsIfNeeded()
    }

    private fun setupScanList() {
        scanAdapter = ScanResultAdapter { info -> onDeviceConnectTapped(info) }
        binding.rvScanResults.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = scanAdapter
            isNestedScrollingEnabled = true
            addItemDecoration(DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupUI() {
        binding.btnScan.setOnClickListener {
            if (bleScanner.isScanning) {
                stopScan()
            } else {
                pendingScanAfterPermission = true
                checkPermissionsAndMaybeScan()
            }
        }

        binding.btnWorkoutStart.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        binding.btnDebug.setOnClickListener { showDebugDialog() }

        updateConnectionStatus()
        resetMetrics()
    }

    // ---- Permissions --------------------------------------------------------

    private fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun haveAllPermissions(): Boolean = getRequiredPermissions().all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Request permissions on startup — does NOT start scanning. */
    private fun requestPermissionsIfNeeded() {
        val needed = getRequiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** Called when user taps Scan — requests permissions then starts scan. */
    private fun checkPermissionsAndMaybeScan() {
        if (haveAllPermissions()) {
            if (!bleScanner.isBluetoothEnabled()) {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                pendingScanAfterPermission = false
                startScanning()
            }
        } else {
            permissionLauncher.launch(getRequiredPermissions().toTypedArray())
        }
    }

    // ---- Scanning -----------------------------------------------------------

    private fun startScanning() {
        scanResultsMap.clear()
        binding.rvScanResults.visibility = View.VISIBLE
        binding.txtScanStatus.visibility = View.VISIBLE
        binding.txtScanStatus.text = getString(R.string.scanning_active)
        binding.btnScan.text = getString(R.string.stop_scan)

        bleScanner.startScan(object : BleScanner.ScanListener {
            override fun onDeviceFound(result: ScanResult) {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) return

                val device = result.device
                val name = device.name ?: return
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                val isFtms = serviceUuids.contains(FtmsConstants.FTMS_SERVICE_UUID)
                val isHr = serviceUuids.contains(FtmsConstants.HR_SERVICE_UUID)

                val existing = scanResultsMap[device.address]
                if (existing != null) {
                    existing.rssi = result.rssi
                } else {
                    scanResultsMap[device.address] = ScannedDeviceInfo(
                        name = name,
                        address = device.address,
                        rssi = result.rssi,
                        isFtms = isFtms,
                        isHr = isHr,
                        device = device
                    )
                    debugLogger.logMessage("Discovered: $name (${device.address}) FTMS=$isFtms HR=$isHr RSSI=${result.rssi}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    binding.btnScan.text = getString(R.string.scan)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.scan_failed, errorCode),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        // Start throttled UI updates every 1 second
        updateHandler.post(scanListUpdateRunnable)

        // Auto-stop after 30 seconds
        binding.root.postDelayed({
            if (bleScanner.isScanning) stopScan()
        }, 30_000)
    }

    private fun stopScan() {
        bleScanner.stopScan()
        updateHandler.removeCallbacks(scanListUpdateRunnable)
        updateScanListUI() // final refresh
        binding.btnScan.text = getString(R.string.scan)
        binding.txtScanStatus.text = getString(R.string.devices_found, scanResultsMap.size)
    }

    private fun updateScanListUI() {
        val list = scanResultsMap.values.toList()
        runOnUiThread {
            binding.txtScanStatus.text = if (bleScanner.isScanning)
                "${getString(R.string.scanning_active)} ${list.size}"
            else
                getString(R.string.devices_found, list.size)
            scanAdapter.updateAll(list)
            // Mark already-connected devices
            ftmsConnectionManager?.connectedDeviceAddress?.let { scanAdapter.markConnected(it) }
            hrConnectionManager?.connectedDeviceAddress?.let { scanAdapter.markConnected(it) }
        }
    }

    // ---- Device connection from scan list -----------------------------------

    private fun onDeviceConnectTapped(info: ScannedDeviceInfo) {
        when {
            info.isFtms -> connectFtmsDevice(info.device)
            info.isHr -> connectHrDevice(info.device)
            else -> {
                // Unknown type: try as FTMS first, fall back to HR
                AlertDialog.Builder(this)
                    .setTitle(info.name)
                    .setMessage("Type not detected. Connect as:")
                    .setPositiveButton("FTMS Machine") { _, _ -> connectFtmsDevice(info.device) }
                    .setNeutralButton("HR Sensor") { _, _ -> connectHrDevice(info.device) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun connectFtmsDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        ftmsConnectionManager?.disconnect()
        ftmsConnectionManager = BleConnectionManager(this, debugLogger)
        ftmsConnectionManager?.connect(device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected(deviceName: String) {
                runOnUiThread {
                    updateConnectionStatus()
                    scanAdapter.markConnected(device.address)
                }
            }

            override fun onDisconnected() {
                fitnessDevice = null
                runOnUiThread {
                    updateConnectionStatus()
                    scanAdapter.markDisconnected(device.address)
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
                val mergedSample = if (sample.heartRateBpm == 0 && lastHeartRateBpm > 0)
                    sample.copy(heartRateBpm = lastHeartRateBpm) else sample
                runOnUiThread { updateDashboard(mergedSample) }
                if (isRecording && currentSessionId != null) saveSample(mergedSample)
            }

            override fun onHeartRateData(data: ByteArray) {
                lastHeartRateBpm = FtmsDataParser.parseHeartRate(data)
                runOnUiThread {
                    binding.valueHeartRate.text = if (lastHeartRateBpm > 0) "$lastHeartRateBpm" else "--"
                }
            }

            override fun onFeaturesRead(data: ByteArray) {
                fitnessDevice?.onFeaturesReceived(data)
                debugLogger.logMessage("FTMS Capabilities: ${fitnessDevice?.capabilities}")
                Log.i(tag, "FTMS Capabilities: ${fitnessDevice?.capabilities}")
            }

            override fun onDeviceInfoRead() {
                runOnUiThread { updateFtmsDeviceInfoUI() }
            }
        })
    }

    private fun connectHrDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        hrConnectionManager?.disconnect()
        hrConnectionManager = BleConnectionManager(this, hrDebugLogger)
        heartRateSensor = HeartRateSensor(device.name ?: "HR Sensor")

        hrConnectionManager?.connect(device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected(deviceName: String) {
                runOnUiThread {
                    updateConnectionStatus()
                    scanAdapter.markConnected(device.address)
                }
            }

            override fun onDisconnected() {
                heartRateSensor = null
                lastHeartRateBpm = 0
                runOnUiThread {
                    updateConnectionStatus()
                    scanAdapter.markDisconnected(device.address)
                }
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

            override fun onDeviceInfoRead() {}
        })
    }

    // ---- UI status ----------------------------------------------------------

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
        else getString(R.string.not_connected)

        binding.txtHrDevice.text = if (hrConnected)
            hrConnectionManager?.connectedDeviceName ?: getString(R.string.connected)
        else getString(R.string.not_connected)

        if (!ftmsConnected) {
            binding.txtFtmsDeviceInfo.visibility = View.GONE
        } else {
            updateFtmsDeviceInfoUI()
        }

        binding.workoutButtonRow.visibility = if (ftmsConnected) View.VISIBLE else View.GONE
        binding.btnWorkoutStart.isEnabled = ftmsConnected
    }

    private fun updateFtmsDeviceInfoUI() {
        val cm = ftmsConnectionManager ?: return
        val parts = buildList {
            cm.connectedDeviceAddress?.let { add("MAC: $it") }
            cm.connectedDeviceSerial?.takeIf { it.isNotBlank() }?.let { add("S/N: $it") }
            cm.connectedDeviceHwRevision?.takeIf { it.isNotBlank() }?.let { add("HW: $it") }
            cm.connectedDeviceFwRevision?.takeIf { it.isNotBlank() }?.let { add("FW: $it") }
        }
        if (parts.isEmpty()) {
            binding.txtFtmsDeviceInfo.visibility = View.GONE
        } else {
            binding.txtFtmsDeviceInfo.text = parts.joinToString("  |  ")
            binding.txtFtmsDeviceInfo.visibility = View.VISIBLE
        }
    }

    private fun updateDashboard(sample: FitnessSample) {
        binding.valueSpeed.text = String.format("%.1f", sample.speedKmh)
        binding.valueCadence.text = if (sample.cadenceRpm > 0) String.format("%.0f", sample.cadenceRpm) else "--"
        binding.valuePower.text = if (sample.instantaneousPowerW > 0) "${sample.instantaneousPowerW}" else "--"
        binding.valueDistance.text = String.format("%.2f", sample.totalDistanceM / 1000.0)
        if (sample.heartRateBpm > 0) binding.valueHeartRate.text = "${sample.heartRateBpm}"
        binding.valueEnergy.text = if (sample.totalEnergyKcal > 0) "${sample.totalEnergyKcal}" else "--"
        binding.valueInclination.text = if (sample.inclinationPercent != 0.0)
            String.format("%.1f", sample.inclinationPercent) else "--"
        binding.valueResistance.text = if (sample.resistanceLevel > 0) "${sample.resistanceLevel}" else "--"
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
        binding.txtFtmsDeviceInfo.visibility = View.GONE
    }

    // ---- Session recording --------------------------------------------------

    private fun startRecording() {
        val device = fitnessDevice ?: return
        isRecording = true
        sessionStartTime = System.currentTimeMillis()
        binding.btnWorkoutStart.text = getString(R.string.stop_session)
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
        binding.btnWorkoutStart.text = getString(R.string.start_session)
        binding.recordingIndicator.visibility = View.GONE
        val sessionId = currentSessionId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.sessionDao().getById(sessionId)
            session?.let { it.endTimeMs = System.currentTimeMillis(); db.sessionDao().update(it) }
            Log.i(tag, "Session stopped: $sessionId")
        }
        currentSessionId = null
    }

    private fun saveSample(sample: FitnessSample) {
        val sessionId = currentSessionId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            db.sampleDao().insert(
                WorkoutSample(
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
            )
        }
    }

    // ---- Debug dialog -------------------------------------------------------

    private fun showDebugDialog() {
        val sb = StringBuilder()

        sb.appendLine("=== ${getString(R.string.connected)} FTMS ===")
        if (ftmsConnectionManager?.isConnected == true) {
            sb.appendLine("Name: ${ftmsConnectionManager?.connectedDeviceName}")
            sb.appendLine("Addr: ${ftmsConnectionManager?.connectedDeviceAddress}")
            sb.appendLine("Type: ${fitnessDevice?.machineType}")
            fitnessDevice?.capabilities?.let { caps ->
                sb.appendLine("${getString(R.string.capabilities)}: ${describeCaps(caps)}")
            }
            sb.appendLine()
            sb.appendLine("${getString(R.string.recent_bt_events)}:")
            ftmsConnectionManager?.recentEvents?.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine(getString(R.string.not_connected))
        }

        sb.appendLine()
        sb.appendLine("=== ${getString(R.string.connected)} HR ===")
        if (hrConnectionManager?.isConnected == true) {
            sb.appendLine("Name: ${hrConnectionManager?.connectedDeviceName}")
            sb.appendLine("Addr: ${hrConnectionManager?.connectedDeviceAddress}")
            sb.appendLine("Last HR: $lastHeartRateBpm bpm")
            sb.appendLine()
            sb.appendLine("${getString(R.string.recent_bt_events)}:")
            hrConnectionManager?.recentEvents?.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine(getString(R.string.not_connected))
        }

        sb.appendLine()
        sb.appendLine("=== ${getString(R.string.log_files)} ===")
        sb.appendLine(getString(R.string.debug_log_location))
        val logFiles = BtDebugLogger.getAllLogFiles(this)
        if (logFiles.isEmpty()) {
            sb.appendLine(getString(R.string.no_log_files))
        } else {
            logFiles.take(10).forEach {
                sb.appendLine("• ${it.name} (${it.length() / 1024} KB)")
            }
            if (logFiles.size > 10) sb.appendLine("… +${logFiles.size - 10} more")
        }
        sb.appendLine()
        sb.appendLine("Debug mode: ${BuildConfig.BT_DEBUG_LOG}")

        val scrollView = ScrollView(this).apply {
            val tv = TextView(this@MainActivity).apply {
                text = sb.toString()
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(32, 16, 32, 16)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            addView(tv)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.debug_info))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.export_logs)) { _, _ -> exportDebugLogs() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun describeCaps(caps: FtmsCapabilities): String {
        val features = mutableListOf<String>()
        if (caps.supportsAverageSpeed) features.add("AvgSpeed")
        if (caps.supportsCadence) features.add("Cadence")
        if (caps.supportsTotalDistance) features.add("Distance")
        if (caps.supportsInclination) features.add("Inclination")
        if (caps.supportsPowerMeasurement) features.add("Power")
        if (caps.supportsHeartRate) features.add("HR")
        if (caps.supportsExpendedEnergy) features.add("Energy")
        if (caps.supportsElapsedTime) features.add("Time")
        if (caps.supportsResistanceLevel) features.add("Resistance")
        return features.joinToString(", ").ifEmpty { "None detected" }
    }

    private fun exportDebugLogs() {
        val logFiles = BtDebugLogger.getAllLogFiles(this)
        if (logFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_log_files), Toast.LENGTH_SHORT).show()
            return
        }
        val uris = logFiles.map { file ->
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_logs)))
    }

    // ---- Lifecycle ----------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        updateHandler.removeCallbacks(scanListUpdateRunnable)
        bleScanner.stopScan()
        if (isRecording) stopRecording()
        ftmsConnectionManager?.disconnect()
        hrConnectionManager?.disconnect()
        debugLogger.close()
        hrDebugLogger.close()
    }
}

