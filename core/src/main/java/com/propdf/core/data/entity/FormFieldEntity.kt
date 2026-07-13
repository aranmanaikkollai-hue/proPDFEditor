package com.propdf.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdf.core.data.database.Converters
import java.util.Date

@Entity(
    tableName = "form_fields",
    indices = [Index(value = ["documentUri", "fieldName"], unique = true)]
)
@TypeConverters(Converters::class)
data class FormFieldEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val fieldName: String,
    val fieldType: String,
    val pageIndex: Int,
    val rectLeft: Float,
    val rectTop: Float,
    val rectRight: Float,
    val rectBottom: Float,
    val value: String?,
    val defaultValue: String?,
    val optionsJson: String?,
    val isRequired: Boolean = false,
    val isReadOnly: Boolean = false,
    val fontSize: Float = 12f,
    val textColor: Int = 0xFF000000.toInt(),
    val backgroundColor: Int? = null,
    val borderColor: Int? = null,
    val borderWidth: Float = 0f,
    val rotation: Int = 0,
    val groupName: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)
