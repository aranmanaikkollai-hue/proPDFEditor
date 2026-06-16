// annotations/src/main/java/com/propdf/annotations/persistence/AnnotationConverters.kt
package com.propdf.annotations.persistence

import androidx.room.TypeConverter

class AnnotationConverters {
    @TypeConverter
    fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    fun toBoolean(value: Int): Boolean = value != 0
}
