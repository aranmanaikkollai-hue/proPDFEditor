package com.propdf.core.data.database

import androidx.room.TypeConverter

/**
 * Room TypeConverters for SearchDatabase.
 * All entities in SearchDatabase use primitive/String types only,
 * so no actual conversions are needed. This class exists to satisfy
 * the @TypeConverters annotation on SearchDatabase.
 */
class SearchConverters {

    @TypeConverter
    fun fromLong(value: Long): Long = value

    @TypeConverter
    fun toLong(value: Long): Long = value
}
