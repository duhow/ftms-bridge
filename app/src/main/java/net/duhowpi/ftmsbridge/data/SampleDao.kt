package net.duhowpi.ftmsbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: WorkoutSample)

    @Query("SELECT * FROM workout_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getAllBySession(sessionId: Long): List<WorkoutSample>
}
