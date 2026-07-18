// security/src/main/java/com/propdf/security/data/converter/RectConverter.kt
package com.propdf.security.data.converter

import android.graphics.RectF
import androidx.room.TypeConverter

class RectConverter {
    @TypeConverter
    fun fromRect(rect: RectF?): String? = rect?.let { 
        "${it.left},${it.top},${it.right},${it.bottom}" 
    }

    @TypeConverter
    fun toRect(value: String?): RectF? = value?.let {
        val parts = it.split(",")
        RectF(
            parts[0].toFloat(),
            parts[1].toFloat(),
            parts[2].toFloat(),
            parts[3].toFloat()
        )
    }
}
