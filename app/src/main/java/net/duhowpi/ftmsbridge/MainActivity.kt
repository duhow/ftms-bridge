package net.duhowpi.ftmsbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
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
import kotlin.math.roundToInt

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
    private var isMachineRunning = false

    // Raw scan results map, updated on every BLE event
    private val scanResultsMap = mutableMapOf<String, ScannedDeviceInfo>()

    // Devices that were previously connected; survives scan cycles so the user can
    // reconnect without re-scanning.
    private val knownDevicesMap = mutableMapOf<String, ScannedDeviceInfo>()

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

        binding.toolbar.title = getString(R.string.app_name)
        setSupportActionBar(binding.toolbar)

        db = AppDatabase.getInstance(this)
        debugLogger = BtDebugLogger(BuildConfig.BT_DEBUG_LOG, this)
        hrDebugLogger = BtDebugLogger(BuildConfig.BT_DEBUG_LOG, this)
        bleScanner = BleScanner(this)

        setupScanList()
        setupUI()

        // Request permissions immediately on app start (without auto-scanning)
        requestPermissionsIfNeeded()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> { showAboutDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_version, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, WorkoutHistoryActivity::class.java))
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

                // Skip devices that advertise service UUIDs but none of them are fitness-related
                // (e.g. audio headphones, earbuds). Devices with no service UUIDs in their
                // advertisement are kept as "unknown" since some fitness equipment omits them.
                // This prevents audio Bluetooth devices from cluttering the scan list.
                if (serviceUuids.isNotEmpty() && !isFtms && !isHr) return

                // Skip devices that are known non-fitness peripherals by name pattern.
                if (isIgnoredDevice(name)) {
                    debugLogger.logMessage("Ignored device: $name (${device.address})")
                    return
                }

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
        addBondedDevicesToList()
        updateScanListUI() // final refresh
        binding.btnScan.text = getString(R.string.scan)
        binding.txtScanStatus.text = getString(R.string.devices_found, scanResultsMap.size)
    }

    private fun updateScanListUI() {
        // Merge current scan results with known devices (previously connected) that
        // are not present in the current scan — so the user can always reconnect.
        val combined = scanResultsMap.toMutableMap()
        knownDevicesMap.forEach { (addr, info) ->
            if (!combined.containsKey(addr)) combined[addr] = info
        }
        val list = combined.values.toList()
        runOnUiThread {
            binding.txtScanStatus.text = if (bleScanner.isScanning)
                "${getString(R.string.scanning_active)} ${scanResultsMap.size}"
            else
                getString(R.string.devices_found, scanResultsMap.size)
            if (list.isNotEmpty()) {
                binding.rvScanResults.visibility = View.VISIBLE
                binding.txtScanStatus.visibility = View.VISIBLE
            }
            scanAdapter.updateAll(list)
            // Mark already-connected devices
            ftmsConnectionManager?.connectedDeviceAddress?.let { scanAdapter.markConnected(it) }
            hrConnectionManager?.connectedDeviceAddress?.let { scanAdapter.markConnected(it) }
        }
    }

    // ---- Device connection from scan list -----------------------------------

    private fun onDeviceConnectTapped(info: ScannedDeviceInfo) {
        // Save device info so it remains accessible for reconnect after scanning stops
        knownDevicesMap[info.address] = info
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
                isMachineRunning = false
                runOnUiThread {
                    updateConnectionStatus()
                    scanAdapter.markDisconnected(device.address)
                    resetMetrics()
                    if (isRecording) stopRecording()
                    Toast.makeText(this@MainActivity, getString(R.string.device_disconnected), Toast.LENGTH_SHORT).show()
                    updateScanListUI()
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
                    updateMetricVisibility(fitnessDevice?.machineType ?: FtmsConstants.MachineType.UNKNOWN)
                }
            }

            override fun onFtmsData(uuid: UUID, data: ByteArray) {
                val sample = fitnessDevice?.onDataReceived(data) ?: return
                val mergedSample = if (sample.heartRateBpm == 0 && lastHeartRateBpm > 0)
                    sample.copy(heartRateBpm = lastHeartRateBpm) else sample
                // Detect machine running state for devices without machine-status updates.
                // BH indoor bikes do not provide a meaningful speed field, so use cadence/power.
                val machineType = fitnessDevice?.machineType
                val nowRunning = if (machineType == FtmsConstants.MachineType.INDOOR_BIKE) {
                    mergedSample.cadenceRpm > 10.0 || mergedSample.instantaneousPowerW > 5
                } else {
                    mergedSample.speedKmh > 0.1
                }
                if (nowRunning != isMachineRunning) {
                    isMachineRunning = nowRunning
                    runOnUiThread { updateMachineRunningState() }
                }
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

            override fun onMachineStatusChanged(opCode: Int, params: ByteArray) {
                val nowRunning = when (opCode) {
                    FtmsConstants.MACHINE_STATUS_STARTED_OR_RESUMED -> true
                    FtmsConstants.MACHINE_STATUS_STOPPED_OR_PAUSED,
                    FtmsConstants.MACHINE_STATUS_STOPPED_BY_SAFETY_KEY,
                    FtmsConstants.MACHINE_STATUS_RESET -> false
                    else -> return
                }
                if (nowRunning == isMachineRunning) return
                isMachineRunning = nowRunning
                runOnUiThread { updateMachineRunningState() }
            }

            override fun onIConceptData(data: ByteArray) {
                fitnessDevice?.onIConceptData(data)
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
                    updateScanListUI()
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

            override fun onMachineStatusChanged(opCode: Int, params: ByteArray) {}

            override fun onIConceptData(data: ByteArray) {}
        })
    }

    // ---- UI status ----------------------------------------------------------

    private fun updateConnectionStatus() {
        val ftmsConnected = ftmsConnectionManager?.isConnected == true
        val hrConnected = hrConnectionManager?.isConnected == true
        val hasHrDevice = hrConnectionManager != null

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

        // Show the HR status row only once a second device has been connected/attempted
        binding.hrStatusRow.visibility = if (hasHrDevice) View.VISIBLE else View.GONE

        if (!ftmsConnected) {
            binding.txtFtmsDeviceInfo.visibility = View.GONE
        } else {
            updateFtmsDeviceInfoUI()
        }

        binding.workoutButtonRow.visibility = if (ftmsConnected) View.VISIBLE else View.GONE
        binding.btnWorkoutStart.isEnabled = ftmsConnected
    }

    /** Called whenever the machine transitions between running and stopped state. */
    private fun updateMachineRunningState() {
        if (isMachineRunning && !isRecording) {
            startRecording()
        } else if (!isMachineRunning && isRecording) {
            stopRecording()
        }
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
        // Prefer device-reported elapsed time; fall back to wall-clock when recording.
        // BH Fitness devices either omit the elapsed-time field entirely or always send 0.
        val elapsedSec = if (sample.elapsedTimeSec > 0) {
            sample.elapsedTimeSec
        } else if (isRecording && sessionStartTime > 0) {
            ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
        } else 0

        binding.valueSpeed.text = String.format("%.1f", sample.speedKmh)
        binding.valueCadence.text = if (sample.cadenceRpm > 0) String.format("%.0f", sample.cadenceRpm) else "--"
        binding.valuePower.text = if (sample.instantaneousPowerW > 0) "${sample.instantaneousPowerW}" else "--"
        binding.valueDistance.text = String.format("%.2f", sample.totalDistanceM / 1000.0)
        if (sample.heartRateBpm > 0) binding.valueHeartRate.text = "${sample.heartRateBpm}"

        // Energy tile: show strides/min (BH Fitness indoor bike) when available,
        // otherwise show calories. Always show a numeric value once data is received.
        if (sample.stridesPerMin > 0) {
            binding.labelEnergy.setText(R.string.metric_strides)
            binding.unitEnergy.setText(R.string.unit_per_min)
            binding.valueEnergy.text = String.format("%.1f", sample.stridesPerMin)
        } else {
            binding.labelEnergy.setText(R.string.metric_energy)
            binding.unitEnergy.setText(R.string.unit_kcal)
            binding.valueEnergy.text = "${sample.totalEnergyKcal}"
        }

        binding.valueInclination.text = "${sample.inclinationPercent.roundToInt()}"
        binding.valueResistance.text = if (sample.resistanceLevel > 0) "${sample.resistanceLevel}" else "--"
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        binding.valueElapsedTime.text = String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Show only the metric tiles that are relevant to [machineType].
     * The Energy tile always shows; its label/unit switches between kcal and strides/min
     * depending on the data received (see [updateDashboard]).
     *
     * Treadmill      → Speed, HR, Distance, Energy, Time, Inclination
     * Indoor Bike    → HR, Cadence, Power, Distance, Energy, Time, Resistance
     * Cross Trainer  → Speed, HR, Cadence, Power, Distance, Energy, Time, Resistance
     * Stair Climber  → HR, Cadence, Distance, Energy, Time
     * Unknown / disconnected → only the universal tiles (no device-specific tiles)
     */
    private fun updateMetricVisibility(machineType: FtmsConstants.MachineType) {
        val isTreadmill = machineType == FtmsConstants.MachineType.TREADMILL
        val isIndoorBike = machineType == FtmsConstants.MachineType.INDOOR_BIKE
        val isBike = machineType == FtmsConstants.MachineType.INDOOR_BIKE ||
                machineType == FtmsConstants.MachineType.CROSS_TRAINER
        val showSpeed = machineType != FtmsConstants.MachineType.INDOOR_BIKE
        binding.cardSpeed.visibility = if (showSpeed) View.VISIBLE else View.GONE
        binding.rowCadencePower.visibility = if (isBike) View.VISIBLE else View.GONE
        binding.cardInclination.visibility = if (isTreadmill) View.VISIBLE else View.GONE
        binding.cardResistance.visibility = if (isBike) View.VISIBLE else View.GONE
        // When speed is hidden on indoor bike, keep HR tile as the only first-row metric.
        binding.cardHeartRate.layoutParams = (binding.cardHeartRate.layoutParams as LinearLayout.LayoutParams).apply {
            weight = if (isIndoorBike) 2f else 1f
        }
    }

    private fun resetMetrics() {
        binding.valueSpeed.text = "--"
        binding.valueCadence.text = "--"
        binding.valuePower.text = "--"
        binding.valueDistance.text = "--"
        binding.valueHeartRate.text = "--"
        binding.valueEnergy.text = "--"
        binding.labelEnergy.setText(R.string.metric_energy)
        binding.unitEnergy.setText(R.string.unit_kcal)
        binding.valueElapsedTime.text = "0:00"
        binding.valueInclination.text = "--"
        binding.valueResistance.text = "--"
        binding.txtMachineType.visibility = View.GONE
        binding.txtFtmsDeviceInfo.visibility = View.GONE
        binding.metricsSection.visibility = View.GONE
        updateMetricVisibility(FtmsConstants.MachineType.UNKNOWN)
    }

    // ---- Session recording --------------------------------------------------

    private fun startRecording() {
        val device = fitnessDevice ?: return
        isRecording = true
        sessionStartTime = System.currentTimeMillis()
        binding.btnWorkoutStart.text = getString(R.string.stop_session)
        binding.recordingIndicator.visibility = View.VISIBLE
        binding.metricsSection.visibility = View.VISIBLE
        // Hide scan results list during workout to reduce clutter
        binding.rvScanResults.visibility = View.GONE
        binding.txtScanStatus.visibility = View.GONE
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
        // Use wall-clock elapsed time when device sends 0 (BH Fitness quirk).
        val elapsedSec = if (sample.elapsedTimeSec > 0) sample.elapsedTimeSec
        else ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
        lifecycleScope.launch(Dispatchers.IO) {
            db.sampleDao().insert(
                WorkoutSample(
                    sessionId = sessionId,
                    timestampMs = System.currentTimeMillis(),
                    elapsedTimeSec = elapsedSec,
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

    // ---- Device filtering ---------------------------------------------------

    /**
     * Adds BLE devices that are already bonded to this phone (via the OS bonded-devices
     * list) but were not found during the active BLE scan.  This makes paired wearables
     * visible even when they are not currently advertising — for example a Xiaomi / Mi Band
     * device managed by Gadgetbridge that uses proprietary service UUIDs and is therefore
     * filtered out during the active scan.
     *
     * To expose real-time HR from a Gadgetbridge-managed device:
     *   1. Open Gadgetbridge → device settings → enable "3rd party realtime HR access".
     *   2. Enable "Visible while connected" in the same device settings.
     *   3. Select the device (shown here as "Paired") and choose "HR Sensor".
     */
    private fun addBondedDevicesToList() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bleScanner.getBondedBleDevices().forEach { device ->
            val name = device.name ?: return@forEach
            if (name.isBlank()) return@forEach
            if (scanResultsMap.containsKey(device.address)) return@forEach
            // Exclude audio/video peripherals (headsets, speakers, earbuds) by Bluetooth class.
            // Classic BT audio devices report DEVICE_TYPE_CLASSIC or DEVICE_TYPE_UNKNOWN and
            // always have the AUDIO_VIDEO major device class.
            val majorClass = device.bluetoothClass?.majorDeviceClass
            if (majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO) return@forEach
            if (isIgnoredDevice(name)) return@forEach
            scanResultsMap[device.address] = ScannedDeviceInfo(
                name = name,
                address = device.address,
                rssi = Int.MIN_VALUE,
                isFtms = false,
                isHr = false,
                device = device,
                isBonded = true
            )
            debugLogger.logMessage("Bonded BLE device added to list: $name (${device.address})")
        }
    }

    /**
     * Returns true if [name] matches a known non-fitness BLE device that should be
     * excluded from the scan list. Checked after the service-UUID filter so this
     * mainly catches devices that do not advertise service UUIDs.
     */
    private fun isIgnoredDevice(name: String): Boolean {
        for (prefix in IGNORED_DEVICE_PREFIXES) {
            if (name.startsWith(prefix, ignoreCase = true)) return true
        }
        for (fragment in IGNORED_DEVICE_CONTAINS) {
            if (name.contains(fragment, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        /** Device name prefixes that identify non-fitness BLE peripherals. */
        private val IGNORED_DEVICE_PREFIXES = listOf(
            "Nuki_",        // Nuki smart locks
            "[TV]",         // Samsung / LG smart TVs
            "[AV]",
            "Flip_",        // JBL Flip speakers
            "Charge_",      // JBL Charge speakers
            "WH-",          // Sony over-ear headphones
            "WF-",          // Sony true-wireless earbuds
            "MDR-",         // Sony MDR headphones / earphones
            "SRS-",         // Sony SRS Bluetooth speakers
            "Mi TV",        // Xiaomi smart TV
            "PHILIPS HUE",  // Philips Hue lighting bridge
            "Hue-",         // Philips Hue accessories
            "SmartThings",  // Samsung SmartThings hubs
            "Ring-",        // Ring smart home devices
        )

        /** Case-insensitive substrings that identify non-fitness BLE peripherals. */
        private val IGNORED_DEVICE_CONTAINS = listOf(
            "soundcore",    // Anker SoundCore speakers / earbuds
            "airpods",      // Apple AirPods
            " buds",        // Generic "Buds" earbuds (e.g. Galaxy Buds)
            "headphone",    // Generic headphone devices
            "headset",      // Generic headset devices
            "keyboard",     // Bluetooth keyboards
            "mouse",        // Bluetooth mice
            "trackpad",     // Bluetooth trackpads
            "smart plug",   // Smart plugs
            "smart bulb",   // Smart bulbs
            "smart lamp",   // Smart lamps
        )
    }
}
