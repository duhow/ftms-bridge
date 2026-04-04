package net.duhowpi.ftmsbridge

import net.duhowpi.ftmsbridge.data.WorkoutSample
import net.duhowpi.ftmsbridge.data.WorkoutSession
import java.io.ByteArrayOutputStream

/**
 * Encodes workout data into the binary FIT (Flexible and Interoperable Data Transfer) format.
 * Produces activity files compatible with Garmin Connect, Strava, and other FIT-aware apps.
 *
 * Spec: ANT+ FIT Protocol v2 / Garmin FIT SDK v21.
 */
internal object FitEncoder {

    /** Seconds between Unix epoch (1970-01-01) and FIT epoch (1989-01-01 UTC). */
    private const val FIT_EPOCH_OFFSET = 631065600L

    /** CRC-16 lookup table as defined in the FIT protocol specification. */
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    private fun crc16(data: ByteArray, from: Int = 0, len: Int = data.size - from): Int {
        var crc = 0
        for (idx in from until from + len) {
            val b = data[idx].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = ((crc ushr 4) and 0x0FFF) xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = ((crc ushr 4) and 0x0FFF) xor tmp xor CRC_TABLE[(b ushr 4) and 0xF]
        }
        return crc
    }

    // ── Byte-writing helpers ────────────────────────────────────────────────

    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }
    private fun ByteArrayOutputStream.s16(v: Int) = u16(v and 0xFFFF)
    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v and 0xFF).toInt())
        write(((v ushr 8) and 0xFF).toInt())
        write(((v ushr 16) and 0xFF).toInt())
        write(((v ushr 24) and 0xFF).toInt())
    }

    // ── FIT base-type constants ─────────────────────────────────────────────

    private const val BT_ENUM   = 0x00  // 1 byte, invalid = 0xFF
    private const val BT_UINT8  = 0x02  // 1 byte, invalid = 0xFF
    private const val BT_UINT16 = 0x84  // 2 bytes, invalid = 0xFFFF
    private const val BT_UINT32 = 0x86  // 4 bytes, invalid = 0xFFFFFFFF
    private const val BT_SINT16 = 0x83  // 2 bytes, invalid = 0x7FFF

    // ── FIT global message numbers ──────────────────────────────────────────

    private const val GLOBAL_FILE_ID  = 0
    private const val GLOBAL_SESSION  = 18
    private const val GLOBAL_LAP      = 19
    private const val GLOBAL_RECORD   = 20
    private const val GLOBAL_EVENT    = 21
    private const val GLOBAL_ACTIVITY = 34

    // ── FIT invalid sentinels ───────────────────────────────────────────────

    private const val INVALID_UINT8  = 0xFF
    private const val INVALID_UINT16 = 0xFFFF
    private const val INVALID_UINT32 = 0xFFFFFFFFL

    /**
     * Writes a FIT definition message.
     * @param local      Local message type (0–15)
     * @param global     Global message number
     * @param fields     List of (fieldDefNum, sizeBytes, baseType)
     */
    private fun ByteArrayOutputStream.defMsg(
        local: Int,
        global: Int,
        fields: List<Triple<Int, Int, Int>>
    ) {
        u8(0x40 or (local and 0xF))  // definition-record header
        u8(0x00)                      // reserved
        u8(0x00)                      // architecture: little-endian
        u16(global)
        u8(fields.size)
        for ((num, size, type) in fields) {
            u8(num); u8(size); u8(type)
        }
    }

    /** Converts Unix milliseconds to FIT timestamp (seconds since FIT epoch). */
    private fun fitTs(ms: Long): Long = (ms / 1000L) - FIT_EPOCH_OFFSET

    // ── Public encoder ──────────────────────────────────────────────────────

    fun encode(session: WorkoutSession, samples: List<WorkoutSample>): ByteArray {
        val buf = ByteArrayOutputStream()

        val startTs  = fitTs(session.startTimeMs)
        val endMs    = session.endTimeMs
            ?: samples.lastOrNull()?.timestampMs
            ?: System.currentTimeMillis()
        val endTs    = fitTs(endMs)
        val elapsedMs = endMs - session.startTimeMs

        val sport: Int = when (session.machineType) {
            "TREADMILL"     -> 1   // running
            "INDOOR_BIKE"   -> 2   // cycling
            "CROSS_TRAINER" -> 4   // fitness_equipment
            "STAIR_CLIMBER" -> 4   // fitness_equipment
            else            -> 0
        }
        val subSport: Int = when (session.machineType) {
            "TREADMILL"     -> 1   // treadmill
            "INDOOR_BIKE"   -> 6   // indoor_cycling
            "CROSS_TRAINER" -> 14  // elliptical
            "STAIR_CLIMBER" -> 15  // stair_climbing
            else            -> 0
        }

        // Derive summary stats from sample data (accurate even if session fields are unset)
        val hrSamples  = samples.filter { it.heartRateBpm > 0 }
        val cadSamples = samples.filter { it.cadenceRpm > 0 }
        val pwrSamples = samples.filter { it.instantaneousPowerW > 0 }
        val spdSamples = samples.filter { it.speedKmh > 0 }

        val avgHr      = if (hrSamples.isNotEmpty())  hrSamples.map { it.heartRateBpm }.average().toInt() else 0
        val maxHr      = samples.maxOfOrNull { it.heartRateBpm } ?: 0
        val avgCadence = if (cadSamples.isNotEmpty()) cadSamples.map { it.cadenceRpm }.average().toInt() else 0
        val avgPower   = if (pwrSamples.isNotEmpty()) pwrSamples.map { it.instantaneousPowerW }.average().toInt() else 0
        val maxPower   = samples.maxOfOrNull { it.instantaneousPowerW } ?: 0
        val avgSpeed   = if (spdSamples.isNotEmpty()) spdSamples.map { it.speedKmh }.average() else 0.0
        val maxSpeed   = samples.maxOfOrNull { it.speedKmh } ?: 0.0
        val avgSpeedMmS = ((avgSpeed / 3.6) * 1000.0).toInt()
        val maxSpeedMmS = ((maxSpeed / 3.6) * 1000.0).toInt()

        val lastSample = samples.lastOrNull()
        val totalDistCm = (lastSample?.totalDistanceM?.toLong() ?: 0L) * 100
        val totalCals   = lastSample?.totalEnergyKcal?.takeIf { it > 0 }
            ?: session.totalEnergyKcal.takeIf { it > 0 }

        // ── Local 0: file_id ────────────────────────────────────────────────
        buf.defMsg(0, GLOBAL_FILE_ID, listOf(
            Triple(0, 1, BT_ENUM),   // type
            Triple(1, 2, BT_UINT16), // manufacturer
            Triple(2, 2, BT_UINT16), // product
            Triple(4, 4, BT_UINT32)  // time_created
        ))
        buf.u8(0)        // data header: local 0
        buf.u8(4)        // type = activity
        buf.u16(255)     // manufacturer = development
        buf.u16(0)       // product = 0
        buf.u32(startTs) // time_created

        // ── Local 1: event ──────────────────────────────────────────────────
        buf.defMsg(1, GLOBAL_EVENT, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(0,   1, BT_ENUM),   // event
            Triple(1,   1, BT_ENUM),   // event_type
            Triple(3,   4, BT_UINT32)  // data
        ))
        // Start event: event=0 (timer), event_type=0 (start)
        buf.u8(1); buf.u32(startTs); buf.u8(0); buf.u8(0); buf.u32(0L)

        // ── Local 2: record ─────────────────────────────────────────────────
        buf.defMsg(2, GLOBAL_RECORD, listOf(
            Triple(253, 4, BT_UINT32),  // timestamp
            Triple(6,   2, BT_UINT16),  // speed        (mm/s; scale=1000 → m/s)
            Triple(4,   1, BT_UINT8),   // cadence      (rpm)
            Triple(7,   2, BT_UINT16),  // power        (W)
            Triple(3,   1, BT_UINT8),   // heart_rate   (bpm)
            Triple(5,   4, BT_UINT32),  // distance     (cm;  scale=100 → m)
            Triple(9,   2, BT_SINT16),  // grade        (scale=100 → %)
            Triple(10,  1, BT_UINT8)    // resistance
        ))

        var prev: WorkoutSample? = null
        for (s in samples) {
            // Deduplication: skip if all measured values equal the previous sample
            if (prev != null &&
                prev.speedKmh           == s.speedKmh &&
                prev.cadenceRpm         == s.cadenceRpm &&
                prev.instantaneousPowerW == s.instantaneousPowerW &&
                prev.heartRateBpm       == s.heartRateBpm &&
                prev.totalDistanceM     == s.totalDistanceM &&
                prev.resistanceLevel    == s.resistanceLevel &&
                prev.inclinationPercent == s.inclinationPercent
            ) continue
            prev = s

            val ts       = fitTs(s.timestampMs)
            val speedMmS = ((s.speedKmh / 3.6) * 1000.0).toInt().coerceIn(0, 65534)
            val distCm   = s.totalDistanceM.toLong() * 100
            val cad      = s.cadenceRpm.toInt().coerceIn(0, 254)
            val pwr      = s.instantaneousPowerW.coerceIn(0, 65534)
            val hr       = s.heartRateBpm.coerceIn(0, 254)
            val grade    = (s.inclinationPercent * 100.0).toInt().coerceIn(-32767, 32767)
            val resist   = s.resistanceLevel.coerceIn(0, 254)

            buf.u8(2)
            buf.u32(ts)
            buf.u16(speedMmS)
            buf.u8(cad)
            buf.u16(pwr)
            buf.u8(hr)
            buf.u32(distCm)
            buf.s16(grade)
            buf.u8(resist)
        }

        // Stop event: event=0 (timer), event_type=4 (stop_all)
        buf.u8(1); buf.u32(endTs); buf.u8(0); buf.u8(4); buf.u32(0L)

        // ── Local 3: lap ────────────────────────────────────────────────────
        buf.defMsg(3, GLOBAL_LAP, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(2,   4, BT_UINT32), // start_time
            Triple(7,   4, BT_UINT32), // total_elapsed_time  (scale=1000; stored as ms)
            Triple(8,   4, BT_UINT32), // total_timer_time
            Triple(9,   4, BT_UINT32), // total_distance      (cm)
            Triple(11,  2, BT_UINT16), // total_calories
            Triple(0,   1, BT_ENUM),   // event
            Triple(1,   1, BT_ENUM)    // event_type
        ))
        buf.u8(3)
        buf.u32(endTs)
        buf.u32(startTs)
        buf.u32(elapsedMs)
        buf.u32(elapsedMs)
        buf.u32(totalDistCm)
        buf.u16(totalCals ?: INVALID_UINT16)
        buf.u8(9)  // event = lap
        buf.u8(1)  // event_type = stop

        // ── Local 4: session ────────────────────────────────────────────────
        buf.defMsg(4, GLOBAL_SESSION, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(2,   4, BT_UINT32), // start_time
            Triple(5,   4, BT_UINT32), // total_elapsed_time
            Triple(6,   4, BT_UINT32), // total_timer_time
            Triple(7,   4, BT_UINT32), // total_distance
            Triple(9,   2, BT_UINT16), // total_calories
            Triple(11,  1, BT_ENUM),   // sport
            Triple(12,  1, BT_ENUM),   // sub_sport
            Triple(0,   1, BT_ENUM),   // event
            Triple(1,   1, BT_ENUM),   // event_type
            Triple(16,  2, BT_UINT16), // avg_speed (mm/s)
            Triple(17,  2, BT_UINT16), // max_speed
            Triple(18,  1, BT_UINT8),  // avg_heart_rate
            Triple(19,  1, BT_UINT8),  // max_heart_rate
            Triple(21,  1, BT_UINT8),  // avg_cadence
            Triple(24,  2, BT_UINT16)  // avg_power
        ))
        buf.u8(4)
        buf.u32(endTs)
        buf.u32(startTs)
        buf.u32(elapsedMs)
        buf.u32(elapsedMs)
        buf.u32(totalDistCm)
        buf.u16(totalCals ?: INVALID_UINT16)
        buf.u8(sport)
        buf.u8(subSport)
        buf.u8(8)  // event = session
        buf.u8(1)  // event_type = stop
        buf.u16(if (avgSpeedMmS > 0) avgSpeedMmS.coerceIn(0, 65534) else INVALID_UINT16)
        buf.u16(if (maxSpeedMmS > 0) maxSpeedMmS.coerceIn(0, 65534) else INVALID_UINT16)
        buf.u8(if (avgHr > 0) avgHr.coerceIn(1, 254) else INVALID_UINT8)
        buf.u8(if (maxHr > 0) maxHr.coerceIn(1, 254) else INVALID_UINT8)
        buf.u8(if (avgCadence > 0) avgCadence.coerceIn(1, 254) else INVALID_UINT8)
        buf.u16(if (avgPower > 0) avgPower.coerceIn(1, 65534) else INVALID_UINT16)

        // ── Local 5: activity ───────────────────────────────────────────────
        buf.defMsg(5, GLOBAL_ACTIVITY, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(5,   4, BT_UINT32), // local_timestamp (no tz offset)
            Triple(0,   4, BT_UINT32), // total_timer_time
            Triple(1,   2, BT_UINT16), // num_sessions
            Triple(2,   1, BT_ENUM),   // type
            Triple(3,   1, BT_ENUM),   // event
            Triple(4,   1, BT_ENUM)    // event_type
        ))
        buf.u8(5)
        buf.u32(endTs)
        buf.u32(endTs)    // local_timestamp = UTC (timezone unknown)
        buf.u32(elapsedMs)
        buf.u16(1)        // num_sessions
        buf.u8(0)         // type = manual
        buf.u8(26)        // event = activity
        buf.u8(1)         // event_type = stop

        // ── Assemble FIT file ───────────────────────────────────────────────
        val dataBytes = buf.toByteArray()
        val dataSize  = dataBytes.size

        // Build 14-byte file header (12 bytes + 2-byte CRC)
        val hdrBuf = ByteArrayOutputStream()
        hdrBuf.u8(14)                    // header size
        hdrBuf.u8(0x20)                  // protocol version 2.0
        hdrBuf.u16(2132)                 // profile version (FIT 21.32)
        hdrBuf.u32(dataSize.toLong())    // data length (bytes)
        hdrBuf.write('.'.code); hdrBuf.write('F'.code)
        hdrBuf.write('I'.code); hdrBuf.write('T'.code)
        val hdrBytes  = hdrBuf.toByteArray()  // 12 bytes
        val hdrCrc    = crc16(hdrBytes)

        val result = ByteArrayOutputStream()
        result.write(hdrBytes)
        result.u16(hdrCrc)    // complete header = 14 bytes
        result.write(dataBytes)

        val fileSoFar = result.toByteArray()
        val fileCrc   = crc16(fileSoFar)
        result.u16(fileCrc)   // append 2-byte file CRC

        return result.toByteArray()
    }
}
