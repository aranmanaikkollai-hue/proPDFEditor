package com.propdfeditor.batch.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdfeditor.batch.data.dao.BatchJobDao
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.UriListConverter

@Database(
    entities = [BatchJobEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(UriListConverter::class)
abstract class BatchDatabase : RoomDatabase() {
    abstract fun batchJobDao(): BatchJobDao

    companion object {
        @Volatile
        private var INSTANCE: BatchDatabase? = null

        fun getInstance(context: Context): BatchDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatchDatabase::class.java,
                    "batch_jobs.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
