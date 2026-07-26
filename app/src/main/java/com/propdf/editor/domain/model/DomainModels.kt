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
