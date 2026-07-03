package com.propdf.editor.di

/**
 * Provides stub bindings for :core module repository interfaces
 * that are injected by existing ViewModels but have no implementations.
 *
 * RecentFilesRepository no longer needs a stub here — it's bound to the real
 * Room-backed com.propdf.core.data.local.RecentFilesRepositoryImpl in
 * CoreRepositoryBindingsModule.
 *
 * PdfViewerRepository also has a real implementation
 * (com.propdf.viewer.data.repository.PdfViewerRepositoryImpl, bound via
 * com.propdf.viewer.di.ViewerBindsModule), so no stub is needed for it here.
 */
