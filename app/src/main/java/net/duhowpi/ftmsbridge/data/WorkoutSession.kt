package net.duhowpi.ftmsbridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTimeMs: Long = System.currentTimeMillis(),
    var endTimeMs: Long? = null,
    val machineType: String = "",
    val deviceName: String = "",
    var totalDistanceM: Int = 0,
    var totalEnergyKcal: Int = 0,
    var totalElapsedTimeSec: Int = 0,
    var avgSpeedKmh: Double = 0.0,
    var avgCadenceRpm: Double = 0.0,
    var avgPowerW: Int = 0,
    var avgHeartRateBpm: Int = 0,
    var maxSpeedKmh: Double = 0.0,
    var maxHeartRateBpm: Int = 0,
    var maxPowerW: Int = 0
)
