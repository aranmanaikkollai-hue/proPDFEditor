package com.propdf.core.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.propdf.core.domain.model.OcrJobStatus

class OcrConverters {

    private val gson = Gson()

    @TypeConverter
    fun fromOcrJobStatus(status: OcrJobStatus): String = status.name

    @TypeConverter
    fun toOcrJobStatus(name: String): OcrJobStatus = OcrJobStatus.valueOf(name)

    @TypeConverter
    fun fromStringList(list: List<String>): String = gson.toJson(list)

    @TypeConverter
    fun toStringList(json: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
}
