package net.duhowpi.ftmsbridge.ble

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BtDebugLogger(val enabled: Boolean, private val context: Context) {
    private val tag = "BtDebugLogger"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Always-on global log (entire app lifetime)
    private val appLogFile: File?
    private var appWriter: PrintWriter?

    // Per-device session log
    private var sessionFile: File? = null
    private var sessionWriter: PrintWriter? = null

    init {
        if (enabled) {
            val dir = File(context.getExternalFilesDir(null), "debug")
            dir.mkdirs()
            val filename = "app_${dateFormat.format(Date())}.log"
            appLogFile = File(dir, filename)
            appWriter = try {
                PrintWriter(FileOutputStream(appLogFile, false), true).also {
                    it.println("# FTMS Bridge Debug Log")
                    it.println("# App started: ${Date()}")
                    it.println("# ---")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to open global log", e)
                null
            }
            Log.i(tag, "Global debug log: ${appLogFile.absolutePath}")
        } else {
            appLogFile = null
            appWriter = null
        }
    }

    fun startSession(deviceName: String, deviceAddress: String) {
        if (!enabled) return
        try {
            val dir = File(context.getExternalFilesDir(null), "debug")
            dir.mkdirs()
            val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filename = "${dateFormat.format(Date())}_${safeName}_$deviceAddress.log"
            sessionFile = File(dir, filename)
            sessionWriter = PrintWriter(FileOutputStream(sessionFile!!, false), true).also {
                it.println("# BT Device Session Log")
                it.println("# Device: $deviceName ($deviceAddress)")
                it.println("# Started: ${Date()}")
                it.println("# ---")
            }
            logMessage("Session started for $deviceName ($deviceAddress)")
            Log.i(tag, "Session log: ${sessionFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start session log", e)
        }
    }

    fun logEvent(direction: String, uuid: String, data: ByteArray) {
        if (!enabled) return
        val timestamp = timestampFormat.format(Date())
        val hex = data.joinToString(" ") { String.format("%02X", it) }
        val line = "$timestamp [$direction] $uuid (${data.size} bytes): $hex"
        sessionWriter?.println(line)
        appWriter?.println(line)
        Log.d(tag, line)
    }

    fun logMessage(message: String) {
        if (!enabled) return
        val timestamp = timestampFormat.format(Date())
        val line = "$timestamp [INFO] $message"
        sessionWriter?.println(line)
        appWriter?.println(line)
    }

    fun stopSession() {
        if (!enabled) return
        val ts = timestampFormat.format(Date())
        sessionWriter?.println("$ts [INFO] Session ended: ${Date()}")
        sessionWriter?.flush()
        sessionWriter?.close()
        sessionWriter = null
        appWriter?.println("$ts [INFO] Session ended: ${sessionFile?.name}")
        Log.i(tag, "Session log closed: ${sessionFile?.absolutePath}")
        sessionFile = null
    }

    fun close() {
        stopSession()
        appWriter?.println("# App closed: ${Date()}")
        appWriter?.flush()
        appWriter?.close()
        appWriter = null
    }

    fun getSessionFile(): File? = sessionFile
    fun getAppLogFile(): File? = appLogFile

    companion object {
        fun getAllLogFiles(context: Context): List<File> {
            val dir = File(context.getExternalFilesDir(null), "debug")
            return (dir.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.extension == "log" }
                .sortedByDescending { it.lastModified() }
        }

        fun getDebugDir(context: Context): File =
            File(context.getExternalFilesDir(null), "debug")
    }
}

