package net.duhowpi.ftmsbridge

import android.Manifest
import android.graphics.drawable.Animatable
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
import android.widget.Button
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import net.duhowpi.ftmsbridge.device.BhFitnessIndoorBike
import net.duhowpi.ftmsbridge.device.DummyTreadmill
import net.duhowpi.ftmsbridge.device.FitnessDevice
import net.duhowpi.ftmsbridge.device.FtmsDevice
import net.duhowpi.ftmsbridge.device.HeartRateSensor
import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.ftms.FtmsDataParser
import net.duhowpi.ftmsbridge.model.FitnessSample
import net.duhowpi.ftmsbridge.model.ScannedDeviceInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private var probeConnectionManager: BleConnectionManager? = null

    private var fitnessDevice: FtmsDevice? = null
    private var heartRateSensor: HeartRateSensor? = null

    private var currentSessionId: Long? = null
    private var sessionStartTime: Long = 0
    private var elapsedFallbackStartTime: Long = 0
    private var lastFallbackElapsedSec: Int = 0
    private var isRecording = false
    private var isMachineRunning = false

    // Raw scan results map, updated on every BLE event
    private val scanResultsMap = mutableMapOf<String, ScannedDeviceInfo>()

    // Devices that were previously connected; survives scan cycles so the user can
    // reconnect without re-scanning.
    private val knownDevicesMap = mutableMapOf<String, ScannedDeviceInfo>()

    // Last known HR for merging into FTMS samples
    private var lastHeartRateBpm = 0
    private var lastFtmsSample: FitnessSample? = null

    // Whether permissions were requested from scan button (so we auto-start scan)
    private var pendingScanAfterPermission = false

    // Live chart data (collected every updateDashboard call, cleared on new session)
    private val liveSpeedPoints = mutableListOf<Float>()
    private val livePaceSecondaryPoints = mutableListOf<Float>()
    private val liveHrPoints = mutableListOf<Float>()
    private var liveChartElapsedSec = 0

    // Lap tracking
    private var lapStartDistanceM: Int = 0
    private var lapStartTimeMs: Long = 0
    private var lapCount: Int = 0

    // Workout view state: false = LAP view, true = CHART view
    private var isChartViewActive = false

    private val isActiveTreadmill: Boolean
        get() = dummyTreadmill != null || fitnessDevice?.machineType == FtmsConstants.MachineType.TREADMILL

    // DummyTreadmill (debug only)
    private var dummyTreadmill: DummyTreadmill? = null
    private val dummyTreadmillHandler = Handler(Looper.getMainLooper())
    private val dummyTreadmillRunnable = object : Runnable {
        override fun run() {
            val dummy = dummyTreadmill ?: return
            val sample = dummy.generateSample()
            val nowRunning = sample.speedKmh > 0.1
            if (nowRunning != isMachineRunning) {
                isMachineRunning = nowRunning
                updateMachineRunningState()
            }
            lastFtmsSample = sample
            updateDashboard(sample)
            if (isRecording && currentSessionId != null) saveSample(sample)
            dummyTreadmillHandler.postDelayed(this, 2000)
        }
    }

    // Recording throttle: minimum 2s between saves; identical data saved at most every 5s
    private var lastSavedSampleMs: Long = 0
    private var lastSavedSampleData: FitnessSample? = null

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
            pendingScanAfterPermission = false
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

        createDebugSampleDataIfNeeded()

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
            R.id.action_history -> { startActivity(Intent(this, WorkoutHistoryActivity::class.java)); true }
            R.id.action_debug -> { showDebugDialog(); true }
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

        binding.btnWorkoutStart.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        binding.cardSpeed.setOnClickListener { showSpeedControlDialog() }
        binding.cardInclination.setOnClickListener { showInclineControlDialog() }

        binding.btnViewLap.setOnClickListener { setWorkoutView(false) }
        binding.btnViewChart.setOnClickListener { setWorkoutView(true) }
        if (BuildConfig.BT_DEBUG_LOG) {
            binding.btnConnectVirtual.setOnClickListener { connectDummyTreadmill() }
        }

        updateConnectionStatus()
        resetMetrics()
    }

    // ---- Permissions --------------------------------------------------------

    private fun setWorkoutView(showChart: Boolean) {
        isChartViewActive = showChart
        binding.lapSection.visibility = if (!showChart) View.VISIBLE else View.GONE
        binding.chartSection.visibility = if (showChart) View.VISIBLE else View.GONE
        if (showChart) updateLiveChart()
    }

    private fun updateLiveChart() {
        if (liveSpeedPoints.isEmpty()) return
        val isTreadmill = isActiveTreadmill
        val durationSec = liveChartElapsedSec.coerceAtLeast(60)
        val speedLabel = if (isTreadmill) getString(R.string.metric_speed) else getString(R.string.metric_cadence)
        val secondaryLabel = if (isTreadmill) getString(R.string.metric_inclination) else getString(R.string.metric_resistance)
        val speedColor = ContextCompat.getColor(this, R.color.metric_speed)
        val secondaryColor = if (isTreadmill)
            ContextCompat.getColor(this, R.color.metric_inclination)
        else
            ContextCompat.getColor(this, R.color.metric_resistance)
        val hrColor = ContextCompat.getColor(this, R.color.metric_heart)

        val seriesList = mutableListOf(
            LineChartView.DataSeries(speedLabel, speedColor, liveSpeedPoints.toList(), 0f)
        )
        if (hasNonZeroValues(livePaceSecondaryPoints)) {
            seriesList.add(LineChartView.DataSeries(secondaryLabel, secondaryColor, livePaceSecondaryPoints.toList()))
        }
        if (hasNonZeroValues(liveHrPoints)) {
            seriesList.add(LineChartView.DataSeries(getString(R.string.metric_heart_rate), hrColor, liveHrPoints.toList(), 40f, 200f))
        }
        binding.liveChart.setData(*seriesList.toTypedArray(), durationSec = durationSec)
    }

    private fun updateLapView(sample: FitnessSample, elapsedSec: Int) {
        val distM = sample.totalDistanceM
        val lapProgressM = (distM - lapStartDistanceM).coerceAtLeast(0)
        if (lapProgressM >= LAP_DISTANCE_METERS) {
            lapCount++
            lapStartDistanceM = distM - (lapProgressM % LAP_DISTANCE_METERS)
            lapStartTimeMs = System.currentTimeMillis()
        }
        val currentLapM = (distM - lapStartDistanceM).coerceAtLeast(0).coerceAtMost(LAP_DISTANCE_METERS)
        val lapElapsedMs = System.currentTimeMillis() - lapStartTimeMs
        val lapSec = (lapElapsedMs / 1000).toInt()
        binding.progressBarLap.progress = currentLapM
        // Show total distance covered, not just within-lap distance
        binding.txtLapProgress.text = getString(R.string.lap_progress_label, distM / 1000.0)
        val lm = lapSec / 60
        val ls = lapSec % 60
        binding.txtLapTime.text = String.format("%d:%02d", lm, ls)
        val tm = elapsedSec / 60
        val ts = elapsedSec % 60
        binding.txtLapCount.text = String.format("%d:%02d", tm, ts)
        // Inline metrics row inside the lap card
        binding.lapValueSpeed.text = String.format("%.1f", sample.speedKmh)
        if (sample.stridesPerMin > 0) {
            binding.lapValueEnergy.text = String.format("%.1f", sample.stridesPerMin)
            binding.lapUnitEnergy.setText(R.string.unit_per_min)
        } else {
            binding.lapValueEnergy.text = "${sample.totalEnergyKcal}"
            binding.lapUnitEnergy.setText(R.string.unit_kcal)
        }
        binding.lapValueHr.text = if (sample.heartRateBpm > 0) "${sample.heartRateBpm}" else "--"
    }

    /** Returns true if [points] contains at least one non-zero value. */
    private fun hasNonZeroValues(points: List<Float>) = points.any { it != 0f }

    private fun connectDummyTreadmill() {
        val dummy = DummyTreadmill()
        dummyTreadmill = dummy
        binding.rvScanResults.visibility = View.GONE
        binding.txtScanStatus.visibility = View.GONE
        binding.debugVirtualDeviceRow.visibility = View.GONE
        updateConnectionStatus()
        updateMetricVisibility(dummy.machineType)
        dummyTreadmillHandler.removeCallbacks(dummyTreadmillRunnable)
        dummyTreadmillHandler.post(dummyTreadmillRunnable)
    }

    // ---- Permissions (continued) --------------------------------------------

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

    /** Request permissions on startup — auto-starts scan if already granted. */
    private fun requestPermissionsIfNeeded() {
        val needed = getRequiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            pendingScanAfterPermission = true
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            // All permissions already granted — auto-start scan
            if (bleScanner.isBluetoothEnabled()) {
                startScanning()
            } else {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
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
        if (BuildConfig.BT_DEBUG_LOG) {
            binding.debugVirtualDeviceRow.visibility = View.VISIBLE
        }
        binding.rvScanResults.visibility = View.VISIBLE
        binding.txtScanStatus.visibility = View.VISIBLE
        binding.txtScanStatus.text = getString(R.string.scanning_active)

        // Spin the scan button icon while scanning (animated vector rotates only the icon)
        binding.btnScan.setIconResource(R.drawable.ic_refresh_anim)
        (binding.btnScan.icon as? Animatable)?.start()

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
                val isHr = serviceUuids.contains(FtmsConstants.HR_SERVICE_UUID) ||
                        serviceUuids.contains(FtmsConstants.MIBAND_HR_SERVICE_UUID)

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
        binding.txtScanStatus.text = getString(R.string.devices_found, scanResultsMap.size)
        // Stop spinning and restore static icon
        (binding.btnScan.icon as? Animatable)?.stop()
        binding.btnScan.setIconResource(R.drawable.ic_refresh)
    }

    private fun updateScanListUI() {
        if (isRecording) return
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
            else -> probeAndConnect(info.device)
        }
    }

    /**
     * If [hasHeartRate] is true, ensures the [ScannedDeviceInfo] entry for [address] is
     * marked with [ScannedDeviceInfo.isHr] = true so the scan list label updates from
     * "BLE" / "Paired" to "HR" (or "FTMS+HR") once the actual service is confirmed at
     * connection time.  Must be called on the main thread.
     */
    private fun markScanEntryHr(address: String, hasHeartRate: Boolean) {
        if (!hasHeartRate) return
        scanResultsMap[address]?.let { existing ->
            if (!existing.isHr) scanResultsMap[address] = existing.copy(isHr = true)
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
                val name = device.name ?: getString(R.string.unknown_device)
                fitnessDevice = FtmsDevice.createFromCharacteristics(name, ftmsCharacteristics)
                runOnUiThread {
                    // Update scan entry with detected machine type
                    val mt = fitnessDevice?.machineType?.name
                    if (mt != null) {
                        scanResultsMap[device.address]?.let { existing ->
                            scanResultsMap[device.address] = existing.copy(machineType = mt)
                        }
                        knownDevicesMap[device.address]?.let { existing ->
                            knownDevicesMap[device.address] = existing.copy(machineType = mt)
                        }
                    }
                    markScanEntryHr(device.address, hasHeartRate)
                    updateConnectionStatus()
                    updateScanListUI()
                    binding.txtMachineType.text = fitnessDevice?.machineType?.name ?: "?"
                    binding.txtMachineType.visibility = View.VISIBLE
                    updateMetricVisibility(fitnessDevice?.machineType ?: FtmsConstants.MachineType.UNKNOWN)
                }
            }

            override fun onFtmsData(uuid: UUID, data: ByteArray) {
                val sample = fitnessDevice?.onDataReceived(data) ?: return
                val mergedSample = if (sample.heartRateBpm == 0 && lastHeartRateBpm > 0)
                    sample.copy(heartRateBpm = lastHeartRateBpm) else sample
                lastFtmsSample = mergedSample
                // Detect machine running state for devices without machine-status updates.
                // BH indoor bikes do not provide a meaningful speed field, so use cadence/power.
                val isBhIndoorBike = fitnessDevice is BhFitnessIndoorBike
                val nowRunning = if (isBhIndoorBike) {
                    mergedSample.cadenceRpm > BhFitnessIndoorBike.MIN_MOVING_CADENCE_RPM ||
                            mergedSample.instantaneousPowerW > BhFitnessIndoorBike.MIN_MOVING_POWER_W
                } else {
                    mergedSample.speedKmh > 0.1
                }
                if (nowRunning != isMachineRunning) {
                    isMachineRunning = nowRunning
                    runOnUiThread { updateMachineRunningState() }
                }
                if (shouldAnchorFallbackTimer(mergedSample, nowRunning)) {
                    elapsedFallbackStartTime = mergedSample.timestampMs
                }
                runOnUiThread {
                    updateDashboard(mergedSample)
                    animateBeat(binding.indicatorFtms)
                    animateBeat(binding.toolbarIndicatorFtms)
                }
                if (isRecording && currentSessionId != null) saveSample(mergedSample)
            }

            override fun onHeartRateData(data: ByteArray) {
                lastHeartRateBpm = FtmsDataParser.parseHeartRate(data)
                runOnUiThread {
                    binding.valueHeartRate.text = if (lastHeartRateBpm > 0) "$lastHeartRateBpm" else "--"
                    animateBeat(binding.indicatorHr)
                    animateBeat(binding.toolbarIndicatorHr)
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
                runOnUiThread {
                    markScanEntryHr(device.address, hasHeartRate)
                    updateConnectionStatus()
                    updateScanListUI()
                }
            }

            override fun onFtmsData(uuid: UUID, data: ByteArray) {}

            override fun onHeartRateData(data: ByteArray) {
                val hr = heartRateSensor?.onDataReceived(data) ?: FtmsDataParser.parseHeartRate(data)
                lastHeartRateBpm = hr
                runOnUiThread {
                    binding.valueHeartRate.text = if (hr > 0) "$hr" else "--"
                    animateBeat(binding.indicatorHr)
                    animateBeat(binding.toolbarIndicatorHr)
                }
            }

            override fun onFeaturesRead(data: ByteArray) {}

            override fun onDeviceInfoRead() {}

            override fun onMachineStatusChanged(opCode: Int, params: ByteArray) {}

            override fun onIConceptData(data: ByteArray) {}
        })
    }

    /**
     * Connects to [device] using a lightweight probe connection to discover its GATT services.
     * Once services are known, automatically routes to [connectFtmsDevice] or [connectHrDevice].
     * Falls back to a manual-selection dialog only if service discovery yields no recognisable type.
     *
     * This is used for bonded devices whose service UUIDs are not cached in the Android OS
     * Bluetooth database (e.g. a Mi Band that has never been connected via this app).
     */
    private fun probeAndConnect(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        probeConnectionManager?.disconnect()
        probeConnectionManager = BleConnectionManager(this, debugLogger)
        probeConnectionManager?.connect(device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected(deviceName: String) {
                runOnUiThread {
                    updateConnectionStatus()
                    scanAdapter.markConnected(device.address)
                }
            }

            override fun onDisconnected() {
                // Only update UI if the probe was not superseded by a real connection.
                if (probeConnectionManager != null) {
                    probeConnectionManager = null
                    runOnUiThread {
                        updateConnectionStatus()
                        scanAdapter.markDisconnected(device.address)
                        updateScanListUI()
                    }
                }
            }

            override fun onServicesReady(ftmsCharacteristics: List<UUID>, hasHeartRate: Boolean) {
                val hasFtms = ftmsCharacteristics.isNotEmpty()
                // Null out first so the onDisconnected guard above does not fire UI updates
                // when we intentionally disconnect the probe below.
                val probeMgr = probeConnectionManager
                probeConnectionManager = null
                runOnUiThread {
                    // Disconnect probe before opening the real connection.
                    probeMgr?.disconnect()
                    when {
                        hasFtms -> {
                            connectFtmsDevice(device)
                        }
                        hasHeartRate -> {
                            connectHrDevice(device)
                        }
                        else -> {
                            // Still unknown — fall back to manual selection dialog
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(device.name ?: getString(R.string.unknown_device))
                                .setMessage(getString(R.string.type_not_detected_message))
                                .setPositiveButton(getString(R.string.connect_as_ftms)) { _, _ -> connectFtmsDevice(device) }
                                .setNeutralButton(getString(R.string.connect_as_hr)) { _, _ -> connectHrDevice(device) }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }

            override fun onFtmsData(uuid: UUID, data: ByteArray) {}
            override fun onHeartRateData(data: ByteArray) {}
            override fun onFeaturesRead(data: ByteArray) {}
            override fun onDeviceInfoRead() {}
            override fun onMachineStatusChanged(opCode: Int, params: ByteArray) {}
            override fun onIConceptData(data: ByteArray) {}
        })
    }

    private fun updateConnectionStatus() {
        val ftmsConnected = ftmsConnectionManager?.isConnected == true || dummyTreadmill != null
        val hrConnected = hrConnectionManager?.isConnected == true
        val hasHrDevice = hrConnectionManager != null

        val ftmsColor = ContextCompat.getColor(this, if (ftmsConnected) R.color.status_connected else R.color.status_disconnected)
        val hrColor = ContextCompat.getColor(this, if (hrConnected) R.color.status_connected else R.color.status_disconnected)

        binding.indicatorFtms.setColorFilter(ftmsColor)
        binding.indicatorHr.setColorFilter(hrColor)
        // Keep toolbar indicators in sync
        binding.toolbarIndicatorFtms.setColorFilter(ftmsColor)
        binding.toolbarIndicatorHr.setColorFilter(hrColor)
        if (isRecording) {
            binding.toolbarIndicatorHr.visibility = if (hasHrDevice) View.VISIBLE else View.GONE
        }

        binding.txtFtmsDevice.text = when {
            dummyTreadmill != null -> dummyTreadmill?.deviceName ?: getString(R.string.connected)
            ftmsConnected -> ftmsConnectionManager?.connectedDeviceName ?: getString(R.string.connected)
            else -> getString(R.string.not_connected)
        }

        binding.txtHrDevice.text = if (hrConnected)
            hrConnectionManager?.connectedDeviceName ?: getString(R.string.connected)
        else getString(R.string.not_connected)

        // Show the HR status row only once a second device has been connected/attempted
        binding.hrStatusRow.visibility = if (hasHrDevice) View.VISIBLE else View.GONE

        if (!ftmsConnected) {
            binding.txtFtmsDeviceInfo.visibility = View.GONE
            lastFtmsSample = null
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
        } else if (isRecording && elapsedFallbackStartTime > 0) {
            calculateFallbackElapsedSec(sample.timestampMs)
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

        // Accumulate live chart data while recording
        if (isRecording) {
            liveChartElapsedSec = elapsedSec
            val isTreadmill = isActiveTreadmill
            liveSpeedPoints.add(if (isTreadmill) sample.speedKmh.toFloat() else sample.cadenceRpm.toFloat())
            livePaceSecondaryPoints.add(
                if (isTreadmill) sample.inclinationPercent.toFloat() else sample.resistanceLevel.toFloat()
            )
            liveHrPoints.add(sample.heartRateBpm.toFloat())
            if (isChartViewActive) updateLiveChart()
            updateLapView(sample, elapsedSec)
        }
    }

    /** Scale up by 15 % then back to 1× in 200 ms total to indicate a BLE data beat. */
    private fun animateBeat(view: View) {
        view.animate()
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    /**
     * Show only the metric tiles that are relevant to [machineType].
     * The Energy tile always shows; its label/unit switches between kcal and strides/min
     * depending on the data received (see [updateDashboard]).
     *
     * Treadmill      → Speed, HR, Distance, Energy, Time, Inclination
     * Indoor Bike    → HR, Cadence, Power, Distance, Energy, Time, Resistance
     *                  (BH indoor bike variant hides Speed because that field is repurposed)
     * Cross Trainer  → Speed, HR, Cadence, Power, Distance, Energy, Time, Resistance
     * Stair Climber  → HR, Cadence, Distance, Energy, Time
     * Unknown / disconnected → only the universal tiles (no device-specific tiles)
     */
    private fun updateMetricVisibility(machineType: FtmsConstants.MachineType) {
        val isTreadmill = machineType == FtmsConstants.MachineType.TREADMILL
        val isBike = machineType == FtmsConstants.MachineType.INDOOR_BIKE ||
                machineType == FtmsConstants.MachineType.CROSS_TRAINER
        val isBhIndoorBike = fitnessDevice is BhFitnessIndoorBike
        val showSpeed = !isBhIndoorBike
        binding.cardSpeed.visibility = if (showSpeed) View.VISIBLE else View.GONE
        binding.rowCadencePower.visibility = if (isBike) View.VISIBLE else View.GONE
        binding.cardInclination.visibility = if (isTreadmill) View.VISIBLE else View.GONE
        binding.cardResistance.visibility = if (isBike) View.VISIBLE else View.GONE
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
        binding.lapValueSpeed.text = "--"
        binding.lapValueEnergy.text = "--"
        binding.lapUnitEnergy.setText(R.string.unit_kcal)
        binding.lapValueHr.text = "--"
        binding.txtMachineType.visibility = View.GONE
        binding.txtFtmsDeviceInfo.visibility = View.GONE
        binding.metricsSection.visibility = View.GONE
        updateMetricVisibility(FtmsConstants.MachineType.UNKNOWN)
    }

    // ---- Session recording --------------------------------------------------

    private fun startRecording() {
        val device: FitnessDevice = fitnessDevice ?: dummyTreadmill ?: return
        isRecording = true
        sessionStartTime = System.currentTimeMillis()
        elapsedFallbackStartTime = 0L
        lastFallbackElapsedSec = 0
        lastSavedSampleMs = 0
        lastSavedSampleData = null
        binding.btnWorkoutStart.text = getString(R.string.stop_session)
        // Hide device header and REC indicator; show compact status icons in toolbar instead
        binding.deviceHeaderRow.visibility = View.GONE
        binding.recordingIndicator.visibility = View.GONE
        val ftmsConnected = ftmsConnectionManager?.isConnected == true || dummyTreadmill != null
        val hrConnected = hrConnectionManager?.isConnected == true
        val hasHrDevice = hrConnectionManager != null
        binding.toolbarIndicatorFtms.setColorFilter(
            ContextCompat.getColor(this, if (ftmsConnected) R.color.status_connected else R.color.status_disconnected)
        )
        binding.toolbarIndicatorHr.setColorFilter(
            ContextCompat.getColor(this, if (hrConnected) R.color.status_connected else R.color.status_disconnected)
        )
        binding.toolbarIndicatorHr.visibility = if (hasHrDevice) View.VISIBLE else View.GONE
        binding.toolbarStatusIcons.visibility = View.VISIBLE
        binding.metricsSection.visibility = View.VISIBLE
        // During recording keep only the interactive cards (speed + inclination/resistance)
        binding.cardHeartRate.visibility = View.GONE
        binding.rowDistanceEnergy.visibility = View.GONE
        binding.cardElapsedTime.visibility = View.GONE
        // Hide scan results list during workout to reduce clutter
        binding.rvScanResults.visibility = View.GONE
        binding.txtScanStatus.visibility = View.GONE
        // Clear live chart data
        liveSpeedPoints.clear()
        livePaceSecondaryPoints.clear()
        liveHrPoints.clear()
        liveChartElapsedSec = 0
        // Reset lap tracking
        lapStartDistanceM = 0
        lapStartTimeMs = System.currentTimeMillis()
        lapCount = 0
        // Show workout view toggle - default to lap view
        binding.viewToggleRow.visibility = View.VISIBLE
        isChartViewActive = false
        binding.lapSection.visibility = View.VISIBLE
        binding.chartSection.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            val session = WorkoutSession(
                startTimeMs = sessionStartTime,
                machineType = device.machineType.name,
                deviceName = device.deviceName,
                deviceAddress = ftmsConnectionManager?.connectedDeviceAddress ?: "",
                hrDeviceName = hrConnectionManager?.connectedDeviceName ?: "",
                hrDeviceAddress = hrConnectionManager?.connectedDeviceAddress ?: ""
            )
            currentSessionId = db.sessionDao().insert(session)
            Log.i(tag, "Session started: $currentSessionId")
        }
    }

    private fun stopRecording() {
        isRecording = false
        elapsedFallbackStartTime = 0L
        lastFallbackElapsedSec = 0
        binding.btnWorkoutStart.text = getString(R.string.start_session)
        binding.recordingIndicator.visibility = View.GONE
        binding.toolbarStatusIcons.visibility = View.GONE
        binding.deviceHeaderRow.visibility = View.VISIBLE
        binding.viewToggleRow.visibility = View.GONE
        binding.lapSection.visibility = View.GONE
        binding.chartSection.visibility = View.GONE
        // Restore metric cards hidden during recording
        binding.cardHeartRate.visibility = View.VISIBLE
        binding.rowDistanceEnergy.visibility = View.VISIBLE
        binding.cardElapsedTime.visibility = View.VISIBLE
        val machineType = fitnessDevice?.machineType ?: dummyTreadmill?.machineType
            ?: FtmsConstants.MachineType.UNKNOWN
        updateMetricVisibility(machineType)
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
        val nowMs = System.currentTimeMillis()
        val msSinceLast = nowMs - lastSavedSampleMs

        // Minimum recording interval: 2 seconds
        if (msSinceLast < 2_000) return

        // If all measured values are identical to the last saved sample, only save every 5 seconds
        val prev = lastSavedSampleData
        if (prev != null && msSinceLast < 5_000 &&
            prev.speedKmh           == sample.speedKmh &&
            prev.cadenceRpm         == sample.cadenceRpm &&
            prev.instantaneousPowerW == sample.instantaneousPowerW &&
            prev.heartRateBpm       == sample.heartRateBpm &&
            prev.totalDistanceM     == sample.totalDistanceM &&
            prev.resistanceLevel    == sample.resistanceLevel &&
            prev.inclinationPercent == sample.inclinationPercent
        ) return

        lastSavedSampleMs = nowMs
        lastSavedSampleData = sample

        // Use wall-clock elapsed time when device sends 0 (BH Fitness quirk).
        val elapsedSec = if (sample.elapsedTimeSec > 0) sample.elapsedTimeSec
        else if (elapsedFallbackStartTime > 0) {
            calculateFallbackElapsedSec(sample.timestampMs)
        } else 0
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

    private fun showSpeedControlDialog() {
        val cm = ftmsConnectionManager
        val machine = fitnessDevice
        if (cm?.isConnected != true || machine?.machineType != FtmsConstants.MachineType.TREADMILL) {
            Toast.makeText(this, getString(R.string.control_not_available), Toast.LENGTH_SHORT).show()
            return
        }
        val current = (lastFtmsSample?.speedKmh ?: SPEED_MIN_KMH).coerceIn(SPEED_MIN_KMH, SPEED_MAX_KMH)
        showAdjustDialog(
            title = getString(R.string.control_set_speed_title),
            label = getString(R.string.control_speed_label),
            min = SPEED_MIN_KMH,
            max = SPEED_MAX_KMH,
            step = 0.1,
            largeStep = 1.0,
            initial = current,
            unitFormatter = { value -> String.format("%.1f %s", value, getString(R.string.unit_kmh)) },
            rangeText = getString(R.string.control_range_speed, SPEED_MIN_KMH, SPEED_MAX_KMH),
            dangerPredicate = { value -> value >= SPEED_DANGER_KMH },
            smallDecLabel = getString(R.string.control_dec_small),
            largeDecLabel = getString(R.string.control_dec_large),
            smallIncLabel = getString(R.string.control_inc_small),
            largeIncLabel = getString(R.string.control_inc_large)
        ) { selected ->
            sendTargetSpeedKmh(selected)
        }
    }

    private fun showInclineControlDialog() {
        val cm = ftmsConnectionManager
        val machine = fitnessDevice
        if (cm?.isConnected != true || machine?.machineType != FtmsConstants.MachineType.TREADMILL) {
            Toast.makeText(this, getString(R.string.control_not_available), Toast.LENGTH_SHORT).show()
            return
        }
        val current = (lastFtmsSample?.inclinationPercent ?: INCLINE_DEFAULT_PERCENT).coerceIn(INCLINE_MIN_PERCENT, INCLINE_MAX_PERCENT)
        showAdjustDialog(
            title = getString(R.string.control_set_incline_title),
            label = getString(R.string.control_incline_label),
            min = INCLINE_MIN_PERCENT,
            max = INCLINE_MAX_PERCENT,
            step = 0.5,
            largeStep = 1.0,
            initial = current,
            unitFormatter = { value -> "${value.roundToInt()}${getString(R.string.unit_percent)}" },
            rangeText = getString(R.string.control_range_incline, INCLINE_MIN_PERCENT.roundToInt(), INCLINE_MAX_PERCENT.roundToInt()),
            dangerPredicate = { value -> value >= INCLINE_DANGER_PERCENT || value <= INCLINE_DECLINE_DANGER_PERCENT },
            smallDecLabel = getString(R.string.control_dec_incline_small),
            largeDecLabel = getString(R.string.control_dec_incline_large),
            smallIncLabel = getString(R.string.control_inc_incline_small),
            largeIncLabel = getString(R.string.control_inc_incline_large)
        ) { selected ->
            sendTargetInclinePercent(selected)
        }
    }

    private fun showAdjustDialog(
        title: String,
        label: String,
        min: Double,
        max: Double,
        step: Double,
        largeStep: Double,
        initial: Double,
        unitFormatter: (Double) -> String,
        rangeText: String,
        dangerPredicate: (Double) -> Boolean,
        smallDecLabel: String,
        largeDecLabel: String,
        smallIncLabel: String,
        largeIncLabel: String,
        onApply: (Double) -> Boolean
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_adjust_metric, null)
        val labelView = view.findViewById<TextView>(R.id.txtDialogMetricLabel)
        val valueView = view.findViewById<TextView>(R.id.txtDialogMetricValue)
        val rangeView = view.findViewById<TextView>(R.id.txtDialogRange)
        val dangerView = view.findViewById<TextView>(R.id.txtDialogDanger)
        val seek = view.findViewById<SeekBar>(R.id.seekDialogMetric)
        val decLarge = view.findViewById<Button>(R.id.btnDialogDecLarge)
        val decSmall = view.findViewById<Button>(R.id.btnDialogDecSmall)
        val incSmall = view.findViewById<Button>(R.id.btnDialogIncSmall)
        val incLarge = view.findViewById<Button>(R.id.btnDialogIncLarge)

        labelView.text = label
        rangeView.text = rangeText
        decLarge.text = largeDecLabel
        decSmall.text = smallDecLabel
        incSmall.text = smallIncLabel
        incLarge.text = largeIncLabel
        decLarge.contentDescription = getString(R.string.control_cd_decrease_by, largeDecLabel.removePrefix("-"))
        decSmall.contentDescription = getString(R.string.control_cd_decrease_by, smallDecLabel.removePrefix("-"))
        incSmall.contentDescription = getString(R.string.control_cd_increase_by, smallIncLabel.removePrefix("+"))
        incLarge.contentDescription = getString(R.string.control_cd_increase_by, largeIncLabel.removePrefix("+"))

        val maxProgress = ((max - min) / step).roundToInt().coerceAtLeast(1)
        seek.max = maxProgress
        var selected = initial.coerceIn(min, max)

        fun updateViews() {
            val clamped = selected.coerceIn(min, max)
            selected = clamped
            seek.progress = ((clamped - min) / step).roundToInt().coerceIn(0, maxProgress)
            valueView.text = unitFormatter(clamped)
            val isDanger = dangerPredicate(clamped)
            dangerView.text = getString(if (isDanger) R.string.control_danger_zone else R.string.control_safe_zone)
            dangerView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isDanger) android.R.color.holo_red_dark else android.R.color.darker_gray
                )
            )
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selected = min + (progress * step)
                updateViews()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        decLarge.setOnClickListener { selected -= largeStep; updateViews() }
        decSmall.setOnClickListener { selected -= step; updateViews() }
        incSmall.setOnClickListener { selected += step; updateViews() }
        incLarge.setOnClickListener { selected += largeStep; updateViews() }

        updateViews()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(getString(R.string.control_apply)) { _, _ ->
                if (!onApply(selected)) {
                    Toast.makeText(this, getString(R.string.control_send_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendTargetSpeedKmh(speedKmh: Double): Boolean {
        val clamped = speedKmh.coerceIn(SPEED_MIN_KMH, SPEED_MAX_KMH)
        val encoded = (clamped * 100.0).roundToInt()
        if (!isEncodableAsSint16(encoded)) {
            Log.w(tag, "Encoded speed out of range: $encoded")
            return false
        }
        val payload = ByteBuffer.allocate(3)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(FtmsConstants.CONTROL_SET_TARGET_SPEED)
            .putShort(encoded.toShort())
            .array()
        return ftmsConnectionManager?.sendControlPoint(payload) == true
    }

    private fun sendTargetInclinePercent(inclinePercent: Double): Boolean {
        val clamped = inclinePercent.coerceIn(INCLINE_MIN_PERCENT, INCLINE_MAX_PERCENT)
        val encoded = (clamped * 10.0).roundToInt()
        if (!isEncodableAsSint16(encoded)) {
            Log.w(tag, "Encoded incline out of range: $encoded")
            return false
        }
        val payload = ByteBuffer.allocate(3)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(FtmsConstants.CONTROL_SET_TARGET_INCLINATION)
            .putShort(encoded.toShort())
            .array()
        return ftmsConnectionManager?.sendControlPoint(payload) == true
    }

    private fun calculateFallbackElapsedSec(sampleTimestampMs: Long): Int {
        val deltaMs = sampleTimestampMs - elapsedFallbackStartTime
        if (deltaMs < 0L) {
            Log.w(tag, "Fallback elapsed delta was negative: sampleTs=$sampleTimestampMs, anchor=$elapsedFallbackStartTime")
            return lastFallbackElapsedSec
        }
        val elapsed = (deltaMs / 1000).toInt()
        lastFallbackElapsedSec = elapsed
        return elapsed
    }

    private fun shouldAnchorFallbackTimer(sample: FitnessSample, nowRunning: Boolean): Boolean {
        return isRecording && nowRunning && sample.elapsedTimeSec <= 0 && elapsedFallbackStartTime == 0L
    }

    private fun isEncodableAsSint16(value: Int): Boolean {
        return value in Short.MIN_VALUE..Short.MAX_VALUE
    }

    private fun exportDebugLogs() {
        val logFile = debugLogger.getAppLogFile()
        if (logFile == null) {
            Toast.makeText(this, getString(R.string.no_log_files), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_logs)))
    }

    // ---- Lifecycle ----------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        updateHandler.removeCallbacks(scanListUpdateRunnable)
        dummyTreadmillHandler.removeCallbacks(dummyTreadmillRunnable)
        dummyTreadmill = null
        bleScanner.stopScan()
        if (isRecording) stopRecording()
        ftmsConnectionManager?.disconnect()
        hrConnectionManager?.disconnect()
        probeConnectionManager?.disconnect()
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
     * Android's Bluetooth stack caches the GATT service UUIDs after the first successful
     * connection to a bonded device ([BluetoothDevice.getUuids]).  Those cached UUIDs are
     * used here to pre-populate [ScannedDeviceInfo.isFtms] and [ScannedDeviceInfo.isHr] so
     * that tapping "Connect" on a previously-connected Mi Band routes directly to
     * [connectHrDevice] without showing the manual type-selection dialog.
     *
     * If the cache is empty (device has never been connected through the Android BT stack),
     * both flags remain false and [probeAndConnect] will auto-detect the type on first
     * connection by performing GATT service discovery.
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

            // Use Android-cached service UUIDs to pre-detect device type.
            // These are populated by the OS after the first GATT service discovery with the
            // device; empty list means this is the first time we see it.
            val cachedUuids = device.uuids?.map { it.uuid } ?: emptyList()
            val isFtms = cachedUuids.contains(FtmsConstants.FTMS_SERVICE_UUID)
            val isHr = cachedUuids.contains(FtmsConstants.HR_SERVICE_UUID) ||
                    cachedUuids.contains(FtmsConstants.MIBAND_HR_SERVICE_UUID)

            scanResultsMap[device.address] = ScannedDeviceInfo(
                name = name,
                address = device.address,
                rssi = Int.MIN_VALUE,
                isFtms = isFtms,
                isHr = isHr,
                device = device,
                isBonded = true
            )
            debugLogger.logMessage("Bonded BLE device added to list: $name (${device.address}) FTMS=$isFtms HR=$isHr cachedUUIDs=${cachedUuids.size}")
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

    /**
     * Seeds two fake workout sessions (treadmill + indoor bike) with realistic sample data
     * when running in debug mode ([BuildConfig.BT_DEBUG_LOG] == true) and the database is empty.
     * This allows testing the Workout History and Detail views without a real device.
     * Only runs once: subsequent launches skip seeding because sessions already exist.
     */
    private fun createDebugSampleDataIfNeeded() {
        if (!BuildConfig.BT_DEBUG_LOG) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (db.sessionDao().getAll().isNotEmpty()) return@launch
            val now = System.currentTimeMillis()
            // Fake treadmill session: 90 minutes ago
            val treadmillStart = now - 90 * 60 * 1000L
            val treadmillEnd = treadmillStart + 30 * 60 * 1000L
            val treadmillSession = WorkoutSession(
                startTimeMs = treadmillStart,
                endTimeMs = treadmillEnd,
                machineType = "TREADMILL",
                deviceName = "Debug Treadmill",
                totalElapsedTimeSec = 30 * 60,
                totalDistanceM = 5200,
                totalEnergyKcal = 320,
                avgSpeedKmh = 10.4,
                maxSpeedKmh = 12.0,
                deviceAddress = "AA:BB:CC:DD:EE:01"
            )
            val treadmillId = db.sessionDao().insert(treadmillSession)
            var treadmillDistanceM = 0
            val treadmillSamples = (0 until 60).map { i ->
                val elapsed = i * 30
                val phase = i.toDouble() / 60.0
                val speed = 6.0 + 4.0 * Math.sin(phase * Math.PI * 2) + 0.5 * (Math.random() - 0.5)
                val incline = 2.5 + 2.5 * Math.sin(phase * Math.PI)
                // Accumulate distance over 30-second interval at current speed
                treadmillDistanceM += (speed * 30.0 / 3.6).toInt()
                WorkoutSample(
                    sessionId = treadmillId,
                    timestampMs = treadmillStart + elapsed * 1000L,
                    elapsedTimeSec = elapsed,
                    speedKmh = speed.coerceIn(0.0, 20.0),
                    inclinationPercent = incline.coerceIn(-3.0, 15.0),
                    totalDistanceM = treadmillDistanceM,
                    totalEnergyKcal = (elapsed * 10 / 60)
                )
            }
            db.sampleDao().insertAll(treadmillSamples)

            // Fake indoor bike session: 60 minutes ago
            val bikeStart = now - 60 * 60 * 1000L
            val bikeEnd = bikeStart + 20 * 60 * 1000L
            val bikeSession = WorkoutSession(
                startTimeMs = bikeStart,
                endTimeMs = bikeEnd,
                machineType = "INDOOR_BIKE",
                deviceName = "Debug Indoor Bike",
                totalElapsedTimeSec = 20 * 60,
                totalDistanceM = 7800,
                totalEnergyKcal = 210,
                avgCadenceRpm = 75.0,
                maxPowerW = 180,
                deviceAddress = "AA:BB:CC:DD:EE:02",
                hrDeviceName = "Mi Band 3",
                hrDeviceAddress = "AA:BB:CC:DD:EE:03"
            )
            val bikeId = db.sessionDao().insert(bikeSession)
            var bikeDistanceM = 0
            val bikeSamples = (0 until 40).map { i ->
                val elapsed = i * 30
                val phase = i.toDouble() / 40.0
                val cadence = 60.0 + 30.0 * Math.abs(Math.sin(phase * Math.PI * 3)) + 2.0 * (Math.random() - 0.5)
                val resistance = (3 + (5 * Math.abs(Math.sin(phase * Math.PI * 2))).toInt()).coerceIn(1, 11)
                // Accumulate distance over 30-second interval (cadence × wheel factor)
                bikeDistanceM += (cadence * 2 * 30 / 60).toInt()
                WorkoutSample(
                    sessionId = bikeId,
                    timestampMs = bikeStart + elapsed * 1000L,
                    elapsedTimeSec = elapsed,
                    cadenceRpm = cadence.coerceIn(0.0, 120.0),
                    resistanceLevel = resistance,
                    totalDistanceM = bikeDistanceM,
                    totalEnergyKcal = (elapsed * 8 / 60)
                )
            }
            db.sampleDao().insertAll(bikeSamples)
            Log.i(tag, "Debug sample data created")
        }
    }

    companion object {
        private const val LAP_DISTANCE_METERS = 1000
        private const val SPEED_MIN_KMH = 0.5
        private const val SPEED_MAX_KMH = 30.0
        private const val INCLINE_MIN_PERCENT = -3.0
        private const val INCLINE_MAX_PERCENT = 16.0
        private const val INCLINE_DEFAULT_PERCENT = 0.0
        private const val SPEED_DANGER_KMH = 20.0
        private const val INCLINE_DANGER_PERCENT = 12.0
        private const val INCLINE_DECLINE_DANGER_PERCENT = -2.0

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
