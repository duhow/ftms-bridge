package net.duhowpi.ftmsbridge.ftms

import net.duhowpi.ftmsbridge.model.FitnessSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FtmsDataParser {

    fun parseTreadmillData(data: ByteArray): FitnessSample {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
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
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
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
