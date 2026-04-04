package net.duhowpi.ftmsbridge

import net.duhowpi.ftmsbridge.data.WorkoutSample
import net.duhowpi.ftmsbridge.data.WorkoutSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object WorkoutExporter {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun exportFileName(session: WorkoutSession, extension: String): String {
        val date = fileNameFormat.format(Date(session.startTimeMs))
        return "workout_${date}.${extension}"
    }

    /**
     * Generates a GPX 1.1 file from a session and its samples.
     * Uses track points with extensions for cadence, power, and heart rate
     * (Garmin TrackPointExtension v2 schema — widely supported by fitness apps).
     */
    fun toGpx(session: WorkoutSession, samples: List<WorkoutSample>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine(
            """<gpx version="1.1" creator="FTMS Bridge"""
                    + """ xmlns="http://www.topografix.com/GPX/1/1""""
                    + """ xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2""""
                    + """ xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance""""
                    + """ xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        )
        sb.appendLine("  <metadata>")
        sb.appendLine("    <name>${escapeXml(session.deviceName.ifEmpty { session.machineType })}</name>")
        sb.appendLine("    <time>${isoFormat.format(Date(session.startTimeMs))}</time>")
        sb.appendLine("  </metadata>")
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>${escapeXml(session.machineType)}</name>")
        sb.appendLine("    <trkseg>")

        // Use a virtual position — indoor machines have no GPS coordinates.
        // We emit a fixed coordinate so the file is valid GPX.
        val baseLat = 0.0
        val baseLon = 0.0

        for (sample in samples) {
            val ts = isoFormat.format(Date(sample.timestampMs))
            sb.appendLine("      <trkpt lat=\"$baseLat\" lon=\"$baseLon\">")
            sb.appendLine("        <time>$ts</time>")
            if (sample.totalDistanceM > 0) {
                // Elevation can represent cumulative distance for indoor tracks
                sb.appendLine("        <ele>${sample.totalDistanceM}</ele>")
            }
            sb.appendLine("        <extensions>")
            sb.appendLine("          <gpxtpx:TrackPointExtension>")
            if (sample.heartRateBpm > 0) {
                sb.appendLine("            <gpxtpx:hr>${sample.heartRateBpm}</gpxtpx:hr>")
            }
            if (sample.cadenceRpm > 0) {
                sb.appendLine("            <gpxtpx:cad>${sample.cadenceRpm.toInt()}</gpxtpx:cad>")
            }
            if (sample.speedKmh > 0) {
                // speed in m/s
                val mps = sample.speedKmh / 3.6
                sb.appendLine("            <gpxtpx:speed>${String.format(Locale.US, "%.3f", mps)}</gpxtpx:speed>")
            }
            if (sample.instantaneousPowerW > 0) {
                sb.appendLine("            <gpxtpx:power>${sample.instantaneousPowerW}</gpxtpx:power>")
            }
            sb.appendLine("          </gpxtpx:TrackPointExtension>")
            sb.appendLine("        </extensions>")
            sb.appendLine("      </trkpt>")
        }

        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    /**
     * Generates a CSV file with all sample fields.
     */
    fun toCsv(session: WorkoutSession, samples: List<WorkoutSample>): String {
        val sb = StringBuilder()
        sb.appendLine("# FTMS Bridge workout export")
        sb.appendLine("# Session: ${session.id} | Device: ${session.deviceName} | Type: ${session.machineType}")
        sb.appendLine("# Start: ${isoFormat.format(Date(session.startTimeMs))}")
        session.endTimeMs?.let {
            sb.appendLine("# End:   ${isoFormat.format(Date(it))}")
        }
        sb.appendLine()
        sb.appendLine("timestamp_ms,elapsed_sec,speed_kmh,cadence_rpm,power_w,distance_m,heart_rate_bpm,inclination_pct,resistance,energy_kcal")
        for (s in samples) {
            sb.appendLine(
                "${s.timestampMs},${s.elapsedTimeSec}," +
                        "${String.format(Locale.US, "%.2f", s.speedKmh)}," +
                        "${String.format(Locale.US, "%.1f", s.cadenceRpm)}," +
                        "${s.instantaneousPowerW},${s.totalDistanceM},${s.heartRateBpm}," +
                        "${String.format(Locale.US, "%.1f", s.inclinationPercent)}," +
                        "${s.resistanceLevel},${s.totalEnergyKcal}"
            )
        }
        return sb.toString()
    }

    fun writeToFile(dir: File, name: String, content: String): File {
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
