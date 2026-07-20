// security/src/main/java/com/propdf/security/di/SecurityModule.kt
package com.propdf.security.di

import android.content.Context
import androidx.room.Room
import com.propdf.security.data.database.SecurityDatabase
import com.propdf.security.data.repository.SecurityRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideSecurityDatabase(
        @ApplicationContext context: Context
    ): SecurityDatabase {
        return Room.databaseBuilder(
            context,
            SecurityDatabase::class.java,
            "security_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideSecurityOperationDao(database: SecurityDatabase) = database.securityOperationDao()

    @Provides
    @Singleton
    fun provideRedactionDao(database: SecurityDatabase) = database.redactionDao()

    @Provides
    @Singleton
    fun provideSecureDocumentDao(database: SecurityDatabase) = database.secureDocumentDao()
}
