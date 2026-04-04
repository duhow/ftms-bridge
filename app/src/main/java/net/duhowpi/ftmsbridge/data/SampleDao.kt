package net.duhowpi.ftmsbridge.data

import androidx.room.Dao
import androidx.room.Insert

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: WorkoutSample)
}
