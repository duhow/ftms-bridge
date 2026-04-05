package net.duhowpi.ftmsbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import net.duhowpi.ftmsbridge.ftms.FtmsConstants

/**
 * Sends workout start/stop intents to OpenTracks via its Public API.
 *
 * When a fitness machine workout begins, OpenTracks starts recording the session
 * with the matching activity category and icon. Gadgetbridge reads the live stats
 * from OpenTracks via the Dashboard API and displays them on the connected band.
 *
 * OpenTracks Public API requires the user to enable it in OpenTracks settings:
 *   Settings → Sensor & accessories → Public API
 *
 * Gadgetbridge setup: Gadgetbridge → Settings → OpenTracks package → select the installed variant.
 */
object OpenTracksHelper {
    private const val TAG = "OpenTracksHelper"

    private const val CLASS_START = "de.dennisguse.opentracks.publicapi.StartRecording"
    private const val CLASS_STOP = "de.dennisguse.opentracks.publicapi.StopRecording"

    // OpenTracks ships under different package names depending on the installation source.
    private val OPENTRACKS_PACKAGES = listOf(
        "de.dennisguse.opentracks",
        "de.dennisguse.opentracks.playStore",
        "de.dennisguse.opentracks.nightly",
        "de.dennisguse.opentracks.debug"
    )

    /**
     * Sends a StartRecording intent to OpenTracks with a category and icon appropriate
     * for the given FTMS machine type.  Does nothing if OpenTracks is not installed.
     */
    fun startWorkout(context: Context, machineType: FtmsConstants.MachineType) {
        val category = categoryFor(machineType)
        val icon = iconFor(machineType)
        sendIntent(context, CLASS_START, category, icon)
    }

    /** Sends a StopRecording intent to OpenTracks.  Does nothing if OpenTracks is not installed. */
    fun stopWorkout(context: Context) {
        sendIntent(context, CLASS_STOP, null, null)
    }

    // -------------------------------------------------------------------------

    private fun categoryFor(machineType: FtmsConstants.MachineType): String? = when (machineType) {
        FtmsConstants.MachineType.TREADMILL -> "Treadmill"
        FtmsConstants.MachineType.CROSS_TRAINER -> "Elliptical"
        FtmsConstants.MachineType.INDOOR_BIKE -> "Indoor Cycling"
        FtmsConstants.MachineType.STAIR_CLIMBER -> "Stair Climber"
        else -> null
    }

    /**
     * Non-localized icon identifiers recognised by OpenTracks (see TrackIconUtils).
     * Only treadmill ("RUN") and indoor bike ("BIKE") have a direct match; the others
     * fall back to null so OpenTracks derives the icon from the category string.
     */
    private fun iconFor(machineType: FtmsConstants.MachineType): String? = when (machineType) {
        FtmsConstants.MachineType.TREADMILL -> "RUN"
        FtmsConstants.MachineType.INDOOR_BIKE -> "BIKE"
        else -> null
    }

    private fun sendIntent(
        context: Context,
        className: String,
        category: String?,
        icon: String?
    ) {
        val packageName = findOpenTracksPackage(context)
        if (packageName == null) {
            Log.d(TAG, "OpenTracks is not installed — skipping workout intent ($className)")
            return
        }

        val intent = Intent().apply {
            component = ComponentName(packageName, className)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            category?.let { putExtra("TRACK_CATEGORY", it) }
            icon?.let { putExtra("TRACK_ICON", it) }
        }

        try {
            context.startActivity(intent)
            Log.i(TAG, "Sent OpenTracks intent: $className (category=$category, icon=$icon)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send OpenTracks intent ($className): ${e.message}")
        }
    }

    /** Returns the first OpenTracks package name that is installed, or null if none are. */
    private fun findOpenTracksPackage(context: Context): String? {
        val pm = context.packageManager
        return OPENTRACKS_PACKAGES.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
