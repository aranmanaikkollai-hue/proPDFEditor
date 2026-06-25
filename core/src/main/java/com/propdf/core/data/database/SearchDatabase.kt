package com.propdf.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
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
abstract class SearchDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao
}
