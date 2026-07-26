package net.duhowpi.ftmsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the app process alive while a workout is being recorded.
 *
 * Android aggressively kills backgrounded processes to reclaim memory. Without a foreground
 * service the BLE connection to the fitness machine is dropped after roughly 10–20 minutes
 * when the screen turns off, even if a wake lock is held by the activity.  Running as a
 * foreground service elevates the process priority so the OS will not terminate it during a
 * session.  The service also holds the PARTIAL_WAKE_LOCK so the CPU stays awake and BLE
 * notifications continue to arrive while the screen is off.
 *
 * Lifecycle: started (not bound) by [MainActivity] when recording begins, stopped when
 * recording ends or the activity is destroyed.
 */
class RecordingService : Service() {

    private val tag = "RecordingService"
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val startTime = intent?.getLongExtra(EXTRA_SESSION_START_MS, 0L)?.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording_notification_text))
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // System-rendered elapsed timer: counts up from session start without
            // the service having to re-post the notification every second.
            .setWhen(startTime)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FtmsBridge:RecordingWakeLock"
            ).also { it.acquire(WAKE_LOCK_TIMEOUT_MS) }
            Log.d(tag, "Wake lock acquired")
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(tag, "Wake lock released")
            }
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours

        /** Unix ms when the session started; used for the notification chronometer. */
        const val EXTRA_SESSION_START_MS = "session_start_ms"
    }
}
