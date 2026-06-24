package com.propdf.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdf.core.data.entity.RecentSearchEntity
import com.propdf.core.data.entity.SearchIndexEntity
import com.propdf.core.data.entity.SearchIndexContent

@Database(
    entities = [
        RecentSearchEntity::class,
        SearchIndexEntity::class,
        SearchIndexContent::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(SearchConverters::class)
abstract class SearchDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao
}
