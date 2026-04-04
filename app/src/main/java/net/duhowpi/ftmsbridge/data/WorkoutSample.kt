package net.duhowpi.ftmsbridge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_samples",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class WorkoutSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val elapsedTimeSec: Int = 0,
    val speedKmh: Double = 0.0,
    val cadenceRpm: Double = 0.0,
    val instantaneousPowerW: Int = 0,
    val totalDistanceM: Int = 0,
    val heartRateBpm: Int = 0,
    val inclinationPercent: Double = 0.0,
    val resistanceLevel: Int = 0,
    val totalEnergyKcal: Int = 0
)
