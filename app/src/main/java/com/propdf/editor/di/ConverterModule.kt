package com.propdf.editor.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * All converter classes (PdfConverter, ImageConverter, TextConverter, HtmlConverter,
 * MarkdownConverter, ZipExporter) declare their own @Inject constructor, so Hilt provides
 * them automatically. This module intentionally has no @Provides methods; duplicate
 * bindings here previously caused KSP "error.NonExistentClass" failures at injection sites.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConverterModule
