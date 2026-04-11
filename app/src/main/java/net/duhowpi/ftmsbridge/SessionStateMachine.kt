package net.duhowpi.ftmsbridge

import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import net.duhowpi.ftmsbridge.databinding.ActivityMainBinding
import net.duhowpi.ftmsbridge.device.BhFitnessIndoorBike
import net.duhowpi.ftmsbridge.device.FitnessDevice
import net.duhowpi.ftmsbridge.ftms.FtmsConstants

/**
 * Manages session state transitions and applies the corresponding UI changes.
 *
 * All public transition methods are safe to call from any state; invalid
 * transitions are silently ignored (with a log warning).
 */
class SessionStateMachine(
    private val binding: ActivityMainBinding,
    private val activity: MainActivity
) {
    private val tag = "SessionStateMachine"

    var state: SessionState = SessionState.Idle
        private set

    // --- Transition helpers ---------------------------------------------------

    /** Transition to [SessionState.Connecting]. */
    fun onConnecting() {
        transition(SessionState.Connecting)
    }

    /** Transition to [SessionState.Connected]. */
    fun onConnected() {
        transition(SessionState.Connected)
    }

    /** Transition to [SessionState.Recording]. */
    fun onRecordingStarted() {
        transition(SessionState.Recording)
    }

    /** Transition to [SessionState.Paused]. */
    fun onPaused() {
        transition(SessionState.Paused)
    }

    /** Transition back to [SessionState.Recording] from [SessionState.Paused]. */
    fun onResumed() {
        if (state !is SessionState.Paused) {
            Log.w(tag, "onResumed ignored: current=${state.label}")
            return
        }
        transition(SessionState.Recording)
    }

    /** Transition to [SessionState.Connected] (session stopped while still connected). */
    fun onSessionStopped() {
        transition(SessionState.Connected)
    }

    /** Transition to [SessionState.Disconnected]. */
    fun onDisconnected() {
        val hadSession = state.hasActiveSession
        transition(SessionState.Disconnected(sessionActive = hadSession))
    }

    /** Transition back to [SessionState.Idle] (e.g. after user dismisses a disconnected session). */
    fun onReturnToIdle() {
        transition(SessionState.Idle)
    }

    /** Restore state after process death. */
    fun restoreState(newState: SessionState) {
        Log.i(tag, "Restoring state: ${newState.label}")
        state = newState
    }

    // --- UI application per state --------------------------------------------

    /**
     * Applies full UI visibility/state for the current [state].
     * Call this after a transition or after restoring state.
     */
    fun applyUI(
        ftmsConnected: Boolean = false,
        hrConnected: Boolean = false,
        hasHrDevice: Boolean = false,
        fitnessDevice: FitnessDevice? = null
    ) {
        when (state) {
            is SessionState.Idle -> applyIdleUI()
            is SessionState.Connecting -> applyConnectingUI()
            is SessionState.Connected -> applyConnectedUI(ftmsConnected, hrConnected, hasHrDevice, fitnessDevice)
            is SessionState.Recording -> applyRecordingUI(ftmsConnected, hrConnected, hasHrDevice)
            is SessionState.Paused -> applyPausedUI(ftmsConnected, hrConnected, hasHrDevice)
            is SessionState.Disconnected -> applyDisconnectedUI((state as SessionState.Disconnected).sessionActive)
        }
    }

    // --- Idle ----------------------------------------------------------------

    private fun applyIdleUI() {
        // Scan area visible
        binding.rvScanResults.visibility = View.VISIBLE
        binding.txtScanStatus.visibility = View.VISIBLE

        // Device header visible, metrics hidden
        binding.deviceHeaderRow.visibility = View.VISIBLE
        binding.metricsSection.visibility = View.GONE

        // Workout buttons
        binding.workoutButtonRow.visibility = View.GONE
        binding.btnWorkoutStart.visibility = View.VISIBLE
        binding.btnWorkoutStart.isEnabled = false
        binding.btnWorkoutStop.visibility = View.GONE

        // Workout views
        binding.viewToggleRow.visibility = View.GONE
        binding.lapSection.visibility = View.GONE
        binding.chartSection.visibility = View.GONE

        // Toolbar status icons hidden
        binding.toolbarStatusIcons.visibility = View.GONE
        binding.recordingIndicator.visibility = View.GONE

        // Connection indicators
        applyConnectionIndicators(ftmsConnected = false, hrConnected = false, hasHrDevice = false)
    }

    // --- Connecting ----------------------------------------------------------

    private fun applyConnectingUI() {
        // Scan area still visible during connect
        binding.rvScanResults.visibility = View.VISIBLE
        binding.txtScanStatus.visibility = View.VISIBLE

        binding.deviceHeaderRow.visibility = View.VISIBLE
        binding.metricsSection.visibility = View.GONE
        binding.workoutButtonRow.visibility = View.GONE
        binding.toolbarStatusIcons.visibility = View.GONE
        binding.recordingIndicator.visibility = View.GONE
    }

    // --- Connected -----------------------------------------------------------

    private fun applyConnectedUI(
        ftmsConnected: Boolean,
        hrConnected: Boolean,
        hasHrDevice: Boolean,
        fitnessDevice: FitnessDevice? = null
    ) {
        // Scan area visible
        binding.rvScanResults.visibility = View.VISIBLE
        binding.txtScanStatus.visibility = View.VISIBLE

        // Device header and metrics
        binding.deviceHeaderRow.visibility = View.VISIBLE
        binding.metricsSection.visibility = View.GONE

        // Workout buttons: show start
        binding.workoutButtonRow.visibility = if (ftmsConnected) View.VISIBLE else View.GONE
        binding.btnWorkoutStart.visibility = View.VISIBLE
        binding.btnWorkoutStart.isEnabled = ftmsConnected
        binding.btnWorkoutStop.visibility = View.GONE

        // Workout views
        binding.viewToggleRow.visibility = View.GONE
        binding.lapSection.visibility = View.GONE
        binding.chartSection.visibility = View.GONE

        // Toolbar status
        binding.toolbarStatusIcons.visibility = View.GONE
        binding.recordingIndicator.visibility = View.GONE

        applyConnectionIndicators(ftmsConnected, hrConnected, hasHrDevice)

        // Restore metric card visibility based on machine type
        val machineType = fitnessDevice?.machineType ?: FtmsConstants.MachineType.UNKNOWN
        applyMetricVisibility(machineType, fitnessDevice)

        // Restore cards hidden during recording
        binding.cardHeartRate.visibility = View.VISIBLE
        binding.rowDistanceEnergy.visibility = View.VISIBLE
        binding.cardElapsedTime.visibility = View.VISIBLE
    }

    // --- Recording -----------------------------------------------------------

    private fun applyRecordingUI(
        ftmsConnected: Boolean,
        hrConnected: Boolean,
        hasHrDevice: Boolean
    ) {
        // Hide scan area
        binding.rvScanResults.visibility = View.GONE
        binding.txtScanStatus.visibility = View.GONE

        // Hide device header; show compact toolbar indicators
        binding.deviceHeaderRow.visibility = View.GONE
        binding.recordingIndicator.visibility = View.GONE
        binding.toolbarStatusIcons.visibility = View.VISIBLE
        applyConnectionIndicators(ftmsConnected, hrConnected, hasHrDevice)
        binding.toolbarIndicatorHr.visibility = if (hasHrDevice) View.VISIBLE else View.GONE

        // Show metrics section, but hide cards replaced by lap view
        binding.metricsSection.visibility = View.VISIBLE
        binding.cardSpeed.visibility = View.GONE
        binding.cardHeartRate.visibility = View.GONE
        binding.rowDistanceEnergy.visibility = View.GONE
        binding.cardElapsedTime.visibility = View.GONE
        binding.cardInclination.visibility = View.GONE

        // Workout buttons: hide start, show stop
        binding.btnWorkoutStart.visibility = View.GONE
        binding.btnWorkoutStop.visibility = View.VISIBLE

        // Workout views
        binding.viewToggleRow.visibility = View.VISIBLE
    }

    // --- Paused --------------------------------------------------------------

    private fun applyPausedUI(
        ftmsConnected: Boolean,
        hrConnected: Boolean,
        hasHrDevice: Boolean
    ) {
        // Paused keeps the same layout as recording so the user sees frozen data
        applyRecordingUI(ftmsConnected, hrConnected, hasHrDevice)
    }

    // --- Disconnected --------------------------------------------------------

    private fun applyDisconnectedUI(hasActiveSession: Boolean) {
        if (hasActiveSession) {
            // Keep the recording layout so user can press Stop to save
            binding.rvScanResults.visibility = View.GONE
            binding.txtScanStatus.visibility = View.GONE
            binding.deviceHeaderRow.visibility = View.GONE
            binding.recordingIndicator.visibility = View.GONE
            binding.toolbarStatusIcons.visibility = View.VISIBLE
            applyConnectionIndicators(ftmsConnected = false, hrConnected = false, hasHrDevice = false)

            binding.metricsSection.visibility = View.VISIBLE
            binding.cardSpeed.visibility = View.GONE
            binding.cardHeartRate.visibility = View.GONE
            binding.rowDistanceEnergy.visibility = View.GONE
            binding.cardElapsedTime.visibility = View.GONE
            binding.cardInclination.visibility = View.GONE

            binding.btnWorkoutStart.visibility = View.GONE
            binding.btnWorkoutStop.visibility = View.VISIBLE
            binding.viewToggleRow.visibility = View.VISIBLE
        } else {
            applyIdleUI()
        }
    }

    // --- Shared helpers ------------------------------------------------------

    /** Updates the FTMS/HR connection colour indicators. */
    fun applyConnectionIndicators(
        ftmsConnected: Boolean,
        hrConnected: Boolean,
        hasHrDevice: Boolean
    ) {
        val ftmsColor = ContextCompat.getColor(
            activity, if (ftmsConnected) R.color.status_connected else R.color.status_disconnected
        )
        val hrColor = ContextCompat.getColor(
            activity, if (hrConnected) R.color.status_connected else R.color.status_disconnected
        )
        binding.indicatorFtms.setColorFilter(ftmsColor)
        binding.indicatorHr.setColorFilter(hrColor)
        binding.toolbarIndicatorFtms.setColorFilter(ftmsColor)
        binding.toolbarIndicatorHr.setColorFilter(hrColor)
        if (state.hasActiveSession) {
            binding.toolbarIndicatorHr.visibility = if (hasHrDevice) View.VISIBLE else View.GONE
        }
        binding.hrStatusRow.visibility = if (hasHrDevice) View.VISIBLE else View.GONE
    }

    /**
     * Shows/hides metric cards based on [machineType].
     * Mirrors the logic previously in `updateMetricVisibility`.
     */
    fun applyMetricVisibility(machineType: FtmsConstants.MachineType, fitnessDevice: FitnessDevice? = null) {
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

    /** Resets all metric text views to their placeholder values. */
    fun resetMetrics() {
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
        binding.lapValueInclination.text = "--"
        binding.lapValueEnergy.text = "--"
        binding.lapUnitEnergy.setText(R.string.unit_kcal)
        binding.lapValueHr.text = "--"
        binding.txtMachineType.visibility = View.GONE
        binding.txtFtmsDeviceInfo.visibility = View.GONE
        binding.metricsSection.visibility = View.GONE
        applyMetricVisibility(FtmsConstants.MachineType.UNKNOWN)
    }

    // --- State transition bookkeeping ----------------------------------------

    private fun transition(newState: SessionState) {
        val old = state
        state = newState
        Log.i(tag, "Transition: ${old.label} → ${newState.label}")
    }
}
