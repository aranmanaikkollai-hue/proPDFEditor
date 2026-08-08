package com.propdfeditor.di

import android.content.Context
import androidx.room.Room
import com.propdfeditor.data.local.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return try {
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "propdf_database"
            )
                .fallbackToDestructiveMigration(false) // NEVER destroy user data silently
                .build()
        } catch (e: Throwable) {
            Timber.e(e, "Database initialization failed")
            // If database is corrupted, create in-memory fallback so app can start
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .build()
        }
    }
}
