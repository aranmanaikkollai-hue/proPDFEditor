// security/src/main/java/com/propdf/security/data/entity/RedactionEntity.kt
package com.propdf.security.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdf.security.data.converter.RectConverter
import android.graphics.RectF

@Entity(tableName = "redactions")
@TypeConverters(RectConverter::class)
data class RedactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val pageNumber: Int,
    val rect: RectF,
    val color: Int = android.graphics.Color.BLACK,
    val overlayText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isApplied: Boolean = false
)
