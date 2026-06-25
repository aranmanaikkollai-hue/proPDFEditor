package com.propdf.core.data.local

import androidx.room.TypeConverter

/**
 * Room TypeConverters for RecentFilesDatabase.
 * All columns in RecentFileEntity use primitive/String/Boolean types which
 * Room handles natively. This class satisfies the @TypeConverters annotation
 * on RecentFilesDatabase.
 */
class RecentFilesConverters {

    @TypeConverter
    fun fromLong(value: Long): Long = value

    @TypeConverter
    fun toLong(value: Long): Long = value
}
