// annotations/src/main/java/com/propdf/annotations/persistence/AnnotationDatabase.kt
package com.propdf.annotations.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AnnotationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AnnotationDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao

    companion object {
        @Volatile
        private var INSTANCE: AnnotationDatabase? = null

        fun getInstance(context: Context): AnnotationDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnnotationDatabase::class.java,
                    "annotations.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
