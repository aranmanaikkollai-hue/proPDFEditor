package com.propdfeditor.batch.data.util

import android.net.Uri
import androidx.room.TypeConverter

class UriListConverter {
    @TypeConverter
    fun fromUriList(uris: List<Uri>): String {
        return uris.joinToString(",") { it.toString() }
    }

    @TypeConverter
    fun toUriList(data: String): List<Uri> {
        if (data.isEmpty()) return emptyList()
        return data.split(",").map { Uri.parse(it) }
    }
}
