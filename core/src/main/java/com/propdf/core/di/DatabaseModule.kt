package com.propdf.core.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.local.RecentFilesDao
import com.propdf.core.data.local.RecentFilesDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideRecentFilesDatabase(
        @ApplicationContext context: Context
    ): RecentFilesDatabase =
        Room.databaseBuilder(
            context,
            RecentFilesDatabase::class.java,
            "propdf_recent_files.db"
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideRecentFilesDao(db: RecentFilesDatabase): RecentFilesDao = db.recentFilesDao()
}
