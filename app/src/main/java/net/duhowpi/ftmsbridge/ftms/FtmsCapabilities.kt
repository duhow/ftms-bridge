package net.duhowpi.ftmsbridge.ftms

data class FtmsCapabilities(
    val machineFeatures: Long,
    val targetSettingFeatures: Long
) {
    // Machine Feature bits (from Fitness Machine Feature characteristic, first 32 bits)
    val supportsAverageSpeed: Boolean get() = machineFeatures and (1L shl 0) != 0L
    val supportsCadence: Boolean get() = machineFeatures and (1L shl 1) != 0L
    val supportsTotalDistance: Boolean get() = machineFeatures and (1L shl 2) != 0L
    val supportsInclination: Boolean get() = machineFeatures and (1L shl 3) != 0L
    val supportsElevationGain: Boolean get() = machineFeatures and (1L shl 4) != 0L
    val supportsPace: Boolean get() = machineFeatures and (1L shl 5) != 0L
    val supportsStepCount: Boolean get() = machineFeatures and (1L shl 6) != 0L
    val supportsResistanceLevel: Boolean get() = machineFeatures and (1L shl 7) != 0L
    val supportsStrideCount: Boolean get() = machineFeatures and (1L shl 8) != 0L
    val supportsExpendedEnergy: Boolean get() = machineFeatures and (1L shl 9) != 0L
    val supportsHeartRate: Boolean get() = machineFeatures and (1L shl 10) != 0L
    val supportsMetabolicEquivalent: Boolean get() = machineFeatures and (1L shl 11) != 0L
    val supportsElapsedTime: Boolean get() = machineFeatures and (1L shl 12) != 0L
    val supportsRemainingTime: Boolean get() = machineFeatures and (1L shl 13) != 0L
    val supportsPowerMeasurement: Boolean get() = machineFeatures and (1L shl 14) != 0L
    val supportsForceOnBelt: Boolean get() = machineFeatures and (1L shl 15) != 0L
    val supportsPowerOutput: Boolean get() = machineFeatures and (1L shl 16) != 0L

    companion object {
        fun fromBytes(data: ByteArray): FtmsCapabilities {
            if (data.size < 8) return FtmsCapabilities(0, 0)
            val machineFeatures = (data[0].toLong() and 0xFF) or
                    ((data[1].toLong() and 0xFF) shl 8) or
                    ((data[2].toLong() and 0xFF) shl 16) or
                    ((data[3].toLong() and 0xFF) shl 24)
            val targetSettingFeatures = (data[4].toLong() and 0xFF) or
                    ((data[5].toLong() and 0xFF) shl 8) or
                    ((data[6].toLong() and 0xFF) shl 16) or
                    ((data[7].toLong() and 0xFF) shl 24)
            return FtmsCapabilities(machineFeatures, targetSettingFeatures)
        }
    }
}
