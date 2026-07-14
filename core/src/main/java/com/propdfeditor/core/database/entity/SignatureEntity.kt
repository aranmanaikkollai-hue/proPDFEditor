package com.propdfeditor.core.database.entity

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdfeditor.core.database.converter.DateConverter
import com.propdfeditor.core.database.converter.RectFConverter
import java.util.Date

@Entity(tableName = "signatures")
@TypeConverters(DateConverter::class, RectFConverter::class)
data class SignatureEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "type")
    val type: SignatureType,

    @ColumnInfo(name = "bitmap_path")
    val bitmapPath: String?,

    @ColumnInfo(name = "text_content")
    val textContent: String? = null,

    @ColumnInfo(name = "font_family")
    val fontFamily: String? = null,

    @ColumnInfo(name = "font_size")
    val fontSize: Float? = null,

    @ColumnInfo(name = "text_color")
    val textColor: Int? = null,

    @ColumnInfo(name = "background_color")
    val backgroundColor: Int?,

    @ColumnInfo(name = "stroke_width")
    val strokeWidth: Float? = null,

    @ColumnInfo(name = "stroke_color")
    val strokeColor: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Date = Date(),

    @ColumnInfo(name = "use_count")
    val useCount: Int = 0,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "width")
    val width: Int = 400,

    @ColumnInfo(name = "height")
    val height: Int = 200,

    @ColumnInfo(name = "certificate_alias")
    val certificateAlias: String? = null,

    @ColumnInfo(name = "timestamp_enabled")
    val timestampEnabled: Boolean = true,

    @ColumnInfo(name = "location_meta")
    val locationMeta: String? = null
) {
    enum class SignatureType {
        DRAWN,
        IMAGE,
        TYPED,
        CERTIFICATE
    }
}
