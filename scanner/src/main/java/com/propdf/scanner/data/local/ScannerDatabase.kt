package com.propdf.scanner.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [ScannedDocumentEntity::class, ScannedPageEntity::class], version = 1, exportSchema = false)
@TypeConverters(ScannerTypeConverters::class)
abstract class ScannerDatabase : RoomDatabase() {
    abstract fun scannedDocumentDao(): ScannedDocumentDao

    companion object {
        private const val DATABASE_NAME = "scanner_database.db"
        @Volatile private var INSTANCE: ScannerDatabase? = null

        fun getInstance(context: Context): ScannerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): ScannerDatabase {
            return Room.databaseBuilder(context.applicationContext, ScannerDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
