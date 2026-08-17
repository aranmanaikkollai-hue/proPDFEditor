package com.propdf.editor.domain.model

// NOTE: PdfDocument, DocumentCategory, Folder, and StorageStats used to also be
// declared in this file, duplicating their canonical declarations in
// PdfDocument.kt, Folder.kt, and StorageStats.kt (which have more fields and
// are what the rest of the app is actually written against). Two data classes
// with the same name in the same package is a Kotlin "Redeclaration" compile
// error, so those duplicates have been removed. ViewMode has no other
// declaration in this package and is imported directly by FilesScreen.kt and
// FilesViewModel.kt, so it stays here.

enum class ViewMode {
    LIST, GRID, TILE
}

// SortField has no other declaration in this package either -- it was
// referenced by FilesUiState (a data class) and FilesViewModel.kt's sort
// logic (SortField.DATE / .NAME / .SIZE) but had no definition anywhere in
// the codebase. An unresolved type used as a data class constructor
// parameter type doesn't surface as a normal "Unresolved reference" error;
// it hits a confirmed kapt bug (JetBrains KT-70718) that instead fails the
// whole :app module with the generic, contentless "e: Could not load module
// <Error module>" during kaptGenerateStubsDebugKotlin.
enum class SortField {
    DATE, NAME, SIZE
}
