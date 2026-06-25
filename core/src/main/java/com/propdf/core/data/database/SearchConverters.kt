package com.propdf.core.data.database

import androidx.room.TypeConverter

/**
 * Room TypeConverters for SearchDatabase.
 * All entities in SearchDatabase use primitive/String types that Room
 * handles natively — no custom conversions needed. This class exists
 * solely to satisfy the @TypeConverters annotation on SearchDatabase.
 */
class SearchConverters
