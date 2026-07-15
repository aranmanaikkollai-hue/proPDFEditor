package com.propdf.editor.di

import com.propdf.editor.data.converter.*
import com.propdf.editor.data.local.ConversionTaskDao
import com.propdf.editor.data.repository.ConversionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideConversionRepository(
        context: android.content.Context,
        taskDao: ConversionTaskDao,
        pdfConverter: PdfConverter,
        imageConverter: ImageConverter,
        textConverter: TextConverter,
        htmlConverter: HtmlConverter,
        markdownConverter: MarkdownConverter,
        zipExporter: ZipExporter
    ): ConversionRepository {
        return ConversionRepository(
            context,
            taskDao,
            pdfConverter,
            imageConverter,
            textConverter,
            htmlConverter,
            markdownConverter,
            zipExporter
        )
    }
}
