package com.propdf.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdf.core.data.database.Converters
import java.util.Date

@Entity(
    tableName = "form_data",
    indices = [Index(value = ["documentUri"], unique = false)]
)
@TypeConverters(Converters::class)
data class FormDataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val fieldName: String,
    val fieldValue: String,
    val filledAt: Date = Date()
)
