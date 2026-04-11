package net.duhowpi.ftmsbridge

/**
 * Represents all possible states of the application's training session lifecycle.
 *
 * Transitions:
 * ```
 *  Idle ──► Connecting ──► Connected ──► Recording ──► Paused ──► Recording
 *   ▲                          │              │           │
 *   │                          ▼              ▼           ▼
 *   └──────── Disconnected ◄──────────────────────────────┘
 * ```
 *
 * - [Idle]          – No device paired; scan list visible.
 * - [Connecting]    – GATT connection in progress.
 * - [Connected]     – Device connected; ready to start a session.
 * - [Recording]     – Workout session actively recording data.
 * - [Paused]        – Workout paused (by machine or user); session data retained.
 * - [Disconnected]  – Device lost; may hold a paused session for stop/save.
 */
sealed class SessionState {

    /** No device connected, scan list visible. Initial state. */
    data object Idle : SessionState()

    /** GATT connection to the fitness device is being established. */
    data object Connecting : SessionState()

    /** Device connected and services discovered; workout not yet started. */
    data object Connected : SessionState()

    /** Workout session is actively recording. */
    data object Recording : SessionState()

    /** Workout session is paused (machine paused or BLE pause event). */
    data object Paused : SessionState()

    /**
     * Device disconnected unexpectedly.
     * If [sessionActive] is true, the session data is kept so the user can press Stop
     * to finalise it.
     */
    data class Disconnected(val sessionActive: Boolean = false) : SessionState()

    // -- Convenience queries --------------------------------------------------

    /** True when a BLE device connection is active (Connected, Recording, or Paused). */
    val isDeviceReady: Boolean
        get() = this is Connected || this is Recording || this is Paused

    /** True when the workout is in progress (Recording or Paused). */
    val hasActiveSession: Boolean
        get() = this is Recording || this is Paused ||
                (this is Disconnected && this.sessionActive)

    /** True when data should be saved from BLE notifications. */
    val isSavingData: Boolean
        get() = this is Recording

    /** Compact label for debugging / logs. */
    val label: String
        get() = when (this) {
            is Idle -> "IDLE"
            is Connecting -> "CONNECTING"
            is Connected -> "CONNECTED"
            is Recording -> "RECORDING"
            is Paused -> "PAUSED"
            is Disconnected -> if (this.sessionActive) "DISCONNECTED_SESSION" else "DISCONNECTED"
        }

    companion object {
        /** Reconstructs a [SessionState] from its persisted [label]. */
        fun fromLabel(label: String): SessionState? = when (label) {
            "IDLE" -> Idle
            "CONNECTING" -> Connecting
            "CONNECTED" -> Connected
            "RECORDING" -> Recording
            "PAUSED" -> Paused
            "DISCONNECTED_SESSION" -> Disconnected(sessionActive = true)
            "DISCONNECTED" -> Disconnected(sessionActive = false)
            else -> null
        }
    }
}
