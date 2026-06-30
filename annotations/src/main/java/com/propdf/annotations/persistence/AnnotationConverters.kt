package com.propdf.annotations.persistence

import android.graphics.RectF
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.propdf.annotations.model.PointF

/**
 * Room TypeConverters for complex annotation data types.
 * Handles RectF, List<RectF>, List<PointF>, FloatArray, and nullable Int colors.
 */
class AnnotationConverters {

    private val gson = Gson()

    // RectF <-> String
    @TypeConverter
    fun fromRectF(rect: RectF?): String? {
        if (rect == null) return null
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    @TypeConverter
    fun toRectF(value: String?): RectF? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parts.size >= 4) RectF(parts[0], parts[1], parts[2], parts[3]) else null
    }

    // List<RectF> <-> String
    @TypeConverter
    fun fromRectFList(rects: List<RectF>?): String? {
        if (rects == null) return null
        return rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
    }

    @TypeConverter
    fun toRectFList(value: String?): List<RectF>? {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(";").mapNotNull { rectStr ->
            val parts = rectStr.split(",").mapNotNull { it.toFloatOrNull() }
            if (parts.size >= 4) RectF(parts[0], parts[1], parts[2], parts[3]) else null
        }
    }

    // List<PointF> <-> JSON String
    @TypeConverter
    fun fromPointFList(points: List<PointF>?): String? {
        if (points == null) return null
        return gson.toJson(points)
    }

    @TypeConverter
    fun toPointFList(value: String?): List<PointF>? {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<PointF>>() {}.type
            gson.fromJson<List<PointF>>(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // FloatArray <-> String
    @TypeConverter
    fun fromFloatArray(array: FloatArray?): String? {
        if (array == null) return null
        return array.joinToString(",")
    }

    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? {
        if (value.isNullOrBlank()) return null
        return value.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
    }

    // Nullable Int (for colors) <-> String
    @TypeConverter
    fun fromNullableInt(value: Int?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun toNullableInt(value: String?): Int? {
        return value?.toIntOrNull()
    }
}
