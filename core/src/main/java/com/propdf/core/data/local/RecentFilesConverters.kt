package com.propdf.core.data.local

import androidx.room.TypeConverter

/**
 * Room TypeConverters for RecentFilesDatabase.
 * All columns in RecentFileEntity use primitive/String/Boolean types that
 * Room handles natively — no custom conversions needed. This class exists
 * solely to satisfy the @TypeConverters annotation on RecentFilesDatabase.
 */
class RecentFilesConverters
