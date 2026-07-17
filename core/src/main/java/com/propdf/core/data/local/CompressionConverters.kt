package com.propdf.core.data.local

import androidx.room.TypeConverter
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CompressionConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromConfig(config: CompressionConfig): String = json.encodeToString(config)

    @TypeConverter
    fun toConfig(value: String): CompressionConfig = json.decodeFromString(value)

    @TypeConverter
    fun fromStrategy(strategy: CompressionStrategy): String = strategy.name

    @TypeConverter
    fun toStrategy(value: String): CompressionStrategy = 
        CompressionStrategy.valueOf(value)
}
