package com.propdf.nas.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.propdf.nas.data.local.entity.NasConfigEntity
import com.propdf.nas.data.local.entity.PendingOperationEntity

@Database(
    entities = [NasConfigEntity::class, PendingOperationEntity::class],
    version = 1,
    exportSchema = true
)
abstract class NasDatabase : RoomDatabase() {
    abstract fun nasConfigDao(): NasConfigDao
    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        @Volatile
        private var INSTANCE: NasDatabase? = null

        fun getInstance(context: Context): NasDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NasDatabase::class.java,
                    "nas.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
