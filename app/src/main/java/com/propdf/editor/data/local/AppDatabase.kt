package com.propdf.editor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdf.editor.domain.model.Converters
import com.propdf.editor.domain.model.ConversionTask

@Database(
    entities = [ConversionTask::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversionTaskDao(): ConversionTaskDao
}
