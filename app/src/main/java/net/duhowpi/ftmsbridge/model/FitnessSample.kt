package net.duhowpi.ftmsbridge.model

data class FitnessSample(
    val timestampMs: Long = System.currentTimeMillis(),
    val elapsedTimeSec: Int = 0,
    val speedKmh: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val cadenceRpm: Double = 0.0,
    val averageCadenceRpm: Double = 0.0,
    val totalDistanceM: Int = 0,
    val resistanceLevel: Int = 0,
    val instantaneousPowerW: Int = 0,
    val averagePowerW: Int = 0,
    val totalEnergyKcal: Int = 0,
    val energyPerHourKcal: Int = 0,
    val energyPerMinuteKcal: Int = 0,
    val heartRateBpm: Int = 0,
    val metabolicEquivalent: Double = 0.0,
    val inclinationPercent: Double = 0.0,
    val rampAngleDeg: Double = 0.0,
    val elevationGainPositiveM: Int = 0,
    val elevationGainNegativeM: Int = 0,
    val remainingTimeSec: Int = 0
)
