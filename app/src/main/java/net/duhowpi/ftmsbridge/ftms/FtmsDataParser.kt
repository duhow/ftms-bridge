package net.duhowpi.ftmsbridge.ftms

import android.util.Log
import net.duhowpi.ftmsbridge.model.FitnessSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FtmsDataParser {

    private val tag = "FtmsDataParser"

    /**
     * Bytes of zero padding added behind every packet before parsing.
     *
     * A machine can describe more fields in its flags than the link can carry: with the
     * default ATT MTU of 23 a notification holds 20 bytes, while a Treadmill Data packet
     * that includes heart rate needs 21. Parsing such a packet straight out of a
     * [ByteBuffer] throws [java.nio.BufferUnderflowException] part way through, and
     * because notifications are delivered on a binder callback thread the exception is
     * swallowed and the entire sample is lost — including the speed and distance that
     * were present at the front of the packet.
     *
     * Truncation only ever removes trailing fields, so padding lets the fields that did
     * arrive parse normally while the missing ones read back as zero. 16 bytes covers the
     * longest tail any of these characteristics can declare.
     */
    private const val TRUNCATION_PADDING = 16

    /** Wraps [data] for parsing so that a short packet yields partial data, not none. */
    private fun paddedBuffer(data: ByteArray): ByteBuffer =
        ByteBuffer.wrap(data.copyOf(data.size + TRUNCATION_PADDING)).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Logs when a packet described more data than it carried, so a machine whose
     * notifications do not fit the current MTU is visible rather than silently degraded.
     */
    private fun warnIfTruncated(buf: ByteBuffer, data: ByteArray, flags: Int, what: String) {
        if (buf.position() > data.size) {
            Log.w(
                tag,
                "$what packet truncated: flags=0x%04X declared %d bytes, link delivered %d"
                    .format(flags, buf.position(), data.size)
            )
        }
    }

    fun parseTreadmillData(data: ByteArray): FitnessSample {
        val buf = paddedBuffer(data)
        val flags = buf.short.toInt() and 0xFFFF

        // Bit 0: More Data (1 = no instantaneous speed in this packet)
        // When More Data = 0, Instantaneous Speed is present
        var speedKmh = 0.0
        if (flags and 0x0001 == 0) {
            speedKmh = (buf.short.toInt() and 0xFFFF) * 0.01
        }

        var averageSpeedKmh = 0.0
        if (flags and 0x0002 != 0) {
            averageSpeedKmh = (buf.short.toInt() and 0xFFFF) * 0.01
        }

        var totalDistanceM = 0
        if (flags and 0x0004 != 0) {
            // 24-bit unsigned
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            totalDistanceM = b0 or (b1 shl 8) or (b2 shl 16)
        }

        var inclinationPercent = 0.0
        var rampAngleDeg = 0.0
        if (flags and 0x0008 != 0) {
            inclinationPercent = buf.short.toInt() * 0.1
            rampAngleDeg = buf.short.toInt() * 0.1
        }

        var elevationGainPositiveM = 0
        var elevationGainNegativeM = 0
        if (flags and 0x0010 != 0) {
            elevationGainPositiveM = buf.short.toInt() and 0xFFFF
            elevationGainNegativeM = buf.short.toInt() and 0xFFFF
        }

        // Bit 5: Instantaneous Pace
        if (flags and 0x0020 != 0) {
            buf.get() // skip 1 byte (pace in km/min * 0.1)
        }

        // Bit 6: Average Pace
        if (flags and 0x0040 != 0) {
            buf.get() // skip 1 byte
        }

        var totalEnergyKcal = 0
        var energyPerHourKcal = 0
        var energyPerMinuteKcal = 0
        if (flags and 0x0080 != 0) {
            totalEnergyKcal = buf.short.toInt() and 0xFFFF
            energyPerHourKcal = buf.short.toInt() and 0xFFFF
            energyPerMinuteKcal = buf.get().toInt() and 0xFF
        }

        var heartRateBpm = 0
        if (flags and 0x0100 != 0) {
            heartRateBpm = buf.get().toInt() and 0xFF
        }

        var metabolicEquivalent = 0.0
        if (flags and 0x0200 != 0) {
            metabolicEquivalent = (buf.get().toInt() and 0xFF) * 0.1
        }

        var elapsedTimeSec = 0
        if (flags and 0x0400 != 0) {
            elapsedTimeSec = buf.short.toInt() and 0xFFFF
        }

        var remainingTimeSec = 0
        if (flags and 0x0800 != 0) {
            remainingTimeSec = buf.short.toInt() and 0xFFFF
        }

        // Bit 12: Force on Belt
        var instantaneousPowerW = 0
        if (flags and 0x1000 != 0) {
            buf.short // skip force on belt (sint16)
            buf.short // skip percentage (sint16)
        }

        // Bit 13: Power Output
        if (flags and 0x2000 != 0) {
            instantaneousPowerW = buf.short.toInt()
            buf.short // skip percentage (sint16)
        }

        warnIfTruncated(buf, data, flags, "Treadmill")
        Log.d(tag, "treadmill raw: speed=%.2f km/h incl=%.1f%% dist=${totalDistanceM}m kcal=${totalEnergyKcal} hr=${heartRateBpm} t=${elapsedTimeSec}s"
            .format(speedKmh, inclinationPercent))
        return FitnessSample(
            speedKmh = speedKmh,
            averageSpeedKmh = averageSpeedKmh,
            totalDistanceM = totalDistanceM,
            inclinationPercent = inclinationPercent,
            rampAngleDeg = rampAngleDeg,
            elevationGainPositiveM = elevationGainPositiveM,
            elevationGainNegativeM = elevationGainNegativeM,
            totalEnergyKcal = totalEnergyKcal,
            energyPerHourKcal = energyPerHourKcal,
            energyPerMinuteKcal = energyPerMinuteKcal,
            heartRateBpm = heartRateBpm,
            metabolicEquivalent = metabolicEquivalent,
            elapsedTimeSec = elapsedTimeSec,
            remainingTimeSec = remainingTimeSec,
            instantaneousPowerW = instantaneousPowerW
        )
    }

    fun parseIndoorBikeData(data: ByteArray): FitnessSample {
        val buf = paddedBuffer(data)
        val flags = buf.short.toInt() and 0xFFFF

        // Bit 0: More Data (0 = Instantaneous Speed present)
        var speedKmh = 0.0
        if (flags and 0x0001 == 0) {
            speedKmh = (buf.short.toInt() and 0xFFFF) * 0.01
        }

        var averageSpeedKmh = 0.0
        if (flags and 0x0002 != 0) {
            averageSpeedKmh = (buf.short.toInt() and 0xFFFF) * 0.01
        }

        var cadenceRpm = 0.0
        if (flags and 0x0004 != 0) {
            cadenceRpm = (buf.short.toInt() and 0xFFFF) * 0.5
        }

        var averageCadenceRpm = 0.0
        if (flags and 0x0008 != 0) {
            averageCadenceRpm = (buf.short.toInt() and 0xFFFF) * 0.5
        }

        var totalDistanceM = 0
        if (flags and 0x0010 != 0) {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            totalDistanceM = b0 or (b1 shl 8) or (b2 shl 16)
        }

        var resistanceLevel = 0
        if (flags and 0x0020 != 0) {
            resistanceLevel = buf.short.toInt()
        }

        var instantaneousPowerW = 0
        if (flags and 0x0040 != 0) {
            instantaneousPowerW = buf.short.toInt()
        }

        var averagePowerW = 0
        if (flags and 0x0080 != 0) {
            averagePowerW = buf.short.toInt()
        }

        var totalEnergyKcal = 0
        var energyPerHourKcal = 0
        var energyPerMinuteKcal = 0
        if (flags and 0x0100 != 0) {
            totalEnergyKcal = buf.short.toInt() and 0xFFFF
            energyPerHourKcal = buf.short.toInt() and 0xFFFF
            energyPerMinuteKcal = buf.get().toInt() and 0xFF
        }

        var heartRateBpm = 0
        if (flags and 0x0200 != 0) {
            heartRateBpm = buf.get().toInt() and 0xFF
        }

        var metabolicEquivalent = 0.0
        if (flags and 0x0400 != 0) {
            metabolicEquivalent = (buf.get().toInt() and 0xFF) * 0.1
        }

        var elapsedTimeSec = 0
        if (flags and 0x0800 != 0) {
            elapsedTimeSec = buf.short.toInt() and 0xFFFF
        }

        var remainingTimeSec = 0
        if (flags and 0x1000 != 0) {
            remainingTimeSec = buf.short.toInt() and 0xFFFF
        }

        warnIfTruncated(buf, data, flags, "Indoor bike")
        return FitnessSample(
            speedKmh = speedKmh,
            averageSpeedKmh = averageSpeedKmh,
            cadenceRpm = cadenceRpm,
            averageCadenceRpm = averageCadenceRpm,
            totalDistanceM = totalDistanceM,
            resistanceLevel = resistanceLevel,
            instantaneousPowerW = instantaneousPowerW,
            averagePowerW = averagePowerW,
            totalEnergyKcal = totalEnergyKcal,
            energyPerHourKcal = energyPerHourKcal,
            energyPerMinuteKcal = energyPerMinuteKcal,
            heartRateBpm = heartRateBpm,
            metabolicEquivalent = metabolicEquivalent,
            elapsedTimeSec = elapsedTimeSec,
            remainingTimeSec = remainingTimeSec
        )
    }

    /**
     * Parses Cross Trainer Data characteristic (0x2ACE) — elliptical trainer.
     *
     * Flags (16 bits):
     *   bit 0: More Data (0 = Instantaneous Speed present, uint16 * 0.01 km/h)
     *   bit 1: Average Speed present (uint16 * 0.01 km/h)
     *   bit 2: Total Distance present (uint24 m)
     *   bit 3: Step Count present (uint16 strides/min * 0.5 + uint16 avg strides/min * 0.5)
     *   bit 4: Stride Count present (uint32 total strides)
     *   bit 5: Elevation Gain present (uint16 positive m + uint16 negative m)
     *   bit 6: Inclination and Ramp Angle present (sint16 * 0.1% + sint16 * 0.1°)
     *   bit 7: Resistance Level present (sint16)
     *   bit 8: Instantaneous Power present (sint16 W)
     *   bit 9: Average Power present (sint16 W)
     *   bit 10: Expended Energy present (uint16 kcal + uint16 kcal/hr + uint8 kcal/min)
     *   bit 11: Heart Rate present (uint8 bpm)
     *   bit 12: Metabolic Equivalent present (uint8 * 0.1)
     *   bit 13: Elapsed Time present (uint16 s)
     *   bit 14: Remaining Time present (uint16 s)
     */
    fun parseCrossTrainerData(data: ByteArray): FitnessSample {
        val buf = paddedBuffer(data)
        val flags = buf.short.toInt() and 0xFFFF

        var speedKmh = 0.0
        if (flags and 0x0001 == 0) {
            speedKmh = (buf.short.toInt() and 0xFFFF) * 0.01
        }

        var averageSpeedKmh = 0.0
        if (flags and 0x0002 != 0) {
            averageSpeedKmh = (buf.short.toInt() and 0xFFFF) * 0.01
        }

        var totalDistanceM = 0
        if (flags and 0x0004 != 0) {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            totalDistanceM = b0 or (b1 shl 8) or (b2 shl 16)
        }

        var cadenceRpm = 0.0
        var averageCadenceRpm = 0.0
        if (flags and 0x0008 != 0) {
            cadenceRpm = (buf.short.toInt() and 0xFFFF) * 0.5
            averageCadenceRpm = (buf.short.toInt() and 0xFFFF) * 0.5
        }

        if (flags and 0x0010 != 0) {
            buf.int // skip total stride count (uint32)
        }

        var elevationGainPositiveM = 0
        var elevationGainNegativeM = 0
        if (flags and 0x0020 != 0) {
            elevationGainPositiveM = buf.short.toInt() and 0xFFFF
            elevationGainNegativeM = buf.short.toInt() and 0xFFFF
        }

        var inclinationPercent = 0.0
        if (flags and 0x0040 != 0) {
            inclinationPercent = buf.short.toInt() * 0.1
            buf.short // skip ramp angle
        }

        var resistanceLevel = 0
        if (flags and 0x0080 != 0) {
            resistanceLevel = buf.short.toInt()
        }

        var instantaneousPowerW = 0
        if (flags and 0x0100 != 0) {
            instantaneousPowerW = buf.short.toInt()
        }

        var averagePowerW = 0
        if (flags and 0x0200 != 0) {
            averagePowerW = buf.short.toInt()
        }

        var totalEnergyKcal = 0
        var energyPerHourKcal = 0
        var energyPerMinuteKcal = 0
        if (flags and 0x0400 != 0) {
            totalEnergyKcal = buf.short.toInt() and 0xFFFF
            energyPerHourKcal = buf.short.toInt() and 0xFFFF
            energyPerMinuteKcal = buf.get().toInt() and 0xFF
        }

        var heartRateBpm = 0
        if (flags and 0x0800 != 0) {
            heartRateBpm = buf.get().toInt() and 0xFF
        }

        var metabolicEquivalent = 0.0
        if (flags and 0x1000 != 0) {
            metabolicEquivalent = (buf.get().toInt() and 0xFF) * 0.1
        }

        var elapsedTimeSec = 0
        if (flags and 0x2000 != 0) {
            elapsedTimeSec = buf.short.toInt() and 0xFFFF
        }

        var remainingTimeSec = 0
        if (flags and 0x4000 != 0) {
            remainingTimeSec = buf.short.toInt() and 0xFFFF
        }

        warnIfTruncated(buf, data, flags, "Cross trainer")
        return FitnessSample(
            speedKmh = speedKmh,
            averageSpeedKmh = averageSpeedKmh,
            cadenceRpm = cadenceRpm,
            averageCadenceRpm = averageCadenceRpm,
            totalDistanceM = totalDistanceM,
            elevationGainPositiveM = elevationGainPositiveM,
            elevationGainNegativeM = elevationGainNegativeM,
            inclinationPercent = inclinationPercent,
            resistanceLevel = resistanceLevel,
            instantaneousPowerW = instantaneousPowerW,
            averagePowerW = averagePowerW,
            totalEnergyKcal = totalEnergyKcal,
            energyPerHourKcal = energyPerHourKcal,
            energyPerMinuteKcal = energyPerMinuteKcal,
            heartRateBpm = heartRateBpm,
            metabolicEquivalent = metabolicEquivalent,
            elapsedTimeSec = elapsedTimeSec,
            remainingTimeSec = remainingTimeSec
        )
    }

    /**
     * Parses Stair Climber Data (0x2AD1) and Step Climber Data (0x2AD0).
     *
     * Both share a similar structure. Fields:
     *   Flags (16 bits):
     *   bit 0: More Data (0 = Instantaneous Step Rate present, uint16 * 0.5 steps/min)
     *   bit 1: Average Step Rate present (uint16 * 0.5 steps/min)
     *   bit 2: Total Distance present (uint24 m)
     *   bit 3: Step Count present (uint32 total steps)
     *   bit 4: Stride Count present (uint32 total strides — Step Climber only)
     *   bit 5: Expended Energy present (uint16 kcal + uint16 kcal/hr + uint8 kcal/min)
     *   bit 6: Heart Rate present (uint8 bpm)
     *   bit 7: Metabolic Equivalent present (uint8 * 0.1)
     *   bit 8: Elapsed Time present (uint16 s)
     *   bit 9: Remaining Time present (uint16 s)
     */
    fun parseStairClimberData(data: ByteArray): FitnessSample {
        val buf = paddedBuffer(data)
        val flags = buf.short.toInt() and 0xFFFF

        var cadenceRpm = 0.0
        if (flags and 0x0001 == 0) {
            cadenceRpm = (buf.short.toInt() and 0xFFFF) * 0.5
        }

        var averageCadenceRpm = 0.0
        if (flags and 0x0002 != 0) {
            averageCadenceRpm = (buf.short.toInt() and 0xFFFF) * 0.5
        }

        var totalDistanceM = 0
        if (flags and 0x0004 != 0) {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            totalDistanceM = b0 or (b1 shl 8) or (b2 shl 16)
        }

        if (flags and 0x0008 != 0) {
            buf.int // skip total step count (uint32)
        }

        if (flags and 0x0010 != 0) {
            buf.int // skip total stride count (uint32)
        }

        var totalEnergyKcal = 0
        var energyPerHourKcal = 0
        var energyPerMinuteKcal = 0
        if (flags and 0x0020 != 0) {
            totalEnergyKcal = buf.short.toInt() and 0xFFFF
            energyPerHourKcal = buf.short.toInt() and 0xFFFF
            energyPerMinuteKcal = buf.get().toInt() and 0xFF
        }

        var heartRateBpm = 0
        if (flags and 0x0040 != 0) {
            heartRateBpm = buf.get().toInt() and 0xFF
        }

        var metabolicEquivalent = 0.0
        if (flags and 0x0080 != 0) {
            metabolicEquivalent = (buf.get().toInt() and 0xFF) * 0.1
        }

        var elapsedTimeSec = 0
        if (flags and 0x0100 != 0) {
            elapsedTimeSec = buf.short.toInt() and 0xFFFF
        }

        var remainingTimeSec = 0
        if (flags and 0x0200 != 0) {
            remainingTimeSec = buf.short.toInt() and 0xFFFF
        }

        warnIfTruncated(buf, data, flags, "Stair climber")
        return FitnessSample(
            cadenceRpm = cadenceRpm,
            averageCadenceRpm = averageCadenceRpm,
            totalDistanceM = totalDistanceM,
            totalEnergyKcal = totalEnergyKcal,
            energyPerHourKcal = energyPerHourKcal,
            energyPerMinuteKcal = energyPerMinuteKcal,
            heartRateBpm = heartRateBpm,
            metabolicEquivalent = metabolicEquivalent,
            elapsedTimeSec = elapsedTimeSec,
            remainingTimeSec = remainingTimeSec
        )
    }

    /**
     * Parses the Fitness Machine Status characteristic (0x2AD7).
     * Returns the op-code byte (see FtmsConstants.MACHINE_STATUS_*).
     */
    fun parseMachineStatusOpCode(data: ByteArray): Int {
        if (data.isEmpty()) return -1
        return data[0].toInt() and 0xFF
    }

    fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val flags = data[0].toInt() and 0xFF
        return if (flags and 0x01 == 0) {
            // HR is UINT8
            if (data.size >= 2) data[1].toInt() and 0xFF else 0
        } else {
            // HR is UINT16
            if (data.size >= 3) {
                (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            } else 0
        }
    }
}
