package com.propdf.annotations.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for annotation persistence.
 * Version 2: Added documentPath and isFlattened columns.
 */
@Database(
    entities = [AnnotationEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AnnotationDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao

    companion object {
        @Volatile
        private var INSTANCE: AnnotationDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE annotations ADD COLUMN documentPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE annotations ADD COLUMN isFlattened INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AnnotationDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnnotationDatabase::class.java,
                    "annotations.db"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }

        fun destroyInstance() {
            INSTANCE = null
        }
    }
}
