package net.duhowpi.ftmsbridge.ble

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BtDebugLogger(private val enabled: Boolean) {
    private val tag = "BtDebugLogger"
    private var writer: PrintWriter? = null
    private var sessionFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun startSession(deviceName: String, deviceAddress: String) {
        if (!enabled) return
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "FTMSBridge/debug"
            )
            dir.mkdirs()
            val safeName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filename = "${dateFormat.format(Date())}_${safeName}_$deviceAddress.log"
            sessionFile = File(dir, filename)
            writer = PrintWriter(FileOutputStream(sessionFile!!, true), true)
            writer?.println("# BT Debug Log")
            writer?.println("# Device: $deviceName ($deviceAddress)")
            writer?.println("# Started: ${Date()}")
            writer?.println("# ---")
            Log.i(tag, "Debug log started: ${sessionFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start debug log", e)
        }
    }

    fun logEvent(direction: String, uuid: String, data: ByteArray) {
        if (!enabled || writer == null) return
        val timestamp = timestampFormat.format(Date())
        val hex = data.joinToString(" ") { String.format("%02X", it) }
        val line = "$timestamp [$direction] $uuid ($${data.size} bytes): $hex"
        writer?.println(line)
        Log.d(tag, line)
    }

    fun logMessage(message: String) {
        if (!enabled || writer == null) return
        val timestamp = timestampFormat.format(Date())
        writer?.println("$timestamp [INFO] $message")
    }

    fun stopSession() {
        if (!enabled) return
        writer?.println("# Ended: ${Date()}")
        writer?.flush()
        writer?.close()
        writer = null
        Log.i(tag, "Debug log ended: ${sessionFile?.absolutePath}")
    }
}
