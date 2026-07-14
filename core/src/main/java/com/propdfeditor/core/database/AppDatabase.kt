package com.propdfeditor.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdfeditor.core.database.converter.DateConverter
import com.propdfeditor.core.database.converter.RectFConverter
import com.propdfeditor.core.database.dao.CertificateDao
import com.propdfeditor.core.database.dao.SignatureDao
import com.propdfeditor.core.database.dao.SignatureHistoryDao
import com.propdfeditor.core.database.entity.CertificateEntity
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.core.database.entity.SignatureHistoryEntity

@Database(
    entities = [
        SignatureEntity::class,
        SignatureHistoryEntity::class,
        CertificateEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(DateConverter::class, RectFConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun signatureDao(): SignatureDao
    abstract fun signatureHistoryDao(): SignatureHistoryDao
    abstract fun certificateDao(): CertificateDao

    companion object {
        private const val DATABASE_NAME = "propdf_editor.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
