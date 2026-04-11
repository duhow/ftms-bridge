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
import net.duhowpi.ftmsbridge.device.BhFitnessFtmsDevice
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

    private lateinit var stateMachine: SessionStateMachine

    private var currentSessionId: Long? = null
    private var sessionStartTime: Long = 0
    private var elapsedFallbackStartTime: Long = 0
    private var lastFallbackElapsedSec: Int = 0
    // True when the BLE machine reports belt/flywheel is physically moving.
    private var isMachineRunning = false

    /** Convenience: true when the state machine is in [SessionState.Recording]. */
    private val isRecording: Boolean get() = stateMachine.state is SessionState.Recording

    /** Convenience: true when the state machine is in [SessionState.Paused]. */
    private val isMachinePaused: Boolean get() = stateMachine.state is SessionState.Paused

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

    // 1-second elapsed-time tick: runs while recording to give a smooth per-second
    // display between BLE notifications (which arrive every ~2 s).  The counter is
    // monotonically non-decreasing: BLE-reported elapsed syncs it forward, never back.
    private var elapsedTickSec: Int = 0
    private val elapsedTickHandler = Handler(Looper.getMainLooper())
    private val elapsedTickRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            elapsedTickSec++
            updateElapsedDisplay(elapsedTickSec)
            elapsedTickHandler.postDelayed(this, 1000)
        }
    }

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
        stateMachine = SessionStateMachine(binding, this)

        createDebugSampleDataIfNeeded()

        setupScanList()
        setupUI()

        // Restore recording/paused UI state after process death.
        savedInstanceState?.let { restoreInstanceState(it) }

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
        scanAdapter = ScanResultAdapter(
            onConnect = { info -> onDeviceConnectTapped(info) },
            onDisconnect = { info -> onDeviceDisconnectTapped(info) }
        )
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
            if (!isRecording) startRecording()
        }

        binding.btnWorkoutStop.setOnClickListener { stopRecording() }
        binding.btnBackToIdle.setOnClickListener { returnToIdle() }

        binding.cardSpeed.setOnClickListener { showSpeedControlDialog() }
        binding.cardInclination.setOnClickListener {
            if (isActiveTreadmill) showInclineControlDialog() else showResistanceControlDialog()
        }
        binding.cardResistance.setOnClickListener { showResistanceControlDialog() }
        binding.lapColSpeed.setOnClickListener { showSpeedControlDialog() }
        binding.lapColInclination.setOnClickListener {
            if (isActiveTreadmill) showInclineControlDialog() else showResistanceControlDialog()
        }

        binding.btnViewLap.setOnClickListener { setWorkoutView(false) }
        binding.btnViewChart.setOnClickListener { setWorkoutView(true) }

        updateConnectionStatus()
        // Apply initial Idle UI (which also resets all metric displays).
        stateMachine.applyUI()
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

    private fun updateLapView(sample: FitnessSample) {
        val isTreadmill = isActiveTreadmill
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
        // txtLapCount (total elapsed) is updated exclusively by updateElapsedDisplay / elapsedTickRunnable
        // Inline metrics row inside the lap card
        binding.lapValueSpeed.text = when {
            isTreadmill -> String.format("%.1f", sample.speedKmh)
            sample.cadenceRpm > 0 -> String.format("%.0f", sample.cadenceRpm)
            else -> "--"
        }
        binding.lapValueInclination.text = when {
            isTreadmill -> "${resolveDisplayIncline(sample).roundToInt()}"
            sample.resistanceLevel > 0 -> "${sample.resistanceLevel}"
            else -> "--"
        }
        binding.lapValueEnergy.text = "${sample.totalEnergyKcal}"
        binding.lapUnitEnergy.setText(R.string.unit_kcal)
        binding.lapValueHr.text = if (sample.heartRateBpm > 0) "${sample.heartRateBpm}" else "--"
    }

    /** Returns true if [points] contains at least one non-zero value. */
    private fun hasNonZeroValues(points: List<Float>) = points.any { it != 0f }

    private fun connectDummyTreadmill() {
        val dummy = DummyTreadmill()
        dummyTreadmill = dummy
        binding.rvScanResults.visibility = View.GONE
        binding.txtScanStatus.visibility = View.GONE
        scanAdapter.markConnected(VIRTUAL_TREADMILL_ADDRESS)
        stateMachine.onConnected()
        updateConnectionStatus()
        stateMachine.applyMetricVisibility(dummy.machineType)
        updateLapMetricPresentation(dummy.machineType)
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
            scanResultsMap[VIRTUAL_TREADMILL_ADDRESS] = ScannedDeviceInfo(
                name = getString(R.string.debug_virtual_treadmill),
                address = VIRTUAL_TREADMILL_ADDRESS,
                rssi = 0,
                isFtms = true,
                isHr = false,
                device = null,
                machineType = FtmsConstants.MachineType.TREADMILL.name,
                isVirtual = true
            )
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
        val realCount = scanResultsMap.values.count { !it.isVirtual }
        binding.txtScanStatus.text = getString(R.string.devices_found, realCount)
        // Stop spinning and restore static icon
        (binding.btnScan.icon as? Animatable)?.stop()
        binding.btnScan.setIconResource(R.drawable.ic_refresh)
    }

    private fun updateScanListUI() {
        if (stateMachine.state.hasActiveSession || stateMachine.state is SessionState.Stopped) return
        // Merge current scan results with known devices (previously connected) that
        // are not present in the current scan — so the user can always reconnect.
        val combined = scanResultsMap.toMutableMap()
        knownDevicesMap.forEach { (addr, info) ->
            if (!combined.containsKey(addr)) combined[addr] = info
        }
        val list = combined.values.toList()
        val realCount = scanResultsMap.values.count { !it.isVirtual }
        runOnUiThread {
            binding.txtScanStatus.text = if (bleScanner.isScanning)
                "${getString(R.string.scanning_active)} $realCount"
            else
                getString(R.string.devices_found, realCount)
            if (list.isNotEmpty()) {
                binding.rvScanResults.visibility = View.VISIBLE
                binding.txtScanStatus.visibility = View.VISIBLE
            }
            scanAdapter.updateAll(list)
            // Mark already-connected devices
            ftmsConnectionManager?.connectedDeviceAddress?.let { scanAdapter.markConnected(it) }
            hrConnectionManager?.connectedDeviceAddress?.let { scanAdapter.markConnected(it) }
            if (dummyTreadmill != null) scanAdapter.markConnected(VIRTUAL_TREADMILL_ADDRESS)
        }
    }

    // ---- Device connection from scan list -----------------------------------

    private fun onDeviceConnectTapped(info: ScannedDeviceInfo) {
        if (info.isVirtual) {
            connectDummyTreadmill()
            return
        }
        val device = info.device ?: run {
            Log.w(tag, "onDeviceConnectTapped: null device for non-virtual entry ${info.address}")
            return
        }
        // Save device info so it remains accessible for reconnect after scanning stops
        knownDevicesMap[info.address] = info
        when {
            info.isFtms -> connectFtmsDevice(device)
            info.isHr -> connectHrDevice(device)
            else -> probeAndConnect(device)
        }
    }

    private fun onDeviceDisconnectTapped(info: ScannedDeviceInfo) {
        when {
            info.isVirtual -> disconnectDummyTreadmill()
            ftmsConnectionManager?.connectedDeviceAddress == info.address -> {
                ftmsConnectionManager?.disconnect()
            }
            hrConnectionManager?.connectedDeviceAddress == info.address -> {
                hrConnectionManager?.disconnect()
            }
        }
    }

    private fun disconnectDummyTreadmill() {
        dummyTreadmillHandler.removeCallbacks(dummyTreadmillRunnable)
        dummyTreadmill = null
        scanAdapter.markDisconnected(VIRTUAL_TREADMILL_ADDRESS)
        stateMachine.onDisconnected()
        updateConnectionStatus()
        updateScanListUI()
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
        stateMachine.onConnecting()
        ftmsConnectionManager?.connect(device, object : BleConnectionManager.ConnectionListener {
            override fun onConnected(deviceName: String) {
                runOnUiThread {
                    stateMachine.onConnected()
                    updateConnectionStatus()
                    scanAdapter.markConnected(device.address)
                }
            }

            override fun onDisconnected() {
                fitnessDevice = null
                isMachineRunning = false
                runOnUiThread {
                    val hadSession = stateMachine.state.hasActiveSession
                    stateMachine.onDisconnected()
                    updateConnectionStatus()
                    scanAdapter.markDisconnected(device.address)
                    if (hadSession) {
                        // Keep frozen metric values visible in the stopped review view.
                        stopRecording()
                    }
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
                    stateMachine.applyMetricVisibility(fitnessDevice?.machineType ?: FtmsConstants.MachineType.UNKNOWN, fitnessDevice)
                }
            }

            override fun onFtmsData(uuid: UUID, data: ByteArray) {
                val sample = fitnessDevice?.onDataReceived(data) ?: return
                val mergedSample = if (sample.heartRateBpm == 0 && lastHeartRateBpm > 0)
                    sample.copy(heartRateBpm = lastHeartRateBpm) else sample
                lastFtmsSample = mergedSample
                // Detect machine running state for devices without machine-status updates.
                val nowRunning = fitnessDevice?.isMoving(mergedSample) ?: (mergedSample.speedKmh > 0.1)
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
                    if (!isRecording) return@runOnUiThread
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
                val isPauseEvent: Boolean
                val stateChanged: Boolean
                when (opCode) {
                    FtmsConstants.MACHINE_STATUS_STARTED_OR_RESUMED -> {
                        isPauseEvent = false
                        stateChanged = !isMachineRunning || isMachinePaused
                        isMachineRunning = true
                    }
                    FtmsConstants.MACHINE_STATUS_STOPPED_OR_PAUSED -> {
                        // params[0] == 0x01 → full stop; 0x02 → pause
                        isPauseEvent = (params.firstOrNull()?.toInt()?.and(0xFF) ?: 0x01) == 0x02
                        stateChanged = isMachineRunning || (isMachinePaused != isPauseEvent)
                        isMachineRunning = false
                    }
                    FtmsConstants.MACHINE_STATUS_STOPPED_BY_SAFETY_KEY,
                    FtmsConstants.MACHINE_STATUS_RESET -> {
                        isPauseEvent = false
                        stateChanged = isMachineRunning || isMachinePaused
                        isMachineRunning = false
                    }
                    else -> return
                }
                if (!stateChanged) return
                runOnUiThread { updateMachineRunningState(isPauseEvent) }
            }

            override fun onIConceptData(data: ByteArray) {
                (fitnessDevice as? BhFitnessFtmsDevice)?.onIConceptData(data)
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
                    if (!isRecording) return@runOnUiThread
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
        val machineType = dummyTreadmill?.machineType
            ?: fitnessDevice?.machineType
            ?: FtmsConstants.MachineType.UNKNOWN

        // Delegate indicator colours and HR-row visibility to the state machine
        stateMachine.applyConnectionIndicators(ftmsConnected, hrConnected, hasHrDevice)
        updateLapMetricPresentation(machineType)

        binding.txtFtmsDevice.text = when {
            dummyTreadmill != null -> dummyTreadmill?.deviceName ?: getString(R.string.connected)
            ftmsConnected -> ftmsConnectionManager?.connectedDeviceName ?: getString(R.string.connected)
            else -> getString(R.string.not_connected)
        }

        binding.txtHrDevice.text = if (hrConnected)
            hrConnectionManager?.connectedDeviceName ?: getString(R.string.connected)
        else getString(R.string.not_connected)

        if (!ftmsConnected) {
            binding.txtFtmsDeviceInfo.visibility = View.GONE
            lastFtmsSample = null
        } else {
            updateFtmsDeviceInfoUI()
        }

        binding.workoutButtonRow.visibility = if (ftmsConnected) View.VISIBLE else View.GONE
        binding.btnWorkoutStart.isEnabled = ftmsConnected
    }

    /**
     * Called whenever the machine transitions between running and stopped state.
     * @param isPauseEvent true when the BLE event was a pause (not a full stop).
     */
    private fun updateMachineRunningState(isPauseEvent: Boolean = false) {
        when {
            isMachineRunning && !isRecording -> {
                // Resuming from a BLE pause continues the same session; any other start begins a new one.
                if (isMachinePaused && currentSessionId != null) {
                    resumeRecording()
                } else {
                    startRecording()
                }
            }
            !isMachineRunning && isRecording -> {
                if (isPauseEvent) pauseRecording() else stopRecording()
            }
            // Transition from paused to fully stopped (e.g. STOP op received while paused).
            else -> {
                stopRecording()
            }
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
        // Freeze the display when the session has stopped; don't update with live BLE data.
        if (!isRecording) return

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

        // Always show energy as kcal in the dashboard.
        binding.labelEnergy.setText(R.string.metric_energy)
        binding.unitEnergy.setText(R.string.unit_kcal)
        binding.valueEnergy.text = "${sample.totalEnergyKcal}"

        binding.valueInclination.text = "${resolveDisplayIncline(sample).roundToInt()}"
        binding.valueResistance.text = if (sample.resistanceLevel > 0) "${sample.resistanceLevel}" else "--"

        // Elapsed display is driven by the 1 s tick handler; sync the tick counter here
        // (taking the max so it never goes backwards).
        if (elapsedSec > elapsedTickSec) {
            elapsedTickSec = elapsedSec
            updateElapsedDisplay(elapsedTickSec)
        }

        // Accumulate live chart data while recording
        liveChartElapsedSec = elapsedTickSec
        val isTreadmill = isActiveTreadmill
        liveSpeedPoints.add(if (isTreadmill) sample.speedKmh.toFloat() else sample.cadenceRpm.toFloat())
        livePaceSecondaryPoints.add(
            if (isTreadmill) resolveDisplayIncline(sample).toFloat() else sample.resistanceLevel.toFloat()
        )
        liveHrPoints.add(sample.heartRateBpm.toFloat())
        if (isChartViewActive) updateLiveChart()
        updateLapView(sample)
    }

    /** Updates the elapsed-time display in both the metric card and the lap-view total. */
    private fun updateElapsedDisplay(elapsedSec: Int) {
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val formatted = String.format("%d:%02d", minutes, seconds)
        binding.valueElapsedTime.text = formatted
        binding.txtLapCount.text = formatted
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


    // ---- Session recording --------------------------------------------------

    private fun startRecording() {
        val device: FitnessDevice = fitnessDevice ?: dummyTreadmill ?: return
        // Reset device-internal elapsed so it starts from zero at activity start, not
        // from the BLE connection time.
        device.reset()
        resetSessionData()
        stateMachine.onRecordingStarted()
        sessionStartTime = System.currentTimeMillis()
        // Reset and start the 1-second tick so the elapsed display counts smoothly.
        updateElapsedDisplay(0)
        elapsedTickHandler.removeCallbacks(elapsedTickRunnable)
        elapsedTickHandler.postDelayed(elapsedTickRunnable, 1000)
        // Apply recording UI via state machine
        val ftmsConnected = ftmsConnectionManager?.isConnected == true || dummyTreadmill != null
        val hrConnected = hrConnectionManager?.isConnected == true
        val hasHrDevice = hrConnectionManager != null
        stateMachine.applyUI(ftmsConnected, hrConnected, hasHrDevice, fitnessDevice)
        // Anchor lap start time to the actual session start (resetSessionData sets it to 0)
        lapStartTimeMs = System.currentTimeMillis()
        // Reset lap-view metric display so previous session values don't bleed through
        binding.lapValueSpeed.text = "--"
        binding.lapValueInclination.text = "--"
        binding.lapValueEnergy.text = "--"
        binding.lapValueHr.text = "--"
        binding.lapUnitEnergy.setText(R.string.unit_kcal)
        // Default to lap view
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
        val ftmsConnected = ftmsConnectionManager?.isConnected == true || dummyTreadmill != null
        val hrConnected = hrConnectionManager?.isConnected == true
        val hasHrDevice = hrConnectionManager != null

        stateMachine.onSessionFinished()

        elapsedTickHandler.removeCallbacks(elapsedTickRunnable)
        elapsedFallbackStartTime = 0L
        lastFallbackElapsedSec = 0

        // Keep the frozen session view so the user can review it; Back button returns to idle.
        stateMachine.applyUI(ftmsConnected, hrConnected, hasHrDevice, fitnessDevice)

        val sessionId = currentSessionId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.sessionDao().getById(sessionId)
            session?.let { it.endTimeMs = System.currentTimeMillis(); db.sessionDao().update(it) }
            Log.i(tag, "Session stopped: $sessionId")
        }
        currentSessionId = null
    }

    /**
     * Resets all in-memory session data to zero.
     * Called by [SessionStateMachine] whenever the state transitions to [SessionState.Idle],
     * ensuring the reset always happens regardless of which code path leads to Idle.
     */
    internal fun resetSessionData() {
        currentSessionId = null
        liveSpeedPoints.clear()
        livePaceSecondaryPoints.clear()
        liveHrPoints.clear()
        liveChartElapsedSec = 0
        lapStartDistanceM = 0
        lapStartTimeMs = 0
        lapCount = 0
        elapsedTickSec = 0
        elapsedFallbackStartTime = 0L
        lastFallbackElapsedSec = 0
        lastSavedSampleMs = 0
        lastSavedSampleData = null
        lastHeartRateBpm = 0
        lastFtmsSample = null
    }

    /**
     * Transitions to idle (or connected if device is still linked), resetting all session data
     * and showing the scan list ready for a new session.
     *
     * The reset is always triggered by routing through [SessionState.Idle] first, which causes
     * [SessionStateMachine] to call [resetSessionData] and [SessionStateMachine.resetMetrics].
     * If the device is still connected, the state immediately advances to [SessionState.Connected].
     */
    private fun returnToIdle() {
        val ftmsConnected = ftmsConnectionManager?.isConnected == true || dummyTreadmill != null
        val hrConnected = hrConnectionManager?.isConnected == true
        val hasHrDevice = hrConnectionManager != null

        // Always pass through Idle so the state machine triggers the data + UI reset.
        stateMachine.onReturnToIdle()

        if (ftmsConnected) {
            // Device still connected — advance to Connected without showing the Idle layout.
            stateMachine.onConnected()
            stateMachine.applyUI(ftmsConnected, hrConnected, hasHrDevice, fitnessDevice)
        } else {
            stateMachine.applyUI()
        }
    }

    /**
     * Pauses the current recording session in response to a BLE pause event.
     * The session ID and all accumulated data are kept intact so [resumeRecording] can
     * continue seamlessly. The elapsed-time ticker is stopped to freeze the display.
     */
    private fun pauseRecording() {
        stateMachine.onPaused()
        elapsedTickHandler.removeCallbacks(elapsedTickRunnable)
        // Keep currentSessionId, elapsedTickSec, chart data, lap data — recording UI stays visible.
    }

    /**
     * Resumes a previously paused recording session (BLE resume/start after a pause event).
     * Restarts the elapsed-time ticker from the frozen value and transitions back to Recording.
     */
    private fun resumeRecording() {
        stateMachine.onResumed()
        elapsedFallbackStartTime = 0L
        lastFallbackElapsedSec = 0
        elapsedTickHandler.removeCallbacks(elapsedTickRunnable)
        elapsedTickHandler.postDelayed(elapsedTickRunnable, 1000)
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

        sb.appendLine("=== Session State: ${stateMachine.state.label} ===")
        sb.appendLine()
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
        val logFiles = debugLogger.getCurrentSessionFiles()
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
        // Round current incline to nearest integer so the seekbar starts on a whole-% step.
        val current = (lastFtmsSample?.let { resolveDisplayIncline(it) }?.roundToInt()?.toDouble()
            ?: INCLINE_DEFAULT_PERCENT).coerceIn(INCLINE_MIN_PERCENT, INCLINE_MAX_PERCENT)
        showAdjustDialog(
            title = getString(R.string.control_set_incline_title),
            label = getString(R.string.control_incline_label),
            min = INCLINE_MIN_PERCENT,
            max = INCLINE_MAX_PERCENT,
            step = 1.0,
            largeStep = 1.0,
            initial = current,
            unitFormatter = { value -> "${value.roundToInt()}${getString(R.string.unit_percent)}" },
            rangeText = getString(R.string.control_range_incline, INCLINE_MIN_PERCENT.roundToInt(), INCLINE_MAX_PERCENT.roundToInt()),
            dangerPredicate = { value -> value >= INCLINE_DANGER_PERCENT || value <= INCLINE_DECLINE_DANGER_PERCENT },
            showAdjustButtons = false
        ) { selected ->
            sendTargetInclinePercent(selected)
        }
    }

    private fun showResistanceControlDialog() {
        val cm = ftmsConnectionManager
        val machine = fitnessDevice
        val supportsResistanceControl = machine?.machineType == FtmsConstants.MachineType.INDOOR_BIKE ||
            machine?.machineType == FtmsConstants.MachineType.CROSS_TRAINER
        if (cm?.isConnected != true || !supportsResistanceControl) {
            Toast.makeText(this, getString(R.string.control_not_available), Toast.LENGTH_SHORT).show()
            return
        }
        val current = (lastFtmsSample?.resistanceLevel
            ?.coerceIn(RESISTANCE_MIN_LEVEL, RESISTANCE_MAX_LEVEL)
            ?: RESISTANCE_MIN_LEVEL).toDouble()
        showAdjustDialog(
            title = getString(R.string.control_set_resistance_title),
            label = getString(R.string.control_resistance_label),
            min = RESISTANCE_MIN_LEVEL.toDouble(),
            max = RESISTANCE_MAX_LEVEL.toDouble(),
            step = 1.0,
            largeStep = 1.0,
            initial = current,
            unitFormatter = { value -> "${value.roundToInt()}" },
            rangeText = getString(R.string.control_range_resistance, RESISTANCE_MIN_LEVEL, RESISTANCE_MAX_LEVEL),
            dangerPredicate = { false },
            showAdjustButtons = false
        ) { selected ->
            sendTargetResistanceLevel(selected.roundToInt())
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
        smallDecLabel: String = "",
        largeDecLabel: String = "",
        smallIncLabel: String = "",
        largeIncLabel: String = "",
        showAdjustButtons: Boolean = true,
        onApply: (Double) -> Boolean
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_adjust_metric, null)
        val labelView = view.findViewById<TextView>(R.id.txtDialogMetricLabel)
        val valueView = view.findViewById<TextView>(R.id.txtDialogMetricValue)
        val rangeView = view.findViewById<TextView>(R.id.txtDialogRange)
        val dangerView = view.findViewById<TextView>(R.id.txtDialogDanger)
        val seek = view.findViewById<SeekBar>(R.id.seekDialogMetric)
        val buttonRow = view.findViewById<android.widget.LinearLayout>(R.id.buttonRowAdjust)
        val decLarge = view.findViewById<Button>(R.id.btnDialogDecLarge)
        val decSmall = view.findViewById<Button>(R.id.btnDialogDecSmall)
        val incSmall = view.findViewById<Button>(R.id.btnDialogIncSmall)
        val incLarge = view.findViewById<Button>(R.id.btnDialogIncLarge)

        labelView.text = label
        rangeView.text = rangeText

        if (showAdjustButtons) {
            decLarge.text = largeDecLabel
            decSmall.text = smallDecLabel
            incSmall.text = smallIncLabel
            incLarge.text = largeIncLabel
            decLarge.contentDescription = getString(R.string.control_cd_decrease_by, largeDecLabel.removePrefix("-"))
            decSmall.contentDescription = getString(R.string.control_cd_decrease_by, smallDecLabel.removePrefix("-"))
            incSmall.contentDescription = getString(R.string.control_cd_increase_by, smallIncLabel.removePrefix("+"))
            incLarge.contentDescription = getString(R.string.control_cd_increase_by, largeIncLabel.removePrefix("+"))
        } else {
            buttonRow.visibility = View.GONE
        }

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

        if (showAdjustButtons) {
            decLarge.setOnClickListener { selected -= largeStep; updateViews() }
            decSmall.setOnClickListener { selected -= step; updateViews() }
            incSmall.setOnClickListener { selected += step; updateViews() }
            incLarge.setOnClickListener { selected += largeStep; updateViews() }
        }

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
        val cm = ftmsConnectionManager ?: return false
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
        return cm.sendControlPoint(payload)
    }

    private fun sendTargetInclinePercent(inclinePercent: Double): Boolean {
        val cm = ftmsConnectionManager ?: return false
        val clamped = inclinePercent.coerceIn(INCLINE_MIN_PERCENT, INCLINE_MAX_PERCENT)
        val encoded = fitnessDevice?.encodeTargetInclineRaw(clamped) ?: (clamped * 10.0).roundToInt()
        if (!isEncodableAsSint16(encoded)) {
            Log.w(tag, "Encoded incline out of range: $encoded")
            return false
        }
        val payload = ByteBuffer.allocate(3)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(FtmsConstants.CONTROL_SET_TARGET_INCLINATION)
            .putShort(encoded.toShort())
            .array()
        return cm.sendControlPoint(payload)
    }

    private fun sendTargetResistanceLevel(level: Int): Boolean {
        val cm = ftmsConnectionManager ?: return false
        val clamped = level.coerceIn(RESISTANCE_MIN_LEVEL, RESISTANCE_MAX_LEVEL)
        val encoded = (clamped * RESISTANCE_LEVEL_MULTIPLIER).roundToInt()
        if (!isEncodableAsSint16(encoded)) {
            Log.w(tag, "Encoded resistance out of range: level=$clamped, encoded=$encoded")
            return false
        }
        val payload = ByteBuffer.allocate(3)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(FtmsConstants.CONTROL_SET_TARGET_RESISTANCE_LEVEL)
            .putShort(encoded.toShort())
            .array()
        return cm.sendControlPoint(payload)
    }

    /** Returns the display-ready inclination for [sample], delegating to the active device. */
    private fun resolveDisplayIncline(sample: FitnessSample): Double =
        fitnessDevice?.getDisplayIncline(sample) ?: sample.inclinationPercent

    private fun updateLapMetricPresentation(machineType: FtmsConstants.MachineType) {
        val isBike = machineType == FtmsConstants.MachineType.INDOOR_BIKE ||
            machineType == FtmsConstants.MachineType.CROSS_TRAINER
        val speedIconColor = ContextCompat.getColor(
            this,
            if (isBike) R.color.metric_cadence else R.color.metric_speed
        )
        val secondaryIconColor = ContextCompat.getColor(
            this,
            if (isBike) R.color.metric_resistance else R.color.metric_inclination
        )
        if (isBike) {
            binding.lapIconSpeed.setImageResource(R.drawable.ic_cadence)
            binding.lapLabelSpeed.setText(R.string.metric_cadence)
            binding.lapUnitSpeed.setText(R.string.unit_rpm)
            binding.lapIconInclination.setImageResource(R.drawable.ic_resistance)
            binding.lapLabelInclination.setText(R.string.metric_resistance)
            binding.lapUnitInclination.visibility = View.GONE
        } else {
            binding.lapIconSpeed.setImageResource(R.drawable.ic_speed)
            binding.lapLabelSpeed.setText(R.string.metric_speed)
            binding.lapUnitSpeed.setText(R.string.unit_kmh)
            binding.lapIconInclination.setImageResource(R.drawable.ic_inclination)
            binding.lapLabelInclination.setText(R.string.metric_inclination)
            binding.lapUnitInclination.visibility = View.VISIBLE
        }
        binding.lapIconSpeed.setColorFilter(speedIconColor)
        binding.lapIconInclination.setColorFilter(secondaryIconColor)
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
        elapsedTickHandler.removeCallbacks(elapsedTickRunnable)
        dummyTreadmill = null
        bleScanner.stopScan()
        if (stateMachine.state.hasActiveSession) stopRecording()
        ftmsConnectionManager?.disconnect()
        hrConnectionManager?.disconnect()
        probeConnectionManager?.disconnect()
        debugLogger.close()
        hrDebugLogger.close()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SESSION_STATE, stateMachine.state.label)
        outState.putLong(KEY_CURRENT_SESSION_ID, currentSessionId ?: -1L)
        outState.putInt(KEY_ELAPSED_TICK_SEC, elapsedTickSec)
        outState.putLong(KEY_SESSION_START_TIME, sessionStartTime)
        outState.putBoolean(KEY_IS_CHART_VIEW_ACTIVE, isChartViewActive)
        outState.putInt(KEY_LAP_COUNT, lapCount)
        outState.putInt(KEY_LAP_START_DISTANCE_M, lapStartDistanceM)
        outState.putLong(KEY_LAP_START_TIME_MS, lapStartTimeMs)
        outState.putFloatArray(KEY_LIVE_SPEED_POINTS, liveSpeedPoints.toFloatArray())
        outState.putFloatArray(KEY_LIVE_PACE_POINTS, livePaceSecondaryPoints.toFloatArray())
        outState.putFloatArray(KEY_LIVE_HR_POINTS, liveHrPoints.toFloatArray())
        outState.putInt(KEY_LIVE_CHART_ELAPSED, liveChartElapsedSec)
    }

    /**
     * Restores the recording, paused, or stopped UI state after the activity is recreated
     * (e.g. screen rotation or process death).
     * Active sessions without a BLE connection are restored as Disconnected so the user can
     * press Stop to finalise.  A previously-stopped session is restored directly as Stopped
     * so the user can review the frozen stats and press Back.
     */
    private fun restoreInstanceState(state: Bundle) {
        val savedStateLabel = state.getString(KEY_SESSION_STATE) ?: return
        val savedState = SessionState.fromLabel(savedStateLabel) ?: return
        if (!savedState.hasActiveSession && savedState !is SessionState.Stopped) return

        val savedSessionId = state.getLong(KEY_CURRENT_SESSION_ID, -1L).takeIf { it != -1L }

        // Restore data fields
        currentSessionId = savedSessionId
        elapsedTickSec = state.getInt(KEY_ELAPSED_TICK_SEC)
        sessionStartTime = state.getLong(KEY_SESSION_START_TIME)
        isChartViewActive = state.getBoolean(KEY_IS_CHART_VIEW_ACTIVE)
        lapCount = state.getInt(KEY_LAP_COUNT)
        lapStartDistanceM = state.getInt(KEY_LAP_START_DISTANCE_M)
        lapStartTimeMs = state.getLong(KEY_LAP_START_TIME_MS)
        liveChartElapsedSec = state.getInt(KEY_LIVE_CHART_ELAPSED)
        state.getFloatArray(KEY_LIVE_SPEED_POINTS)?.let { liveSpeedPoints.addAll(it.toList()) }
        state.getFloatArray(KEY_LIVE_PACE_POINTS)?.let { livePaceSecondaryPoints.addAll(it.toList()) }
        state.getFloatArray(KEY_LIVE_HR_POINTS)?.let { liveHrPoints.addAll(it.toList()) }

        // After process death (or screen rotation) the BLE link is gone, so restore
        // active sessions as Disconnected (user can press Stop to finalise) and
        // already-stopped sessions directly as Stopped (user reviews then presses Back).
        val restoredState = when {
            savedState is SessionState.Stopped -> SessionState.Stopped
            else -> SessionState.Disconnected(sessionActive = savedSessionId != null)
        }
        stateMachine.restoreState(restoredState)
        stateMachine.applyUI()

        // Restore chart/lap view selection
        binding.lapSection.visibility = if (!isChartViewActive) View.VISIBLE else View.GONE
        binding.chartSection.visibility = if (isChartViewActive) View.VISIBLE else View.GONE

        updateElapsedDisplay(elapsedTickSec)
        if (isChartViewActive) updateLiveChart()
    }

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
                val resistance = (3 + (5 * Math.abs(Math.sin(phase * Math.PI * 2))).toInt())
                    .coerceIn(RESISTANCE_MIN_LEVEL, RESISTANCE_MAX_LEVEL)
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
        private const val RESISTANCE_MIN_LEVEL = 1
        private const val RESISTANCE_MAX_LEVEL = 100
        // BH indoor-bike firmware expects FTMS target resistance encoded with this scale.
        private const val RESISTANCE_LEVEL_MULTIPLIER = 6.25

        /** Sentinel address used for the virtual (debug) treadmill in the scan list. */
        private const val VIRTUAL_TREADMILL_ADDRESS = "VIRTUAL:TREADMILL"

        // Keys for onSaveInstanceState
        private const val KEY_SESSION_STATE = "session_state"
        private const val KEY_CURRENT_SESSION_ID = "current_session_id"
        private const val KEY_ELAPSED_TICK_SEC = "elapsed_tick_sec"
        private const val KEY_SESSION_START_TIME = "session_start_time"
        private const val KEY_IS_CHART_VIEW_ACTIVE = "is_chart_view_active"
        private const val KEY_LAP_COUNT = "lap_count"
        private const val KEY_LAP_START_DISTANCE_M = "lap_start_distance_m"
        private const val KEY_LAP_START_TIME_MS = "lap_start_time_ms"
        private const val KEY_LIVE_SPEED_POINTS = "live_speed_points"
        private const val KEY_LIVE_PACE_POINTS = "live_pace_points"
        private const val KEY_LIVE_HR_POINTS = "live_hr_points"
        private const val KEY_LIVE_CHART_ELAPSED = "live_chart_elapsed"

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
