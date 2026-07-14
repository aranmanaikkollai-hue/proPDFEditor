package com.propdfeditor.core.database.converter

import android.graphics.RectF
import androidx.room.TypeConverter

class RectFConverter {
    @TypeConverter
    fun fromRectF(rect: RectF?): String? {
        if (rect == null) return null
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    @TypeConverter
    fun toRectF(value: String?): RectF? {
        if (value == null) return null
        val parts = value.split(",")
        if (parts.size != 4) return null
        return try {
            RectF(
                parts[0].toFloat(),
                parts[1].toFloat(),
                parts[2].toFloat(),
                parts[3].toFloat()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
