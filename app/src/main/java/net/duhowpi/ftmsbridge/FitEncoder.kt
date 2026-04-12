package net.duhowpi.ftmsbridge

import kotlin.math.roundToInt
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

    private const val APP_MANUFACTURER = 255 // development
    private const val APP_PRODUCT = 1
    private const val APP_HARDWARE_VERSION = 1
    private const val APP_PRODUCT_NAME = "FTMS Bridge"
    private const val PRODUCT_NAME_SIZE = 32
    private const val DEVICE_DESCRIPTOR_SIZE = 32

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

    private data class Accumulators(
        val totalCycles: Long,
        val totalWorkJ: Long
    )

    private data class LapInfo(
        val startTs: Long,
        val endTs: Long,
        val elapsedMs: Long,
        val distCm: Long,
        val totalCycles: Long,
        val totalWorkJ: Long,
        val avgSpeedMmS: Int,
        val maxSpeedMmS: Int,
        val avgHr: Int,
        val maxHr: Int,
        val avgCadence: Int,
        val avgPower: Int,
        val maxPower: Int,
        val calories: Int?,
        val isLast: Boolean
    )

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
    private fun ByteArrayOutputStream.fitString(size: Int, value: String) {
        val raw = value.toByteArray(Charsets.UTF_8)
        val copyLen = raw.size.coerceAtMost(size - 1)
        write(raw, 0, copyLen)
        repeat(size - copyLen) { write(0) }
    }

    // ── FIT base-type constants ─────────────────────────────────────────────

    private const val BT_ENUM   = 0x00  // 1 byte, invalid = 0xFF
    private const val BT_UINT8  = 0x02  // 1 byte, invalid = 0xFF
    private const val BT_STRING = 0x07  // variable length, null-terminated
    private const val BT_UINT16 = 0x84  // 2 bytes, invalid = 0xFFFF
    private const val BT_UINT32 = 0x86  // 4 bytes, invalid = 0xFFFFFFFF
    private const val BT_SINT16 = 0x83  // 2 bytes, invalid = 0x7FFF

    // ── FIT global message numbers ──────────────────────────────────────────

    private const val GLOBAL_FILE_ID      = 0
    private const val GLOBAL_SESSION      = 18
    private const val GLOBAL_LAP          = 19
    private const val GLOBAL_RECORD       = 20
    private const val GLOBAL_EVENT        = 21
    private const val GLOBAL_DEVICE_INFO  = 23
    private const val GLOBAL_ACTIVITY     = 34
    private const val GLOBAL_FILE_CREATOR = 49

    // ── FIT invalid sentinels ───────────────────────────────────────────────

    private const val INVALID_UINT8  = 0xFF
    private const val INVALID_UINT16 = 0xFFFF
    private const val INVALID_UINT32 = 0xFFFFFFFFL

    // ── FIT event_type values ───────────────────────────────────────────────

    private const val EVENT_TYPE_START    = 0
    private const val EVENT_TYPE_STOP     = 1  // stop (intermediate lap)
    private const val EVENT_TYPE_STOP_ALL = 4  // stop_all (final event)

    private const val EVENT_TIMER = 0
    private const val EVENT_SESSION = 8
    private const val EVENT_LAP = 9
    private const val EVENT_ACTIVITY = 26

    private const val LAP_TRIGGER_DISTANCE = 2
    private const val LAP_TRIGGER_SESSION_END = 7
    private const val SESSION_TRIGGER_ACTIVITY_END = 0

    private const val ACTIVITY_TYPE_MANUAL = 0
    private const val SOURCE_TYPE_LOCAL = 5

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

    private fun speedToMmPerSecond(speedKmh: Double): Int =
        ((speedKmh / 3.6) * 1000.0).roundToInt().coerceIn(0, 65534)

    private fun softwareVersionCode(versionName: String): Int {
        val parts = Regex("""\d+""").findAll(versionName)
            .map { it.value.toIntOrNull() ?: 0 }
            .take(3)
            .toList()
        if (parts.isEmpty()) return 1
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return (major * 10000 + minor * 100 + patch).coerceIn(1, 65534)
    }

    private fun integrateAccumulators(samples: List<WorkoutSample>): Accumulators {
        if (samples.size < 2) return Accumulators(0L, 0L)

        var totalCycles = 0.0
        var totalWorkJ = 0.0

        for (idx in 1 until samples.size) {
            val prev = samples[idx - 1]
            val cur = samples[idx]
            val deltaSec = ((cur.timestampMs - prev.timestampMs).coerceAtLeast(0L)) / 1000.0
            if (deltaSec <= 0.0) continue

            val avgCadence = ((prev.cadenceRpm + cur.cadenceRpm) / 2.0).coerceAtLeast(0.0)
            val avgPower = ((prev.instantaneousPowerW + cur.instantaneousPowerW) / 2.0).coerceAtLeast(0.0) / 2.0

            totalCycles += (avgCadence / 60.0) * deltaSec
            totalWorkJ += avgPower * deltaSec
        }

        return Accumulators(totalCycles.roundToInt().toLong(), totalWorkJ.roundToInt().toLong())
    }

    private fun cumulativeCalories(sample: WorkoutSample): Int? = sample.totalEnergyKcal.takeIf { it > 0 }

    // ── Public encoder ──────────────────────────────────────────────────────

    fun encode(session: WorkoutSession, samples: List<WorkoutSample>): ByteArray {
        val buf = ByteArrayOutputStream()

        val fallbackElapsedMs = session.totalElapsedTimeSec.coerceAtLeast(0).toLong() * 1000L
        val endMs = session.endTimeMs
            ?: samples.lastOrNull()?.timestampMs
            ?: (session.startTimeMs + fallbackElapsedMs).takeIf { fallbackElapsedMs > 0L }
            ?: System.currentTimeMillis()
        val startTs = fitTs(session.startTimeMs)
        val endTs = fitTs(endMs)
        val elapsedMs = (endMs - session.startTimeMs).coerceAtLeast(fallbackElapsedMs)

        val sport: Int = when (session.machineType) {
            "TREADMILL"     -> FitSport.RUNNING.value
            "INDOOR_BIKE"   -> FitSport.CYCLING.value
            "CROSS_TRAINER" -> FitSport.FITNESS_EQUIPMENT.value
            "STAIR_CLIMBER" -> FitSport.FITNESS_EQUIPMENT.value
            else            -> FitSport.GENERIC.value
        }
        val subSport: Int = when (session.machineType) {
            "TREADMILL"     -> FitSubSport.TREADMILL.value
            "INDOOR_BIKE"   -> FitSubSport.INDOOR_CYCLING.value
            "CROSS_TRAINER" -> FitSubSport.ELLIPTICAL.value
            "STAIR_CLIMBER" -> FitSubSport.STAIR_CLIMBING.value
            else            -> FitSubSport.GENERIC.value
        }

        // Derive summary stats from sample data (accurate even if session fields are unset)
        val hrSamples  = samples.filter { it.heartRateBpm > 0 }
        val cadSamples = samples.filter { it.cadenceRpm > 0 }
        val pwrSamples = samples.filter { it.instantaneousPowerW > 0 }
        val spdSamples = samples.filter { it.speedKmh > 0 }

        val avgHr = if (hrSamples.isNotEmpty()) {
            hrSamples.map { it.heartRateBpm }.average().roundToInt()
        } else {
            session.avgHeartRateBpm.coerceAtLeast(0)
        }
        val maxHr = maxOf(samples.maxOfOrNull { it.heartRateBpm } ?: 0, session.maxHeartRateBpm)
        val avgCadence = if (cadSamples.isNotEmpty()) {
            cadSamples.map { it.cadenceRpm }.average().roundToInt()
        } else {
            session.avgCadenceRpm.roundToInt().coerceAtLeast(0)
        }
        val avgPower = if (pwrSamples.isNotEmpty()) {
            pwrSamples.map { it.instantaneousPowerW }.average().roundToInt()
        } else {
            session.avgPowerW.coerceAtLeast(0)
        }
        val maxPower = maxOf(samples.maxOfOrNull { it.instantaneousPowerW } ?: 0, session.maxPowerW)
        val avgSpeedKmh = if (spdSamples.isNotEmpty()) {
            spdSamples.map { it.speedKmh }.average()
        } else {
            session.avgSpeedKmh.coerceAtLeast(0.0)
        }
        val maxSpeedKmh = maxOf(samples.maxOfOrNull { it.speedKmh } ?: 0.0, session.maxSpeedKmh)
        val avgSpeedMmS = speedToMmPerSecond(avgSpeedKmh)
        val maxSpeedMmS = speedToMmPerSecond(maxSpeedKmh)

        val derivedAccumulators = integrateAccumulators(samples)
        val totalCycles = derivedAccumulators.totalCycles.takeIf { it > 0L }
            ?: if (avgCadence > 0 && elapsedMs > 0L) {
                ((avgCadence.toDouble() / 60.0) * (elapsedMs / 1000.0)).roundToInt().toLong()
            } else {
                0L
            }
        val totalWorkJ = derivedAccumulators.totalWorkJ.takeIf { it > 0L }
            ?: if (avgPower > 0 && elapsedMs > 0L) {
                (avgPower.toDouble() * (elapsedMs / 1000.0)).roundToInt().toLong()
            } else {
                0L
            }

        val lastSample = samples.lastOrNull()
        val totalDistM = maxOf(lastSample?.totalDistanceM ?: 0, session.totalDistanceM)
        val totalDistCm = totalDistM.toLong() * 100L
        val totalCals = maxOf(lastSample?.totalEnergyKcal ?: 0, session.totalEnergyKcal).takeIf { it > 0 }

        val softwareVersion = softwareVersionCode(BuildConfig.VERSION_NAME)
        val sourceDescriptor = session.deviceName.ifEmpty { session.machineType }.ifEmpty { APP_PRODUCT_NAME }

        // ── Local 0: file_id ────────────────────────────────────────────────
        buf.defMsg(0, GLOBAL_FILE_ID, listOf(
            Triple(0, 1, BT_ENUM),                  // type
            Triple(1, 2, BT_UINT16),                // manufacturer
            Triple(2, 2, BT_UINT16),                // product
            Triple(4, 4, BT_UINT32),                // time_created
            Triple(8, PRODUCT_NAME_SIZE, BT_STRING) // product_name
        ))
        buf.u8(0)             // data header: local 0
        buf.u8(4)             // type = activity
        buf.u16(APP_MANUFACTURER)
        buf.u16(APP_PRODUCT)
        buf.u32(startTs)      // time_created
        buf.fitString(PRODUCT_NAME_SIZE, APP_PRODUCT_NAME)

        // ── Local 6: file_creator ───────────────────────────────────────────
        buf.defMsg(6, GLOBAL_FILE_CREATOR, listOf(
            Triple(0, 2, BT_UINT16),
            Triple(1, 1, BT_UINT8)
        ))
        buf.u8(6)
        buf.u16(softwareVersion)
        buf.u8(APP_HARDWARE_VERSION)

        // ── Local 7: device_info ────────────────────────────────────────────
        buf.defMsg(7, GLOBAL_DEVICE_INFO, listOf(
            Triple(253, 4, BT_UINT32),
            Triple(0, 1, BT_UINT8),
            Triple(25, 1, BT_ENUM),
            Triple(27, PRODUCT_NAME_SIZE, BT_STRING),
            Triple(19, DEVICE_DESCRIPTOR_SIZE, BT_STRING)
        ))
        buf.u8(7)
        buf.u32(startTs)
        buf.u8(0)
        buf.u8(SOURCE_TYPE_LOCAL)
        buf.fitString(PRODUCT_NAME_SIZE, APP_PRODUCT_NAME)
        buf.fitString(DEVICE_DESCRIPTOR_SIZE, sourceDescriptor)

        // ── Local 1: event ──────────────────────────────────────────────────
        buf.defMsg(1, GLOBAL_EVENT, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(0,   1, BT_ENUM),   // event
            Triple(1,   1, BT_ENUM),   // event_type
            Triple(3,   4, BT_UINT32)  // data
        ))
        // Start event: event=0 (timer), event_type=0 (start)
        buf.u8(1); buf.u32(startTs); buf.u8(EVENT_TIMER); buf.u8(EVENT_TYPE_START); buf.u32(0L)

        // ── Local 2: record ─────────────────────────────────────────────────
        buf.defMsg(2, GLOBAL_RECORD, listOf(
            Triple(253, 4, BT_UINT32),  // timestamp
            Triple(6,   2, BT_UINT16),  // speed        (mm/s; scale=1000 → m/s)
            Triple(4,   1, BT_UINT8),   // cadence      (rpm)
            Triple(7,   2, BT_UINT16),  // power        (W)
            Triple(3,   1, BT_UINT8),   // heart_rate   (bpm)
            Triple(5,   4, BT_UINT32),  // distance     (cm;  scale=100 → m)
            Triple(9,   2, BT_SINT16),  // grade        (scale=100 → %)
            Triple(10,  1, BT_UINT8),   // resistance
            Triple(19,  4, BT_UINT32),  // total_cycles
            Triple(33,  2, BT_UINT16)   // calories
        ))

        var prevRaw: WorkoutSample? = null
        var prevEmitted: WorkoutSample? = null
        var cumulativeCycles = 0.0
        var cumulativeWorkJ = 0.0

        for (sample in samples) {
            prevRaw?.let { prev ->
                val deltaSec = ((sample.timestampMs - prev.timestampMs).coerceAtLeast(0L)) / 1000.0
                if (deltaSec > 0.0) {
                    val avgCad = ((prev.cadenceRpm + sample.cadenceRpm) / 2.0).coerceAtLeast(0.0)
                    val avgPwr = ((prev.instantaneousPowerW + sample.instantaneousPowerW) / 2.0).coerceAtLeast(0.0) / 2.0
                    cumulativeCycles += (avgCad / 60.0) * deltaSec
                    cumulativeWorkJ += avgPwr * deltaSec
                }
            }

            // Deduplication: skip if all measured values equal the previous sample
            if (prevEmitted != null &&
                prevEmitted.speedKmh == sample.speedKmh &&
                prevEmitted.cadenceRpm == sample.cadenceRpm &&
                prevEmitted.instantaneousPowerW == sample.instantaneousPowerW &&
                prevEmitted.heartRateBpm == sample.heartRateBpm &&
                prevEmitted.totalDistanceM == sample.totalDistanceM &&
                prevEmitted.resistanceLevel == sample.resistanceLevel &&
                prevEmitted.inclinationPercent == sample.inclinationPercent &&
                prevEmitted.totalEnergyKcal == sample.totalEnergyKcal
            ) {
                prevRaw = sample
                continue
            }
            prevEmitted = sample
            prevRaw = sample

            val ts = fitTs(sample.timestampMs)
            val speedMmS = speedToMmPerSecond(sample.speedKmh)
            val distCm = sample.totalDistanceM.toLong().coerceAtLeast(0L) * 100L
            val cad = sample.cadenceRpm.roundToInt().coerceIn(0, 254)
            val pwr = sample.instantaneousPowerW.coerceIn(0, 65534)
            val hr = sample.heartRateBpm.coerceIn(0, 254)
            val grade = (sample.inclinationPercent * 100.0).roundToInt().coerceIn(-32767, 32767)
            val resist = sample.resistanceLevel.coerceIn(0, 254)
            val sampleCycles = cumulativeCycles.roundToInt().toLong().coerceIn(0L, 0xFFFFFFFEL)
            val sampleCalories = cumulativeCalories(sample)?.coerceIn(0, 65534) ?: INVALID_UINT16

            buf.u8(2)
            buf.u32(ts)
            buf.u16(speedMmS)
            buf.u8(cad)
            buf.u16(pwr)
            buf.u8(hr)
            buf.u32(distCm)
            buf.s16(grade)
            buf.u8(resist)
            buf.u32(sampleCycles)
            buf.u16(sampleCalories)
        }

        // Stop event: event=0 (timer), event_type=stop_all
        buf.u8(1); buf.u32(endTs); buf.u8(EVENT_TIMER); buf.u8(EVENT_TYPE_STOP_ALL); buf.u32(0L)

        // ── Local 3: lap ────────────────────────────────────────────────────
        buf.defMsg(3, GLOBAL_LAP, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(2,   4, BT_UINT32), // start_time
            Triple(7,   4, BT_UINT32), // total_elapsed_time  (ms, scale=1000 → s)
            Triple(8,   4, BT_UINT32), // total_timer_time    (ms, scale=1000 → s)
            Triple(9,   4, BT_UINT32), // total_distance      (cm, scale=100 → m)
            Triple(10,  4, BT_UINT32), // total_cycles
            Triple(11,  2, BT_UINT16), // total_calories
            Triple(13,  2, BT_UINT16), // avg_speed           (mm/s, scale=1000 → m/s)
            Triple(14,  2, BT_UINT16), // max_speed           (mm/s)
            Triple(15,  1, BT_UINT8),  // avg_heart_rate      (bpm)
            Triple(16,  1, BT_UINT8),  // max_heart_rate
            Triple(17,  1, BT_UINT8),  // avg_cadence         (rpm)
            Triple(19,  2, BT_UINT16), // avg_power           (W)
            Triple(20,  2, BT_UINT16), // max_power           (W)
            Triple(24,  1, BT_ENUM),   // lap_trigger
            Triple(25,  1, BT_ENUM),   // sport
            Triple(39,  1, BT_ENUM),   // sub_sport
            Triple(41,  4, BT_UINT32), // total_work          (J)
            Triple(0,   1, BT_ENUM),   // event
            Triple(1,   1, BT_ENUM)    // event_type
        ))

        // Build per-km laps from sample data, plus final partial lap
        val lapList = mutableListOf<LapInfo>()
        val lapDistanceM = 1000
        var lapNum = 1
        var lapStartIdx = 0
        var lapStartTsMs = session.startTimeMs

        fun buildLap(lapSamples: List<WorkoutSample>, lapEndTsMs: Long, isLast: Boolean): LapInfo {
            val lapElapsedMs = lapEndTsMs - lapStartTsMs
            val lapStartDist = if (lapStartIdx > 0) samples[lapStartIdx - 1].totalDistanceM else 0
            val lapEndDist = lapSamples.lastOrNull()?.totalDistanceM ?: lapStartDist
            val lapDistCm = (lapEndDist - lapStartDist).toLong().coerceAtLeast(0L) * 100L
            val hrS = lapSamples.filter { it.heartRateBpm > 0 }
            val spdS = lapSamples.filter { it.speedKmh > 0 }
            val cadS = lapSamples.filter { it.cadenceRpm > 0 }
            val pwrS = lapSamples.filter { it.instantaneousPowerW > 0 }
            val lapAvgSpeedMmS = if (spdS.isNotEmpty()) speedToMmPerSecond(spdS.map { it.speedKmh }.average()) else 0
            val lapMaxSpeedMmS = speedToMmPerSecond(lapSamples.maxOfOrNull { it.speedKmh } ?: 0.0)
            val lapAvgHr = if (hrS.isNotEmpty()) hrS.map { it.heartRateBpm }.average().roundToInt() else 0
            val lapMaxHr = lapSamples.maxOfOrNull { it.heartRateBpm } ?: 0
            val lapAvgCad = if (cadS.isNotEmpty()) cadS.map { it.cadenceRpm }.average().roundToInt() else 0
            val lapAvgPwr = if (pwrS.isNotEmpty()) pwrS.map { it.instantaneousPowerW }.average().roundToInt() else 0
            val lapMaxPwr = lapSamples.maxOfOrNull { it.instantaneousPowerW } ?: 0
            val lapAccumulators = integrateAccumulators(lapSamples)
            val lapTotalCycles = lapAccumulators.totalCycles.takeIf { it > 0L }
                ?: if (lapAvgCad > 0 && lapElapsedMs > 0L) {
                    ((lapAvgCad.toDouble() / 60.0) * (lapElapsedMs / 1000.0)).roundToInt().toLong()
                } else {
                    0L
                }
            val lapTotalWorkJ = lapAccumulators.totalWorkJ.takeIf { it > 0L }
                ?: if (lapAvgPwr > 0 && lapElapsedMs > 0L) {
                    (lapAvgPwr.toDouble() * (lapElapsedMs / 1000.0)).roundToInt().toLong()
                } else {
                    0L
                }
            val lapStartCalories = if (lapStartIdx > 0) samples[lapStartIdx - 1].totalEnergyKcal else 0
            val lapEndCalories = lapSamples.lastOrNull()?.totalEnergyKcal ?: lapStartCalories
            val lapCalories = (lapEndCalories - lapStartCalories).takeIf { it > 0 }

            return LapInfo(
                startTs = fitTs(lapStartTsMs),
                endTs = fitTs(lapEndTsMs),
                elapsedMs = lapElapsedMs,
                distCm = lapDistCm,
                totalCycles = lapTotalCycles,
                totalWorkJ = lapTotalWorkJ,
                avgSpeedMmS = lapAvgSpeedMmS,
                maxSpeedMmS = lapMaxSpeedMmS,
                avgHr = lapAvgHr,
                maxHr = lapMaxHr,
                avgCadence = lapAvgCad,
                avgPower = lapAvgPwr,
                maxPower = lapMaxPwr,
                calories = lapCalories,
                isLast = isLast
            )
        }

        if (samples.isNotEmpty()) {
            samples.forEachIndexed { idx, sample ->
                if (sample.totalDistanceM >= lapNum * lapDistanceM) {
                    val lapSamples = samples.subList(lapStartIdx, idx + 1)
                    val lapEndTsMs = sample.timestampMs
                    lapList.add(buildLap(lapSamples, lapEndTsMs, false))
                    lapNum++
                    lapStartTsMs = lapEndTsMs
                    lapStartIdx = idx + 1
                }
            }
            // Final partial lap (or the only lap if distance < 1km)
            if (lapStartIdx < samples.size) {
                val lapSamples = samples.subList(lapStartIdx, samples.size)
                lapList.add(buildLap(lapSamples, endMs, true))
            } else if (lapList.isEmpty()) {
                // No distance data at all — write a single summary lap
                lapList.add(buildLap(samples, endMs, true))
            }
        }

        // If we have no lap data (empty samples), fall back to single session lap
        if (lapList.isEmpty()) {
            buf.u8(3)
            buf.u32(endTs)
            buf.u32(startTs)
            buf.u32(elapsedMs)
            buf.u32(elapsedMs)
            buf.u32(totalDistCm)
            buf.u32(totalCycles.coerceIn(0L, 0xFFFFFFFEL))
            buf.u16(totalCals ?: INVALID_UINT16)
            buf.u16(if (avgSpeedMmS > 0) avgSpeedMmS else INVALID_UINT16)
            buf.u16(if (maxSpeedMmS > 0) maxSpeedMmS else INVALID_UINT16)
            buf.u8(if (avgHr > 0) avgHr.coerceIn(1, 254) else INVALID_UINT8)
            buf.u8(if (maxHr > 0) maxHr.coerceIn(1, 254) else INVALID_UINT8)
            buf.u8(if (avgCadence > 0) avgCadence.coerceIn(1, 254) else INVALID_UINT8)
            buf.u16(if (avgPower > 0) avgPower.coerceIn(1, 65534) else INVALID_UINT16)
            buf.u16(if (maxPower > 0) maxPower.coerceIn(1, 65534) else INVALID_UINT16)
            buf.u8(LAP_TRIGGER_SESSION_END)
            buf.u8(sport)
            buf.u8(subSport)
            buf.u32(totalWorkJ.coerceIn(0L, 0xFFFFFFFEL))
            buf.u8(EVENT_LAP)
            buf.u8(EVENT_TYPE_STOP_ALL)
        } else {
            lapList.forEach { lap ->
                buf.u8(3)
                buf.u32(lap.endTs)
                buf.u32(lap.startTs)
                buf.u32(lap.elapsedMs)
                buf.u32(lap.elapsedMs)
                buf.u32(lap.distCm)
                buf.u32(lap.totalCycles.coerceIn(0L, 0xFFFFFFFEL))
                buf.u16(lap.calories?.coerceIn(0, 65534) ?: INVALID_UINT16)
                buf.u16(if (lap.avgSpeedMmS > 0) lap.avgSpeedMmS else INVALID_UINT16)
                buf.u16(if (lap.maxSpeedMmS > 0) lap.maxSpeedMmS else INVALID_UINT16)
                buf.u8(if (lap.avgHr > 0) lap.avgHr.coerceIn(1, 254) else INVALID_UINT8)
                buf.u8(if (lap.maxHr > 0) lap.maxHr.coerceIn(1, 254) else INVALID_UINT8)
                buf.u8(if (lap.avgCadence > 0) lap.avgCadence.coerceIn(1, 254) else INVALID_UINT8)
                buf.u16(if (lap.avgPower > 0) lap.avgPower.coerceIn(1, 65534) else INVALID_UINT16)
                buf.u16(if (lap.maxPower > 0) lap.maxPower.coerceIn(1, 65534) else INVALID_UINT16)
                buf.u8(if (lap.isLast) LAP_TRIGGER_SESSION_END else LAP_TRIGGER_DISTANCE)
                buf.u8(sport)
                buf.u8(subSport)
                buf.u32(lap.totalWorkJ.coerceIn(0L, 0xFFFFFFFEL))
                buf.u8(EVENT_LAP)
                buf.u8(if (lap.isLast) EVENT_TYPE_STOP_ALL else EVENT_TYPE_STOP)
            }
        }

        // ── Local 4: session ────────────────────────────────────────────────
        val numLapsWritten = if (lapList.isEmpty()) 1 else lapList.size

        buf.defMsg(4, GLOBAL_SESSION, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(2,   4, BT_UINT32), // start_time
            Triple(7,   4, BT_UINT32), // total_elapsed_time (ms, scale=1000 → s)
            Triple(8,   4, BT_UINT32), // total_timer_time   (ms, scale=1000 → s)
            Triple(9,   4, BT_UINT32), // total_distance     (cm, scale=100 → m)
            Triple(10,  4, BT_UINT32), // total_cycles
            Triple(11,  2, BT_UINT16), // total_calories
            Triple(5,   1, BT_ENUM),   // sport
            Triple(6,   1, BT_ENUM),   // sub_sport
            Triple(0,   1, BT_ENUM),   // event
            Triple(1,   1, BT_ENUM),   // event_type
            Triple(14,  2, BT_UINT16), // avg_speed (mm/s, scale=1000 → m/s)
            Triple(15,  2, BT_UINT16), // max_speed
            Triple(16,  1, BT_UINT8),  // avg_heart_rate
            Triple(17,  1, BT_UINT8),  // max_heart_rate
            Triple(18,  1, BT_UINT8),  // avg_cadence
            Triple(20,  2, BT_UINT16), // avg_power
            Triple(21,  2, BT_UINT16), // max_power
            Triple(25,  2, BT_UINT16), // first_lap_index
            Triple(26,  2, BT_UINT16), // num_laps
            Triple(28,  1, BT_ENUM),   // trigger
            Triple(48,  4, BT_UINT32), // total_work (J)
            Triple(59,  4, BT_UINT32)  // total_moving_time (ms)
        ))
        buf.u8(4)
        buf.u32(endTs)
        buf.u32(startTs)
        buf.u32(elapsedMs)
        buf.u32(elapsedMs)
        buf.u32(totalDistCm)
        buf.u32(totalCycles.coerceIn(0L, 0xFFFFFFFEL))
        buf.u16(totalCals ?: INVALID_UINT16)
        buf.u8(sport)
        buf.u8(subSport)
        buf.u8(EVENT_SESSION)
        buf.u8(EVENT_TYPE_STOP_ALL)
        buf.u16(if (avgSpeedMmS > 0) avgSpeedMmS else INVALID_UINT16)
        buf.u16(if (maxSpeedMmS > 0) maxSpeedMmS else INVALID_UINT16)
        buf.u8(if (avgHr > 0) avgHr.coerceIn(1, 254) else INVALID_UINT8)
        buf.u8(if (maxHr > 0) maxHr.coerceIn(1, 254) else INVALID_UINT8)
        buf.u8(if (avgCadence > 0) avgCadence.coerceIn(1, 254) else INVALID_UINT8)
        buf.u16(if (avgPower > 0) avgPower.coerceIn(1, 65534) else INVALID_UINT16)
        buf.u16(if (maxPower > 0) maxPower.coerceIn(1, 65534) else INVALID_UINT16)
        buf.u16(0)               // first_lap_index = 0
        buf.u16(numLapsWritten)  // num_laps
        buf.u8(SESSION_TRIGGER_ACTIVITY_END)
        buf.u32(totalWorkJ.coerceIn(0L, 0xFFFFFFFEL))
        buf.u32(elapsedMs)

        // ── Local 5: activity ───────────────────────────────────────────────
        buf.defMsg(5, GLOBAL_ACTIVITY, listOf(
            Triple(253, 4, BT_UINT32), // timestamp
            Triple(0,   4, BT_UINT32), // total_timer_time
            Triple(1,   2, BT_UINT16), // num_sessions
            Triple(2,   1, BT_ENUM),   // type
            Triple(3,   1, BT_ENUM),   // event
            Triple(4,   1, BT_ENUM)    // event_type
        ))
        buf.u8(5)
        buf.u32(endTs)
        buf.u32(elapsedMs)
        buf.u16(1)        // num_sessions
        buf.u8(ACTIVITY_TYPE_MANUAL)
        buf.u8(EVENT_ACTIVITY)
        buf.u8(EVENT_TYPE_STOP)

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
        val hdrBytes = hdrBuf.toByteArray()
        val hdrCrc = crc16(hdrBytes)

        val result = ByteArrayOutputStream()
        result.write(hdrBytes)
        result.u16(hdrCrc)    // complete header = 14 bytes
        result.write(dataBytes)

        val fileSoFar = result.toByteArray()
        val fileCrc = crc16(fileSoFar)
        result.u16(fileCrc)   // append 2-byte file CRC

        return result.toByteArray()
    }
}
