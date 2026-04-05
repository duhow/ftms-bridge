package net.duhowpi.ftmsbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE workout_sessions ADD COLUMN deviceAddress TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE workout_sessions ADD COLUMN hrDeviceName TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE workout_sessions ADD COLUMN hrDeviceAddress TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [WorkoutSession::class, WorkoutSample::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ftms_bridge.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
