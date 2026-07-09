package com.propdf.scanner.data.local

import androidx.room.TypeConverter
import com.propdf.scanner.model.ColorFilter
import com.propdf.scanner.model.ScanMode

class ScannerTypeConverters {
    @TypeConverter fun fromScanMode(mode: ScanMode): String = mode.name
    @TypeConverter fun toScanMode(value: String): ScanMode = ScanMode.valueOf(value)
    @TypeConverter fun fromColorFilter(filter: ColorFilter): String = filter.name
    @TypeConverter fun toColorFilter(value: String): ColorFilter = ColorFilter.valueOf(value)
}
