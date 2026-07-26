package net.duhowpi.ftmsbridge

import net.duhowpi.ftmsbridge.data.WorkoutSample
import net.duhowpi.ftmsbridge.data.WorkoutSession
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Minimal FIT activity file decoder — the inverse of [FitEncoder].
 * Reads session (18), record (20) and device_info (23) messages and maps them
 * back to Room entities, so exported workouts can be re-imported (backup/restore).
 */
internal object FitDecoder {

    /** Seconds between Unix epoch (1970-01-01) and FIT epoch (1989-01-01 UTC). */
    private const val FIT_EPOCH_OFFSET = 631065600L

    /** Safety cap for a single zip entry / fit file (a real workout is a few hundred KB). */
    private const val MAX_FIT_BYTES = 20 * 1024 * 1024

    class DecodedWorkout(val session: WorkoutSession, val samples: List<WorkoutSample>)

    private class FieldDef(val num: Int, val size: Int, val baseType: Int)
    private class MsgDef(val global: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val devBytes: Int)

    /** Byte size per FIT base-type number (0 = unsupported here). */
    private val BASE_SIZE = intArrayOf(1, 1, 1, 2, 2, 4, 4, 0, 0, 0, 1, 2, 4, 1, 0, 0, 0)

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /**
     * Extracts all root-level `.fit` entries from a zip archive (subfolders ignored).
     */
    fun unzipFits(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !entry.name.contains('/') &&
                    entry.name.endsWith(".fit", ignoreCase = true)
                ) {
                    val data = zip.readBytes()
                    if (data.size <= MAX_FIT_BYTES) result.add(data)
                }
                entry = zip.nextEntry
            }
        }
        return result
    }

    /**
     * Decodes a FIT activity file.
     * @throws IllegalArgumentException if the data is not a parseable FIT file.
     */
    fun decode(bytes: ByteArray): DecodedWorkout {
        require(bytes.size in 17..MAX_FIT_BYTES) { "Bad file size" }
        val headerSize = bytes[0].toInt() and 0xFF
        require(headerSize >= 12 && bytes.size > headerSize + 2) { "Bad FIT header" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") { "Missing .FIT magic" }
        val dataSize = readUInt(bytes, 4, 4, bigEndian = false)
        val dataEnd = (headerSize + dataSize).coerceAtMost((bytes.size - 2).toLong()).toInt()

        val defs = HashMap<Int, MsgDef>()
        var sessionMsg: Map<Int, Long>? = null
        var deviceName = ""
        val records = ArrayList<Map<Int, Long>>()
        var lastTimestamp = 0L

        var pos = headerSize
        while (pos < dataEnd) {
            val hdr = bytes[pos++].toInt() and 0xFF
            if (hdr and 0xC0 == 0x40) {
                // ── Definition message ──
                val hasDev = hdr and 0x20 != 0
                val local = hdr and 0xF
                pos++ // reserved
                val bigEndian = bytes[pos++].toInt() != 0
                val global = readUInt(bytes, pos, 2, bigEndian).toInt(); pos += 2
                val numFields = bytes[pos++].toInt() and 0xFF
                val fields = ArrayList<FieldDef>(numFields)
                repeat(numFields) {
                    fields.add(FieldDef(
                        bytes[pos].toInt() and 0xFF,
                        bytes[pos + 1].toInt() and 0xFF,
                        bytes[pos + 2].toInt() and 0xFF
                    ))
                    pos += 3
                }
                var devBytes = 0
                if (hasDev) {
                    val numDev = bytes[pos++].toInt() and 0xFF
                    repeat(numDev) {
                        devBytes += bytes[pos + 1].toInt() and 0xFF
                        pos += 3
                    }
                }
                defs[local] = MsgDef(global, bigEndian, fields, devBytes)
            } else {
                // ── Data message (normal or compressed-timestamp) ──
                val compressed = hdr and 0x80 != 0
                val local = if (compressed) (hdr ushr 5) and 0x3 else hdr and 0xF
                val def = defs[local] ?: throw IllegalArgumentException("Data before definition")
                val values = HashMap<Int, Long>()
                val strings = HashMap<Int, String>()
                for (f in def.fields) {
                    decodeField(bytes, pos, f, def.bigEndian, values, strings)
                    pos += f.size
                }
                pos += def.devBytes
                if (compressed) {
                    val offset = (hdr and 0x1F).toLong()
                    lastTimestamp += (offset - (lastTimestamp and 0x1FL)) and 0x1FL
                    values[253] = lastTimestamp
                } else {
                    values[253]?.let { lastTimestamp = it }
                }
                when (def.global) {
                    18 -> sessionMsg = values
                    20 -> records.add(values)
                    23 -> strings[19]?.takeIf { it.isNotBlank() }?.let { deviceName = it }
                }
            }
        }

        return buildWorkout(sessionMsg, records, deviceName)
    }

    private fun buildWorkout(
        s: Map<Int, Long>?,
        records: List<Map<Int, Long>>,
        deviceName: String
    ): DecodedWorkout {
        val startTs = s?.get(2) ?: records.firstOrNull()?.get(253)
            ?: throw IllegalArgumentException("No session or record data")
        val endTs = s?.get(253) ?: records.lastOrNull()?.get(253)
        val startMs = fitToUnixMs(startTs)

        val machineType = when (s?.get(6)?.toInt()) {
            FitSubSport.TREADMILL.value      -> "TREADMILL"
            FitSubSport.INDOOR_CYCLING.value -> "INDOOR_BIKE"
            FitSubSport.ELLIPTICAL.value     -> "CROSS_TRAINER"
            FitSubSport.STAIR_CLIMBING.value -> "STAIR_CLIMBER"
            else                             -> ""
        }

        val session = WorkoutSession(
            startTimeMs = startMs,
            endTimeMs = endTs?.let { fitToUnixMs(it) },
            machineType = machineType,
            deviceName = deviceName,
            totalDistanceM = ((s?.get(9) ?: records.lastOrNull()?.get(5) ?: 0L) / 100L).toInt(),
            totalEnergyKcal = (s?.get(11) ?: 0L).toInt(),
            totalElapsedTimeSec = (s?.get(7)?.div(1000L) ?: endTs?.minus(startTs) ?: 0L).toInt(),
            avgSpeedKmh = mmSToKmh(s?.get(14) ?: 0L),
            avgCadenceRpm = (s?.get(18) ?: 0L).toDouble(),
            avgPowerW = (s?.get(20) ?: 0L).toInt(),
            avgHeartRateBpm = (s?.get(16) ?: 0L).toInt(),
            maxSpeedKmh = mmSToKmh(s?.get(15) ?: 0L),
            maxHeartRateBpm = (s?.get(17) ?: 0L).toInt(),
            maxPowerW = (s?.get(21) ?: 0L).toInt()
        )

        val samples = records.mapNotNull { r ->
            val ts = r[253] ?: return@mapNotNull null
            val tsMs = fitToUnixMs(ts)
            WorkoutSample(
                sessionId = 0,
                timestampMs = tsMs,
                elapsedTimeSec = ((tsMs - startMs) / 1000L).toInt(),
                speedKmh = mmSToKmh(r[6] ?: 0L),
                cadenceRpm = (r[4] ?: 0L).toDouble(),
                instantaneousPowerW = (r[7] ?: 0L).toInt(),
                totalDistanceM = ((r[5] ?: 0L) / 100L).toInt(),
                heartRateBpm = (r[3] ?: 0L).toInt(),
                inclinationPercent = (r[9] ?: 0L) / 100.0,
                resistanceLevel = (r[10] ?: 0L).toInt(),
                totalEnergyKcal = (r[33] ?: 0L).toInt()
            )
        }

        return DecodedWorkout(session, samples)
    }

    private fun fitToUnixMs(ts: Long): Long = (ts + FIT_EPOCH_OFFSET) * 1000L

    private fun mmSToKmh(mmS: Long): Double = mmS / 1000.0 * 3.6

    private fun readUInt(bytes: ByteArray, pos: Int, len: Int, bigEndian: Boolean): Long {
        var v = 0L
        for (i in 0 until len) {
            val b = (bytes[pos + i].toInt() and 0xFF).toLong()
            v = v or (b shl (8 * if (bigEndian) len - 1 - i else i))
        }
        return v
    }

    private fun decodeField(
        bytes: ByteArray,
        pos: Int,
        f: FieldDef,
        bigEndian: Boolean,
        values: MutableMap<Int, Long>,
        strings: MutableMap<Int, String>
    ) {
        val bt = f.baseType and 0x1F
        if (bt == 7) { // string: null-terminated
            var end = pos
            while (end < pos + f.size && bytes[end].toInt() != 0) end++
            strings[f.num] = String(bytes, pos, end - pos, Charsets.UTF_8)
            return
        }
        val expected = BASE_SIZE.getOrElse(bt) { 0 }
        // Skip floats, 64-bit types and arrays — not used by our profile subset
        if (expected == 0 || f.size != expected) return
        var raw = readUInt(bytes, pos, f.size, bigEndian)
        val invalid = when (bt) {
            0, 2, 13   -> 0xFFL
            1          -> 0x7FL
            3          -> 0x7FFFL
            4          -> 0xFFFFL
            5          -> 0x7FFFFFFFL
            6          -> 0xFFFFFFFFL
            10, 11, 12 -> 0L
            else       -> return
        }
        if (raw == invalid) return
        when (bt) { // sign-extend signed types
            1 -> raw = raw.toByte().toLong()
            3 -> raw = raw.toShort().toLong()
            5 -> raw = raw.toInt().toLong()
        }
        values[f.num] = raw
    }
}
