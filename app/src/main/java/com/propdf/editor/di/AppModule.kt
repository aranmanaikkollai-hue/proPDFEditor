
package com.propdf.editor.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // NOTE: ProPDFDatabase is provided by DatabaseModule.provideDatabase() in this
    // same package. This module used to duplicate that @Provides fun provideDatabase()
    // binding here (identical return type, no qualifiers), which is a
    // [Dagger/DuplicateBindings] error. Dagger's validation for duplicate bindings runs
    // during kapt's stub-generation/processing phase, and a known kapt bug (KT-70718)
    // swallows that specific diagnostic and rethrows it as the generic, unhelpful
    // "e: Could not load module <Error module>" instead of the real duplicate-binding
    // message - hence no other error appeared in the build log.

    // NOTE: DispatcherProvider is provided by :core's CoreModule.provideDispatcherProvider()
    // and RecentFilesDao by :core's DatabaseModule.provideRecentFilesDao(RecentFilesDatabase).
    // Re-declaring them here caused Dagger [Dagger/DuplicateBindings] errors.

    // OcrJobDao and OcrJobManager are provided by the :ocr module or core module
    // Removing duplicate declarations to avoid unresolved references
}
