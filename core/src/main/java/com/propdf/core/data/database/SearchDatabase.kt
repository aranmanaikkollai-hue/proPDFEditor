package com.propdf.core.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
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

    companion object {
        @Volatile
        private var instance: SearchDatabase? = null

        fun getInstance(context: Context): SearchDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SearchDatabase::class.java,
                    "search_database"
                ).build().also { instance = it }
            }
    }
}
