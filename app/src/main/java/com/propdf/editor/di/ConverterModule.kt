package com.propdf.editor.di

import android.content.Context
import com.propdf.editor.data.converter.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConverterModule {

    @Provides
    @Singleton
    fun providePdfConverter(@ApplicationContext context: Context): PdfConverter {
        return PdfConverter(context)
    }

    @Provides
    @Singleton
    fun provideImageConverter(@ApplicationContext context: Context): ImageConverter {
        return ImageConverter(context)
    }

    @Provides
    @Singleton
    fun provideTextConverter(@ApplicationContext context: Context): TextConverter {
        return TextConverter(context)
    }

    @Provides
    @Singleton
    fun provideHtmlConverter(@ApplicationContext context: Context): HtmlConverter {
        return HtmlConverter(context)
    }

    @Provides
    @Singleton
    fun provideMarkdownConverter(@ApplicationContext context: Context): MarkdownConverter {
        return MarkdownConverter(context)
    }

    @Provides
    @Singleton
    fun provideZipExporter(@ApplicationContext context: Context): ZipExporter {
        return ZipExporter(context)
    }
}
