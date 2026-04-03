// FILE: AppModule_v4.kt  -- RENAME to AppModule.kt (replaces existing)
// FLAT REPO ROOT -- codemagic.yaml copies to:
// app/src/main/java/com/propdf/editor/di/AppModule.kt
//
// UPDATED: Adds Hilt providers for all new v4.0 managers:
//   - EdgeDetectionProcessor
//   - SignatureManager
//   - TextReflowManager
//   - PdfRedactionManager
//   (CategoryManager and AppShortcutsHelper are object singletons -- no Hilt needed)
//
// RULES OBEYED:
//   - Pure ASCII  (rule #32)
//   - No JitPack

package com.propdf.editor.di

import android.content.Context
import com.propdf.editor.data.local.RecentFilesDatabase
import com.propdf.editor.data.local.RecentFilesDao
import com.propdf.editor.data.repository.EdgeDetectionProcessor
import com.propdf.editor.data.repository.OcrManager
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.data.repository.PdfRedactionManager
import com.propdf.editor.data.repository.ScannerProcessor
import com.propdf.editor.data.repository.SignatureManager
import com.propdf.editor.data.repository.TextReflowManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun providePdfOperationsManager(
        @ApplicationContext context: Context
    ): PdfOperationsManager = PdfOperationsManager(context)

    @Provides @Singleton
    fun provideOcrManager(
        @ApplicationContext context: Context
    ): OcrManager = OcrManager(context)

    @Provides @Singleton
    fun provideScannerProcessor(): ScannerProcessor = ScannerProcessor()

    @Provides @Singleton
    fun provideEdgeDetectionProcessor(): EdgeDetectionProcessor = EdgeDetectionProcessor()

    @Provides @Singleton
    fun provideSignatureManager(
        @ApplicationContext context: Context
    ): SignatureManager = SignatureManager(context)

    @Provides @Singleton
    fun provideTextReflowManager(
        @ApplicationContext context: Context
    ): TextReflowManager = TextReflowManager(context)

    @Provides @Singleton
    fun providePdfRedactionManager(): PdfRedactionManager = PdfRedactionManager()

    @Provides @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): RecentFilesDatabase = RecentFilesDatabase.get(context)

    @Provides @Singleton
    fun provideRecentFilesDao(
        db: RecentFilesDatabase
    ): RecentFilesDao = db.recentFilesDao()
}
